import { describe, expect, it } from 'vitest';
import {
  COMMUNITY_DRIVE_HISTORY_LIMIT,
  DAY_MS,
  DRIVE_HISTORY_PAGE_SIZE_DEFAULT,
  DRIVE_HISTORY_PAGE_SIZE_MAX,
  PLUS_DRIVE_HISTORY_DAYS,
  driveHistoryPageSize,
  driveHistoryPolicyForTier,
  driveHistoryReadLimit,
  parseListDriveHistoryInput,
} from '../drives/driveHistory-core';

const NOW = Date.parse('2026-08-31T12:00:00.000Z');

describe('drive-history tier policy', () => {
  it('gives Community only the five newest drives', () => {
    const policy = driveHistoryPolicyForTier('community', NOW);
    expect(policy).toEqual({ kind: 'latest_count', limit: COMMUNITY_DRIVE_HISTORY_LIMIT });
    expect(driveHistoryPageSize(policy, 100)).toBe(5);
    expect(driveHistoryReadLimit(policy, 5)).toBe(5);
  });

  it('gives Plus a rolling 90-day, paginated window', () => {
    const policy = driveHistoryPolicyForTier('plus', NOW);
    expect(policy).toEqual({
      kind: 'rolling_days',
      days: PLUS_DRIVE_HISTORY_DAYS,
      cutoffMillis: NOW - 90 * DAY_MS,
    });
    expect(driveHistoryPageSize(policy)).toBe(DRIVE_HISTORY_PAGE_SIZE_DEFAULT);
    expect(driveHistoryPageSize(policy, 25)).toBe(25);
    expect(driveHistoryReadLimit(policy, 25)).toBe(26);
  });

  it('gives Supporter paginated unlimited history', () => {
    const policy = driveHistoryPolicyForTier('supporter', NOW);
    expect(policy).toEqual({ kind: 'unlimited' });
    expect(driveHistoryPageSize(policy, 75)).toBe(DRIVE_HISTORY_PAGE_SIZE_MAX);
    expect(driveHistoryReadLimit(policy, 25)).toBe(26);
  });
});

describe('drive-history input', () => {
  it('accepts an empty request and a bounded page cursor', () => {
    expect(parseListDriveHistoryInput({})).toEqual({ ok: true, input: {} });
    expect(parseListDriveHistoryInput({ cursorRideId: 'ride-1', pageSize: 25 })).toEqual({
      ok: true,
      input: { cursorRideId: 'ride-1', pageSize: 25 },
    });
  });

  it('rejects unknown fields, blank cursors, and unbounded pages', () => {
    expect(parseListDriveHistoryInput({ extra: true }).ok).toBe(false);
    expect(parseListDriveHistoryInput({ cursorRideId: ' ' }).ok).toBe(false);
    expect(parseListDriveHistoryInput({ pageSize: 0 }).ok).toBe(false);
    expect(parseListDriveHistoryInput({ pageSize: 26 }).ok).toBe(false);
  });
});
