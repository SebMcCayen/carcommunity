/**
 * notifications.adminSend — admin callable (contracts/functions/functions.json).
 *
 * Deployed via the `notifications` export group as `notifications-adminSend`.
 *
 * Fans an in-app notification out to a resolved audience. Ports
 * services/api notification-delivery-service.deliverToAudience:
 *  - Admin only; a mandatory reason is written to adminAuditEvents.
 *  - Idempotent per idempotencyKey (deterministic batch id): a replay is
 *    rejected with already-exists rather than double-sending.
 *  - Broad audiences (all_users / free_users) require an explicit confirmation.
 *  - Synchronous fan-out is bounded by MAX_SYNC_AUDIENCE_SIZE (larger audiences
 *    are rejected — a background queue is the documented follow-up).
 *  - Per-recipient delivery goes through writeInAppNotification, which skips
 *    deleted/suspended recipients and honours per-category in-app opt-outs;
 *    essential categories are always delivered. Push delivery is out of scope
 *    (needs FCM credentials) — this is the in-app path only.
 *
 * The batch record (adminNotificationBatches) and the per-user notification
 * items are Admin-SDK writes; both are backend-only for clients.
 */

import { createHash } from 'node:crypto';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { isRestricted, toUserAccessState } from '../shared/access';
import { writeInAppNotification } from './deliver';
import {
  MAX_SYNC_AUDIENCE_SIZE,
  parseAdminSendInput,
  validateAudienceRequirements,
  type AdminSendInput,
} from './adminSend-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 120,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const FANOUT_CHUNK_SIZE = 25;

export interface AdminSendResult {
  batchId: string;
  audience: AdminSendInput['audience'];
  recipientCount: number;
  createdAt: string;
}

/** Non-deleted, non-suspended (legacy status in ['active','warned']). */
function isEligible(userData: Record<string, unknown> | undefined): boolean {
  return userData !== undefined && !isRestricted(toUserAccessState(userData));
}

/** Resolves the eligible recipient uids for an audience. Throws on too-large audiences. */
async function resolveRecipients(input: AdminSendInput): Promise<string[]> {
  const users = db.collection('users');
  switch (input.audience) {
    case 'specific_user': {
      const snap = await users.doc(input.targetUserId!).get();
      return snap.exists && isEligible(snap.data()) ? [snap.id] : [];
    }
    case 'admins': {
      const snap = await users.where('role', 'in', ['admin', 'owner']).limit(MAX_SYNC_AUDIENCE_SIZE + 1).get();
      // Reject on the RAW size — never silently truncate to the eligible subset
      // of an over-cap audience.
      assertAudienceWithinCap(snap.docs.length);
      return snap.docs.filter((d) => isEligible(d.data())).map((d) => d.id);
    }
    case 'members': {
      const snap = await users.where('activeMember', '==', true).limit(MAX_SYNC_AUDIENCE_SIZE + 1).get();
      assertAudienceWithinCap(snap.docs.length);
      return snap.docs.filter((d) => isEligible(d.data())).map((d) => d.id);
    }
    case 'free_users': {
      const snap = await users.where('activeMember', '==', false).limit(MAX_SYNC_AUDIENCE_SIZE + 1).get();
      assertAudienceWithinCap(snap.docs.length);
      return snap.docs.filter((d) => isEligible(d.data())).map((d) => d.id);
    }
    case 'all_users': {
      const snap = await users.limit(MAX_SYNC_AUDIENCE_SIZE + 1).get();
      assertAudienceWithinCap(snap.docs.length);
      return snap.docs.filter((d) => isEligible(d.data())).map((d) => d.id);
    }
    case 'event_participants': {
      const snap = await db
        .collection('events')
        .doc(input.eventId!)
        .collection('rsvps')
        .where('status', 'in', ['going', 'maybe'])
        .get();
      // Eligibility is enforced per-recipient by writeInAppNotification.
      return snap.docs.map((d) => d.id);
    }
  }
}

/**
 * Rejects an audience query whose RAW result size exceeds the cap (before
 * eligibility filtering). Used by every audience whose recipient set is
 * resolved via a Firestore query (admins, members, free_users, all_users):
 * each of those queries is itself bounded with `.limit(MAX_SYNC_AUDIENCE_SIZE
 * + 1)`, so a RAW size over the cap means "more than the cap exists" rather
 * than "the whole collection, unbounded, happened to be this size" — the
 * +1 lets us distinguish "exactly at the cap" from "over the cap" without
 * reading the entire matching set into memory first.
 */
function assertAudienceWithinCap(rawSize: number): void {
  if (rawSize > MAX_SYNC_AUDIENCE_SIZE) {
    throw new HttpsError(
      'invalid-argument',
      `Audience too large for a synchronous send (max ${MAX_SYNC_AUDIENCE_SIZE}).`,
    );
  }
}

export const adminSend = onCall(CALLABLE_OPTS, async (request): Promise<AdminSendResult> => {
  const actor = await requireAdminActor(request);

  const parsed = parseAdminSendInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const validated = validateAudienceRequirements(parsed.input);
  if (!validated.ok) {
    // Missing confirmation is a precondition; missing ids are invalid arguments.
    const code = validated.kind === 'confirmation_required' ? 'failed-precondition' : 'invalid-argument';
    throw new HttpsError(code, validated.message);
  }
  const input = validated.input;

  const recipients = await resolveRecipients(input);
  if (recipients.length > MAX_SYNC_AUDIENCE_SIZE) {
    throw new HttpsError(
      'invalid-argument',
      `Audience too large for a synchronous send (max ${MAX_SYNC_AUDIENCE_SIZE}).`,
    );
  }

  // Deterministic batch id from the idempotency key: claiming the record with
  // create() rejects a replay atomically before any fan-out.
  const batchId = createHash('sha256').update(input.idempotencyKey).digest('hex').slice(0, 32);
  const now = new Date();
  const batchRef = db.collection('adminNotificationBatches').doc(batchId);
  try {
    await batchRef.create({
      batchId,
      category: input.category,
      audience: input.audience,
      title: input.title,
      previewText: input.previewText,
      body: input.body,
      actionType: input.actionType ?? 'none',
      relatedEntityId: input.relatedEntityId ?? null,
      eventId: input.eventId ?? null,
      targetUserId: input.targetUserId ?? null,
      recipientCount: recipients.length,
      reason: input.reason,
      idempotencyKey: input.idempotencyKey,
      createdByUserId: actor.uid,
      createdAt: FieldValue.serverTimestamp(),
    });
  } catch (error) {
    if (error instanceof Error && 'code' in error && (error as { code?: number }).code === 6) {
      // ALREADY_EXISTS (gRPC 6): this idempotency key was already used.
      throw new HttpsError('already-exists', 'A notification batch with this idempotency key already exists.');
    }
    throw error;
  }

  // Audit the admin action BEFORE fan-out: once the batch is claimed the send
  // is committed, so the audit record must exist even if fan-out later errors
  // or times out mid-way. (The fan-out itself is idempotent per recipient.)
  await db
    .collection('adminAuditEvents')
    .doc()
    .set(
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'notifications.adminSend',
          targetType: 'notificationBatch',
          targetId: batchId,
          reason: input.reason,
          details: { audience: input.audience, category: input.category, recipientCount: recipients.length },
        },
        () => FieldValue.serverTimestamp(),
      ),
    );

  // Fan-out in bounded chunks. Delivery is idempotent per recipient
  // (notificationId = batchId), so any retry never duplicates items.
  for (let i = 0; i < recipients.length; i += FANOUT_CHUNK_SIZE) {
    const chunk = recipients.slice(i, i + FANOUT_CHUNK_SIZE);
    await Promise.all(
      chunk.map((uid) =>
        writeInAppNotification(
          uid,
          {
            category: input.category,
            title: input.title,
            previewText: input.previewText,
            body: input.body,
            actionType: input.actionType,
            relatedEntityId: input.relatedEntityId ?? null,
            batchId,
          },
          batchId,
        ),
      ),
    );
  }

  return {
    batchId,
    audience: input.audience,
    recipientCount: recipients.length,
    createdAt: now.toISOString(),
  };
});
