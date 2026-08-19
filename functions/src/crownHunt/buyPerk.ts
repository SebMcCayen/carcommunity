/**
 * crownHunt.buyPerk — callable (contracts/functions/functions.json).
 *
 * The FIRST member-facing Kronpoäng SINK: a member spends KP to buy a perk,
 * which lands in their backend-only `perkInventory`. Deploys/activation of the
 * perk (traps, shields, the boost multiplier) come in later PRs — this one only
 * sells. Everything is gated on the contract-default-OFF `crownHuntPerks` flag,
 * so the shop is dark until an operator turns it on.
 *
 * The whole purchase is ONE transaction via the Phase 9g ledger primitives:
 * `debitPoints` spends `cost*qty` KP (source `perk_shop`) and its
 * AtomicExtraWrites hook increments `perkInventory/{uid}.{perkId}` by `qty` in
 * the SAME transaction — the debit and the inventory grant commit together or
 * not at all. `debitPoints` never overdrafts (a purchase past the balance
 * throws `failed-precondition` and writes nothing), and the ledger idempotency
 * key (derived from the scoped client key) makes a retried or double-tapped buy
 * a transactional no-op that debits once and grants once.
 *
 * Deployed via the `crownHunt` export group as `crownHunt-buyPerk`.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { toUserAccessState } from '../shared/access';
import { memberGateAllows } from '../shared/memberGating';
import { debitPoints } from '../points/ledger';
import { DEBIT_OVERDRAFT_MESSAGE } from '../points/points-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import {
  CROWN_HUNT_PERKS_FLAG_KEY,
  PERK_PURCHASE_REASON_INSUFFICIENT_FUNDS,
  PERK_PURCHASE_REASON_SHOP_UNAVAILABLE,
  parseBuyPerkInput,
  perkById,
  perkCost,
  perkPurchaseLedgerKey,
  scopePerkPurchaseKey,
  type PerkId,
} from './perks-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface BuyPerkResponse {
  perkId: PerkId;
  qty: number;
  /** Positive integer KP spent (cost * qty). */
  costKp: number;
  /** KP balance after the debit. */
  newBalance: number;
  /** The buyer's count of this perk AFTER the grant. */
  inventoryCount: number;
  /** True when an idempotent replay returned the original purchase. */
  alreadyPurchased: boolean;
}

export const buyPerk = onCall(CALLABLE_OPTS, async (request): Promise<BuyPerkResponse> => {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError('unauthenticated', 'Sign in to buy a perk.');
  }
  const uid = auth.uid;

  // Malformed input (missing perkId, bad qty, unsafe idempotency key) is an
  // ERROR, not a result code.
  const parsed = parseBuyPerkInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { perkId, qty, idempotencyKey } = parsed.input;

  // 1. Feature flag. The shop is off by default; a member must never be able to
  // spend KP against a system that is officially disabled.
  if (!(await readFeatureFlag(CROWN_HUNT_PERKS_FLAG_KEY))) {
    // Reason-coded rejection log (no PII: perk catalog id + qty only, never the
    // uid) so an operator can see the shop being hit while it is officially off
    // — mirrors the reason-per-rejection logging in claimSpawn/claimLagDetector.
    logger.info('crownHunt.buyPerk rejected', {
      reason: PERK_PURCHASE_REASON_SHOP_UNAVAILABLE,
      cause: 'flag_off',
      perkId,
      qty,
    });
    throw new HttpsError('failed-precondition', 'Kronjaktsbutiken är inte tillgänglig just nu.', {
      reason: PERK_PURCHASE_REASON_SHOP_UNAVAILABLE,
    });
  }

  // 2. Account status. Suspended/deleted users cannot spend (debitPoints also
  // enforces this, but reject early with a member-facing message).
  const userSnap = await db.collection('users').doc(uid).get();
  if (!memberGateAllows(toUserAccessState(userSnap.data()))) {
    logger.info('crownHunt.buyPerk rejected', {
      reason: PERK_PURCHASE_REASON_SHOP_UNAVAILABLE,
      cause: 'account_blocked',
      perkId,
      qty,
    });
    throw new HttpsError('failed-precondition', 'Ditt konto kan inte köpa perks just nu.', {
      reason: PERK_PURCHASE_REASON_SHOP_UNAVAILABLE,
    });
  }

  // 3. Resolve the perk + cost from SERVER constants only — the client never
  // supplies a price. An unknown perk is a semantic rejection.
  const perk = perkById(perkId);
  const cost = perkCost(perkId, qty);
  if (!perk || cost === null) {
    logger.info('crownHunt.buyPerk rejected', {
      reason: PERK_PURCHASE_REASON_SHOP_UNAVAILABLE,
      cause: 'unknown_perk',
      perkId,
      qty,
    });
    throw new HttpsError('failed-precondition', 'Okänd perk.', {
      reason: PERK_PURCHASE_REASON_SHOP_UNAVAILABLE,
    });
  }

  const scopedKey = scopePerkPurchaseKey(uid, idempotencyKey);
  const inventoryRef = db.collection('perkInventory').doc(uid);

  // 4. Debit + grant, atomically. The AtomicExtraWrites hook runs INSIDE the
  // ledger transaction, only when a NEW debit entry is written — an idempotent
  // replay adds nothing, so the inventory is never double-incremented.
  let result: Awaited<ReturnType<typeof debitPoints>>;
  try {
    result = await debitPoints(
      {
        targetUid: uid,
        amount: cost,
        transactionType: 'spend',
        source: 'perk_shop',
        description: `Kronjaktsbutik: ${perk.name} x${qty}`,
        idempotencyKey: perkPurchaseLedgerKey(scopedKey),
        relatedEntityType: 'perk',
        relatedEntityId: perk.perkId,
      },
      (tx) => {
        tx.set(
          inventoryRef,
          {
            [perk.perkId]: FieldValue.increment(qty),
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
      },
    );
  } catch (err) {
    // The ledger's overdraft guard rejects an unaffordable buy with a
    // `failed-precondition` carrying the shared DEBIT_OVERDRAFT_MESSAGE. Keep
    // the FAILED_PRECONDITION code but re-throw with a member-facing message and
    // a structured `reason` so the client tells "insufficient funds" apart from
    // a shop-unavailable rejection WITHOUT substring-matching the wire message.
    if (
      err instanceof HttpsError &&
      err.code === 'failed-precondition' &&
      err.message === DEBIT_OVERDRAFT_MESSAGE
    ) {
      logger.info('crownHunt.buyPerk rejected', {
        reason: PERK_PURCHASE_REASON_INSUFFICIENT_FUNDS,
        perkId,
        qty,
        costKp: cost,
      });
      throw new HttpsError(
        'failed-precondition',
        'Du har inte tillräckligt med Kronpoäng för den här perken.',
        { reason: PERK_PURCHASE_REASON_INSUFFICIENT_FUNDS },
      );
    }
    // Any OTHER debit failure is an unexpected transaction/infrastructure error
    // (not an ordinary overdraft) — surface it before it propagates so a failed
    // purchase is diagnosable. No PII: perk catalog id + qty only.
    logger.error('crownHunt.buyPerk purchase transaction failed', {
      perkId,
      qty,
      error: String(err),
    });
    throw err;
  }

  // Read back the buyer's count AFTER the transaction so the response reflects
  // the granted total (the inventory doc is backend-only; the client learns its
  // count from the callable result, not a direct read on the write path).
  const inventorySnap = await inventoryRef.get();
  const inventoryCount = (inventorySnap.data()?.[perk.perkId] as number | undefined) ?? qty;

  return {
    perkId: perk.perkId,
    qty,
    costKp: cost,
    newBalance: result.balanceAfter,
    inventoryCount,
    alreadyPurchased: result.alreadyApplied,
  };
});
