/**
 * Subscription reconciliation — pure decision logic.
 *
 * WHAT THIS SWEEP IS (and is NOT). The RTDN handler is the primary, event
 * driven path that keeps entitlement in step with Play; the expiry sweep
 * (scheduled.ts) is the `expiresAt`-driven backstop for a renewal that simply
 * stopped. This reconciliation is the THIRD leg: a periodic pass that
 * re-converges the three representations of entitlement to the AUTHORITATIVE
 * `subscriptions/{uid}` record, catching drift a lost/half-applied write left
 * behind (a revoke that cleared the record but not the claim; a delivery that
 * updated the record but whose downstream claim write failed and was never
 * retried).
 *
 * It is deliberately DOWNGRADE-ONLY. It removes privilege a member is no
 * longer entitled to (record no longer grants, yet the claim/flag still say
 * "member"); it never ADDS privilege from a stored record, because a record
 * can momentarily lead the claim during an in-flight GRANT
 * (applyPrivilegeChange writes records before the claim when privilege
 * increases), and re-granting from it could fight that transition. An
 * under-privileged member (record grants, claim/flag missing) is LOGGED for
 * observability and left for the next verify call to repair — the safe
 * direction to err.
 *
 * WHY NOT RE-QUERY PLAY PER SUBSCRIPTION. The obvious "re-ask Play for every
 * active sub" cannot be done here: the authoritative Play call needs the RAW
 * purchase token, and the repo-wide security rule is that raw tokens are NEVER
 * stored (only their SHA-256 hash). So Play truth reaches entitlement through
 * the two paths that DO hold the raw token in-flight — a fresh verify call and
 * an RTDN delivery — plus the `expiresAt` backstop. This sweep reconciles the
 * REPRESENTATIONS around that authoritative record; it does not re-derive the
 * record from Play. (See the PR body: closing the "missed RTDN before
 * expiresAt" gap fully would require a deliberate token-retention decision.)
 *
 * Pure module — no Firebase Admin SDK, no clock reads.
 */

import {
  grantsLegacyActiveMember,
  type EntitlementRecordInput,
  type SubscriptionEntitlement,
  type SubscriptionPlatform,
  type SubscriptionStatus,
  type SubscriptionTier,
} from './subscription-core';

/**
 * Upper bound on subscriptions inspected per run, mirroring
 * MAX_EXPIRIES_PER_RUN: the rotating cursor drains the collection across runs,
 * so this bounds LATENCY, never correctness.
 */
export const MAX_RECONCILE_PER_RUN = 200;

/** Cursor document id under the backend-only reconcileState collection. */
export const RECONCILE_CURSOR_DOC = 'subscriptionRepresentation';

/** The subset of a `subscriptions/{uid}` document the decision reads. */
export interface ReconcileRecord {
  status: SubscriptionStatus;
  entitlement: SubscriptionEntitlement;
  tier?: SubscriptionTier;
  platform: SubscriptionPlatform;
  purchaseTokenHash: string | null;
  /** Already converted from a Firestore Timestamp by the caller. */
  startsAt: Date | null;
  /** Already converted from a Firestore Timestamp by the caller. */
  expiresAt: Date | null;
}

export type ReconcileDecision =
  | { action: 'downgrade'; input: EntitlementRecordInput }
  | { action: 'consistent' }
  | { action: 'under_privileged' };

/**
 * Re-applies the record's OWN fields. Because the record does not grant
 * access (checked by the caller before this is used), running it through
 * applyEntitlement recomputes the flag/claim to false and clears them — an
 * idempotent overwrite that never adds privilege.
 */
function entitlementInputFromRecord(uid: string, record: ReconcileRecord): EntitlementRecordInput {
  return {
    userId: uid,
    platform: record.platform,
    status: record.status,
    entitlement: record.entitlement,
    tier: record.tier,
    purchaseTokenHash: record.purchaseTokenHash,
    startsAt: record.startsAt,
    expiresAt: record.expiresAt,
  };
}

/**
 * Decides the action for one subscription record given whether the account
 * CURRENTLY holds member privilege (its activeMember claim OR its
 * users/{uid}.activeMember flag is true).
 */
export function decideReconciliation(
  uid: string,
  record: ReconcileRecord,
  userHoldsPrivilege: boolean,
): ReconcileDecision {
  const recordGrants = grantsLegacyActiveMember(record);
  if (!recordGrants && userHoldsPrivilege) {
    // Over-privileged: the record no longer grants, but access lingers.
    return { action: 'downgrade', input: entitlementInputFromRecord(uid, record) };
  }
  if (recordGrants && !userHoldsPrivilege) {
    // Under-privileged: safe direction — leave for verify to repair.
    return { action: 'under_privileged' };
  }
  return { action: 'consistent' };
}
