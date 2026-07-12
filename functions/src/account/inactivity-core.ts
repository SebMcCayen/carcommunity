/**
 * Inactive-account lifecycle — constants and PURE decision logic (account
 * lifecycle cross-lane).
 *
 * Two mechanisms sit on top of this module:
 *
 * 1. `auth.recordLogin` stamps `users/{uid}.lastLoginAt` on every sign-in
 *    (functions/src/auth/recordLogin.ts). That Firestore field — NOT Firebase
 *    Auth's built-in lastSignInTime, which is not queryable — is the source of
 *    truth for "when did this account last sign in".
 * 2. The scheduled `account-cleanupInactive` sweep
 *    (functions/src/account/inactivityCleanup.ts) resolves each account's last
 *    activity, then asks `decideInactivity` below what to do: skip, warn (mark +
 *    would-email), clear a stale warning (the user came back), or hard-delete.
 *
 * Activity resolution: `lastLoginAt` when present, otherwise the account
 * creation time (`createdAt`). Accounts that predate this feature — or that
 * never became members and so never called the member-gated recordLogin — have
 * no lastLoginAt and fall back to createdAt, exactly as the design intends.
 *
 * Timeline (all durations are configuration-free constants):
 * - >= 11 months since last activity, not yet warned  → WARN: stamp
 *   inactivityWarnedAt = now, inactivityDeleteAfter = now + 30 days, and attempt
 *   an email warning (degrades to a logged no-op until email is wired).
 * - warned, and the user has since signed in           → CLEAR the warning.
 * - warned, past inactivityDeleteAfter, still inactive → DELETE — but ONLY when
 *   deletion is enabled (see the CRITICAL GATE below); otherwise WOULD_DELETE.
 *
 * CRITICAL GATE (`deletionEnabled`): the caller computes this as
 *   config/accountLifecycle.inactiveAccountDeletionEnabled (default FALSE)
 *   AND email delivery actually being available.
 * Because email is not wired for the MVP, deletionEnabled is FALSE today, so the
 * sweep only ever MARKS + would-warn and NEVER hard-deletes. A gated candidate
 * surfaces as WOULD_DELETE (logged, no mutation) instead of DELETE. See the
 * scheduled function's header for how to turn deletion on once email works.
 *
 * Pure module — no Firebase Admin SDK imports — so the decision table is
 * unit-testable without emulators.
 */

/** Inactivity threshold before the first warning is issued. */
export const INACTIVITY_WARN_AFTER_MONTHS = 11;

/** Grace period between the warning and the earliest possible hard-delete. */
export const INACTIVITY_DELETE_GRACE_DAYS = 30;

/** Backend-only config document + field holding the hard-delete kill-switch. */
export const ACCOUNT_LIFECYCLE_CONFIG_DOC = 'accountLifecycle';
export const INACTIVE_DELETION_ENABLED_FIELD = 'inactiveAccountDeletionEnabled';
/** The kill-switch defaults OFF — deletion never runs unless explicitly turned on. */
export const INACTIVE_DELETION_ENABLED_DEFAULT = false;

/** Reason stamped on the proof-of-deletion record for an auto-cleanup delete. */
export const INACTIVITY_DELETION_REASON = 'inactivity_auto_cleanup';

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * `from` minus `months` calendar months (UTC). Month-length overflow is left to
 * Date's normalization (subtracting a month from the 31st can land a couple of
 * days off) — irrelevant at an 11-month granularity.
 */
export function subtractMonths(from: Date, months: number): Date {
  const result = new Date(from.getTime());
  result.setUTCMonth(result.getUTCMonth() - months);
  return result;
}

/** Add `days` to `from`. */
export function addDays(from: Date, days: number): Date {
  return new Date(from.getTime() + days * DAY_MS);
}

/**
 * Instant at/left-of which an account counts as inactive: `now` minus the
 * 11-month warning threshold. lastActivity <= this → inactive.
 */
export function inactivityWarnCutoff(now: Date): Date {
  return subtractMonths(now, INACTIVITY_WARN_AFTER_MONTHS);
}

/** Resolves an account's last activity: lastLoginAt, else createdAt. */
export function resolveLastActivity(
  lastLoginAt: Date | null | undefined,
  createdAt: Date,
): Date {
  return lastLoginAt ?? createdAt;
}

export type InactivityAction =
  /** No action — active, or warned but still inside the grace window. */
  | 'skip'
  /** Inactive and not yet warned — mark + attempt the email warning. */
  | 'warn'
  /** Previously warned but the user signed in again — clear the warning. */
  | 'clear_warning'
  /** Warned, past the grace window, still inactive, AND deletion enabled. */
  | 'delete'
  /** Would delete, but the hard-delete gate is closed — log only, no mutation. */
  | 'would_delete';

export interface InactivityDecisionInput {
  /** Evaluation instant (the scheduled run's `now`). */
  now: Date;
  /** Resolved last activity (lastLoginAt ?? createdAt). */
  lastActivityAt: Date;
  /** users/{uid}.inactivityWarnedAt, or null when never warned. */
  warnedAt: Date | null;
  /** users/{uid}.inactivityDeleteAfter, or null when never warned. */
  deleteAfter: Date | null;
  /** The resolved hard-delete gate (config flag AND email available). */
  deletionEnabled: boolean;
}

export interface InactivityDecision {
  action: InactivityAction;
  /** Short machine-readable explanation, surfaced in logs. */
  reason: string;
}

/**
 * The full inactivity decision table. Deterministic and side-effect-free — the
 * scheduled sweep merely executes whatever this returns.
 *
 * "Still inactive" after a warning means the account has not signed in SINCE the
 * warning was issued: lastActivity has not advanced past warnedAt. A member who
 * came back refreshes lastLoginAt, pushing lastActivity beyond warnedAt, which
 * clears the warning rather than proceeding to deletion.
 */
export function decideInactivity(input: InactivityDecisionInput): InactivityDecision {
  const { now, lastActivityAt, warnedAt, deleteAfter, deletionEnabled } = input;
  const cutoff = inactivityWarnCutoff(now);
  const inactive = lastActivityAt.getTime() <= cutoff.getTime();

  if (warnedAt === null) {
    if (!inactive) {
      return { action: 'skip', reason: 'active' };
    }
    return { action: 'warn', reason: 'inactive_not_yet_warned' };
  }

  // Already warned. If the account has signed in since the warning, it is no
  // longer inactive — retract the warning and let the account continue.
  if (lastActivityAt.getTime() > warnedAt.getTime()) {
    return { action: 'clear_warning', reason: 'reactivated_after_warning' };
  }

  // Still inactive since the warning. Not yet past the grace window → wait.
  if (deleteAfter === null || now.getTime() < deleteAfter.getTime()) {
    return { action: 'skip', reason: 'warned_within_grace_period' };
  }

  // Past the grace window and still inactive. Hard-delete ONLY if the gate is
  // open; otherwise surface would_delete so the sweep can log it without acting.
  if (!deletionEnabled) {
    return { action: 'would_delete', reason: 'delete_gate_disabled' };
  }
  return { action: 'delete', reason: 'inactive_past_grace_period' };
}
