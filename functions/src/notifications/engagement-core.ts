import { createHash } from 'node:crypto';
import { isWithinRadius } from '../incidents/incidents-core';
import { isValidCoordinate } from '../crownHunt/crown-hunt-geo';

export const ENGAGEMENT_COOLDOWN_MS = 6 * 60 * 60 * 1000;
export const ENGAGEMENT_DAILY_LIMIT = 2;
export const EPIC_RADIUS_METERS = 3000;
export const ENGAGEMENT_MAX_AGE_MS = 5 * 60 * 1000;

export function engagementId(...parts: string[]): string {
  return createHash('sha256').update(JSON.stringify(parts)).digest('hex');
}

/** At most two notices per rolling 24 hours, with six hours between notices. */
export function nextEngagementBudget(
  state: { lastSentAtMs?: number; recentSentAtMs?: number[] } | undefined,
  nowMs: number,
): { lastSentAtMs: number; recentSentAtMs: number[] } | null {
  if (
    typeof state?.lastSentAtMs === 'number' &&
    nowMs - state.lastSentAtMs < ENGAGEMENT_COOLDOWN_MS
  )
    return null;
  const recent = Array.isArray(state?.recentSentAtMs)
    ? state.recentSentAtMs.filter((at) => Number.isFinite(at) && nowMs - at < 24 * 60 * 60 * 1000)
    : [];
  if (recent.length >= ENGAGEMENT_DAILY_LIMIT) return null;
  return { lastSentAtMs: nowMs, recentSentAtMs: [...recent, nowMs] };
}

/** Engagement pushes are useful only while fresh, including when the trigger was delayed. */
export function engagementPushRemainingMs(createdAtMs: number, nowMs: number): number {
  if (!Number.isFinite(createdAtMs) || createdAtMs > nowMs) return 0;
  return Math.max(0, ENGAGEMENT_MAX_AGE_MS - (nowMs - createdAtMs));
}

export function epicWithinRange(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  const coords = [a.latitude, a.longitude, b.latitude, b.longitude];
  if (!coords.every((v) => typeof v === 'number' && Number.isFinite(v))) return false;
  const [lat, lng, otherLat, otherLng] = coords as number[];
  return (
    isValidCoordinate(lat!, lng!) &&
    isValidCoordinate(otherLat!, otherLng!) &&
    isWithinRadius(lat!, lng!, otherLat!, otherLng!, EPIC_RADIUS_METERS)
  );
}
