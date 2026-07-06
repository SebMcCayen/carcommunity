import { describe, expect, it } from 'vitest';
import { BADGE_KEYS, RECENT_BADGE_WINDOW_DAYS, buildAdminBadgeSummary } from './badge-core';

const NOW = Date.UTC(2026, 6, 7, 12, 0, 0);
const DAY = 24 * 60 * 60 * 1000;

describe('buildAdminBadgeSummary', () => {
  it('reports one row per catalog key in catalog order, defaulting to zero', () => {
    const summary = buildAdminBadgeSummary([], NOW);
    expect(summary.map((s) => s.key)).toEqual([...BADGE_KEYS]);
    expect(summary.every((s) => s.totalCount === 0 && s.recentCount === 0)).toBe(true);
    expect(summary.every((s) => typeof s.name === 'string' && s.name.length > 0)).toBe(true);
  });

  it('counts totals and the 30-day recent window per key', () => {
    const recent = NOW - 5 * DAY;
    const old = NOW - (RECENT_BADGE_WINDOW_DAYS + 5) * DAY;
    const summary = buildAdminBadgeSummary(
      [
        { badgeKey: 'first_event', awardedAtMillis: recent },
        { badgeKey: 'first_event', awardedAtMillis: old },
        { badgeKey: 'first_event', awardedAtMillis: null }, // undated → total only
        { badgeKey: 'garage_created', awardedAtMillis: recent },
      ],
      NOW,
    );
    const first = summary.find((s) => s.key === 'first_event')!;
    expect(first.totalCount).toBe(3);
    expect(first.recentCount).toBe(1);
    const garage = summary.find((s) => s.key === 'garage_created')!;
    expect(garage).toMatchObject({ totalCount: 1, recentCount: 1 });
  });

  it('ignores unknown/legacy badge keys', () => {
    const summary = buildAdminBadgeSummary(
      [{ badgeKey: 'retired_badge', awardedAtMillis: NOW }],
      NOW,
    );
    expect(summary.reduce((sum, s) => sum + s.totalCount, 0)).toBe(0);
  });

  it('treats an award exactly at the window boundary as recent', () => {
    const boundary = NOW - RECENT_BADGE_WINDOW_DAYS * DAY;
    const summary = buildAdminBadgeSummary(
      [{ badgeKey: 'five_events', awardedAtMillis: boundary }],
      NOW,
    );
    expect(summary.find((s) => s.key === 'five_events')!.recentCount).toBe(1);
  });
});
