import { Timestamp, type Firestore, type Transaction } from 'firebase-admin/firestore';
import { describe, expect, it, vi } from 'vitest';
// Exercise the real single writer without Firebase app initialization or network.
vi.mock('../firebase', () => ({ db: undefined }));
import { writeInAppNotification } from '../notifications/deliver';
import {
  buildPushDeepLink,
  decidePushDelivery,
  ADMIN_SENDABLE_CATEGORIES,
} from '../notifications/notifications-core';
import {
  ClubInvitationNotifications,
  invitationGeneration,
  invitationNotificationId,
  invitationReceiptPath,
  newInvitationGeneration,
} from './service';

type Data = Record<string, unknown>;
const NOW = 1800000000000;
const CREATED = Timestamp.fromMillis(NOW - 1000);
const JOINED = Timestamp.fromMillis(NOW - 100000);
const path = 'driverClubs/club/invitations/recipient';
const receipt = invitationReceiptPath('club', 'recipient');
const invite = (extra: Data = {}): Data => ({
  clubId: 'club',
  recipientUid: 'recipient',
  inviterUid: 'sender',
  status: 'pending',
  createdAt: CREATED,
  expiresAt: Timestamp.fromMillis(NOW + 100000),
  ...extra,
});
const user = () => ({
  onboardingCompletedAt: JOINED,
  status: 'active',
  deleted: false,
  suspended: false,
});

/** Serialized atomic store with retry injection: only fixed docs are supported. */
class Store {
  readonly docs = new Map<string, Data>();
  readonly reads: string[] = [];
  writes = 0;
  failPath: string | undefined;
  beforeCommit: (() => void) | undefined;
  private queue = Promise.resolve();
  seed(path: string, data: Data) {
    this.docs.set(path, data);
    return this;
  }
  doc(path: string) {
    return { path, id: path.split('/').at(-1)! };
  }
  collection(path: string) {
    return {
      doc: (id: string) => ({
        ...this.doc(`${path}/${id}`),
        collection: (child: string) => this.collection(`${path}/${id}/${child}`),
      }),
    };
  }
  async runTransaction<T>(run: (tx: Transaction) => Promise<T>): Promise<T> {
    const previous = this.queue;
    let release!: () => void;
    this.queue = new Promise<void>((resolve) => {
      release = resolve;
    });
    await previous;
    try {
      for (;;) {
        const pending = new Map<string, Data>();
        const read = async (ref: { path: string }) => {
          if (pending.size) throw new Error('Read after write');
          this.reads.push(ref.path);
          if (this.failPath === ref.path) throw new Error('unavailable');
          const data = this.docs.get(ref.path);
          return { exists: data !== undefined, data: () => data };
        };
        const tx = {
          firestore: this,
          get: read,
          getAll: (...refs: { path: string }[]) => Promise.all(refs.map(read)),
          set: (ref: { path: string }, data: Data) => {
            pending.set(ref.path, data);
          },
        };
        const result = await run(tx as unknown as Transaction);
        if (this.beforeCommit) {
          const conflict = this.beforeCommit;
          this.beforeCommit = undefined;
          conflict();
          continue;
        }
        for (const [key, data] of pending) {
          this.docs.set(key, data);
          this.writes++;
        }
        return result;
      }
    } finally {
      release();
    }
  }
  inbox() {
    return [...this.docs].filter(([key]) => key.startsWith('notifications/recipient/items/'));
  }
}
function setup() {
  const store = new Store()
    .seed('config/sharedClubs', {
      enabled: true,
      invitationNotificationsReady: true,
      activatedAt: JOINED,
    })
    .seed(path, invite())
    .seed('driverClubs/club', {
      schemaVersion: 2,
      status: 'active',
      visibility: 'closed',
      ownerUid: 'sender',
    })
    .seed('users/recipient', user())
    .seed('users/sender', user())
    .seed('carCommunityAccess/recipient', { initialized: true })
    .seed('driverClubAccounts/sender', { activeClubId: 'club', role: 'owner', joinedAt: JOINED })
    .seed('driverClubs/club/members/sender', { role: 'owner', joinedAt: JOINED });
  const identity = vi.fn(async () => ({ disabled: false, providerData: [] }));
  const service = new ClubInvitationNotifications(
    store as unknown as Firestore,
    identity,
    writeInAppNotification,
    () => NOW,
  );
  const process = (data: Data | undefined = store.docs.get(path)) =>
    service.process('club', 'recipient', data);
  const push = () =>
    service.canPush(
      'club',
      'recipient',
      invitationNotificationId(invitationGeneration('club', 'recipient', invite())!),
    );
  return { store, service, identity, process, push };
}

describe('canonical club invitation notifications', () => {
  it('creates one optional inbox item via the real writer and existing push contract', async () => {
    const { store, process, push } = setup();
    expect(await process()).toBe('delivered');
    expect(store.inbox()).toHaveLength(1);
    expect(store.inbox()[0]![1]).toMatchObject({
      category: 'club_invite',
      actionType: 'open_notifications',
      relatedEntityId: 'club',
      read: false,
    });
    expect(buildPushDeepLink('club_invite', 'club', 'recipient')).toEqual({
      target: 'clubs',
      entityId: 'club',
    });
    expect(ADMIN_SENDABLE_CATEGORIES).not.toContain('club_invite');
    expect(await push()).toBe(true);
    // No private club/sender name in the lock-screen preview.
    expect(store.inbox()[0]![1].previewText).toBe('Du har fått en inbjudan till en klubb.');
    expect(store.reads.length).toBe(37);
  });

  it('serializes concurrent duplicate delivery and preserves read/deleted inbox state', async () => {
    const { store, process } = setup();
    expect(await Promise.all([process(), process(), process()])).toEqual([
      'delivered',
      'duplicate',
      'duplicate',
    ]);
    const [key, value] = store.inbox()[0]!;
    store.seed(key, { ...value, read: true });
    expect(await process()).toBe('duplicate');
    expect(store.docs.get(key)?.read).toBe(true);
    store.docs.delete(key);
    expect(await process()).toBe('duplicate');
    expect(store.inbox()).toHaveLength(0);
  });

  it('suppresses older events and pending generations after cancel/replace', async () => {
    const { store, process } = setup();
    const old = invite();
    store.seed(path, invite({ status: 'cancelled' }));
    expect(await process(old)).toBe('ineligible');
    store.seed(path, invite({ createdAt: Timestamp.fromMillis(NOW - 100) }));
    const beforeStale = store.reads.length;
    expect(await process(old)).toBe('ineligible');
    expect(store.reads.length - beforeStale).toBe(4);
    expect(await process()).toBe('delivered');
    store.seed(path, old);
    expect(await process(old)).toBe('duplicate');
    expect(store.inbox()).toHaveLength(1);
  });

  it('supports a genuine reinvite with a distinct native-nanosecond generation', async () => {
    const { store, process } = setup();
    await process();
    const next = invite({ createdAt: new Timestamp(CREATED.seconds, CREATED.nanoseconds + 1) });
    store.seed(path, next);
    expect(
      newInvitationGeneration('club', 'recipient', invite({ status: 'declined' }), next),
    ).not.toBeNull();
    expect(await process()).toBe('delivered');
    expect(store.inbox()).toHaveLength(2);
  });

  it.each(['accepted', 'declined', 'cancelled', 'expired'])(
    'ignores %s and deletion events without reads',
    async (status) => {
      const { store, process } = setup();
      expect(await process(invite({ status }))).toBe('invalid');
      expect(newInvitationGeneration('club', 'recipient', invite(), undefined)).toBeNull();
      expect(store.reads).toHaveLength(0);
    },
  );

  it('does not turn pending edits, expiry extension, or older timestamps into new generations', () => {
    expect(newInvitationGeneration('club', 'recipient', invite(), invite())).toBeNull();
    expect(
      newInvitationGeneration(
        'club',
        'recipient',
        invite(),
        invite({ expiresAt: Timestamp.fromMillis(NOW + 200000) }),
      ),
    ).toBeNull();
    expect(
      newInvitationGeneration(
        'club',
        'recipient',
        invite(),
        invite({ createdAt: Timestamp.fromMillis(NOW - 500) }),
      ),
    ).toBeNull();
    expect(
      newInvitationGeneration(
        'club',
        'recipient',
        invite({ status: 'cancelled' }),
        invite({ createdAt: Timestamp.fromMillis(NOW - 2000) }),
      ),
    ).toBeNull();
    expect(
      newInvitationGeneration(
        'club',
        'recipient',
        invite({ expiresAt: Timestamp.fromMillis(NOW - 500) }),
        invite({ createdAt: Timestamp.fromMillis(NOW - 100) }),
      ),
    ).not.toBeNull();
  });

  it.each([
    { recipientUid: 'other' },
    { clubId: 'other' },
    { inviterUid: 'recipient' },
    { inviterUid: 'bad/path' },
    { createdAt: new Date(NOW) },
    { createdAt: { seconds: CREATED.seconds, nanoseconds: 0 } },
    { expiresAt: '2099-01-01' },
    { expiresAt: CREATED },
    { expiresAt: Timestamp.fromMillis(NOW + 8 * 86400000) },
  ])('rejects malformed documents before reads: %j', async (changes) => {
    const { store, process } = setup();
    expect(await process(invite(changes))).toBe('invalid');
    expect(store.reads).toHaveLength(0);
  });

  const suppressed: [string, string, Data | null][] = [
    [
      'missing notification readiness',
      'config/sharedClubs',
      { enabled: true, activatedAt: JOINED },
    ],
    [
      'disabled notification readiness',
      'config/sharedClubs',
      { enabled: true, invitationNotificationsReady: false, activatedAt: JOINED },
    ],
    [
      'disabled feature',
      'config/sharedClubs',
      { enabled: false, invitationNotificationsReady: true, activatedAt: JOINED },
    ],
    [
      'missing activation',
      'config/sharedClubs',
      { enabled: true, invitationNotificationsReady: true },
    ],
    [
      'archived club',
      'driverClubs/club',
      { schemaVersion: 2, status: 'archived', visibility: 'closed', ownerUid: 'sender' },
    ],
    [
      'open club',
      'driverClubs/club',
      { schemaVersion: 2, status: 'active', visibility: 'open', ownerUid: 'sender' },
    ],
    [
      'legacy club',
      'driverClubs/club',
      { status: 'active', visibility: 'closed', ownerUid: 'sender' },
    ],
    ['missing recipient', 'users/recipient', null],
    ['deleted recipient', 'users/recipient', { ...user(), deleted: true }],
    ['suspended recipient', 'users/recipient', { ...user(), suspended: true }],
    ['banned recipient', 'users/recipient', { ...user(), status: 'banned' }],
    ['malformed recipient', 'users/recipient', { ...user(), suspended: 'false' }],
    ['incomplete onboarding', 'users/recipient', {}],
    ['deleted sender', 'users/sender', { ...user(), deleted: true }],
    ['revoked recipient', 'driverClubAccounts/recipient', { deletionRevoked: true }],
    [
      'revoked sender',
      'driverClubAccounts/sender',
      { activeClubId: 'club', role: 'owner', joinedAt: JOINED, deletionRevoked: true },
    ],
    ['demoted sender', 'driverClubs/club/members/sender', { role: 'member', joinedAt: JOINED }],
    ['left sender', 'driverClubAccounts/sender', { activeClubId: null }],
    [
      'stale membership',
      'driverClubAccounts/sender',
      { activeClubId: 'club', role: 'owner', joinedAt: CREATED },
    ],
    [
      'already a member',
      'driverClubs/club/members/recipient',
      { role: 'member', joinedAt: JOINED },
    ],
    ['club ban', 'driverClubs/club/bans/recipient', {}],
    ['legacy outgoing block', 'driverUsers/recipient/blockedUsers/sender', {}],
    ['legacy incoming block', 'driverUsers/sender/blockedUsers/recipient', {}],
    ['outgoing block', 'userBlocks/recipient/blocked/sender', {}],
    ['incoming block', 'userBlocks/sender/blocked/recipient', {}],
    ['missing canonical access', 'carCommunityAccess/recipient', null],
    [
      'canonical suspension',
      'carCommunityAccess/recipient',
      { initialized: true, suspended: true },
    ],
    [
      'inherited block',
      'carCommunityAccess/recipient',
      { initialized: true, inheritedBlocked: true },
    ],
    [
      'blacklist',
      'carCommunityAccess/recipient',
      { initialized: true, explicitBlacklistId: 'blocked' },
    ],
    ['identity block', 'carCommunityIdentityBlocks/' + 'a'.repeat(64), { blocked: true }],
  ];
  it.each(suppressed)('suppresses %s at inbox and push time', async (_, key, data) => {
    const { store, process, push } = setup();
    if (key.startsWith('carCommunityIdentityBlocks'))
      store.seed('carCommunityAccess/recipient', { initialized: true, googleKey: 'a'.repeat(64) });
    if (data === null) store.docs.delete(key);
    else store.seed(key, data);
    expect(await process()).toBe('ineligible');
    expect(await push()).toBe(false);
    expect(store.inbox()).toHaveLength(0);
  });

  it.each([
    { expiresAt: Timestamp.fromMillis(NOW) },
    { expiresAt: Timestamp.fromMillis(NOW - 1) },
    { createdAt: Timestamp.fromMillis(NOW + 1) },
  ])('suppresses expired/future generation %j', async (changes) => {
    const { store, process } = setup();
    store.seed(path, invite(changes));
    expect(await process()).toBe('ineligible');
  });

  it.each([
    'userBlocks/recipient/blocked/owner',
    'userBlocks/owner/blocked/recipient',
    'userBlocks/sender/blocked/owner',
    'userBlocks/owner/blocked/sender',
    'driverUsers/recipient/blockedUsers/owner',
    'driverUsers/owner/blockedUsers/recipient',
    'driverUsers/sender/blockedUsers/owner',
    'driverUsers/owner/blockedUsers/sender',
  ])('checks moderator-invite owner block edge %s', async (blockPath) => {
    const { store, process, push } = setup();
    store.seed('driverClubs/club', {
      schemaVersion: 2,
      status: 'active',
      visibility: 'closed',
      ownerUid: 'owner',
    });
    store.seed('driverClubAccounts/sender', {
      activeClubId: 'club',
      role: 'moderator',
      joinedAt: JOINED,
    });
    store.seed('driverClubs/club/members/sender', { role: 'moderator', joinedAt: JOINED });
    store.seed(blockPath, {});
    expect(await process()).toBe('ineligible');
    expect(await push()).toBe(false);
    expect(store.inbox()).toHaveLength(0);
  });

  it('bounds moderator delivery and delayed-push reads with all four identity keys', async () => {
    const { store, process, push, identity } = setup();
    store.seed('driverClubs/club', {
      schemaVersion: 2,
      status: 'active',
      visibility: 'closed',
      ownerUid: 'owner',
    });
    store.seed('driverClubAccounts/sender', {
      activeClubId: 'club',
      role: 'moderator',
      joinedAt: JOINED,
    });
    store.seed('driverClubs/club/members/sender', { role: 'moderator', joinedAt: JOINED });
    store.seed('carCommunityAccess/recipient', {
      initialized: true,
      googleKey: 'a'.repeat(64),
      appleKey: 'b'.repeat(64),
    });
    identity.mockResolvedValue({
      disabled: false,
      providerData: [
        { providerId: 'google.com', uid: 'google-user' },
        { providerId: 'apple.com', uid: 'apple-user' },
      ] as never[],
    });
    expect(await process()).toBe('delivered');
    expect(store.reads).toHaveLength(32);
    expect(store.writes).toBe(2);
    const beforePush = store.reads.length;
    expect(await push()).toBe(true);
    expect(store.reads.length - beforePush).toBe(29);
    const beforeReplay = store.reads.length;
    expect(await process()).toBe('duplicate');
    expect(store.reads.length - beforeReplay).toBe(1);
    expect(store.writes).toBe(2);
  });

  it('rechecks expiry after a slow Auth lookup before delivery', async () => {
    const { store } = setup();
    let clock = NOW;
    const service = new ClubInvitationNotifications(
      store as unknown as Firestore,
      async () => {
        clock = NOW + 100001;
        return { disabled: false, providerData: [] };
      },
      writeInAppNotification,
      () => clock,
    );
    expect(await service.process('club', 'recipient', invite())).toBe('ineligible');
    expect(store.inbox()).toHaveLength(0);
    expect(store.docs.has(receipt)).toBe(false);
  });

  it('retains opt-out decisions independently of inbox retention', async () => {
    const { store, process } = setup();
    store.seed('userPrivate/recipient', {
      notificationPreferences: { club_invite: { inApp: false } },
    });
    expect(await process()).toBe('opted_out');
    store.docs.delete('userPrivate/recipient');
    expect(await process()).toBe('duplicate');
    expect(store.docs.get(receipt)?.outcome).toBe('opted_out');
    expect(store.inbox()).toHaveLength(0);
  });

  it('allows an inbox item with push-only opt-out and honors push preview preference', async () => {
    const { store, process } = setup();
    const state = { role: 'user' as const, activeMember: false, suspended: false, deleted: false };
    const preferences = { club_invite: { push: false } };
    store.seed('userPrivate/recipient', { notificationPreferences: preferences });
    expect(await process()).toBe('delivered');
    expect(decidePushDelivery('club_invite', state, preferences)).toEqual({
      deliver: false,
      reason: 'push_opted_out',
    });
    expect(decidePushDelivery('club_invite', state, { pushPreviews: false })).toEqual({
      deliver: true,
      includePreview: false,
    });
  });

  it('rechecks state after a commit conflict and caches the Auth lookup per invocation', async () => {
    const { store, identity, process } = setup();
    store.beforeCommit = () => store.seed(path, invite({ status: 'cancelled' }));
    expect(await process()).toBe('ineligible');
    expect(store.inbox()).toHaveLength(0);
    expect(store.docs.has(receipt)).toBe(false);
    expect(identity).toHaveBeenCalledTimes(1);
  });

  it('does not commit the old notice when a genuine reinvite races the commit', async () => {
    const { store, process } = setup();
    store.beforeCommit = () =>
      store.seed(path, invite({ createdAt: Timestamp.fromMillis(NOW - 100) }));
    expect(await process(invite())).toBe('ineligible');
    expect(store.inbox()).toHaveLength(0);
    expect(store.docs.has(receipt)).toBe(false);
    expect(await process()).toBe('delivered');
    expect(store.inbox()).toHaveLength(1);
  });

  it('rechecks preferences in the same transaction after a concurrent settings change', async () => {
    const { store, process } = setup();
    store.beforeCommit = () =>
      store.seed('userPrivate/recipient', {
        notificationPreferences: { club_invite: { inApp: false } },
      });
    expect(await process()).toBe('opted_out');
    expect(store.inbox()).toHaveLength(0);
  });

  it('suppresses lagging push after cancellation, expiry or a block', async () => {
    for (const [key, data] of [
      [path, invite({ status: 'declined' })],
      [path, invite({ expiresAt: Timestamp.fromMillis(NOW) })],
      ['userBlocks/sender/blocked/recipient', {}],
      ['driverUsers/recipient/blockedUsers/sender', {}],
    ] as [string, Data][]) {
      const { store, process, push } = setup();
      await process();
      store.seed(key, data);
      expect(await push()).toBe(false);
    }
  });

  it('supports invitees who already belong to another club', async () => {
    const { store, process } = setup();
    store.seed('driverClubAccounts/recipient', { activeClubId: 'other-club', role: 'member' });
    expect(await process()).toBe('delivered');
  });

  it('treats disabled/missing Auth recipients as ineligible and propagates infrastructure failures for retry', async () => {
    const { identity, store, process } = setup();
    identity.mockResolvedValueOnce({ disabled: true, providerData: [] });
    expect(await process()).toBe('ineligible');
    identity.mockRejectedValueOnce({ code: 'auth/user-not-found' });
    expect(await process()).toBe('ineligible');
    identity.mockRejectedValueOnce(new Error('temporarily unavailable'));
    await expect(process()).rejects.toThrow('temporarily unavailable');
    store.failPath = path;
    await expect(process()).rejects.toThrow('unavailable');
    expect(store.inbox()).toHaveLength(0);
  });
});
