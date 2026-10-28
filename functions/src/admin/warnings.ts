/** Private warning lifecycle, safe own-account projection and immutable decisions. */
import { createHash, randomUUID } from 'node:crypto';
import { FieldPath, FieldValue, Timestamp, type Query } from 'firebase-admin/firestore';
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { db, adminAuth } from '../firebase';
import { requireAdminActor } from './actorContext';
import {
  buildAdminAuditEvent,
  buildModerationAction,
  guardModerationTarget,
  isAuthUserNotFoundError,
} from './claims-core';
import { toUserAccessState } from '../shared/access';
import { writeInAppNotification } from '../notifications/deliver';
import { logger } from 'firebase-functions';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import {
  historyCursor,
  pageInput,
  timestamp,
  documentPosition,
  nextHistoryCursor,
} from './history-pagination';
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const uid = z
  .string()
  .trim()
  .min(1)
  .max(128)
  .refine((v) => !v.includes('/') && v !== '.' && v !== '..');
const key = z.string().regex(/^[A-Za-z0-9._-]{16,128}$/);
const reason = z.string().trim().min(1).max(500);
const expiryFields = {
  expiresAt: z.string().datetime().nullable().optional(),
  durationDays: z.number().int().min(1).max(3650).optional(),
};
export const warningInput = z
  .object({
    targetUid: uid,
    expectedActorUid: uid.optional(),
    reason,
    ...expiryFields,
    internalNote: z.string().trim().max(1000).default(''),
    idempotencyKey: key.optional(),
  })
  .strict()
  .refine((v) => v.durationDays === undefined || v.expiresAt == null);
const updateInput = z
  .object({
    targetUid: uid,
    expectedActorUid: uid.optional(),
    warningId: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/),
    reason,
    idempotencyKey: key,
    expectedVersion: z.number().int().min(1),
    ...expiryFields,
  })
  .strict()
  .refine((v) => v.durationDays === undefined || v.expiresAt == null);
const hash = (value: unknown) => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const items = (targetUid: string) =>
  db.collection('userWarnings').doc(targetUid).collection('warnings');
const signal = (targetUid: string) => db.collection('accountWarningSignals').doc(targetUid);
function expiry(
  input: { expiresAt?: string | null; durationDays?: number },
  now: Timestamp,
): Timestamp | null {
  const date =
    input.durationDays !== undefined
      ? new Date(now.toMillis() + input.durationDays * 86400000)
      : input.expiresAt
        ? new Date(input.expiresAt)
        : null;
  if (date === null) return null;
  if (
    !Number.isFinite(date.getTime()) ||
    date.getTime() <= now.toMillis() ||
    date.getUTCFullYear() > 9999
  )
    throw new HttpsError('invalid-argument', 'Varningens sluttid måste ligga i framtiden.');
  return Timestamp.fromDate(date);
}
function authorizeTarget(
  actor: Awaited<ReturnType<typeof requireAdminActor>>,
  targetUid: string,
  profile: FirebaseFirestore.DocumentSnapshot,
) {
  if (!profile.exists) throw new HttpsError('not-found', 'Medlemmen finns inte.');
  const guard = guardModerationTarget({
    actorUid: actor.uid,
    actorRole: actor.state.role,
    targetUid,
    targetRole: toUserAccessState(profile.data()).role,
  });
  if (!guard.ok) throw new HttpsError(guard.code, guard.message);
}
function publicWarning(id: string, data: FirebaseFirestore.DocumentData, now: Timestamp) {
  return {
    id,
    reason: String(data.reason),
    createdAt: (data.createdAt as Timestamp).toDate().toISOString(),
    expiresAt: data.expiresAt ? (data.expiresAt as Timestamp).toDate().toISOString() : null,
    remainingMillis: data.expiresAt
      ? Math.max(0, (data.expiresAt as Timestamp).toMillis() - now.toMillis())
      : null,
    version: Number(data.version ?? 1),
  };
}
function active(data: FirebaseFirestore.DocumentData, now: Timestamp) {
  return (
    data.revokedAt == null &&
    (data.expiresAt == null || (data.expiresAt as Timestamp).toMillis() > now.toMillis())
  );
}

export const warnUser = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request);
  const parsed = warningInput.safeParse(request.data);
  if (!parsed.success)
    throw new HttpsError('invalid-argument', 'Ange mottagare, orsak och en giltig giltighetstid.');
  const input = parsed.data;
  if (input.expectedActorUid && input.expectedActorUid !== actor.uid)
    throw new HttpsError('permission-denied', 'Administrator session changed. Reload the member.');
  await adminAuth.getUser(input.targetUid).catch((error) => {
    if (isAuthUserNotFoundError(error)) throw new HttpsError('not-found', 'Medlemmen finns inte.');
    throw error;
  });
  const id = input.idempotencyKey
    ? 'warning_' + hash([actor.uid, input.targetUid, input.idempotencyKey])
    : db.collection('moderationActions').doc().id;
  const reference = items(input.targetUid).doc(id);
  const fingerprint = hash({
    targetUid: input.targetUid,
    reason: input.reason,
    internalNote: input.internalNote,
    expiresAt: input.expiresAt ?? null,
    durationDays: input.durationDays ?? null,
  });
  const result = await db.runTransaction(async (tx) => {
    const [profile, existing] = await Promise.all([
      tx.get(db.collection('users').doc(input.targetUid)),
      tx.get(reference),
    ]);
    authorizeTarget(actor, input.targetUid, profile);
    if (existing.exists) {
      if (existing.get('requestFingerprint') !== fingerprint)
        throw new HttpsError('failed-precondition', 'Åtgärdsnyckeln avser en annan varning.');
      return { data: existing.data()!, alreadyApplied: true };
    }
    const now = Timestamp.now(),
      expiresAt = expiry(input, now);
    const data = {
      reason: input.reason,
      internalNote: input.internalNote,
      actorUserId: actor.uid,
      createdAt: now,
      updatedAt: now,
      expiresAt,
      revokedAt: null,
      version: 1,
      requestFingerprint: fingerprint,
    };
    tx.create(reference, data);
    tx.create(
      db.collection('moderationActions').doc(id),
      buildModerationAction(
        {
          targetUserId: input.targetUid,
          actorUserId: actor.uid,
          actionType: 'warning',
          reason: input.reason,
        },
        () => FieldValue.serverTimestamp(),
      ),
    );
    tx.create(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'user.warn',
          targetType: 'user',
          targetId: input.targetUid,
          reason: input.reason,
          details: {
            moderationActionId: id,
            expiresAt: expiresAt?.toDate().toISOString() ?? null,
            hasInternalNote: !!input.internalNote,
          },
        },
        () => FieldValue.serverTimestamp(),
      ),
    );
    tx.set(signal(input.targetUid), {
      updatedAt: FieldValue.serverTimestamp(),
      changeId: randomUUID(),
    });
    return { data, alreadyApplied: false };
  });
  let notificationStatus = 'not_active';
  if (active(result.data, Timestamp.now()))
    try {
      const notice = await writeInAppNotification(
        input.targetUid,
        {
          category: 'account_warning',
          title: 'Varning utfärdad',
          previewText: 'Ditt konto har fått en varning från moderatorerna.',
          body:
            input.idempotencyKey || input.expiresAt || input.durationDays
              ? 'Se aktuell varning och giltighet i din profil.'
              : input.reason,
        },
        `warn-${id}`,
      );
      notificationStatus =
        notice.delivered || notice.skippedReason === 'duplicate' ? 'delivered' : 'skipped';
    } catch {
      notificationStatus = 'failed';
      logger.warn('Warning saved; notice delivery failed', {
        targetUid: input.targetUid,
        actionId: id,
      });
    }
  return {
    targetUid: input.targetUid,
    actionId: id,
    alreadyApplied: result.alreadyApplied,
    notificationStatus,
  };
});

async function mutateWarning(request: CallableRequest<unknown>, revoke: boolean) {
  const actor = await requireAdminActor(request);
  const parsed = updateInput.safeParse(request.data);
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Ogiltig varningsåtgärd.');
  const input = parsed.data;
  if (input.expectedActorUid && input.expectedActorUid !== actor.uid)
    throw new HttpsError('permission-denied', 'Administrator session changed. Reload the member.');
  const reference = items(input.targetUid).doc(input.warningId);
  const receipt = db
    .collection('adminMemberOperations')
    .doc(input.targetUid)
    .collection('items')
    .doc((revoke ? 'revoke-' : 'expiry-') + hash([actor.uid, input.idempotencyKey]));
  const fingerprint = hash(input);
  return db.runTransaction(async (tx) => {
    const [profile, warning, operation] = await Promise.all([
      tx.get(db.collection('users').doc(input.targetUid)),
      tx.get(reference),
      tx.get(receipt),
    ]);
    authorizeTarget(actor, input.targetUid, profile);
    if (operation.exists) {
      if (operation.get('fingerprint') !== fingerprint)
        throw new HttpsError('failed-precondition', 'Åtgärdsnyckeln avser ett annat beslut.');
      return { ...operation.get('result'), alreadyApplied: true };
    }
    if (!warning.exists) throw new HttpsError('not-found', 'Varningen finns inte.');
    if (revoke && warning.get('revokedAt') != null)
      return { warningId: input.warningId, alreadyApplied: true };
    if (warning.get('revokedAt') != null)
      throw new HttpsError(
        'failed-precondition',
        'En återkallad varning kan inte aktiveras igen. Utfärda en ny varning.',
      );
    if (warning.get('version') !== input.expectedVersion)
      throw new HttpsError(
        'failed-precondition',
        'Varningen har ändrats. Uppdatera och granska igen.',
      );
    const now = Timestamp.now(),
      expiresAt = revoke ? warning.get('expiresAt') : expiry(input, now);
    const result = {
      warningId: input.warningId,
      version: input.expectedVersion + 1,
      alreadyApplied: false,
    };
    tx.update(reference, {
      ...(revoke ? { revokedAt: now } : { expiresAt }),
      updatedAt: now,
      version: result.version,
    });
    tx.create(receipt, { fingerprint, result, createdAt: FieldValue.serverTimestamp() });
    tx.create(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: revoke ? 'user.warning.revoke' : 'user.warning.expiry',
          targetType: 'user',
          targetId: input.targetUid,
          reason: input.reason,
          details: {
            warningId: input.warningId,
            previousExpiresAt: warning.get('expiresAt')?.toDate().toISOString() ?? null,
            expiresAt: expiresAt?.toDate().toISOString() ?? null,
          },
        },
        () => FieldValue.serverTimestamp(),
      ),
    );
    tx.set(signal(input.targetUid), {
      updatedAt: FieldValue.serverTimestamp(),
      changeId: randomUUID(),
    });
    return result;
  });
}
export const revokeWarning = onCall(CALLABLE_OPTS, (request) => mutateWarning(request, true));
export const setWarningExpiry = onCall(CALLABLE_OPTS, (request) => mutateWarning(request, false));

export const listWarnings = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  const parsed = z
    .object({ ...pageInput, targetUid: uid })
    .strict()
    .safeParse(request.data);
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Ogiltig varningsförfrågan.');
  const input = parsed.data,
    cursor = historyCursor(input, ['adminWarnings', input.targetUid]);
  const query = items(input.targetUid)
    .where('createdAt', '<=', timestamp(cursor.asOf))
    .orderBy('createdAt', 'desc')
    .orderBy(FieldPath.documentId(), 'desc');
  const after = cursor.after.first;
  const [snapshot, count] = await Promise.all([
    (after ? query.startAfter(timestamp(after), after.id) : query).limit(input.pageSize + 1).get(),
    query.count().get(),
  ]);
  const docs = snapshot.docs.slice(0, input.pageSize),
    now = Timestamp.now();
  if (docs.length) cursor.after.first = documentPosition(docs.at(-1)!, 'createdAt');
  return {
    warnings: docs.map((doc) => {
      const data = doc.data();
      return {
        ...publicWarning(doc.id, data, now),
        internalNote: data.internalNote ?? '',
        revokedAt: data.revokedAt?.toDate().toISOString() ?? null,
        status: data.revokedAt ? 'revoked' : active(data, now) ? 'active' : 'expired',
      };
    }),
    serverNow: now.toDate().toISOString(),
    meta: {
      total: count.data().count,
      hasNext: snapshot.size > input.pageSize,
      nextCursor: snapshot.size > input.pageSize ? nextHistoryCursor(cursor) : null,
    },
  };
});

/** Essential own-account read: remains available under suspension/access restriction. */
export const listOwnWarnings = onCall(CALLABLE_OPTS, async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Sign-in required.');
  const parsed = z
    .object(pageInput)
    .strict()
    .safeParse(request.data ?? {});
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Invalid warning request.');
  const input = parsed.data,
    userId = request.auth.uid,
    cursor = historyCursor(input, ['ownWarnings', userId]),
    now = Timestamp.now();
  const permanent = items(userId)
    .where('revokedAt', '==', null)
    .where('expiresAt', '==', null)
    .orderBy('createdAt', 'desc')
    .orderBy(FieldPath.documentId(), 'desc');
  const expiring = items(userId)
    .where('revokedAt', '==', null)
    .where('expiresAt', '>', now)
    .orderBy('expiresAt', 'asc')
    .orderBy(FieldPath.documentId(), 'asc');
  const read = async (query: Query, slot: 'first' | 'second', field: string) => {
    const after = cursor.after[slot],
      position = after ? timestamp(after) : null;
    const [snapshot, count] = await Promise.all([
      (position ? query.startAfter(position, after!.id) : query)
        .limit(input.pageSize + 1)
        .select('reason', 'createdAt', 'expiresAt', 'version')
        .get(),
      query.count().get(),
    ]);
    return { docs: snapshot.docs, total: count.data().count, slot, field };
  };
  const [dated, indefinite] = await Promise.all([
    read(expiring, 'second', 'expiresAt'),
    read(permanent, 'first', 'createdAt'),
  ]);
  const merged = [
    ...dated.docs.map((doc) => ({ doc, page: dated })),
    ...indefinite.docs.map((doc) => ({ doc, page: indefinite })),
  ];
  const selected = merged.slice(0, input.pageSize),
    responseTime = Timestamp.now();
  for (const row of selected)
    cursor.after[row.page.slot] = documentPosition(row.doc, row.page.field);
  // Filter again at response time, so expiry during a slow query cannot leak an active reason.
  const warnings = selected
    .filter((row) => active(row.doc.data(), responseTime))
    .map(({ doc }) => publicWarning(doc.id, doc.data(), responseTime));
  return {
    userId,
    warnings,
    serverNow: responseTime.toDate().toISOString(),
    meta: {
      total: dated.total + indefinite.total,
      hasNext: merged.length > input.pageSize,
      nextCursor: merged.length > input.pageSize ? nextHistoryCursor(cursor) : null,
    },
  };
});
