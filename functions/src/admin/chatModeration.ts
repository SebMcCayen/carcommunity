/** Atomic public-chat decisions. This module never accepts a raw path or target UID. */
import { createHash } from 'node:crypto';
import {
  FieldValue,
  Timestamp,
  type DocumentSnapshot,
  type Transaction,
} from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { onCall } from '../access/callable';
import { identityKeys, isCarCommunityRestricted } from '../access/carCommunity-core';
import { db } from '../firebase';
import { toUserAccessState } from '../shared/access';
import { requireAdminActor } from './actorContext';
import {
  buildAdminAuditEvent,
  buildModerationAction,
  guardActorIsActiveAdmin,
  guardModerationTarget,
} from './claims-core';
import { redactPublicReplies } from '../chatchannels/redactPublicReplies';
import { writeInAppNotification } from '../notifications/deliver';
import { CPU_ADMIN, MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';
import {
  chatModerationContextInput,
  chatModerationDecision,
  chatPauseDuration,
  MAX_CHAT_PAUSE_DAYS,
  type ChatModerationTarget,
} from './chatModeration-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const hash = (value: unknown) => createHash('sha256').update(JSON.stringify(value)).digest('hex');
function messageRef(target: ChatModerationTarget) {
  return target.source === 'community'
    ? db.doc(`communityChat/global/messages/${target.messageId}`)
    : db.doc(`events/${target.eventId}/messages/${target.messageId}`);
}
function stamp(doc: DocumentSnapshot) {
  return doc.updateTime
    ? [doc.ref.path, doc.updateTime.seconds, doc.updateTime.nanoseconds]
    : [doc.ref.path, null];
}
function version(
  message: DocumentSnapshot,
  restriction: DocumentSnapshot,
  profile: DocumentSnapshot,
) {
  return hash([stamp(message), stamp(restriction), stamp(profile)]);
}
function project(target: ChatModerationTarget, message: DocumentSnapshot) {
  const d = message.data() ?? {};
  return target.source === 'event'
    ? {
        authorUid: d.authorUserId,
        authorName: String(d.authorDisplayName ?? ''),
        text: String(d.message ?? ''),
      }
    : {
        authorUid: d.senderUid,
        authorName: String(d.senderDisplayName ?? ''),
        text: String(d.text ?? ''),
      };
}
/** Reads role AND product access in the same transaction as the decision/receipt. */
async function authorize(tx: Transaction, uid: string) {
  const [profile, access] = await Promise.all([
    tx.get(db.doc(`users/${uid}`)),
    tx.get(db.doc(`carCommunityAccess/${uid}`)),
  ]);
  const state = toUserAccessState(profile.data());
  const allowed = guardActorIsActiveAdmin(state);
  if (!allowed.ok) throw new HttpsError(allowed.code, allowed.message);
  const keys = identityKeys(access.data());
  const identities = keys.length
    ? await tx.getAll(...keys.map((key) => db.doc(`carCommunityIdentityBlocks/${key}`)))
    : [];
  if (
    isCarCommunityRestricted(
      access.data(),
      identities.map((d) => d.data() ?? {}),
    )
  )
    throw new HttpsError('permission-denied', 'Åtkomsten till CarCommunity har ändrats.');
  return state;
}
function available(message: DocumentSnapshot) {
  if (!message.exists || message.get('moderationState') === 'removed')
    throw new HttpsError('failed-precondition', 'Meddelandet är redan borttaget.');
}
function author(target: ChatModerationTarget, message: DocumentSnapshot) {
  const value = project(target, message).authorUid;
  if (
    typeof value !== 'string' ||
    !value ||
    value.includes('/') ||
    value.length > 128 ||
    value === '.' ||
    value === '..'
  )
    throw new HttpsError('failed-precondition', 'Meddelandets avsändare kan inte verifieras.');
  return value;
}
function sanctionGuard(
  actorUid: string,
  actorRole: 'user' | 'admin' | 'owner',
  authorUid: string,
  profile: DocumentSnapshot,
) {
  return guardModerationTarget({
    actorUid,
    actorRole,
    targetUid: authorUid,
    targetRole: toUserAccessState(profile.data()).role,
  });
}
const untilMillis = (doc: DocumentSnapshot): number | null =>
  doc.get('pauseUntil') instanceof Timestamp
    ? (doc.get('pauseUntil') as Timestamp).toMillis()
    : null;

export const getChatModerationContext = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request);
  const parsed = chatModerationContextInput.safeParse(request.data);
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Ogiltigt meddelande.');
  const { target } = parsed.data;
  return db.runTransaction(async (tx) => {
    const actorState = await authorize(tx, actor.uid);
    const message = await tx.get(messageRef(target));
    available(message);
    const authorUid = author(target, message);
    const [profile, restriction] = await Promise.all([
      tx.get(db.doc(`users/${authorUid}`)),
      tx.get(db.doc(`carCommunityChatRestrictions/${authorUid}`)),
    ]);
    if (!profile.exists || profile.get('deleted') === true)
      throw new HttpsError('failed-precondition', 'Medlemmen finns inte längre.');
    const guard = sanctionGuard(actor.uid, actorState.role, authorUid, profile);
    const projected = project(target, message);
    return {
      actorUid: actor.uid,
      authorUid,
      version: version(message, restriction, profile),
      message: { authorName: projected.authorName, text: projected.text },
      canRemove: true,
      canWarn: guard.ok,
      canPause: guard.ok,
      pauseUntilMillis: untilMillis(restriction),
      serverNowMillis: Date.now(),
      maxPauseDays: MAX_CHAT_PAUSE_DAYS,
    };
  });
});

interface DecisionResult {
  messageId: string;
  authorUid: string;
  alreadyApplied: boolean;
  warningId: string | null;
  pauseUntilMillis: number | null;
}
export const moderateChatMessage = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request);
  const parsed = chatModerationDecision.safeParse(request.data);
  if (!parsed.success)
    throw new HttpsError('invalid-argument', 'Ange åtgärd, orsak och giltig tid (högst 30 dagar).');
  const input = parsed.data;
  if (input.expectedActorUid !== actor.uid)
    throw new HttpsError('permission-denied', 'Administratörens session har ändrats.');
  // Namespace separates mobile combined decisions from the existing admin-web receipts.
  const operationId = hash(['chat', actor.uid, input.idempotencyKey]);
  const receiptRef = db.doc(`adminModerationOperations/${operationId}`);
  const fingerprint = hash(input);
  const result = await db.runTransaction(async (tx): Promise<DecisionResult> => {
    const actorState = await authorize(tx, actor.uid);
    const [message, receipt] = await Promise.all([
      tx.get(messageRef(input.target)),
      tx.get(receiptRef),
    ]);
    // A valid receipt is replayable after removal, but never after actor revocation.
    if (receipt.exists) {
      if (receipt.get('fingerprint') !== fingerprint)
        throw new HttpsError('failed-precondition', 'Åtgärdsnyckeln avser ett annat beslut.');
      return { ...receipt.get('result'), alreadyApplied: true } as DecisionResult;
    }
    available(message);
    const authorUid = author(input.target, message);
    const restrictionRef = db.doc(`carCommunityChatRestrictions/${authorUid}`);
    const [profile, restriction] = await Promise.all([
      tx.get(db.doc(`users/${authorUid}`)),
      tx.get(restrictionRef),
    ]);
    if (!profile.exists || profile.get('deleted') === true)
      throw new HttpsError('failed-precondition', 'Medlemmen finns inte längre.');
    if (version(message, restriction, profile) !== input.expectedVersion)
      throw new HttpsError(
        'failed-precondition',
        'Underlaget har ändrats. Uppdatera och granska beslutet igen.',
      );
    if (input.warn || input.pause !== null) {
      const guard = sanctionGuard(actor.uid, actorState.role, authorUid, profile);
      if (!guard.ok) throw new HttpsError(guard.code, guard.message);
    }
    const now = Timestamp.now();
    const stamp = FieldValue.serverTimestamp();
    const currentUntil = untilMillis(restriction);
    // A new pause may extend, never silently shorten, an existing active pause.
    const pauseUntilMillis =
      input.pause === null
        ? currentUntil
        : Math.max(currentUntil ?? 0, now.toMillis() + chatPauseDuration(input.pause));
    const warningId = input.warn ? `chat_${operationId}` : null;
    if (input.action === 'remove') {
      if (input.target.source === 'event')
        tx.update(message.ref, {
          message: '',
          moderationState: 'removed',
          removedAt: stamp,
          removedByUserId: actor.uid,
        });
      else {
        tx.delete(message.ref);
        tx.set(db.doc(`communityChatDeletedMessages/${input.target.messageId}`), {
          removedAt: stamp,
        });
      }
    }
    if (input.pause !== null)
      tx.set(restrictionRef, {
        pauseUntil: Timestamp.fromMillis(pauseUntilMillis!),
        reason: input.reason,
        version: Number(restriction.get('version') ?? 0) + 1,
        updatedAt: stamp,
      });
    if (warningId) {
      tx.create(db.doc(`userWarnings/${authorUid}/warnings/${warningId}`), {
        reason: input.reason,
        internalNote: '',
        actorUserId: actor.uid,
        createdAt: now,
        updatedAt: now,
        expiresAt: null,
        revokedAt: null,
        version: 1,
        requestFingerprint: fingerprint,
      });
      tx.create(
        db.doc(`moderationActions/${warningId}`),
        buildModerationAction(
          {
            targetUserId: authorUid,
            actorUserId: actor.uid,
            actionType: 'warning',
            reason: input.reason,
          },
          () => stamp,
        ),
      );
      tx.set(db.doc(`accountWarningSignals/${authorUid}`), {
        updatedAt: stamp,
        changeId: operationId,
      });
    }
    tx.create(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          // Keep remove evidence compatible with the existing admin-web case viewer.
          adminId: actor.uid,
          action: input.action === 'remove' ? 'publicChat.remove' : 'publicChat.pause',
          targetType: 'publicChatMessage',
          targetId: input.target.messageId,
          reason: input.reason,
          details: {
            source: input.target.source,
            eventId: input.target.eventId ?? null,
            authorUid,
            authorName: project(input.target, message).authorName,
            messageCreatedAt: message.get('createdAt') ?? null,
            originalText: input.action === 'remove' ? project(input.target, message).text : null,
            remove: input.action === 'remove',
            warningId,
            pause: input.pause,
            pauseUntilMillis: input.pause ? pauseUntilMillis : null,
          },
        },
        () => stamp,
      ),
    );
    const value: DecisionResult = {
      messageId: input.target.messageId,
      authorUid,
      alreadyApplied: false,
      warningId,
      pauseUntilMillis,
    };
    tx.create(receiptRef, { fingerprint, result: value, createdAt: stamp });
    return value;
  });
  // Delivery is deduplicated after commit. A failed notice never rolls back the decision.
  let notificationStatus = 'not_requested';
  if (result.warningId || input.pause !== null) {
    try {
      const notice = await db.runTransaction(async (tx) => {
        // Retry delivery is serialized with warning revocation/public pause
        // changes, so an old receipt cannot manufacture a new stale notice.
        const current = await tx.get(
          result.warningId
            ? db.doc(`userWarnings/${result.authorUid}/warnings/${result.warningId}`)
            : db.doc(`carCommunityChatRestrictions/${result.authorUid}`),
        );
        const expiry = result.warningId ? current.get('expiresAt') : current.get('pauseUntil');
        const active =
          current.exists &&
          (result.warningId
            ? current.get('revokedAt') == null &&
              (expiry == null || (expiry instanceof Timestamp && expiry.toMillis() > Date.now()))
            : expiry instanceof Timestamp && expiry.toMillis() > Date.now());
        if (!active) return { delivered: false, skippedReason: 'not_active' };
        return writeInAppNotification(
          result.authorUid,
          {
            category: result.warningId ? 'account_warning' : 'system_notice',
            title: result.warningId ? 'Varning utfärdad' : 'Skrivande pausat',
            previewText: result.warningId
              ? 'Du har fått en varning från moderatorerna.'
              : 'Ditt skrivande i Community- och eventchattar är pausat.',
            body: result.warningId
              ? 'Se aktuell varning och orsak i din profil.'
              : 'Se sluttid och orsak i chatten. PM och konvojer påverkas inte.',
          },
          `chat-moderation-${operationId}`,
          { transaction: tx, firestore: db },
        );
      });
      notificationStatus =
        notice.delivered || notice.skippedReason === 'duplicate' ? 'delivered' : 'skipped';
    } catch {
      notificationStatus = 'failed';
      logger.warn('Chat decision saved; notice delivery failed', { operationId });
    }
  }
  const cleanup =
    input.action === 'remove' && input.target.source === 'community'
      ? await redactPublicReplies(input.target.messageId)
      : { redacted: 0, hasMore: false };
  return { ...result, notificationStatus, cleanup };
});
