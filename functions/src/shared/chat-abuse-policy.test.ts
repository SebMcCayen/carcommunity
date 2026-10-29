import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import {
  CHAT_GUARD_COLLECTION,
  CHAT_POLICY_VERSION,
  evaluateChatSend,
  type ChatGuardState,
} from './chat-abuse-policy.js';

const golden = JSON.parse(readFileSync('../contracts/chat-flood-v1/golden.json', 'utf8')) as {
  policyVersion: number;
  collection: string;
  policySha256: string;
  cases: {
    name: string;
    state?: unknown;
    now: number;
    forceViolation?: boolean;
    expected: unknown;
  }[];
};

describe('shared chat flood policy v1', () => {
  test('pins the mirrored policy and shared UID collection', () => {
    expect(CHAT_POLICY_VERSION).toBe(golden.policyVersion);
    expect(CHAT_GUARD_COLLECTION).toBe(golden.collection);
    // Each repo pins the identical source in the identical golden contract.
    const source = readFileSync('src/shared/chat-abuse-policy.ts');
    expect(createHash('sha256').update(source).digest('hex')).toBe(golden.policySha256);
  });
  for (const row of golden.cases)
    test(row.name, () => {
      expect(evaluateChatSend(row.state, row.now, row.forceViolation)).toEqual(row.expected);
    });
  test('six sends, each escalation, no extension or write during a pause', () => {
    let now = 2_000_000,
      state: ChatGuardState | undefined;
    for (const duration of [5000, 30000, 120000, 600000, 600000]) {
      let accepted = 0;
      for (;;) {
        const decision = evaluateChatSend(state, now);
        if (decision.next) state = decision.next;
        if (decision.allowed) {
          accepted++;
          continue;
        }
        expect(accepted).toBeLessThanOrEqual(6);
        if (duration === 5000) expect(accepted).toBe(6);
        expect(decision.details.retryAfterMs).toBe(duration);
        const until = decision.details.blockedUntilMillis;
        for (const tick of [0, 1, duration - 1]) {
          expect(evaluateChatSend(state, now + tick)).toMatchObject({
            allowed: false,
            next: null,
            details: { blockedUntilMillis: until, retryAfterMs: duration - tick },
          });
        }
        now = until;
        break;
      }
    }
    const reset = evaluateChatSend(state, now + 1800000);
    expect(reset).toMatchObject({
      allowed: true,
      next: { violationLevel: 0, lastViolationMillis: 0 },
    });
  });
  test('persistent stricter-quota overruns escalate despite full credit and active retries are free', () => {
    let now = 2_000_000;
    let state: ChatGuardState | undefined;
    for (const duration of [5000, 30000, 120000, 600000, 600000]) {
      const decision = evaluateChatSend(state, now, true);
      expect(decision).toMatchObject({
        allowed: false,
        next: { creditMs: 12000 },
        details: { reason: 'chat-rate-limited', retryAfterMs: duration },
      });
      state = decision.next!;
      for (const tick of [0, 1, duration - 1])
        expect(evaluateChatSend(state, now + tick, true)).toMatchObject({
          allowed: false,
          next: null,
          details: { blockedUntilMillis: now + duration, retryAfterMs: duration - tick },
        });
      now += duration;
    }
    expect(evaluateChatSend(state, now + 1800000, true)).toMatchObject({
      allowed: false,
      next: { violationLevel: 1, creditMs: 12000 },
      details: { retryAfterMs: 5000 },
    });
  });
  test('normal traffic does not postpone the thirty-minute violation reset', () => {
    const t = 2_000_000;
    let state: ChatGuardState = {
      policyVersion: 1,
      creditMs: 0,
      lastRefillMillis: t,
      violationLevel: 3,
      lastViolationMillis: t,
      blockedUntilMillis: t + 120000,
    };
    for (let now = t + 120000; now <= t + 1800000; now += 2000) {
      const decision = evaluateChatSend(state, now);
      expect(decision.allowed).toBe(true);
      state = decision.next!;
    }
    expect(state.violationLevel).toBe(0);
  });
  test('corruption and future timestamps cannot grant a burst or an unbounded ban', () => {
    const now = 2_000_000;
    const baseline = evaluateChatSend(undefined, now).next!;
    for (const state of [
      { ...baseline, creditMs: -1 },
      { ...baseline, creditMs: 12001 },
      { ...baseline, violationLevel: 5 },
      { ...baseline, lastViolationMillis: now + 1 },
      { ...baseline, blockedUntilMillis: Number.MAX_SAFE_INTEGER },
      { ...baseline, text: 'must be discarded' },
      null,
      {},
      { policyVersion: 99 },
      { creditMs: NaN },
      {
        policyVersion: 1,
        creditMs: 12000,
        lastRefillMillis: now + 1,
        violationLevel: 0,
        lastViolationMillis: 0,
        blockedUntilMillis: 0,
      },
    ]) {
      const decision = evaluateChatSend(state, now);
      expect(decision).toMatchObject({ allowed: false, details: { retryAfterMs: 600000 } });
      expect(evaluateChatSend(decision.next, now + 1)).toMatchObject({
        allowed: false,
        next: null,
      });
      expect(evaluateChatSend(decision.next, now + 600000).allowed).toBe(true);
      expect(JSON.stringify(decision.next).length).toBeLessThan(1024);
    }
    for (const clock of [NaN, Infinity, -1, 0.5, Number.MAX_SAFE_INTEGER])
      expect(() => evaluateChatSend(undefined, clock)).toThrow(RangeError);
  });
});
