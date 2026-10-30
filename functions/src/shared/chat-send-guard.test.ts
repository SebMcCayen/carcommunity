import type { Firestore, Transaction } from 'firebase-admin/firestore';
import { describe, expect, test, vi } from 'vitest';
import { evaluateChatSend, type ChatGuardState } from './chat-abuse-policy.js';
import { stageChatSendGuard } from './chat-send-guard.js';

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** Only the guard document is modeled. No SDK initialization, network or emulator. */
function fixture(read: () => unknown | Promise<unknown>) {
  const ref = { path: 'chatSendGuards/test-sender' };
  const get = vi.fn(async () => {
    const value = await read();
    return { data: () => value };
  });
  const set = vi.fn((_ref: unknown, value: ChatGuardState) => value);
  const db = { collection: () => ({ doc: () => ref }) } as unknown as Firestore;
  const tx = { get, set } as unknown as Transaction;
  return { db, tx, get, set };
}

const start = 2_000_000;

describe('transactional chat guard clock', () => {
  test('samples only after the asynchronous guard read completes', async () => {
    const reading = deferred();
    const release = deferred();
    const events: string[] = [];
    let now = start;
    const f = fixture(async () => {
      events.push('read-start');
      reading.resolve();
      await release.promise;
      events.push('read-complete');
      return undefined;
    });
    const clock = vi.fn(() => {
      events.push('clock');
      return now;
    });
    const pending = stageChatSendGuard(f.db, f.tx, 'test-sender', clock);
    await reading.promise;
    expect(clock).not.toHaveBeenCalled();
    now += 100;
    release.resolve();
    expect(await pending).toMatchObject({ allowed: true, next: { lastRefillMillis: now } });
    expect(events).toEqual(['read-start', 'read-complete', 'clock']);
    expect(clock).toHaveBeenCalledTimes(1);
    expect(f.set).toHaveBeenCalledTimes(1);
  });

  test('an earlier delayed request may consume remaining credit from a later committed send', async () => {
    let now = start;
    let committed = evaluateChatSend(undefined, now).next!;
    const reading = deferred();
    const release = deferred();
    const f = fixture(async () => {
      reading.resolve();
      await release.promise;
      return committed;
    });
    const pending = stageChatSendGuard(f.db, f.tx, 'test-sender', () => now);
    await reading.promise;
    // A later request finishes first, while the earlier guard get is still pending.
    now += 100;
    committed = evaluateChatSend(committed, now).next!;
    expect(committed.creditMs).toBe(8100);
    release.resolve();
    expect(await pending).toMatchObject({
      allowed: true,
      next: {
        creditMs: 6100,
        lastRefillMillis: now,
        violationLevel: 0,
        blockedUntilMillis: 0,
      },
    });
    expect(f.set).toHaveBeenCalledTimes(1);
  });

  test('a retried callback samples a fresh clock after its new guard read', async () => {
    let now = start;
    const initial = evaluateChatSend(undefined, now).next!;
    let committed = initial;
    let attempt = 0;
    const samples: number[] = [];
    const f = fixture(async () => {
      attempt++;
      if (attempt === 2) {
        // Another writer commits after this retry starts and before its get returns.
        now += 50;
        committed = evaluateChatSend(committed, now).next!;
        now += 50;
      }
      return committed;
    });
    const clock = () => {
      samples.push(now);
      return now;
    };
    const callback = () => stageChatSendGuard(f.db, f.tx, 'test-sender', clock);
    const discarded = await callback();
    expect(discarded.allowed).toBe(true);
    // Firestore discards the staged first attempt after a conflicting commit.
    now += 100;
    committed = evaluateChatSend(initial, now).next!;
    const retried = await callback();
    expect(samples).toEqual([start, start + 200]);
    expect(retried).toMatchObject({
      allowed: true,
      next: {
        creditMs: 4200,
        lastRefillMillis: start + 200,
        violationLevel: 0,
      },
    });
    expect(f.get).toHaveBeenCalledTimes(2);
  });

  test('a force violation uses the post-read clock without consuming available credit', async () => {
    let now = start;
    const f = fixture(async () => {
      now += 100;
      return undefined;
    });
    expect(await stageChatSendGuard(f.db, f.tx, 'test-sender', () => now, true)).toMatchObject({
      allowed: false,
      next: { creditMs: 12000, violationLevel: 1 },
      details: { retryAfterMs: 5000, serverNowMillis: now, blockedUntilMillis: now + 5000 },
    });
  });

  test('genuinely future state still fails closed instead of weakening corruption validation', async () => {
    const future = evaluateChatSend(undefined, start + 1).next!;
    const f = fixture(() => future);
    expect(await stageChatSendGuard(f.db, f.tx, 'test-sender', () => start)).toMatchObject({
      allowed: false,
      next: { violationLevel: 4, creditMs: 0 },
      details: { retryAfterMs: 600000, serverNowMillis: start },
    });
  });
});
