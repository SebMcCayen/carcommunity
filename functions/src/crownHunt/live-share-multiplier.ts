/**
 * Kronjakt live-share SCORING — the per-claim reward multiplier that depends on
 * whether the collector is CURRENTLY sharing a live session.
 *
 * A crown collected while the member is NOT live-sharing pays half its
 * Kronpoäng ({@link NON_LIVE_SHARE_MULTIPLIER}); a crown collected during an
 * active live session pays full. Shared by BOTH crown award paths
 * (`crownHunt.claimSpawn` for auto-spawn crowns and `crownHunt.submitClaim` for
 * hand-placed points), so the rule is identical wherever a crown is awarded.
 *
 * Flag-gated on the contract-default-OFF `crownHuntLiveShareScoring`, and
 * DELIBERATELY FAIL-OPEN: the award is reduced ONLY when the backend can
 * CONFIRM the member is not sharing (the flag is on AND an active session is
 * absent). The flag being off, an active session, OR any error reading the
 * session all return 1 (full award) — a member who IS live-sharing must never
 * be wrongly penalised for a transient read failure.
 *
 * Composes multiplicatively with the PvP boost multiplier
 * ({@link resolveActiveBoostMultiplier}); the caller folds both into the reward
 * and rounds with `Math.round`. The reduced (or boosted) amount is still
 * credited with `source: 'crown_hunt'`, so the existing daily fold charges the
 * actual awarded amount to the economy cap.
 */

import { logger } from 'firebase-functions';
import { adminRtdb } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { isSessionActive } from '../live/live-core';

/** The flag that gates this whole rule; contract default OFF. */
export const CROWN_HUNT_LIVE_SHARE_SCORING_FLAG_KEY = 'crownHuntLiveShareScoring' as const;

/** Award multiplier applied when the collector is confirmed NOT live-sharing. */
export const NON_LIVE_SHARE_MULTIPLIER = 0.5;

/**
 * A live-session node whose shape `isSessionActive` can actually judge: a plain
 * object with a string `status` and a parseable `expiresAt`. Distinguishes a
 * genuine (if stopped/expired) session from a MALFORMED node so fail-open can
 * treat "can't tell" (garbage) differently from "confirmed inactive" (a real
 * stopped/expired session).
 */
type JudgeableSession = NonNullable<Parameters<typeof isSessionActive>[0]>;

function isWellFormedSession(value: unknown): value is JudgeableSession {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as { status?: unknown; expiresAt?: unknown };
  // `status` is only ever COMPARED to 'active' by isSessionActive, so any string
  // is a judgeable shape here — an unknown status simply reads as inactive.
  return (
    typeof v.status === 'string' &&
    typeof v.expiresAt === 'string' &&
    Number.isFinite(Date.parse(v.expiresAt))
  );
}

/**
 * The live-share award multiplier for `uid` at `now` — 1 while the member is
 * sharing an active live session (or when the rule is off / unreadable), else
 * {@link NON_LIVE_SHARE_MULTIPLIER}. Best-effort and fail-open: the halving is
 * only ever returned when the flag is on AND an active session is confirmed
 * absent, so a sharer is never penalised by a transient error.
 */
export async function resolveLiveShareMultiplier(uid: string, now: Date): Promise<number> {
  try {
    // DELIBERATELY an UNCACHED read, mirroring resolveActiveBoostMultiplier: the
    // multiplier is a direct, per-crown-claim reward the member expects the
    // instant they start (or stop) sharing, and a claim is far lower frequency
    // than a position sample, so a per-claim flag read costs little.
    if (!(await readFeatureFlag(CROWN_HUNT_LIVE_SHARE_SCORING_FLAG_KEY))) {
      return 1;
    }
    // The live-sharing signal is the RTDB session node the live domain writes
    // (liveLocation/{uid}/session), read exactly like readLatestTrustedPosition.
    const snap = await adminRtdb.ref(`liveLocation/${uid}/session`).get();
    const session = snap.val() as unknown;
    // An ABSENT node is a POSITIVE confirmation that no session exists → halve.
    if (session === null || session === undefined) {
      return NON_LIVE_SHARE_MULTIPLIER;
    }
    // A MALFORMED node (present but not a judgeable session shape) is AMBIGUOUS:
    // we cannot confirm the member is not sharing, so fail open to full rather
    // than penalise on a corrupt/partial write.
    if (!isWellFormedSession(session)) {
      return 1;
    }
    // A well-formed node: active → full, stopped/expired → confirmed not sharing.
    return isSessionActive(session, now) ? 1 : NON_LIVE_SHARE_MULTIPLIER;
  } catch (error) {
    // No PII: the uid is deliberately NOT logged (hard no-identifiers-in-logs
    // rule); "live-share read failed" plus the error is enough to diagnose, and
    // fail-open means the member still gets full Kronpoäng.
    logger.warn('Live-share multiplier read failed; awarding full', {
      error: error instanceof Error ? error.message : String(error),
    });
    return 1;
  }
}
