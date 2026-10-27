import { beforeEach, expect, it, vi } from 'vitest';
const fixture = vi.hoisted(() => ({
  actorRole: 'admin',
  actorSuspended: false,
  productSuspended: false,
  identityBlocked: false,
  targetRole: 'user',
  writes: vi.fn(),
}));
function snapshot(path: string) {
  const data =
    path === 'users/actor'
      ? { role: fixture.actorRole, suspended: fixture.actorSuspended, deleted: false }
      : path === 'users/target'
        ? { role: fixture.targetRole, suspended: false, deleted: false }
        : path === 'carCommunityPolicy/access'
          ? { enforceInitialized: true, rolloutState: 'ready' }
          : path === 'carCommunityAccess/actor'
            ? {
                initialized: true,
                suspended: fixture.productSuspended,
                version: 1,
                googleKey: 'a'.repeat(64),
              }
            : path === 'carCommunityIdentityBlocks/' + 'a'.repeat(64)
              ? { blocked: fixture.identityBlocked }
              : path === 'carCommunityAccess/target'
                ? { initialized: true, suspended: false, version: 0 }
                : undefined;
  return {
    exists: data !== undefined,
    id: path.split('/').at(-1),
    ref: reference(path),
    data: () => data,
    get: (key: string) => data?.[key as keyof typeof data],
  };
}
function reference(path: string): {
  path: string;
  get: () => Promise<ReturnType<typeof snapshot>>;
  collection: (name: string) => ReturnType<typeof collection>;
} {
  return {
    path,
    get: async () => snapshot(path),
    collection: (name: string) => collection(`${path}/${name}`),
  };
}
function collection(path: string): { doc: (name?: string) => ReturnType<typeof reference> } {
  return { doc: (name = 'generated') => reference(`${path}/${name}`) };
}
vi.mock('../firebase', () => ({
  db: {
    collection: (name: string) => collection(name),
    runTransaction: async (handler: (tx: unknown) => unknown) =>
      handler({
        get: async (ref: { path: string }) => snapshot(ref.path),
        getAll: async (...refs: Array<{ path: string }>) => refs.map((ref) => snapshot(ref.path)),
        set: fixture.writes,
        create: fixture.writes,
        update: fixture.writes,
      }),
  },
  adminAuth: { getUser: async () => ({ providerData: [] }) },
  adminRtdb: { ref: () => ({ set: async () => undefined, remove: async () => undefined }) },
}));
// The request has passed admission with owner authority. These fixtures model
// authoritative state changed during prework, before the decision transaction.
vi.mock('../admin/actorContext', () => ({
  requireAdminActor: async () => ({
    uid: 'actor',
    state: { role: 'owner', suspended: false, deleted: false },
  }),
}));
vi.mock('./carCommunity', () => ({
  requireCarCommunityAccess: async () => undefined,
  readCarCommunityAccess: async () => ({
    data: { initialized: true, version: 0 },
    identities: [],
    restricted: false,
  }),
  mirrorAccess: async () => undefined,
  mirrorIdentity: async () => undefined,
  synchronizeInheritedAccess: async () => undefined,
}));
vi.mock('../live/session', () => ({ stopAndClear: async () => undefined }));
import { updateAccess } from './adminAccess';
beforeEach(() => {
  fixture.actorRole = 'admin';
  fixture.actorSuspended = false;
  fixture.productSuspended = false;
  fixture.identityBlocked = false;
  fixture.targetRole = 'user';
  fixture.writes.mockClear();
  process.env.FIREBASE_DATABASE_EMULATOR_HOST = '127.0.0.1:9000';
});
async function decision() {
  return updateAccess.run({
    auth: { uid: 'actor', token: { admin: true } },
    data: {
      targetUid: 'target',
      action: 'suspend',
      reason: 'Reviewed decision',
      expectedActorUid: 'actor',
      expectedVersion: 0,
      expectedBlacklistIds: [],
      idempotencyKey: 'local-transaction-intent',
    },
  } as never);
}
it.each([
  'demoted',
  'shared-suspended',
  'product-suspended',
  'identity-blacklisted',
  'former-owner',
] as const)('rechecks %s authority in the canonical transaction', async (change) => {
  if (change === 'demoted') fixture.actorRole = 'user';
  if (change === 'shared-suspended') fixture.actorSuspended = true;
  if (change === 'product-suspended') fixture.productSuspended = true;
  if (change === 'identity-blacklisted') fixture.identityBlocked = true;
  if (change === 'former-owner') fixture.targetRole = 'owner';
  await expect(decision()).rejects.toMatchObject({ code: 'permission-denied' });
  expect(fixture.writes).not.toHaveBeenCalled();
});
it('commits the reviewed decision when current actor and target authority permit it', async () => {
  await expect(decision()).resolves.toMatchObject({ action: 'suspend', alreadyApplied: false });
  expect(fixture.writes).toHaveBeenCalled();
});
