import type { Firestore, Transaction } from 'firebase-admin/firestore';
import { describe, expect, test, vi } from 'vitest';
import { evaluateChatSend } from '../shared/chat-abuse-policy';
import { evaluateCommunitySend } from './community-send-policy';
import { stageCommunitySendGuard } from './community-send-guard';

const now = 1_000_000;
function fixture(community: unknown = undefined, shared: unknown = undefined) {
  let writing = false;
  const set = vi.fn((_ref: unknown, _state: unknown) => {
    writing = true;
  });
  const get = vi.fn(async (ref: { path: string }) => {
    if (writing) throw Error('read after write');
    return { data: () => (ref.path.startsWith('communityChatSendGuards/') ? community : shared) };
  });
  const db = {
    collection: (name: string) => ({ doc: (uid: string) => ({ path: `${name}/${uid}` }) }),
  } as unknown as Firestore;
  const tx = { get, set } as unknown as Transaction;
  return { db, tx, set, get };
}
describe('atomic Community and shared send budgets', () => {
  test('accepted send stages both budgets after all reads', async () => {
    const f = fixture();
    expect((await stageCommunitySendGuard(f.db, f.tx, 'me', 'hej', () => now)).allowed).toBe(true);
    expect(f.get).toHaveBeenCalledTimes(2);
    expect(f.set).toHaveBeenCalledTimes(2);
    expect(f.set.mock.calls).toMatchObject([
      [{ path: 'chatSendGuards/me' }, { creditMs: 10_000 }],
      [{ path: 'communityChatSendGuards/me' }, { creditMs: 5000 }],
    ]);
  });
  test('Community exhaustion commits a shared pause while preserving the shared tokens', async () => {
    const first = evaluateCommunitySend(undefined, 'a', now).next;
    const second = evaluateCommunitySend(first, 'b', now).next;
    const f = fixture(second);
    const result = await stageCommunitySendGuard(f.db, f.tx, 'me', 'c', () => now);
    expect(result).toMatchObject({
      allowed: false,
      details: { retryAfterMs: 5000 },
      next: { creditMs: 12000, violationLevel: 1 },
    });
    expect(f.set).toHaveBeenCalledTimes(2);
  });
  test('third identical message with available Community credit uses shared escalation', async () => {
    const first = evaluateCommunitySend(undefined, '🚗', now - 10_000).next;
    const second = evaluateCommunitySend(first, '🚗', now - 5000).next;
    const f = fixture(second);
    expect(await stageCommunitySendGuard(f.db, f.tx, 'me', '🚗', () => now)).toMatchObject({
      allowed: false,
      details: { retryAfterMs: 5000 },
    });
  });
  test('active pause writes neither guard and never extends its deadline', async () => {
    const shared = evaluateChatSend(undefined, now, true).next;
    const f = fixture(undefined, shared);
    expect(await stageCommunitySendGuard(f.db, f.tx, 'me', 'hej', () => now + 1000)).toMatchObject({
      allowed: false,
      next: null,
      details: { blockedUntilMillis: now + 5000 },
    });
    expect(f.set).not.toHaveBeenCalled();
  });
  test('a shared denial does not consume Community credit or record a duplicate', async () => {
    let shared = evaluateChatSend(undefined, now).next!;
    for (let index = 0; index < 5; index++) shared = evaluateChatSend(shared, now).next!;
    const f = fixture(undefined, shared);
    expect((await stageCommunitySendGuard(f.db, f.tx, 'me', 'hej', () => now)).allowed).toBe(false);
    expect(f.set).toHaveBeenCalledTimes(1);
    expect(f.set.mock.calls[0]?.[0]).toEqual({ path: 'chatSendGuards/me' });
  });
});
