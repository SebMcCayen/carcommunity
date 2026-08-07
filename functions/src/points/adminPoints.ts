/**
 * points.adminAdjust / points.adminReverse — admin callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `points` export group as `points-adminAdjust` and
 * `points-adminReverse`. Requires an active admin via requireAdminActor.
 *
 * Legacy parity (points-service applyAdminPointsAdjustment /
 * reversePointsTransaction):
 * - Adjustments need a positive integer amount and a reason (<=500 chars);
 *   debits never overdraft; only an owner may adjust another owner's points.
 * - Reversals negate the original entry exactly once (deterministic
 *   reversal entry ID), reference it via relatedEntityId, and never reverse
 *   a reversal. A reversal that would overdraft is rejected.
 * - Both write an adminAuditEvents record in the primary operation's
 *   transaction path.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { isOwnerRole, toUserAccessState } from '../shared/access';
import {
  applyDelta,
  buildLedgerEntry,
  parseAdminAdjustInput,
  parseAdminReverseInput,
  reversalDescription,
  reversalEntryId,
  toStoredBalance,
  type PointsTransactionType,
} from './points-core';
import { creditPoints, debitPoints } from './ledger';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface PointsAdminResponse {
  targetUid: string;
  entryId: string;
  amount: number;
  balanceAfter: number;
  alreadyApplied: boolean;
}

export const adminAdjust = onCall(CALLABLE_OPTS, async (request): Promise<PointsAdminResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseAdminAdjustInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { targetUid, type, amount, reason } = parsed.input;

  // Owner protection (legacy): only an owner may adjust another owner.
  const targetSnap = await db.collection('users').doc(targetUid).get();
  if (!targetSnap.exists) {
    throw new HttpsError('not-found', 'Target user not found.');
  }
  const targetState = toUserAccessState(targetSnap.data());
  if (
    isOwnerRole(targetState.role) &&
    targetUid !== actor.uid &&
    !isOwnerRole(actor.state.role)
  ) {
    throw new HttpsError(
      'permission-denied',
      'Only an owner may adjust points for another owner.',
    );
  }

  const isCredit = type === 'adjustment_credit';
  const description = isCredit
    ? `Adminjustering (kredit): ${reason}`
    : `Adminjustering (debet): ${reason}`;
  const mutate = isCredit ? creditPoints : debitPoints;
  // The audit record commits ATOMICALLY with the balance/entry write — a
  // points change can never exist without its audit trail.
  const result = await mutate(
    {
      targetUid,
      amount,
      transactionType: type,
      source: 'admin_adjustment',
      description,
      createdByUserId: actor.uid,
    },
    (tx, mutation) => {
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'points.adminAdjust',
            targetType: 'user',
            targetId: targetUid,
            reason,
            details: {
              type,
              amount,
              entryId: mutation.entryId,
              balanceAfter: mutation.balanceAfter,
            },
          },
          () => FieldValue.serverTimestamp(),
        ),
      );
    },
  );

  return { targetUid, ...result };
});

export const adminReverse = onCall(CALLABLE_OPTS, async (request): Promise<PointsAdminResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseAdminReverseInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { targetUid, entryId, reason } = parsed.input;

  const ledgerRef = db.collection('pointsLedger').doc(targetUid);
  const originalRef = ledgerRef.collection('entries').doc(entryId);
  const reversalRef = ledgerRef.collection('entries').doc(reversalEntryId(entryId));
  const serverTimestamp = () => FieldValue.serverTimestamp();

  const result = await db.runTransaction(async (tx) => {
    const [originalSnap, reversalSnap, ledgerSnap] = await Promise.all([
      tx.get(originalRef),
      tx.get(reversalRef),
      tx.get(ledgerRef),
    ]);

    if (!originalSnap.exists) {
      throw new HttpsError('not-found', 'Original transaction not found.');
    }
    const original = originalSnap.data()!;
    if ((original.transactionType as PointsTransactionType) === 'reversal') {
      throw new HttpsError('failed-precondition', 'A reversal cannot be reversed.');
    }
    if (reversalSnap.exists) {
      const existing = reversalSnap.data()!;
      return {
        entryId: reversalRef.id,
        amount: existing.amount as number,
        balanceAfter: existing.balanceAfter as number,
        alreadyApplied: true,
      };
    }

    const originalAmount = original.amount;
    if (typeof originalAmount !== 'number' || !Number.isSafeInteger(originalAmount)) {
      // A corrupted entry must fail loudly rather than write NaN balances.
      throw new HttpsError(
        'failed-precondition',
        'Original entry has a corrupted amount and cannot be reversed.',
      );
    }
    const reversalAmount = -originalAmount;
    const currentBalance = toStoredBalance(ledgerSnap.data()?.balance);
    const check = applyDelta(currentBalance, reversalAmount);
    if (!check.ok) {
      throw new HttpsError('failed-precondition', 'Reversal would produce a negative balance.');
    }

    tx.set(
      reversalRef,
      buildLedgerEntry(
        {
          transactionType: 'reversal',
          source: original.source,
          amount: reversalAmount,
          balanceAfter: check.balanceAfter,
          description: reversalDescription(entryId, reason),
          relatedEntityType: 'points_ledger_entry',
          relatedEntityId: entryId,
          createdByUserId: actor.uid,
        },
        serverTimestamp,
      ),
    );
    tx.set(
      ledgerRef,
      { balance: check.balanceAfter, updatedAt: serverTimestamp() },
      { merge: true },
    );
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'points.adminReverse',
          targetType: 'user',
          targetId: targetUid,
          reason,
          details: {
            originalEntryId: entryId,
            reversalAmount,
            balanceAfter: check.balanceAfter,
          },
        },
        serverTimestamp,
      ),
    );

    return {
      entryId: reversalRef.id,
      amount: reversalAmount,
      balanceAfter: check.balanceAfter,
      alreadyApplied: false,
    };
  });

  return { targetUid, ...result };
});
