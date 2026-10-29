/** Product-only access. No Auth disabling, token revocation or canonical restriction writes. */
import { FieldValue, Filter, type Transaction } from 'firebase-admin/firestore';
import { z } from 'zod';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { db, adminAuth, adminRtdb } from '../firebase';
import { providerIdentityKeys, identityKeys, isCarCommunityRestricted } from './carCommunity-core';
import { CPU_ADMIN, MAX_INSTANCES_ADMIN, MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
const accessRef = (uid: string) => db.collection('carCommunityAccess').doc(uid);
const identityRef = (key: string) => db.collection('carCommunityIdentityBlocks').doc(key);
const fields = (data: FirebaseFirestore.DocumentData) => ({
  initialized: data.initialized === true,
  suspended: data.suspended === true,
  inheritedBlocked: data.inheritedBlocked === true,
  explicitBlacklistId:
    typeof data.explicitBlacklistId === 'string' ? data.explicitBlacklistId : null,
  googleKey: typeof data.googleKey === 'string' ? data.googleKey : null,
  appleKey: typeof data.appleKey === 'string' ? data.appleKey : null,
  version: Number(data.version ?? 0),
});
export async function accessEnforced() {
  const policy = await db.collection('carCommunityPolicy').doc('access').get();
  return policy.get('enforceInitialized') !== false;
}
/** Preserve an inherited blocked identity even if it is unlinked from shared Auth. */
export async function initializeCarCommunityAccess(uid: string) {
  const authUser = await adminAuth.getUser(uid).catch(() => {
    throw new HttpsError('permission-denied', 'CarCommunity access could not be verified.');
  });
  const current = providerIdentityKeys(authUser.providerData);
  const data = await db.runTransaction(async (tx) => {
    const existing = await tx.get(accessRef(uid)),
      old = existing.data();
    const keys = [...new Set([...identityKeys(old), ...identityKeys(current)])];
    const snapshots = keys.length ? await tx.getAll(...keys.map(identityRef)) : [];
    const blocked = new Set(snapshots.filter((s) => s.get('blocked') === true).map((s) => s.id));
    const next = {
      initialized: true,
      blacklistReason: snapshots.find((s) => s.get('blocked') === true)?.get('reason') ?? null,
      inheritedBlocked: blocked.size > 0,
      suspended: old?.suspended === true,
      explicitBlacklistId:
        typeof old?.explicitBlacklistId === 'string' ? old.explicitBlacklistId : null,
      googleKey:
        typeof old?.googleKey === 'string' && blocked.has(old.googleKey)
          ? old.googleKey
          : current.googleKey,
      appleKey:
        typeof old?.appleKey === 'string' && blocked.has(old.appleKey)
          ? old.appleKey
          : current.appleKey,
    };
    if (existing.exists && Object.entries(next).every(([key, value]) => old?.[key] === value))
      return fields(old!);
    const value = { ...next, version: Number(old?.version ?? 0) + 1 };
    tx.set(accessRef(uid), { ...value, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return value;
  });
  // The own readiness call awaits this mirror; failed mirroring is retriable.
  await mirrorAccess(uid, data);
  return data;
}
export async function readCarCommunityAccess(uid: string, initialize = true) {
  let snapshot = await accessRef(uid).get();
  if (initialize) {
    await initializeCarCommunityAccess(uid);
    snapshot = await accessRef(uid).get();
  }
  const data = snapshot.data();
  const keys = identityKeys(data),
    identities = keys.length ? await db.getAll(...keys.map(identityRef)) : [];
  const blocked = identities.filter((s) => s.get('blocked') === true);
  return {
    data,
    identities,
    restricted: isCarCommunityRestricted(
      data,
      identities.map((s) => s.data() ?? {}),
    ),
    status: {
      suspended: data?.suspended === true,
      blacklisted: typeof data?.explicitBlacklistId === 'string' || blocked.length > 0,
      reason:
        typeof data?.suspensionReason === 'string'
          ? data.suspensionReason
          : typeof blocked[0]?.get('reason') === 'string'
            ? (blocked[0].get('reason') as string)
            : null,
      persistentIdentitySupported: keys.length > 0,
    },
  };
}
export async function requireCarCommunityAccess(uid: string) {
  if (!(await accessEnforced())) return;
  // Resolve providers on the server at every call, including already-issued
  // shared Auth sessions; a fresh/recreated account cannot choose its identity.
  const access = await readCarCommunityAccess(uid);
  if (access.restricted)
    throw new HttpsError('permission-denied', 'CarCommunity access is restricted.', {
      reason: 'carcommunity_access_restricted',
    });
}
async function monotonicMirror(path: string, value: Record<string, unknown>) {
  if (process.env.FUNCTIONS_EMULATOR === 'true' && !process.env.FIREBASE_DATABASE_EMULATOR_HOST)
    throw new HttpsError(
      'failed-precondition',
      'The complete local Database emulator is required.',
    );
  await adminRtdb
    .ref(path)
    .transaction((current) =>
      Number(current?.version ?? -1) > Number(value.version) ? undefined : value,
    );
}
export async function mirrorAccess(uid: string, data: FirebaseFirestore.DocumentData) {
  await monotonicMirror(`carCommunityAccess/${uid}`, fields(data));
}
export async function mirrorIdentity(key: string, data: FirebaseFirestore.DocumentData) {
  await monotonicMirror(`carCommunityIdentityBlocks/${key}`, {
    blocked: data.blocked === true,
    version: Number(data.version ?? 0),
  });
}
const mirrorOptions = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  retry: true,
};
export const onAccessWrite = onDocumentWritten(
  { ...mirrorOptions, document: 'carCommunityAccess/{uid}' },
  async (event) => {
    if (!event.data) return;
    const snapshot = await accessRef(event.params.uid).get();
    await mirrorAccess(
      event.params.uid,
      snapshot.exists
        ? snapshot.data()!
        : { initialized: false, version: Number(event.data.before.get('version') ?? 0) + 1 },
    );
  },
);
export const onIdentityWrite = onDocumentWritten(
  { ...mirrorOptions, document: 'carCommunityIdentityBlocks/{key}' },
  async (event) => {
    if (!event.data) return;
    const snapshot = await identityRef(event.params.key).get();
    // A tombstone remains blocking. Only an audited unlist writes blocked:false.
    await mirrorIdentity(
      event.params.key,
      snapshot.exists
        ? snapshot.data()!
        : { blocked: true, version: Number(event.data.before.get('version') ?? 0) + 1 },
    );
    await synchronizeInheritedAccess([event.params.key]);
  },
);
export const getAccess = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request) => {
    if (!request.auth) throw new HttpsError('unauthenticated', 'Sign-in required.');
    if (
      !z
        .object({})
        .strict()
        .safeParse(request.data ?? {}).success
    )
      throw new HttpsError('invalid-argument', 'Own account only.');
    const result = await readCarCommunityAccess(request.auth.uid);
    const profile = await db.collection('users').doc(request.auth.uid).get();
    return {
      userId: request.auth.uid,
      version: Number(result.data?.version ?? 0),
      ...result.status,
      sharedRestricted: profile.get('suspended') === true || profile.get('deleted') === true,
    };
  },
);
/** Transaction-safe reads used by the audited admin decision handler. */
export async function readAccessTransaction(tx: Transaction, uid: string) {
  return tx.get(accessRef(uid));
}

/** Storage supports only two cross-service rule reads. Its one-doc restriction
 * projection is updated for every linked UID before a blacklist/unlist ack.
 * Registration transactions read the same identity docs, so a concurrent new
 * account either appears in this query or retries against the new block state.
 */
export async function synchronizeInheritedAccess(keys: readonly string[]) {
  const uids = new Set<string>();
  for (const key of keys) {
    const matches = await db
      .collection('carCommunityAccess')
      .where(Filter.or(Filter.where('googleKey', '==', key), Filter.where('appleKey', '==', key)))
      .limit(101)
      .get();
    if (matches.size > 100)
      throw new HttpsError('resource-exhausted', 'Access projection requires an operator batch.');
    for (const snapshot of matches.docs) uids.add(snapshot.id);
  }
  for (const uid of uids) {
    const data = await db.runTransaction(async (tx) => {
      const snapshot = await tx.get(accessRef(uid));
      if (!snapshot.exists) return null;
      const old = snapshot.data()!,
        keys = identityKeys(old),
        identities = keys.length ? await tx.getAll(...keys.map(identityRef)) : [];
      const inheritedBlocked = identities.some((s) => s.get('blocked') === true);
      if (old.inheritedBlocked === inheritedBlocked) return old;
      const blacklistReason =
        identities.find((s) => s.get('blocked') === true)?.get('reason') ?? null;
      const next = {
        ...old,
        inheritedBlocked,
        blacklistReason,
        version: Number(old.version ?? 0) + 1,
      };
      tx.update(snapshot.ref, {
        inheritedBlocked,
        blacklistReason,
        version: next.version,
        updatedAt: FieldValue.serverTimestamp(),
      });
      return next;
    });
    if (data) await mirrorAccess(uid, data);
  }
}
