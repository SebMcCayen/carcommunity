/**
 * Pure logic for the live-location WAVE — a transient "hey 👋" a live sharer
 * broadcasts to every OTHER live sharer near them, popping a mid-screen
 * animation on each nearby recipient's map for a second or two
 * (contracts/functions/functions.json: live.sendWave).
 *
 * A wave is NOT a chat message and NOT convoy-scoped: it is a short-lived,
 * proximity-broadcast nudge to STRANGERS nearby who are also sharing their live
 * location. It reuses the EXISTING live-discovery substrate rather than inventing
 * a presence system:
 *  - the sender's authoritative position + geoCell come from their own
 *    `liveSessions/{uid}` discovery doc (written by live.updatePosition), so a
 *    client can never spoof a far-away blast radius;
 *  - recipients are found by the SAME geo-cell query live.listNearby uses over
 *    `liveSessions` (+ the same block-matrix exclusions);
 *  - delivery fans one short-lived doc out to each recipient's OWN
 *    `liveWaves/{uid}/waves/{waveId}` inbox — modeled on the per-user
 *    notifications inbox (`notifications/{uid}/items`): owner-only read,
 *    backend-only write. Each recipient's client holds a Firestore listener on
 *    its own inbox and pops the wave via the shared ReactionOverlay.
 *
 * ANTI-SPAM is SERVER-ENFORCED. Every send reads-and-writes a per-USER cooldown
 * document (`liveWaveCooldowns/{uid}`, backend-only) — the FIRST thing the
 * callable does, so a throttled spammer never triggers the (read-bearing) nearby
 * geo-query or the fan-out. Modeled on the convoy-reaction cooldown
 * (functions/src/convoy/reaction-core.ts): a deterministic backend-only doc, an
 * `expireAt` TTL field, and a pure `isWithinWaveCooldown` predicate. The client
 * greys the icon for the same window for UX, but the SERVER is the source of
 * truth.
 *
 * Everything here is pure (no Firestore, no admin SDK) so the cooldown window,
 * the radius, the doc/payload builders and the parser are unit-testable without
 * the emulator — wave-core.test.ts.
 */

import { z } from 'zod';
import { Timestamp } from 'firebase-admin/firestore';
import type { ParseResult } from './nearby-core';

const SECOND_MS = 1_000;
const MINUTE_MS = 60 * SECOND_MS;

// ---------------------------------------------------------------------------
// Cooldown (server-enforced anti-spam)
// ---------------------------------------------------------------------------

/**
 * The wave cooldown window, in milliseconds. A single per-user window (a wave is
 * one kind, unlike the convoy reactions) — 45s is long enough that a wave stays a
 * friendly greeting rather than a strobe on nearby strangers' maps, short enough
 * that re-waving after a real interaction never feels punished.
 */
export const WAVE_COOLDOWN_MS = 45 * SECOND_MS;

/**
 * True while the sender is still inside the wave cooldown — a send is refused.
 * `lastSentAtMs === null` (never waved) is never in cooldown. Guards a
 * non-finite stored value so a corrupt timestamp cannot wedge a user out of ever
 * waving again.
 */
export function isWithinWaveCooldown(lastSentAtMs: number | null, nowMs: number): boolean {
  if (lastSentAtMs === null || !Number.isFinite(lastSentAtMs)) return false;
  return nowMs - lastSentAtMs < WAVE_COOLDOWN_MS;
}

/**
 * Milliseconds until the sender may wave again — 0 when they may wave now.
 * Surfaced to the client on a refusal so its icon greys for exactly the remaining
 * time rather than guessing the server's policy.
 */
export function waveCooldownRemainingMs(lastSentAtMs: number | null, nowMs: number): number {
  if (lastSentAtMs === null || !Number.isFinite(lastSentAtMs)) return 0;
  const remaining = WAVE_COOLDOWN_MS - (nowMs - lastSentAtMs);
  return remaining > 0 ? remaining : 0;
}

// ---------------------------------------------------------------------------
// Radius (server-FIXED — a broadcast-to-strangers surface)
// ---------------------------------------------------------------------------

/**
 * The wave reach, in metres. FIXED server-side (never client-supplied) so a
 * client cannot widen its own blast radius. Set to the live-discovery default
 * (DEFAULT_NEARBY_RADIUS_METERS on Android / listNearby's default) so the wave
 * reaches EXACTLY the nearby live sharers the client's wave icon is derived from:
 * the icon appears when ≥1 sharer is within the nearby radius, and a tap reaches
 * everyone within that same radius. Still clamped by clampRadiusMeters at the
 * call site (which enforces the 100..50000 m bounds) as defence in depth.
 */
export const WAVE_RADIUS_METERS = 15_000;

// ---------------------------------------------------------------------------
// TTL
// ---------------------------------------------------------------------------

/**
 * How long a delivered wave doc is retained before the field-scoped Firestore TTL
 * policy on `expireAt` sweeps it. A wave is consumed within seconds of arriving; a
 * few minutes is ample slack for a client that reconnects, and keeps the per-user
 * inbox from accumulating. Mirrors the convoy-reaction retention.
 */
export const WAVE_RETENTION_MINUTES = 5;

/** A delivered wave doc's `expireAt`, [WAVE_RETENTION_MINUTES] from now. */
export function waveExpiry(now: Date): Timestamp {
  return Timestamp.fromMillis(now.getTime() + WAVE_RETENTION_MINUTES * MINUTE_MS);
}

// ---------------------------------------------------------------------------
// Persistent-notification rate-limit window (de-noising)
// ---------------------------------------------------------------------------

/**
 * The rate-limit window for the PERSISTENT "{name} waved at you" notification on
 * the recipient's Notifications page (distinct from [WAVE_COOLDOWN_MS], the
 * sender's send throttle). At most ONE such notice is created per
 * sender→recipient PAIR per window: the notification's deterministic id buckets
 * the current time by this window (`wave_<sender>_<recipient>_<bucket>`), and
 * writeInAppNotification is create-if-absent on that id, so a second wave from
 * the same sender to the same recipient inside the window is a no-op.
 *
 * TUNABLE. A FIXED (aligned) bucket, not a rolling window — simple and cheap for
 * de-noising; two waves either side of a bucket boundary can still yield two
 * notices, which is acceptable. Buckets by the wave's authoritative stamp time so
 * a retried/resumed send reproduces the same id (retry-idempotent).
 */
export const WAVE_NOTIF_WINDOW_MS = 60 * MINUTE_MS;

/** The current rate-limit bucket for [WAVE_NOTIF_WINDOW_MS] at `stampMs`. */
export function waveNotifBucket(stampMs: number): number {
  return Math.floor(stampMs / WAVE_NOTIF_WINDOW_MS);
}

/**
 * Deterministic per-pair-per-window id for the persistent wave notification.
 * A second wave from the same sender to the same recipient inside the same
 * bucket collapses to this same id (create-if-absent = no-op), and a retry of
 * one logical wave lands in the same bucket (same stamp) = same id.
 */
export function waveNotificationId(
  senderUid: string,
  recipientUid: string,
  stampMs: number,
): string {
  return `wave_${senderUid}_${recipientUid}_${waveNotifBucket(stampMs)}`;
}

/**
 * The cooldown document's `expireAt`. Must comfortably outlive the cooldown
 * window so a doc is never swept while it is still throttling; an hour past the
 * last wave is far more than the 45s window, and an actively-waving user keeps
 * bumping it, so their doc never expires under them.
 */
export function waveCooldownExpiry(now: Date): Timestamp {
  return Timestamp.fromMillis(now.getTime() + 60 * MINUTE_MS);
}

// ---------------------------------------------------------------------------
// Delivered wave document payload
// ---------------------------------------------------------------------------

/**
 * The stored wave document (one per recipient, at
 * `liveWaves/{recipientUid}/waves/{waveId}`). Denormalizes the sender's PUBLIC
 * display name so the receiving client renders the "who waved" caption with no
 * profile read. No PII beyond that already-public name. `createdAt` is a fixed
 * logical time (not a server sentinel) so the client can order/dedupe
 * deterministically, and `expireAt` drives the TTL sweep. `waveId` is the shared
 * id of this one logical broadcast (the doc id under every recipient), so a
 * client dedupes a wave it might receive twice by id.
 */
export interface WaveDocument {
  waveId: string;
  senderUid: string;
  senderDisplayName: string | null;
  createdAt: Timestamp;
  expireAt: Timestamp;
}

export function buildWaveDocument(params: {
  waveId: string;
  senderUid: string;
  senderDisplayName: string | null;
  createdAt: Timestamp;
  expireAt: Timestamp;
}): WaveDocument {
  return {
    waveId: params.waveId,
    senderUid: params.senderUid,
    senderDisplayName: params.senderDisplayName,
    createdAt: params.createdAt,
    expireAt: params.expireAt,
  };
}

// ---------------------------------------------------------------------------
// Input parsing
// ---------------------------------------------------------------------------

/**
 * Optional idempotency key, reused verbatim as the shared wave doc id. A retried
 * optimistic send replays the committed wave WITHOUT re-fanning-out or re-charging
 * the cooldown, so a flaky network never double-pops receivers. Same character
 * class as the convoy-reaction clientId (a valid Firestore doc id segment).
 */
const clientWaveIdSchema = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);

const sendWaveSchema = z
  .object({
    // No position and no radius: the server reads the sender's authoritative
    // position from their own discovery doc and uses the FIXED WAVE_RADIUS_METERS,
    // so a client can neither spoof where it waves from nor how far.
    clientId: clientWaveIdSchema.optional(),
  })
  .strict();

export type SendWaveInput = z.infer<typeof sendWaveSchema>;

export const SEND_WAVE_EXPECTED =
  'Expected { clientId? } where clientId matches [A-Za-z0-9_-]{1,64}.';

export function parseSendWaveInput(data: unknown): ParseResult<SendWaveInput> {
  const result = sendWaveSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: SEND_WAVE_EXPECTED };
  }
  return { ok: true, input: result.data };
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const WAVE_RATE_LIMITED_MESSAGE = 'Slow down — you just waved.';
export const WAVE_NOT_SHARING_MESSAGE =
  'Start sharing your live location to wave at people nearby.';
