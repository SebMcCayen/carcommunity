import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';

const state = vi.hoisted(() => ({
  actor: { role: 'admin', suspended: false, deleted: false } as Record<string, unknown>,
  access: { initialized: true, suspended: false } as Record<string, unknown>,
  blocked: false,
  writes: vi.fn(),
}));
vi.mock('../firebase', () => {
  const document = (path: string) => ({ path });
  const snapshot = (path: string) => {
    const data =
      path === 'users/actor'
        ? state.actor
        : path === 'carCommunityAccess/actor'
          ? state.access
          : path.startsWith('carCommunityIdentityBlocks/')
            ? { blocked: state.blocked }
            : {};
    return { ref: document(path), exists: true, data: () => data, get: (key: string) => data[key] };
  };
  const tx = {
    getAll: async (...refs: Array<{ path: string }>) => refs.map((r) => snapshot(r.path)),
    get: async (ref: { path: string }) => snapshot(ref.path),
    create: state.writes,
    update: state.writes,
    set: state.writes,
    delete: state.writes,
  };
  return {
    db: {
      doc: document,
      collection: (path: string) => ({ doc: (id: string) => document(`${path}/${id}`) }),
      runTransaction: async (fn: (value: typeof tx) => unknown) => fn(tx),
    },
  };
});
// Deliberately return the earlier successful check while the database now says
// otherwise: this models revocation between the outer guard and transaction.
vi.mock('./actorContext', () => ({
  requireAdminActor: async () => ({
    uid: 'actor',
    state: { role: 'admin', suspended: false, deleted: false },
  }),
}));
vi.mock('../access/callable', () => ({ onCall: (_: unknown, fn: unknown) => ({ run: fn }) }));
vi.mock('../notifications/deliver', () => ({ writeInAppNotification: vi.fn() }));
vi.mock('../chatchannels/redactPublicReplies', () => ({ redactPublicReplies: vi.fn() }));

import { getChatModerationContext, moderateChatMessage } from './chatModeration';
import { requireChatSender } from '../shared/chat-actor';
const input = {
  target: { source: 'community', messageId: 'message' },
  expectedActorUid: 'actor',
  expectedVersion: 'a'.repeat(64),
  action: 'remove',
  reason: 'Reason',
  warn: true,
  pause: { amount: 2, unit: 'minutes' },
  idempotencyKey: '1234567890123456',
};
beforeEach(() => {
  state.actor = { role: 'admin', suspended: false, deleted: false };
  state.access = { initialized: true, suspended: false };
  state.blocked = false;
  state.writes.mockClear();
});
describe('commit-time admin authorization independent of precheck', () => {
  it.each([
    [
      'role revoked',
      () => {
        state.actor.role = 'user';
      },
    ],
    [
      'suspended',
      () => {
        state.actor.suspended = true;
      },
    ],
    [
      'deleted',
      () => {
        state.actor.deleted = true;
      },
    ],
    [
      'CC access revoked',
      () => {
        state.access.suspended = true;
      },
    ],
    [
      'CC initialization absent',
      () => {
        state.access.initialized = false;
      },
    ],
    [
      'identity blocked',
      () => {
        state.access.googleKey = 'a'.repeat(64);
        state.blocked = true;
      },
    ],
  ])('denies %s before a message read, receipt replay or write', async (_label, revoke) => {
    revoke();
    const request = {
      auth: { uid: 'actor', token: { admin: true } },
      data: input,
    } as unknown as CallableRequest;
    await expect(moderateChatMessage.run(request)).rejects.toMatchObject({
      code: 'permission-denied',
    });
    await expect(
      getChatModerationContext.run({ ...request, data: { target: input.target } }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    expect(state.writes).not.toHaveBeenCalled();
  });
});

describe('writing pause boundary', () => {
  function transaction(pauseUntil: unknown) {
    return {
      get: vi.fn(async (ref: { path: string }) =>
        ref.path.startsWith('users/')
          ? { exists: true, data: () => ({ role: 'user' }) }
          : { exists: true, get: () => pauseUntil },
      ),
    };
  }
  it('rejects active pause for public scope with useful safe status', async () => {
    const until = Timestamp.fromMillis(Date.now() + 120000);
    const tx = transaction(until);
    await expect(requireChatSender(tx as never, 'member', 'public')).rejects.toMatchObject({
      code: 'permission-denied',
      details: { reason: 'chat-write-paused', pauseUntilMillis: until.toMillis() },
    });
  });
  it('does not even read public restriction for PM/convoy callers', async () => {
    const tx = transaction(Timestamp.fromMillis(Date.now() + 120000));
    await expect(requireChatSender(tx as never, 'member')).resolves.toBeUndefined();
    await expect(requireChatSender(tx as never, 'member', 'private')).resolves.toBeUndefined();
    expect(tx.get).toHaveBeenCalledTimes(2);
  });
  it('allows expired public pause and fails closed for malformed stored status', async () => {
    await expect(
      requireChatSender(
        transaction(Timestamp.fromMillis(Date.now() - 1)) as never,
        'member',
        'public',
      ),
    ).resolves.toBeUndefined();
    await expect(
      requireChatSender(transaction('tomorrow') as never, 'member', 'public'),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});
