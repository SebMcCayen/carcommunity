/**
 * Event trap-exclusion — PURE core (anti-griefing rule).
 *
 * A Crown Hunt trap (spike_strip) must never be dropped on a car meet: if it
 * could, everyone attending an event would spike the gathering spot. The
 * `crownHunt.deployPerk` callable is server-authoritative, so the rule lives
 * there — but ALL of the time-window and distance maths live HERE, in a pure
 * function with no Firebase Admin SDK, so it is unit-testable without the
 * emulator (mirroring perks-core.ts / points-economy-core.ts).
 *
 * A trap deploy at (lat, lng, nowMs) is BLOCKED when there is any candidate
 * event E such that:
 *   - nowMs is within [E.startsAt - EVENT_TRAP_BLOCK_BEFORE_START_MS,
 *     effectiveEnd(E) + EVENT_TRAP_BLOCK_AFTER_END_MS], where
 *     effectiveEnd(E) = E.endsAt ?? E.startsAt, AND
 *   - the haversine distance from (lat, lng) to E's precise coordinates is
 *     < EVENT_TRAP_EXCLUSION_RADIUS_METERS.
 *
 * The caller (deployPerk) is responsible for restricting the candidate list to
 * published/completed events whose coordinates are valid; this function applies
 * the exact window + radius test to whatever candidates it is given.
 */

import { haversineDistanceMeters } from './crown-hunt-geo';
import {
  EVENT_TRAP_BLOCK_AFTER_END_MS,
  EVENT_TRAP_BLOCK_BEFORE_START_MS,
  EVENT_TRAP_EXCLUSION_RADIUS_METERS,
} from './perks-core';

/** A candidate event, reduced to only what the exclusion test needs. */
export interface TrapExclusionEvent {
  /** Event start, epoch-ms. */
  startsAtMs: number;
  /** Event end, epoch-ms, or null when the event has no explicit end. */
  endsAtMs: number | null;
  /** The event's precise latitude (WGS-84). */
  latitude: number;
  /** The event's precise longitude (WGS-84). */
  longitude: number;
}

/**
 * True when a trap at (lat, lng) placed at nowMs falls inside ANY candidate
 * event's blocking window AND within the exclusion radius of it.
 *
 * Pure: no I/O, no clock, no Firestore. `nowMs` is passed in.
 */
export function isTrapBlockedByEvents(
  events: TrapExclusionEvent[],
  lat: number,
  lng: number,
  nowMs: number,
): boolean {
  for (const event of events) {
    const effectiveEndMs = event.endsAtMs ?? event.startsAtMs;
    const windowStartMs = event.startsAtMs - EVENT_TRAP_BLOCK_BEFORE_START_MS;
    const windowEndMs = effectiveEndMs + EVENT_TRAP_BLOCK_AFTER_END_MS;
    if (nowMs < windowStartMs || nowMs > windowEndMs) {
      continue;
    }
    const distance = haversineDistanceMeters(lat, lng, event.latitude, event.longitude);
    if (distance < EVENT_TRAP_EXCLUSION_RADIUS_METERS) {
      return true;
    }
  }
  return false;
}
