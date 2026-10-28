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

import { createHash } from 'node:crypto';
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
  const { targetUid, type, amount, reason, idempotencyKey } = parsed.input;
  if (parsed.input.expectedActorUid && parsed.input.expectedActorUid !== actor.uid)
    throw new HttpsError(
      'permission-denied',
      'The administrator session changed; reload the member card.',
    );

  // Owner protection (legacy): only an owner may adjust another owner.
  const targetSnap = await db.collection('users').doc(targetUid).get();
  if (!targetSnap.exists) {
    throw new HttpsError('not-found', 'Target user not found.');
  }
  const targetState = toUserAccessState(targetSnap.data());
  if (isOwnerRole(targetState.role) && targetUid !== actor.uid && !isOwnerRole(actor.state.role)) {
    throw new HttpsError('permission-denied', 'Only an owner may adjust points for another owner.');
  }

  // The opaque key names one operation; the fingerprint binds its immutable intent.
  const requestFingerprint = createHash('sha256')
    .update(JSON.stringify({ actorUid: actor.uid, targetUid, type, amount, reason }))
    .digest('hex');
  const entryKey = idempotencyKey
    ? 'admin-adjust-' +
      createHash('sha256')
        .update(JSON.stringify([actor.uid, targetUid, idempotencyKey]))
        .digest('hex')
    : undefined;
  const receipt = entryKey
    ? db.collection('pointsLedger').doc(targetUid).collection('entries').doc(entryKey)
    : null;
  const checkReceipt = (data: FirebaseFirestore.DocumentData | undefined) => {
    if (!data || data.requestFingerprint !== requestFingerprint)
      throw new HttpsError(
        'failed-precondition',
        'This adjustment key was already used for a different request.',
      );
    return {
      targetUid,
      entryId: entryKey!,
      amount: data.amount as number,
      balanceAfter: data.balanceAfter as number,
      alreadyApplied: true,
    };
  };
  if (receipt) {
    const existing = await receipt.get();
    if (existing.exists) return checkReceipt(existing.data());
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
      idempotencyKey: entryKey,
      requestFingerprint: entryKey ? requestFingerprint : undefined,
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

  if (result.alreadyApplied && receipt) return checkReceipt((await receipt.get()).data());
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
    const [originalSnap, reversalSnap, ledgerSnap, targetSnap] = await Promise.all([
      tx.get(originalRef),
      tx.get(reversalRef),
      tx.get(ledgerRef),
      tx.get(db.collection('users').doc(targetUid)),
    ]);

    if (!targetSnap.exists) {
      throw new HttpsError('not-found', 'Target user not found.');
    }
    // Reauthorize even a replay before returning any existing compensation.
    if (
      isOwnerRole(toUserAccessState(targetSnap.data()).role) &&
      targetUid !== actor.uid &&
      !isOwnerRole(actor.state.role)
    ) {
      throw new HttpsError(
        'permission-denied',
        'Only an owner may reverse points for another owner.',
      );
    }

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
