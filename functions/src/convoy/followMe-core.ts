/**
 * Pure logic for the convoy "Follow me" LEADER TRAIL — the persistent, shared,
 * toggleable line of where the current leader has recently driven, drawn on
 * EVERY convoy member's map so a member who gets separated can rejoin.
 *
 * This is a DIFFERENT thing from the transient follow-me REACTION (reaction-core
 * ts): the reaction is a one-shot ~30s mid-screen animation; the TRAIL is durable
 * shared state that stays on the map until the leader turns it off, is taken over
 * by another member, or the leader leaves / the convoy ends. Pressing the
 * Follow-me button fires BOTH (the animation on activation + this trail toggle).
 *
 * ## Shared-state shape
 * The trail lives on a per-convoy SUBCOLLECTION document
 * `convoys/{convoyId}/followMe/current` (FOLLOW_ME_DOC_ID) rather than a map on
 * the convoy doc itself, deliberately:
 *  - the leader rewrites `polyline`/`updatedAt` every few seconds while driving;
 *    on the convoy doc that would fire the convoy onWrite badge trigger and bloat
 *    every `convoy.list` read (which fetches the whole convoy doc). A dedicated
 *    subdoc isolates the high-frequency write from the roster document.
 *  - it still reuses convoy membership security: the rules `get()` the parent
 *    convoy for the member/leader gate (firebase/firestore.rules), exactly like
 *    the convoyChats subcollections.
 *
 * Fields on `convoys/{convoyId}/followMe/current`:
 *  - `leaderUid: string`   — the ONE current leader (exclusivity: a takeover
 *                            overwrites it under the callable's admin transaction)
 *  - `polyline: string`    — base64 of the CCRB-encoded rolling ~15 km trail,
 *                            written DIRECTLY by the leader's client on a throttle
 *  - `updatedAt: Timestamp`— last write instant (server clock); the member-side
 *                            freshness gate reads it so a crashed/vanished
 *                            leader's stale trail stops drawing
 *
 * ## Exclusivity + toggle
 * Only ONE leader trail exists per convoy at a time. `decideSetFollowMe` is the
 * whole state machine for the `convoy.setFollowMe` callable:
 *  - active=true  → SET leaderUid = caller (takeover: replaces any prior leader,
 *                   resetting the polyline), whoever held it before.
 *  - active=false → CLEAR, but ONLY when the caller is the current leader (a
 *                   toggle-off is the leader turning their own trail off); anyone
 *                   else is a no-op so a non-leader can never wipe the trail.
 *
 * Kept Firebase-free (no admin SDK, no Firestore) so the decision logic, the
 * freshness gate, and the input parser are unit-testable without the emulator —
 * followMe-core.test.ts. The callable in setFollowMe.ts owns all Firestore I/O.
 */

import { z } from 'zod';
import type { ParseResult } from '../chatchannels/chat-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * The single follow-me doc id under `convoys/{convoyId}/followMe`. There is at
 * most ONE leader trail per convoy, so a fixed id (not an auto-id) makes the
 * doc addressable by every reader/writer without a query and makes "takeover
 * replaces the previous leader" a plain overwrite of one known document.
 */
export const FOLLOW_ME_DOC_ID = 'current';

/**
 * The rolling trail length, in metres. ~15 km — far longer than the private ~1 km
 * self-breadcrumb — so a member who fell well behind still sees a continuous line
 * back to the leader. Enforced CLIENT-SIDE by the leader's BreadcrumbTrail buffer
 * (windowMeters = this); duplicated here as the canonical constant and asserted
 * by the Android trail test so the two ends cannot silently diverge.
 */
export const FOLLOW_ME_TRAIL_WINDOW_METERS = 15_000;

/**
 * Member-side freshness window, in milliseconds. If the leader's trail has not
 * been refreshed within this window (their app crashed, lost signal, or the
 * background process was killed), members STOP drawing the line rather than
 * leave a stale ghost trail pointing at where the leader was minutes ago.
 *
 * The owner explicitly did NOT want an inactivity TIMEOUT that turns the feature
 * off for an active leader — so this is deliberately generous (90s, comfortably
 * longer than the ~3-5s write throttle plus slack for a red light or a tunnel):
 * it only hides a trail whose owner has genuinely gone quiet, and the instant the
 * leader writes again the line reappears. It never CLEARS the shared doc; it is
 * purely a render-time gate each member applies to what they read.
 */
export const FOLLOW_ME_STALE_MS = 90_000;

/**
 * Upper bound on the stored polyline string length (base64 chars). A ~15 km trail
 * at the client's sampling density is a few KB; 200 KB is far above any honest
 * trail yet well under Firestore's 1 MiB document limit, so it caps an abusive
 * client without ever rejecting a real one. Mirrored in the Firestore write rule.
 */
export const FOLLOW_ME_MAX_POLYLINE_CHARS = 200_000;

// ---------------------------------------------------------------------------
// Leadership toggle decision (the callable's whole state machine)
// ---------------------------------------------------------------------------

/**
 * What the `convoy.setFollowMe` transaction must do, given the CURRENT leader on
 * the followMe doc (or null when there is no active trail) and the caller's
 * requested `active`.
 *  - `set`  : write leaderUid = caller and reset the polyline (activation OR a
 *             takeover from another leader — same operation, last writer wins).
 *  - `clear`: delete the trail (toggle-off BY the current leader).
 *  - `noop` : do nothing and leave the trail exactly as it is. Two cases:
 *             (a) the caller asked to turn OFF a trail they do not own — so one
 *             member can never clear another member's trail; and (b) the CURRENT
 *             leader re-activated (double-tap / retry / state lag) — idempotent, so
 *             re-activation never resets their own polyline.
 */
export type FollowMeAction =
  | { kind: 'set'; leaderUid: string }
  | { kind: 'clear' }
  | { kind: 'noop' };

export function decideSetFollowMe(
  currentLeaderUid: string | null | undefined,
  callerUid: string,
  active: boolean,
): FollowMeAction {
  if (active) {
    // Already the leader? Re-activation must be IDEMPOTENT — a double-tap, a
    // callable retry, or a client calling active=true while already leading due
    // to state lag must NOT reset the polyline (the 'set' path overwrites it with
    // an empty trail). Leave the trail intact.
    if (currentLeaderUid && currentLeaderUid === callerUid) {
      return { kind: 'noop' };
    }
    // Activation (no current leader) OR takeover from ANOTHER member: the caller
    // becomes the leader with a fresh (reset) trail. Exclusivity is structural —
    // there is one doc and one leaderUid.
    return { kind: 'set', leaderUid: callerUid };
  }
  // Toggle-off only succeeds for the member who actually owns the trail.
  if (currentLeaderUid && currentLeaderUid === callerUid) {
    return { kind: 'clear' };
  }
  return { kind: 'noop' };
}

// ---------------------------------------------------------------------------
// Member-side freshness gate
// ---------------------------------------------------------------------------

/**
 * True when a trail last refreshed at `lastFreshMs` is still fresh enough to draw
 * at `nowMs` (within `windowMs`). A null/non-finite timestamp is treated as STALE
 * (fail closed — never draw a trail we can't date). `lastFreshMs` is the leader's
 * freshness signal: either the followMe doc's `updatedAt` or the leader's live
 * marker `recordedAt`, whichever the caller has cheapest to hand.
 */
export function isFollowMeTrailFresh(
  lastFreshMs: number | null | undefined,
  nowMs: number,
  windowMs: number = FOLLOW_ME_STALE_MS,
): boolean {
  if (lastFreshMs === null || lastFreshMs === undefined || !Number.isFinite(lastFreshMs)) {
    return false;
  }
  return nowMs - lastFreshMs < windowMs;
}

/**
 * The full member-side render gate: whether THIS member should draw the shared
 * trail. It draws when
 *  - a leader is set AND it is not the viewer themselves (the leader keeps their
 *    OWN private self-trail; the shared line is for the OTHERS), AND
 *  - the leader is still an accepted member of the convoy (a vanished/removed
 *    leader's line stops drawing even if server cleanup lagged), AND
 *  - the trail is fresh (isFollowMeTrailFresh).
 *
 * Pure so the identical decision can be asserted in a unit test and mirrored on
 * the Android renderer.
 */
export function shouldDrawFollowMeTrail(params: {
  leaderUid: string | null | undefined;
  selfUid: string;
  leaderIsMember: boolean;
  lastFreshMs: number | null | undefined;
  nowMs: number;
  windowMs?: number;
}): boolean {
  const { leaderUid, selfUid, leaderIsMember, lastFreshMs, nowMs, windowMs } = params;
  if (!leaderUid) return false;
  if (leaderUid === selfUid) return false;
  if (!leaderIsMember) return false;
  return isFollowMeTrailFresh(lastFreshMs, nowMs, windowMs);
}

// ---------------------------------------------------------------------------
// Input parsing
// ---------------------------------------------------------------------------

const convoyIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const setFollowMeSchema = z
  .object({
    convoyId: convoyIdSchema,
    active: z.boolean(),
  })
  .strict();

export type SetFollowMeInput = z.infer<typeof setFollowMeSchema>;

export const SET_FOLLOW_ME_EXPECTED = 'Expected { convoyId, active: boolean }.';

export function parseSetFollowMeInput(data: unknown): ParseResult<SetFollowMeInput> {
  const result = setFollowMeSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: SET_FOLLOW_ME_EXPECTED };
  }
  return { ok: true, input: result.data };
}
