/**
 * Unit tests for the global auto-issue budget's pure parts: the UTC hour bucket
 * id and the cap predicate. The transactional consumer (shared/issueBudget.ts) is
 * covered against a real Firestore in serverErrors.emulator.test.ts.
 */

import { describe, expect, it } from 'vitest';
import {
  GITHUB_ISSUE_BUDGET_COLLECTION,
  GITHUB_ISSUE_BUDGET_PER_HOUR,
  isIssueBudgetExhausted,
  issueBudgetBucketId,
} from './issueBudget-core';

describe('issueBudgetBucketId', () => {
  it('is the UTC hour, zero-padded', () => {
    expect(issueBudgetBucketId(new Date('2026-07-30T03:07:59.999Z'))).toBe('2026073003');
    expect(issueBudgetBucketId(new Date('2026-01-02T00:00:00.000Z'))).toBe('2026010200');
    expect(issueBudgetBucketId(new Date('2026-12-31T23:59:59.000Z'))).toBe('2026123123');
  });

  it('changes exactly on the UTC hour boundary', () => {
    expect(issueBudgetBucketId(new Date('2026-07-30T03:59:59.999Z'))).toBe('2026073003');
    expect(issueBudgetBucketId(new Date('2026-07-30T04:00:00.000Z'))).toBe('2026073004');
  });

  it('uses UTC, not Europe/Stockholm — the DST-duplicated local hour must not double the budget', () => {
    // 02:30 and 03:30 local on the Swedish DST spring-forward date are distinct
    // UTC hours; on the autumn fall-back date the repeated local 02:30 maps to two
    // distinct UTC hours too, so no bucket is ever visited twice.
    expect(issueBudgetBucketId(new Date('2026-10-25T00:30:00.000Z'))).toBe('2026102500');
    expect(issueBudgetBucketId(new Date('2026-10-25T01:30:00.000Z'))).toBe('2026102501');
  });
});

describe('isIssueBudgetExhausted', () => {
  it(`allows exactly ${GITHUB_ISSUE_BUDGET_PER_HOUR} issues per bucket`, () => {
    expect(isIssueBudgetExhausted(0)).toBe(false);
    expect(isIssueBudgetExhausted(GITHUB_ISSUE_BUDGET_PER_HOUR - 1)).toBe(false);
    expect(isIssueBudgetExhausted(GITHUB_ISSUE_BUDGET_PER_HOUR)).toBe(true);
    expect(isIssueBudgetExhausted(GITHUB_ISSUE_BUDGET_PER_HOUR + 50)).toBe(true);
  });

  it('honours an explicit cap (used by the emulator test to reach the cap cheaply)', () => {
    expect(isIssueBudgetExhausted(1, 2)).toBe(false);
    expect(isIssueBudgetExhausted(2, 2)).toBe(true);
  });

  it('exposes a stable collection name for the rules file', () => {
    expect(GITHUB_ISSUE_BUDGET_COLLECTION).toBe('githubIssueBudget');
  });
});
