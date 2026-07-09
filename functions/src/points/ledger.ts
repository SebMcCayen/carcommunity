/**
 * Points ledger primitives (Phase 9g) — the internal Admin SDK writers every
 * points mutation goes through. NOT exposed as generic endpoints (legacy
 * parity): domains call creditPoints/debitPoints directly (Kronjakt claims,
 * future event/badge awards), and the two admin callables wrap them.
 *
 * Every mutation follows the mapping's transaction pattern:
 *   1. read pointsLedger/{uid}.balance (or initialize to 0)
 *   2. append pointsLedger/{uid}/entries/{entryId}
 *   3. update the denormalized balance
 * in ONE Firestore transaction, so concurrent credits/debits serialize —
 * the Firestore equivalent of the legacy PostgreSQL advisory lock.
 *
 * Idempotency: when an idempotencyKey is provided it becomes the entry
 * document ID, so a replayed automated award is a transactional no-op that
 * returns the original entry.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import {
  applyDelta,
  buildLedgerEntry,
  isFirestoreSafeId,
  toStoredBalance,
  type PointsTransactionSource,
  type PointsTransactionType,
} from './points-core';

export interface PointsMutationParams {
  targetUid: string;
  /** Positive integer; the sign comes from credit vs debit. */
  amount: number;
  transactionType: PointsTransactionType;
  source: PointsTransactionSource;
  description: string;
  idempotencyKey?: string | null;
  relatedEntityType?: string | null;
  relatedEntityId?: string | null;
  createdByUserId?: string | null;
}

export interface PointsMutationResult {
  entryId: string;
  amount: number;
  balanceAfter: number;
  /** True when an idempotent replay returned the existing entry. */
  alreadyApplied: boolean;
}

/**
 * Extra writes committed ATOMICALLY with the mutation (e.g. the admin audit
 * record) — runs inside the transaction, only when a new entry is written
 * (idempotent replays add nothing).
 *
 * WRITE-ONLY: this runs AFTER the mutation's ledger/entry writes, so a
 * `tx.get` here would violate Firestore's read-before-write rule and fail at
 * runtime. Any transactional read the caller needs (cap checks, uniqueness
 * lookups) must go in an {@link AtomicReadGuard}; `tx.create`/`tx.set`
 * against documents whose IDs are already known is fine here.
 */
export type AtomicExtraWrites = (
  tx: FirebaseFirestore.Transaction,
  result: PointsMutationResult,
) => void;

/**
 * A read-phase guard run INSIDE the mutation transaction, after the balance
 * read and BEFORE any writes (Firestore requires all reads before all
 * writes). This is the ONLY place a caller may add transactional reads; it
 * may THROW to abort the whole mutation with no points credited. Callers use
 * it to enforce a cap or uniqueness invariant atomically with the award —
 * e.g. a Kronjakt claim reading its deterministic per-window guard and
 * daily-counter documents so concurrent claims cannot double-award. Values it
 * reads can be closed over and used by the paired {@link AtomicExtraWrites}.
 * Not invoked on an idempotent replay.
 */
export type AtomicReadGuard = (tx: FirebaseFirestore.Transaction) => Promise<void>;

async function assertTargetCanTransact(targetUid: string): Promise<void> {
  const snap = await db.collection('users').doc(targetUid).get();
  if (!snap.exists) {
    throw new HttpsError('not-found', 'Target user not found.');
  }
  if (isRestricted(toUserAccessState(snap.data()))) {
    // Suspended/deleted users must not earn or spend new points; existing
    // balances stay untouched (legacy parity).
    throw new HttpsError(
      'failed-precondition',
      'Suspended or deleted users cannot earn or spend points.',
    );
  }
}

async function mutatePoints(
  params: PointsMutationParams,
  signedAmount: number,
  extraWrites?: AtomicExtraWrites,
  readGuard?: AtomicReadGuard,
): Promise<PointsMutationResult> {
  if (!Number.isInteger(params.amount) || params.amount <= 0) {
    throw new HttpsError('invalid-argument', 'Amount must be a positive integer.');
  }
  // The idempotencyKey becomes a document ID — reject anything that is not
  // Firestore-safe before building a path from it.
  if (params.idempotencyKey && !isFirestoreSafeId(params.idempotencyKey)) {
    throw new HttpsError('invalid-argument', 'idempotencyKey is not a valid document ID.');
  }
  await assertTargetCanTransact(params.targetUid);

  const ledgerRef = db.collection('pointsLedger').doc(params.targetUid);
  const entryRef = params.idempotencyKey
    ? ledgerRef.collection('entries').doc(params.idempotencyKey)
    : ledgerRef.collection('entries').doc();

  return db.runTransaction(async (tx) => {
    if (params.idempotencyKey) {
      const existing = await tx.get(entryRef);
      if (existing.exists) {
        const data = existing.data()!;
        return {
          entryId: entryRef.id,
          amount: data.amount as number,
          balanceAfter: data.balanceAfter as number,
          alreadyApplied: true,
        };
      }
    }

    const ledgerSnap = await tx.get(ledgerRef);
    const currentBalance = toStoredBalance(ledgerSnap.data()?.balance);
    const check = applyDelta(currentBalance, signedAmount);
    if (!check.ok) {
      throw new HttpsError('failed-precondition', check.message);
    }

    // Read-phase guard: any additional reads (and abort-throws) must happen
    // before the first write below, per Firestore's read-before-write rule.
    if (readGuard) {
      await readGuard(tx);
    }

    const serverTimestamp = () => FieldValue.serverTimestamp();
    tx.set(
      entryRef,
      buildLedgerEntry(
        {
          transactionType: params.transactionType,
          source: params.source,
          amount: signedAmount,
          balanceAfter: check.balanceAfter,
          description: params.description,
          idempotencyKey: params.idempotencyKey ?? null,
          relatedEntityType: params.relatedEntityType ?? null,
          relatedEntityId: params.relatedEntityId ?? null,
          createdByUserId: params.createdByUserId ?? null,
        },
        serverTimestamp,
      ),
    );
    tx.set(
      ledgerRef,
      { balance: check.balanceAfter, updatedAt: serverTimestamp() },
      { merge: true },
    );

    const result: PointsMutationResult = {
      entryId: entryRef.id,
      amount: signedAmount,
      balanceAfter: check.balanceAfter,
      alreadyApplied: false,
    };
    extraWrites?.(tx, result);
    return result;
  });
}

/** Awards points (positive entry). Internal — never client-invoked directly. */
export function creditPoints(
  params: PointsMutationParams,
  extraWrites?: AtomicExtraWrites,
  readGuard?: AtomicReadGuard,
): Promise<PointsMutationResult> {
  return mutatePoints(params, params.amount, extraWrites, readGuard);
}

/** Spends points (negative entry); never allows overdraft. Internal. */
export function debitPoints(
  params: PointsMutationParams,
  extraWrites?: AtomicExtraWrites,
): Promise<PointsMutationResult> {
  return mutatePoints(params, -params.amount, extraWrites);
}
