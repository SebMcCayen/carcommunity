/**
 * Inactive-account decision-table unit tests (account lifecycle cross-lane).
 *
 * Exercises decideInactivity across the full matrix — active, inactive-not-yet-
 * warned, reactivated-after-warning, warned-within-grace, warned-past-grace with
 * the delete gate OPEN and CLOSED — plus the lastLoginAt ?? createdAt fallback
 * and the month/day cutoff helpers. Pure module: no emulator required.
 */

import { describe, expect, it } from 'vitest';
import {
  INACTIVITY_DELETE_GRACE_DAYS,
  INACTIVITY_WARN_AFTER_MONTHS,
  addDays,
  decideInactivity,
  inactivityWarnCutoff,
  resolveLastActivity,
  subtractMonths,
  type InactivityDecisionInput,
} from '../account/inactivity-core';

const NOW = new Date('2026-07-12T00:00:00.000Z');
const DAY_MS = 24 * 60 * 60 * 1000;

/** A last-activity instant `months` before NOW. */
function monthsAgo(months: number): Date {
  return subtractMonths(NOW, months);
}

function decide(overrides: Partial<InactivityDecisionInput>) {
  return decideInactivity({
    now: NOW,
    lastActivityAt: monthsAgo(0),
    warnedAt: null,
    deleteAfter: null,
    deletionEnabled: false,
    ...overrides,
  });
}

describe('date helpers', () => {
  it('subtractMonths goes back whole calendar months (UTC)', () => {
    expect(subtractMonths(new Date('2026-07-12T00:00:00Z'), 11).toISOString()).toBe(
      '2025-08-12T00:00:00.000Z',
    );
  });

  it('addDays adds exact 24h days', () => {
    expect(addDays(NOW, INACTIVITY_DELETE_GRACE_DAYS).getTime()).toBe(
      NOW.getTime() + 30 * DAY_MS,
    );
  });

  it('inactivityWarnCutoff is NOW minus the 11-month threshold', () => {
    expect(inactivityWarnCutoff(NOW).toISOString()).toBe(
      subtractMonths(NOW, INACTIVITY_WARN_AFTER_MONTHS).toISOString(),
    );
  });
});

describe('resolveLastActivity', () => {
  it('prefers lastLoginAt when present', () => {
    const login = monthsAgo(1);
    const created = monthsAgo(20);
    expect(resolveLastActivity(login, created)).toBe(login);
  });

  it('falls back to createdAt when lastLoginAt is absent', () => {
    const created = monthsAgo(20);
    expect(resolveLastActivity(null, created)).toBe(created);
    expect(resolveLastActivity(undefined, created)).toBe(created);
  });
});

describe('decideInactivity — not yet warned', () => {
  it('skips an account active within 11 months', () => {
    expect(decide({ lastActivityAt: monthsAgo(10) })).toEqual({
      action: 'skip',
      reason: 'active',
    });
  });

  it('warns an account inactive exactly at the 11-month boundary', () => {
    // lastActivity == cutoff counts as inactive (<=).
    expect(decide({ lastActivityAt: inactivityWarnCutoff(NOW) })).toEqual({
      action: 'warn',
      reason: 'inactive_not_yet_warned',
    });
  });

  it('warns an account inactive beyond 11 months', () => {
    expect(decide({ lastActivityAt: monthsAgo(14) })).toEqual({
      action: 'warn',
      reason: 'inactive_not_yet_warned',
    });
  });

  it('warns a never-logged-in old account (createdAt fallback drives inactivity)', () => {
    const created = monthsAgo(18);
    expect(
      decide({ lastActivityAt: resolveLastActivity(null, created) }),
    ).toEqual({ action: 'warn', reason: 'inactive_not_yet_warned' });
  });
});

describe('decideInactivity — already warned', () => {
  const warnedAt = monthsAgo(1); // warned one month ago
  const deleteAfter = addDays(warnedAt, INACTIVITY_DELETE_GRACE_DAYS);

  it('clears the warning when the user signed in after being warned', () => {
    expect(
      decide({
        lastActivityAt: addDays(warnedAt, 2), // logged in 2 days after the warning
        warnedAt,
        deleteAfter,
      }),
    ).toEqual({ action: 'clear_warning', reason: 'reactivated_after_warning' });
  });

  it('skips while still within the 30-day grace window', () => {
    const freshWarn = addDays(NOW, -10); // warned 10 days ago
    expect(
      decide({
        lastActivityAt: monthsAgo(14),
        warnedAt: freshWarn,
        deleteAfter: addDays(freshWarn, INACTIVITY_DELETE_GRACE_DAYS),
      }),
    ).toEqual({ action: 'skip', reason: 'warned_within_grace_period' });
  });

  it('would_delete past grace when the delete gate is CLOSED (MVP default)', () => {
    expect(
      decide({
        lastActivityAt: monthsAgo(14),
        warnedAt,
        deleteAfter, // ~ 2 days ago, past grace
        deletionEnabled: false,
      }),
    ).toEqual({ action: 'would_delete', reason: 'delete_gate_disabled' });
  });

  it('deletes past grace only when the delete gate is OPEN', () => {
    expect(
      decide({
        lastActivityAt: monthsAgo(14),
        warnedAt,
        deleteAfter,
        deletionEnabled: true,
      }),
    ).toEqual({ action: 'delete', reason: 'inactive_past_grace_period' });
  });

  it('never deletes before deleteAfter even with the gate open', () => {
    const freshWarn = addDays(NOW, -5);
    expect(
      decide({
        lastActivityAt: monthsAgo(14),
        warnedAt: freshWarn,
        deleteAfter: addDays(freshWarn, INACTIVITY_DELETE_GRACE_DAYS),
        deletionEnabled: true,
      }),
    ).toEqual({ action: 'skip', reason: 'warned_within_grace_period' });
  });

  it('reactivation takes precedence over an elapsed grace window', () => {
    // Past deleteAfter, gate open, but the user came back after the warning:
    // clear the warning rather than delete.
    expect(
      decide({
        lastActivityAt: addDays(warnedAt, 1),
        warnedAt,
        deleteAfter,
        deletionEnabled: true,
      }),
    ).toEqual({ action: 'clear_warning', reason: 'reactivated_after_warning' });
  });
});
