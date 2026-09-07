import { stockholmDayKey } from '../points/points-economy-core';

export const PAID_CROWN_DAILY_KP = 3000;
export const FREE_CROWN_DAILY_KP = 2250;
export const CROWN_DAILY_ALLOWANCES = 'crownDailyAllowances';

export interface CrownAllowance {
  cap: number;
  earned: number;
  remaining: number;
  resetsAt: string;
}

// Find civil midnight using the IANA calendar, including 23/25-hour DST days.
function midnight(day: string): number {
  let lo = Date.parse(`${day}T00:00:00Z`) - 24 * 3600_000;
  let hi = lo + 48 * 3600_000;
  while (hi - lo > 1) {
    const mid = Math.floor((lo + hi) / 2);
    if (stockholmDayKey(new Date(mid)) < day) lo = mid;
    else hi = mid;
  }
  return hi;
}

export function crownAllowanceWindow(now: Date) {
  const day = stockholmDayKey(now);
  const next = new Date(`${day}T12:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return {
    day,
    startsAt: new Date(midnight(day)),
    resetsAt: new Date(midnight(next.toISOString().slice(0, 10))),
  };
}

export function crownAllowance(paid: boolean, earned: number, now: Date): CrownAllowance {
  if (!Number.isSafeInteger(earned) || earned < 0) throw new Error('Invalid crown allowance total');
  const cap = paid ? PAID_CROWN_DAILY_KP : FREE_CROWN_DAILY_KP;
  return {
    cap,
    earned,
    remaining: Math.max(0, cap - earned),
    resetsAt: crownAllowanceWindow(now).resetsAt.toISOString(),
  };
}

export function roundedCrownReward(base: number, boost: number, liveShare: number): number {
  return Math.round(base * boost * liveShare);
}

export function crownAllowanceMessage(allowance: CrownAllowance): string {
  const reset = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Europe/Stockholm',
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(allowance.resetsAt));
  return `${allowance.remaining} av ${allowance.cap} KP kvar för Kronjakt. Ny dagsgräns ${reset} (Stockholm).`;
}
