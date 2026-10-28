import { createHash } from 'node:crypto';
import { FieldPath, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { db, adminAuth, adminRtdb } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import {
  buildAdminAuditEvent,
  guardModerationTarget,
  guardActorIsActiveAdmin,
} from '../admin/claims-core';
import { stopAndClear } from '../live/session';
import { toUserAccessState } from '../shared/access';
import { CPU_ADMIN, MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';
import {
  historyCursor,
  pageInput,
  timestamp,
  documentPosition,
  nextHistoryCursor,
} from '../admin/history-pagination';
import { identityKeys, providerIdentityKeys, isCarCommunityRestricted } from './carCommunity-core';
import {
  mirrorAccess,
  mirrorIdentity,
  readCarCommunityAccess,
  requireCarCommunityAccess,
  synchronizeInheritedAccess,
} from './carCommunity';
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const uid = z
  .string()
  .min(1)
  .max(128)
  .refine((v) => !v.includes('/') && v !== '.' && v !== '..');
const inputSchema = z
  .object({
    targetUid: uid,
    action: z.enum(['suspend', 'restore', 'blacklist', 'unlist']),
    reason: z.string().trim().min(1).max(500),
    expectedActorUid: uid,
    expectedVersion: z.number().int().min(0),
    expectedBlacklistIds: z.array(z.string().regex(/^[0-9a-f]{64}$/)).max(4),
    idempotencyKey: z.string().regex(/^[A-Za-z0-9._-]{16,128}$/),
  })
  .strict();
const hash = (v: unknown) => createHash('sha256').update(JSON.stringify(v)).digest('hex');
const accessRef = (targetUid: string) => db.collection('carCommunityAccess').doc(targetUid);
const identityRef = (key: string) => db.collection('carCommunityIdentityBlocks').doc(key);
export async function adminAccessView(targetUid: string) {
  const result = await readCarCommunityAccess(targetUid, false);
  const originRecords = await db
    .collection('carCommunityIdentityBlocks')
    .where('originUid', '==', targetUid)
    .where('blocked', '==', true)
    .limit(101)
    .get();
  if (originRecords.size > 100)
    throw new HttpsError('resource-exhausted', 'Access history requires an operator batch.');
  const active = [
    ...new Map(
      [...result.identities.filter((s) => s.get('blocked') === true), ...originRecords.docs].map(
        (s) => [s.id, s],
      ),
    ).values(),
  ];
  const policy = await db.collection('carCommunityPolicy').doc('access').get();
  return {
    ...result.status,
    initialized: result.data?.initialized === true,
    blacklisted: typeof result.data?.explicitBlacklistId === 'string' || active.length > 0,
    version: Number(result.data?.version ?? 0),
    ready: policy.get('enforceInitialized') === true && policy.get('rolloutState') === 'ready',
    blacklistIds: [
      ...new Set(
        [result.data?.explicitBlacklistId, ...active.map((s) => s.get('blacklistId'))].filter(
          (v): v is string => typeof v === 'string',
        ),
      ),
    ].sort(),
  };
}
export const getAccessTarget = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request);
  await requireCarCommunityAccess(actor.uid);
  const input = z.object({ targetUid: uid }).strict().safeParse(request.data);
  if (!input.success) throw new HttpsError('invalid-argument', 'Choose a target account.');
  const profile = await db.collection('users').doc(input.data.targetUid).get();
  return {
    uid: input.data.targetUid,
    actorUid: actor.uid,
    canManage:
      actor.uid !== input.data.targetUid &&
      (profile.get('role') !== 'owner' || actor.state.role === 'owner'),
    sharedRestricted: profile.get('suspended') === true || profile.get('deleted') === true,
    displayName: profile.get('displayName') ?? null,
    ...(await adminAccessView(input.data.targetUid)),
  };
});
export const updateAccess = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request);
  await requireCarCommunityAccess(actor.uid);
  const parsed = inputSchema.safeParse(request.data);
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Invalid access decision.');
  const input = parsed.data;
  if (input.expectedActorUid !== actor.uid)
    throw new HttpsError('permission-denied', 'Administrator session changed.');
  const policy = await db.collection('carCommunityPolicy').doc('access').get();
  if (policy.get('enforceInitialized') !== true || policy.get('rolloutState') !== 'ready')
    throw new HttpsError(
      'failed-precondition',
      'CarCommunity access administration is not available yet.',
    );
  const authUser = await adminAuth.getUser(input.targetUid).catch((error) => {
    if (input.action === 'unlist' && (error as { code?: string }).code === 'auth/user-not-found')
      return null;
    throw error;
  });
  const providers = authUser ? providerIdentityKeys(authUser.providerData) : {};
  if (process.env.FUNCTIONS_EMULATOR === 'true' && !process.env.FIREBASE_DATABASE_EMULATOR_HOST)
    throw new HttpsError('failed-precondition', 'Complete local Database emulator required.');
  await adminRtdb.ref('carCommunityPolicy/enforceInitialized').set(true);
  const operationId = hash([actor.uid, input.targetUid, input.idempotencyKey]);
  const receipt = db
    .collection('adminMemberOperations')
    .doc(input.targetUid)
    .collection('items')
    .doc(`access-${operationId}`);
  const fingerprint = hash(input);
  const current = await readCarCommunityAccess(input.targetUid, false);
  const relevantKeys = new Set(identityKeys(current.data));
  if (input.action === 'blacklist')
    for (const key of identityKeys(providers)) relevantKeys.add(key);
  if (input.action === 'unlist') {
    for (const snapshot of current.identities)
      if (snapshot.get('blocked') === true)
        for (const key of snapshot.get('relatedKeys') ?? [])
          if (typeof key === 'string' && /^[0-9a-f]{64}$/.test(key)) relevantKeys.add(key);
    const inherited = await db
      .collection('carCommunityIdentityBlocks')
      .where('originUid', '==', input.targetUid)
      .where('blocked', '==', true)
      .limit(101)
      .get();
    if (inherited.size > 100)
      throw new HttpsError('resource-exhausted', 'Too many identity records for one decision.');
    for (const snapshot of inherited.docs) relevantKeys.add(snapshot.id);
  }
  if (relevantKeys.size > 100)
    throw new HttpsError('resource-exhausted', 'Too many identity records.');
  const keys = [...relevantKeys].sort();
  const result = await db.runTransaction(async (tx) => {
    const [profile, access, oldOperation, latestPolicy, actorProfile, actorAccess] =
      await Promise.all([
        tx.get(db.collection('users').doc(input.targetUid)),
        tx.get(accessRef(input.targetUid)),
        tx.get(receipt),
        tx.get(db.collection('carCommunityPolicy').doc('access')),
        tx.get(db.collection('users').doc(actor.uid)),
        tx.get(accessRef(actor.uid)),
      ]);
    if (
      latestPolicy.get('enforceInitialized') !== true ||
      latestPolicy.get('rolloutState') !== 'ready'
    )
      throw new HttpsError('failed-precondition', 'Access administration is unavailable.');
    // Serialize admission with canonical role/product revocation, including
    // transaction retries and receipt replay. No admitted request is grandfathered.
    const freshActorState = toUserAccessState(actorProfile.data());
    const actorGuard = guardActorIsActiveAdmin(freshActorState);
    if (!actorGuard.ok) throw new HttpsError(actorGuard.code, actorGuard.message);
    const actorKeys = identityKeys(actorAccess.data());
    const actorIdentities = actorKeys.length ? await tx.getAll(...actorKeys.map(identityRef)) : [];
    if (
      isCarCommunityRestricted(
        actorAccess.data(),
        actorIdentities.map((s) => s.data() ?? {}),
      )
    )
      throw new HttpsError('permission-denied', 'CarCommunity administrator access is restricted.');
    const identities = keys.length ? await tx.getAll(...keys.map(identityRef)) : [];
    const active = identities.filter((s) => s.get('blocked') === true);
    const originUids = [
      ...new Set(
        active
          .map((s) => s.get('originUid'))
          .filter((v): v is string => typeof v === 'string' && v !== input.targetUid),
      ),
    ];
    const origins = originUids.length ? await tx.getAll(...originUids.map(accessRef)) : [];
    const cases = [
      ...new Set(
        active.map((s) => s.get('blacklistId')).filter((v): v is string => typeof v === 'string'),
      ),
    ];
    const caseSnapshots = cases.length
      ? await tx.getAll(...cases.map((id) => db.collection('carCommunityBlacklistCases').doc(id)))
      : [];
    if (!profile.exists && input.action !== 'unlist')
      throw new HttpsError('not-found', 'Member not found.');
    const targetRole = toUserAccessState(profile.data()).role;
    const protectedOwner =
      targetRole === 'owner' || active.some((s) => s.get('protectedRole') === 'owner');
    const guard = guardModerationTarget({
      actorUid: actor.uid,
      actorRole: freshActorState.role,
      targetUid: input.targetUid,
      targetRole: protectedOwner ? 'owner' : targetRole,
    });
    if (!guard.ok) throw new HttpsError(guard.code, guard.message);
    if (oldOperation.exists) {
      if (oldOperation.get('fingerprint') !== fingerprint)
        throw new HttpsError(
          'failed-precondition',
          'This decision key was already used for another action.',
        );
      return { ...oldOperation.get('result'), alreadyApplied: true };
    }
    const old = access.data() ?? {};
    const ids = [
      ...new Set(
        [old.explicitBlacklistId, ...active.map((s) => s.get('blacklistId'))].filter(
          (v): v is string => typeof v === 'string',
        ),
      ),
    ].sort();
    if (
      Number(old.version ?? 0) !== input.expectedVersion ||
      JSON.stringify(ids) !== JSON.stringify([...input.expectedBlacklistIds].sort())
    )
      throw new HttpsError(
        'failed-precondition',
        'Access changed. Reload and review the decision again.',
      );
    const changedIdentities: Array<{ key: string; data: FirebaseFirestore.DocumentData }> = [];
    const changedAccess: Array<{ uid: string; data: FirebaseFirestore.DocumentData }> = [];
    const next = {
      ...old,
      initialized: true,
      suspended: old.suspended === true,
      explicitBlacklistId: old.explicitBlacklistId ?? null,
      version: Number(old.version ?? 0) + 1,
    };
    if (input.action === 'suspend') {
      next.suspended = true;
      Object.assign(next, { suspensionReason: input.reason });
    }
    if (input.action === 'restore') {
      next.suspended = false;
      Object.assign(next, { suspensionReason: null });
    }
    if (input.action === 'blacklist') {
      if (ids.length)
        throw new HttpsError('failed-precondition', 'This account is already blacklisted.');
      const providerKeys = identityKeys(providers);
      if (!providerKeys.length)
        throw new HttpsError(
          'failed-precondition',
          'No verified Google or Apple identity is linked. Persistent blacklisting is unavailable.',
        );
      next.explicitBlacklistId = operationId;
      Object.assign(next, { blacklistReason: input.reason });
      Object.assign(next, providers);
      for (const key of providerKeys) {
        const previous = identities.find((s) => s.id === key);
        if (previous?.get('blocked') === true && previous.get('originUid') !== input.targetUid)
          throw new HttpsError(
            'failed-precondition',
            'The identity is already part of another blacklist decision.',
          );
        changedIdentities.push({
          key,
          data: {
            blocked: true,
            version: Number(previous?.get('version') ?? 0) + 1,
            blacklistId: operationId,
            originUid: input.targetUid,
            relatedKeys: providerKeys,
            protectedRole: targetRole,
            reason: input.reason,
            updatedAt: FieldValue.serverTimestamp(),
          },
        });
      }
      tx.create(db.collection('carCommunityBlacklistCases').doc(operationId), {
        targetUid: input.targetUid,
        displayName: profile.get('displayName') ?? '',
        protectedRole: targetRole,
        identityKeys: providerKeys,
        status: 'active',
        reason: input.reason,
        createdAt: Timestamp.now(),
        actorUid: actor.uid,
      });
    }
    if (input.action === 'unlist') {
      next.explicitBlacklistId = null;
      Object.assign(next, { blacklistReason: null });
      for (const snapshot of active)
        changedIdentities.push({
          key: snapshot.id,
          data: {
            ...snapshot.data(),
            blocked: false,
            version: Number(snapshot.get('version') ?? 0) + 1,
            unlistedAt: FieldValue.serverTimestamp(),
            unlistedBy: actor.uid,
            unlistReason: input.reason,
          },
        });
      for (const origin of origins)
        if (typeof origin.get('explicitBlacklistId') === 'string') {
          if (!ids.includes(origin.get('explicitBlacklistId')))
            throw new HttpsError(
              'failed-precondition',
              'A newer blacklist decision exists. Reload and review.',
            );
          changedAccess.push({
            uid: origin.id,
            data: {
              ...origin.data(),
              explicitBlacklistId: null,
              version: Number(origin.get('version') ?? 0) + 1,
            },
          });
        }
      for (const snapshot of caseSnapshots)
        if (snapshot.exists)
          tx.update(snapshot.ref, {
            status: 'unlisted',
            unlistedAt: FieldValue.serverTimestamp(),
            unlistedBy: actor.uid,
            unlistReason: input.reason,
          });
    }
    if (profile.exists || access.exists) changedAccess.push({ uid: input.targetUid, data: next });
    for (const row of changedIdentities) tx.set(identityRef(row.key), row.data);
    for (const row of changedAccess)
      tx.set(accessRef(row.uid), { ...row.data, updatedAt: FieldValue.serverTimestamp() });
    const outcome = {
      targetUid: input.targetUid,
      action: input.action,
      version: next.version,
      alreadyApplied: false,
    };
    tx.create(receipt, { fingerprint, result: outcome, createdAt: FieldValue.serverTimestamp() });
    tx.create(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: `user.carCommunity.${input.action}`,
          targetType: 'user',
          targetId: input.targetUid,
          reason: input.reason,
          details: {
            version: next.version,
            blacklistId: input.action === 'blacklist' ? operationId : null,
            identityCount: changedIdentities.length,
          },
        },
        () => FieldValue.serverTimestamp(),
      ),
    );
    return outcome;
  });
  // Read current canonical state on retries, never replay an old mirror payload.
  const latestAccess = await accessRef(input.targetUid).get();
  if (latestAccess.exists) await mirrorAccess(input.targetUid, latestAccess.data()!);
  for (const key of keys) {
    const latest = await identityRef(key).get();
    if (latest.exists) await mirrorIdentity(key, latest.data()!);
  }
  // All origin UID rows have onWrite retry mirrors as well; mirror before ack.
  const changedOrigins = new Set(
    current.identities
      .filter((s) => s.get('blocked') === true)
      .map((s) => s.get('originUid'))
      .filter((v): v is string => typeof v === 'string' && v !== input.targetUid),
  );
  for (const origin of changedOrigins) {
    const latest = await accessRef(origin).get();
    if (latest.exists) await mirrorAccess(origin, latest.data()!);
  }
  await synchronizeInheritedAccess(keys);
  if (input.action === 'suspend' || input.action === 'blacklist') {
    const current = await readCarCommunityAccess(input.targetUid, false);
    if (current.restricted) {
      await stopAndClear(input.targetUid, 'hide_me_now');
      await adminRtdb.ref(`presence/${input.targetUid}`).remove();
    }
  }
  return result;
});

/** Cases, rather than duplicate provider rows; retained cases support unlisting
 * after the original shared Auth/user record has been explicitly erased. */
export const listAccessBlocks = onCall(CALLABLE_OPTS, async (request) => {
  await requireAdminActor(request);
  const parsed = z
    .object(pageInput)
    .strict()
    .safeParse(request.data ?? {});
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Invalid blacklist page.');
  const input = parsed.data,
    cursor = historyCursor(input, ['activeBlacklistCases']);
  const query = db
    .collection('carCommunityBlacklistCases')
    .where('status', '==', 'active')
    .where('createdAt', '<=', timestamp(cursor.asOf))
    .orderBy('createdAt', 'desc')
    .orderBy(FieldPath.documentId(), 'desc');
  const after = cursor.after.first;
  const [page, count] = await Promise.all([
    (after ? query.startAfter(timestamp(after), after.id) : query).limit(input.pageSize + 1).get(),
    query.count().get(),
  ]);
  const docs = page.docs.slice(0, input.pageSize);
  if (docs.length) cursor.after.first = documentPosition(docs.at(-1)!, 'createdAt');
  return {
    cases: docs.map((doc) => ({
      id: doc.id,
      targetUid: doc.get('targetUid'),
      displayName: doc.get('displayName') ?? '',
      reason: doc.get('reason'),
      createdAt: doc.get('createdAt').toDate().toISOString(),
      identityCount: Array.isArray(doc.get('identityKeys')) ? doc.get('identityKeys').length : 0,
    })),
    meta: {
      total: count.data().count,
      hasNext: page.size > input.pageSize,
      nextCursor: page.size > input.pageSize ? nextHistoryCursor(cursor) : null,
    },
  };
});
