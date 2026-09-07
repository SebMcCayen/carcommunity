/**
 * applyEntitlement — the single writer for subscription entitlement state
 * (Phase 11). Not a callable.
 *
 * Applies one verified (or manually granted) entitlement outcome
 * atomically-in-order across the three places it lives:
 * subscriptions/{uid} (the record), users/{uid}.activeMember (rules
 * read this via isActiveMember), and the activeMember custom claim
 * (RTDB rules read this for live-location markers).
 *
 * Fail-safe ordering via applyPrivilegeChange (Phase 8): granting
 * entitlement INCREASES privilege → records commit before the claim;
 * revoking DECREASES it → the claim is cleared (and refresh tokens
 * revoked) before the records. A partial failure never leaves someone
 * with more access than their records say.
 */

import { FieldValue } from 'firebase-admin/firestore';
import { adminAuth, db } from '../firebase';
import { applyPrivilegeChange, computeUpdatedClaims } from '../admin/claims-core';
import { supporterBadgeEligible } from './supporter-badge-core';
import {
  buildSubscriptionDocument,
  grantsLegacyActiveMember,
  type EntitlementRecordInput,
} from './subscription-core';

export interface ApplyEntitlementOptions {
  /** Optional audit event committed atomically with the Firestore entitlement records. */
  auditEvent?: Record<string, unknown>;
}

export async function applyEntitlement(
  input: EntitlementRecordInput,
  options: ApplyEntitlementOptions = {},
): Promise<void> {
  const active = grantsLegacyActiveMember(input);
  const targetUser = await adminAuth.getUser(input.userId);

  await applyPrivilegeChange({
    decreasesPrivilege: !active,
    writeClaims: async () => {
      await adminAuth.setCustomUserClaims(
        input.userId,
        computeUpdatedClaims(targetUser.customClaims, { activeMember: active }),
      );
      if (!active) {
        await adminAuth.revokeRefreshTokens(input.userId);
      }
    },
    commitRecords: async () => {
      const batch = db.batch();
      batch.set(
        db.collection('subscriptions').doc(input.userId),
        buildSubscriptionDocument(input, () => FieldValue.serverTimestamp()),
      );
      batch.set(
        db.collection('users').doc(input.userId),
        {
          activeMember: active,
          supporterBadgeEligible: supporterBadgeEligible(input, new Date()),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      if (options.auditEvent) {
        batch.set(db.collection('adminAuditEvents').doc(), options.auditEvent);
      }
      await batch.commit();
    },
  });
}
