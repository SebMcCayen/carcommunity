import { describe, expect, test } from 'vitest';
import { evaluateCommunitySend, type CommunitySendState } from './community-send-policy';

describe('Community send overlay', () => {
  const now = 1_000_000;
  test('two immediate sends, then exactly one new slot every five seconds', () => {
    const a = evaluateCommunitySend(undefined, 'Hej', now);
    const b = evaluateCommunitySend(a.next, 'Hur är läget?', now);
    expect(a.allowed && b.allowed).toBe(true);
    expect(evaluateCommunitySend(b.next, 'tre', now).allowed).toBe(false);
    expect(evaluateCommunitySend(b.next, 'tre', now + 4999).allowed).toBe(false);
    expect(evaluateCommunitySend(b.next, 'tre', now + 5000).allowed).toBe(true);
  });
  test('idle credit never exceeds two sends', () => {
    const state = evaluateCommunitySend(undefined, 'a', now).next;
    const a = evaluateCommunitySend(state, 'b', now + 60_000);
    const b = evaluateCommunitySend(a.next, 'c', now + 60_000);
    expect(b.allowed).toBe(true);
    expect(evaluateCommunitySend(b.next, 'd', now + 60_000).allowed).toBe(false);
  });
  test.each(['🚗', '👨‍👩‍👧‍👦', 'Hej', 'Å'])(
    'third identical %s is denied, even with available credit',
    (text) => {
      const a = evaluateCommunitySend(undefined, text, now);
      const b = evaluateCommunitySend(a.next, text, now + 5000);
      const c = evaluateCommunitySend(b.next, text, now + 10_000);
      expect(c.allowed).toBe(false);
      expect(c.next.creditMs).toBe(10_000);
      expect(c.next.recent).toHaveLength(2);
      expect(evaluateCommunitySend(b.next, text, now + 30_000).allowed).toBe(true);
    },
  );
  test('interleaving text cannot evade duplicate detection; Unicode and line endings normalize only the comparison', () => {
    let state: CommunitySendState | undefined;
    for (const [index, text] of ['Å\r\nhej', 'other', 'A\u030a\nhej'].entries()) {
      const result = evaluateCommunitySend(state, text, now + index * 5000);
      expect(result.allowed).toBe(true);
      state = result.next;
    }
    expect(evaluateCommunitySend(state, ' Å\nhej ', now + 15_000).allowed).toBe(false);
    expect(JSON.stringify(state)).not.toContain('hej');
  });
  test('recent state stays bounded during normal conversation and logically expires', () => {
    let state: CommunitySendState | undefined;
    for (let index = 0; index < 100; index++) {
      state = evaluateCommunitySend(state, `message-${index}`, now + index * 5000).next;
      expect(state.recent.length).toBeLessThanOrEqual(8);
    }
    expect(evaluateCommunitySend(state, 'new', now + 1_000_000).next.recent).toHaveLength(1);
  });
  test.each([null, {}, { policyVersion: 2 }, { creditMs: Infinity }])(
    'invalid state %j is repaired without a fresh burst',
    (value) => {
      const denied = evaluateCommunitySend(value, 'hej', now);
      expect(denied.allowed).toBe(false);
      expect(denied.next.creditMs).toBe(0);
      expect(evaluateCommunitySend(denied.next, 'hej', now + 5000).allowed).toBe(true);
    },
  );
});
