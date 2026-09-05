import { describe, expect, it } from 'vitest';
import {
  crownAllowance,
  crownAllowanceWindow,
  FREE_CROWN_DAILY_KP,
  PAID_CROWN_DAILY_KP,
  roundedCrownReward,
} from './daily-allowance-core';

describe('crown-only allowance policy', () => {
  const now = new Date('2026-09-05T12:00:00Z');
  it('gives free accounts exactly 75% and retains earnings across tier changes', () => {
    expect(FREE_CROWN_DAILY_KP).toBe(PAID_CROWN_DAILY_KP * 0.75);
    expect(crownAllowance(false, 2250, now).remaining).toBe(0);
    expect(crownAllowance(true, 2250, now).remaining).toBe(750);
    expect(crownAllowance(false, 2700, now)).toMatchObject({ earned: 2700, remaining: 0 });
    expect(crownAllowance(true, 2700, now).remaining).toBe(300);
  });
  it.each([
    ['2026-03-29T12:00:00Z', '2026-03-28T23:00:00.000Z', '2026-03-29T22:00:00.000Z', 23],
    ['2026-10-25T12:00:00Z', '2026-10-24T22:00:00.000Z', '2026-10-25T23:00:00.000Z', 25],
    ['2026-09-05T12:00:00Z', '2026-09-04T22:00:00.000Z', '2026-09-05T22:00:00.000Z', 24],
  ])('uses Swedish civil midnight on %s', (instant, start, end, hours) => {
    const window = crownAllowanceWindow(new Date(instant));
    expect(window.startsAt.toISOString()).toBe(start);
    expect(window.resetsAt.toISOString()).toBe(end);
    expect((window.resetsAt.getTime() - window.startsAt.getTime()) / 3600_000).toBe(hours);
    expect(crownAllowanceWindow(new Date(window.resetsAt.getTime() - 1)).day).toBe(window.day);
    expect(crownAllowanceWindow(window.resetsAt).day).not.toBe(window.day);
  });
  it.each([
    [25, 1, 0.5, 13],
    [25, 2, 0.5, 25],
    [25, 2, 1, 50],
    [1, 1, 0.5, 1],
  ])('rounds once after multiplying %i × %i × %f', (base, boost, share, expected) => {
    expect(roundedCrownReward(base, boost, share)).toBe(expected);
  });
  it.each([-1, 0.5, NaN, Infinity, undefined])(
    'fails closed on malformed stored total %s',
    (earned) => {
      expect(() => crownAllowance(false, earned as number, now)).toThrow();
    },
  );
});
