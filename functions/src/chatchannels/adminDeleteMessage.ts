/**
 * chatchannels.adminDeleteMessage — admin callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `chatchannels` export group as
 * `chatchannels-adminDeleteMessage`. Requires an active admin via
 * requireAdminActor (server-managed `admin` custom claim + non-suspended,
 * non-deleted users/{uid} state).
 *
 * Deletes a single GLOBAL community-chat message
 * (communityChat/global/messages/{id}) that a member reported, and resolves the
 * open moderationReports that point at it. This is the admin-side counterpart to
 * chatchannels.reportMessage: reports land in `moderationReports`, an admin
 * reviews them in the KCC admin web, and this callable is the delete action.
 *
 * HARD delete (not a soft tombstone), deliberately, and unlike
 * events.removeChatMessage which soft-removes:
 *
 *  - The community channel's LIVE window is a DIRECT client Firestore listener
 *    (see communityChat.ts) that no server code can filter per-document. A soft
 *    tombstone that left the document readable would keep showing the (blanked)
 *    message on every client's live listener until the Android client learned to
 *    hide a tombstone — and the Android side is a separate task. A hard delete
 *    removes the message from BOTH the communityChat.list read path AND the
 *    direct listener immediately, with no client change.
 *  - The evidence is NOT lost: chatchannels.reportMessage already SNAPSHOTS the
 *    reported message (text + author + createdAt) into the report document, and
 *    this callable additionally preserves the original text in the
 *    adminAuditEvents record. So the moderator keeps what they judged even though
 *    the live message is gone.
 *  - Nothing references a community message by id after the fact: inline replies
 *    snapshot their parent at post time (buildReplyToSnapshot), and community
 *    messages already carry a TTL (`expireAt`, 120 days) after which Firestore
 *    hard-deletes them anyway — so the schema already tolerates a message
 *    vanishing, and a dangling reference cannot arise.
 *
 * Because the write is a pure Admin-SDK delete/update, it bypasses Security
 * Rules entirely (the Admin SDK is not subject to firestore.rules) — so NO rules
 * change is needed. The rules already deny every client write to
 * communityChat/{c}/messages and every client write to moderationReports; this
 * callable is simply the sanctioned server path.
 *
 * Idempotent: a re-delete of an already-deleted message does not throw — it
 * finds no message, resolves any lingering open report (there normally is none),
 * and returns { deleted: false }.
 *
 * WRITE-COUNT SAFETY (Firestore caps a transaction/batch at 500 writes). The
 * ATOMIC, critical part — the message DELETE + the adminAuditEvents record — is
 * the ONLY thing inside the transaction (2 writes, always). The pending reports
 * are resolved AFTER the transaction in batched commits of
 * REPORT_RESOLVE_BATCH_SIZE (<= 500). This is deliberate: a heavily-reported
 * message (the one an admin most needs to gone) can carry hundreds of open
 * reports, and folding one update-per-report into the delete transaction would
 * make the worst messages the ONLY ones that fail to delete. So the delete
 * always succeeds regardless of report count, and report resolution is
 * best-effort bookkeeping — a chunk that fails just leaves some reports 'pending'
 * in the queue while the message is already gone (logged, PII-free; an admin can
 * re-run the delete, which is idempotent, or resolve the stragglers by hand).
 */

import { logger } from 'firebase-functions';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  COMMUNITY_CHANNEL_ID,
  REPORT_RESOLVE_BATCH_SIZE,
  chunk,
  parseAdminDeleteCommunityMessageInput,
} from './chat-core';
import { MODERATION_REPORTS_COLLECTION } from '../moderation/moderation-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

/** Stamped on the audit record when the admin supplies no explicit note. */
export const DEFAULT_ADMIN_DELETE_REASON = 'Reported community message removed by moderator.';

/**
 * The report status the admin queue treats as "open" (moderation-core's initial
 * status). Deleting the message resolves every open report that points at it to
 * 'reviewed' — the same terminal-ish status the admin web's "mark reviewed"
 * action writes — so the report leaves the pending queue in lock-step with the
 * message it was about. Reports already 'reviewed' / 'dismissed' are left
 * untouched (a moderator's prior decision is not overwritten).
 */
const OPEN_REPORT_STATUS = 'pending';
const RESOLVED_REPORT_STATUS = 'reviewed';

export interface AdminDeleteCommunityMessageResponse {
  messageId: string;
  /** True when a message document was actually deleted by THIS call. */
  deleted: boolean;
  /**
   * How many open reports this call successfully moved to 'reviewed'. On a
   * partial batch failure this can be less than the number found open (the
   * remainder stays 'pending' — see the write-count note in the header).
   */
  resolvedReports: number;
}

/**
 * Resolves the given open-report refs to 'reviewed' in batched commits of
 * REPORT_RESOLVE_BATCH_SIZE, AFTER the delete transaction. Best-effort: a
 * failing chunk is logged (PII-free — only the count + message id, never report
 * contents) and skipped, so it never fails a delete that already committed.
 * Returns how many reports were actually committed.
 */
async function resolvePendingReports(
  reportRefs: readonly FirebaseFirestore.DocumentReference[],
  messageId: string,
): Promise<number> {
  let resolved = 0;
  for (const group of chunk(reportRefs, REPORT_RESOLVE_BATCH_SIZE)) {
    const batch = db.batch();
    for (const ref of group) {
      batch.update(ref, { status: RESOLVED_REPORT_STATUS });
    }
    try {
      await batch.commit();
      resolved += group.length;
    } catch (error) {
      logger.error('chatchannels.adminDeleteMessage: report-resolution batch failed', {
        messageId,
        chunkSize: group.length,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }
  return resolved;
}

export const adminDeleteMessage = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<AdminDeleteCommunityMessageResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseAdminDeleteCommunityMessageInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { messageId, reason } = parsed.input;

    const messageRef = db
      .collection('communityChat')
      .doc(COMMUNITY_CHANNEL_ID)
      .collection('messages')
      .doc(messageId);
    const reportsRef = db.collection(MODERATION_REPORTS_COLLECTION);
    const serverTimestamp = () => FieldValue.serverTimestamp();

    // Read the open reports OUTSIDE the transaction. Every report that escalated
    // THIS community message — two equality filters (surface + targetId), no
    // range and no orderBy, so Firestore serves it by merging single-field
    // indexes (NO composite index). `targetId` is the reported messageId
    // (moderation-core buildMessageReportDocument); `surface` pins it to the
    // community channel so a same-id message on another surface can't be swept
    // in. Reading here (not in the tx) is safe: a report can never be filed
    // against an already-deleted message (reportMessage 404s on a missing
    // message), and there can be an unbounded number of them — which is exactly
    // why their resolution must NOT sit inside the 500-write delete transaction.
    const reportsSnap = await reportsRef
      .where('surface', '==', 'community')
      .where('targetId', '==', messageId)
      .get();
    const openReportRefs = reportsSnap.docs
      .filter((doc) => doc.data().status === OPEN_REPORT_STATUS)
      .map((doc) => doc.ref);

    // The ATOMIC critical section — message delete + audit — is the ONLY thing
    // in the transaction (a fixed 2 writes), so it can never hit the 500-write
    // cap no matter how many reports the message has.
    const deleted = await db.runTransaction(async (tx) => {
      const messageSnap = await tx.get(messageRef);
      if (!messageSnap.exists) {
        // Idempotent re-delete: nothing to remove, no audit record (nothing
        // happened to the channel). Report resolution below still runs.
        return false;
      }

      const message = messageSnap.data() ?? {};
      tx.delete(messageRef);
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'communityChat.deleteMessage',
            targetType: 'communityChatMessage',
            targetId: messageId,
            reason: reason ?? DEFAULT_ADMIN_DELETE_REASON,
            details: {
              channelId: COMMUNITY_CHANNEL_ID,
              authorUserId: typeof message.senderUid === 'string' ? message.senderUid : null,
              authorDisplayName:
                typeof message.senderDisplayName === 'string' ? message.senderDisplayName : null,
              // Original text preserved for audit — the live message is gone.
              originalText: typeof message.text === 'string' ? message.text : null,
              // The number of open reports found for this message (the intended
              // resolution count; the actual committed count is returned to the
              // caller and may be lower on a partial batch failure).
              resolvedReports: openReportRefs.length,
            },
          },
          serverTimestamp,
        ),
      );
      return true;
    });

    // Resolve the pending reports in batched commits AFTER the transaction, so
    // the queue never strands a pending report over an already-gone message —
    // best-effort, and unbounded in count. Runs whether or not the message
    // existed (an idempotent re-delete normally finds none still open).
    const resolvedReports = await resolvePendingReports(openReportRefs, messageId);

    return { messageId, deleted, resolvedReports };
  },
);
