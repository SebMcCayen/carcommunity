/**
 * Unit tests for the partner insights pure logic (insights-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
  aggregateId,
  buildScopedHash,
  computeAggregateMetric,
  effectiveThreshold,
  eventExpiry,
  interactionEventId,
  parseRecordInteractionInput,
  previousUtcDay,
  resolvePeriodBounds,
} from '../partnerInsights/insights-core';

describe('insights-core privacy primitives', () => {
  it('scopes user hashes per partner (no cross-partner correlation)', () => {
    const atPartnerA = buildScopedHash('company-a', 'user-1');
    const atPartnerB = buildScopedHash('company-b', 'user-1');
    expect(atPartnerA).toMatch(/^[a-f0-9]{64}$/);
    expect(atPartnerA).not.toBe(atPartnerB);
    // Deterministic per (partner, user) — the per-day dedupe depends on it.
    expect(buildScopedHash('company-a', 'user-1')).toBe(atPartnerA);
    // The raw UID never appears in the hash output.
    expect(atPartnerA).not.toContain('user-1');
  });

  it('lets configuration only RAISE the contributor threshold, never lower it', () => {
    expect(MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD).toBe(10);
    expect(effectiveThreshold(undefined)).toBe(10);
    expect(effectiveThreshold(3)).toBe(10);
    expect(effectiveThreshold(-5)).toBe(10);
    expect(effectiveThreshold('25')).toBe(10);
    expect(effectiveThreshold(25)).toBe(25);
  });

  it('zeroes below-threshold pass-by aggregates (never just hides them)', () => {
    const below = computeAggregateMetric('anonymous_pass_by', 40, 9, 10);
    expect(below.resultStatus).toBe('insufficient_data');
    expect(below.totalCount).toBe(0);
    expect(below.uniqueContributorCount).toBeNull();

    const at = computeAggregateMetric('anonymous_pass_by', 40, 10, 10);
    expect(at.resultStatus).toBe('available');
    expect(at.totalCount).toBe(40);
    expect(at.uniqueContributorCount).toBe(10);

    // The threshold applies ONLY to anonymous_pass_by (legacy parity):
    // user-initiated interactions are not aggregate-suppressed.
    const regular = computeAggregateMetric('profile_view', 2, 1, 10);
    expect(regular.resultStatus).toBe('available');
    expect(regular.totalCount).toBe(2);

    const empty = computeAggregateMetric('map_view', 0, 0, 10);
    expect(empty.resultStatus).toBe('no_data');
  });
});

describe('insights-core periods and identifiers', () => {
  const date = new Date('2026-07-04T15:30:00Z'); // Saturday

  it('resolves UTC period bounds (day / ISO week / month)', () => {
    expect(resolvePeriodBounds(date, 'day')).toEqual({
      start: new Date('2026-07-04T00:00:00Z'),
      end: new Date('2026-07-05T00:00:00Z'),
    });
    expect(resolvePeriodBounds(date, 'week')).toEqual({
      start: new Date('2026-06-29T00:00:00Z'),
      end: new Date('2026-07-06T00:00:00Z'),
    });
    expect(resolvePeriodBounds(date, 'month')).toEqual({
      start: new Date('2026-07-01T00:00:00Z'),
      end: new Date('2026-08-01T00:00:00Z'),
    });
  });

  it('derives deterministic event and aggregate IDs, and the 7-day expiry', () => {
    const hash = buildScopedHash('c1', 'u1');
    expect(interactionEventId('c1', 'profile_view', new Date('2026-07-04T00:00:00Z'), hash)).toBe(
      `c1_profile_view_2026-07-04_${hash}`,
    );
    expect(aggregateId('c1', 'profile_view', 'week', new Date('2026-06-29T00:00:00Z'))).toBe(
      'c1_profile_view_week_2026-06-29',
    );
    expect(eventExpiry(new Date('2026-07-04T12:00:00Z'))).toEqual(
      new Date('2026-07-11T12:00:00Z'),
    );
  });

  it('computes the previous UTC day with real date arithmetic', () => {
    expect(previousUtcDay(new Date('2026-07-04T03:00:00+02:00'))).toEqual(
      new Date('2026-07-03T00:00:00Z'),
    );
    // Month and year boundaries.
    expect(previousUtcDay(new Date('2026-07-01T00:30:00Z'))).toEqual(
      new Date('2026-06-30T00:00:00Z'),
    );
    expect(previousUtcDay(new Date('2026-01-01T12:00:00Z'))).toEqual(
      new Date('2025-12-31T00:00:00Z'),
    );
  });

  it('parses interaction input strictly', () => {
    expect(
      parseRecordInteractionInput({ companyId: 'c1', interactionType: 'profile_view' }).ok,
    ).toBe(true);
    expect(
      parseRecordInteractionInput({ companyId: 'c1', interactionType: 'tracking_pixel' }).ok,
    ).toBe(false);
    expect(
      parseRecordInteractionInput({ companyId: 'c/1', interactionType: 'profile_view' }).ok,
    ).toBe(false);
    expect(
      parseRecordInteractionInput({
        companyId: 'c1',
        interactionType: 'offer_view',
        relatedOfferId: 'o1',
        extra: 1,
      }).ok,
    ).toBe(false);
  });
});
