import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import {
  effectiveSubscriptionTierFromStoredRecord,
  isPaidSubscriptionTier,
} from '../subscription/subscription-core';
import {
  creditPointsResolved,
  type AtomicReadGuard,
  type PointsMutationParams,
  type PointsMutationResult,
} from '../points/ledger';
import {
  CROWN_DAILY_ALLOWANCES,
  crownAllowance,
  crownAllowanceWindow,
  type CrownAllowance,
} from './daily-allowance-core';

export { type CrownAllowance } from './daily-allowance-core';

export class CrownAllowanceReached extends Error {
  constructor(readonly allowance: CrownAllowance) {
    super('crown_points_limit_reached');
  }
}

/** Both claim paths share this transaction. The legacy economy fold remains the
 * ONLY writer charging crowns to pointsDailyTotals; it never writes this counter.
 * Spending, admin reversals and other KP sources cannot restore/consume allowance.
 */
export async function creditCrownPoints(
  params: PointsMutationParams,
  extraWrites: (
    tx: FirebaseFirestore.Transaction,
    result: PointsMutationResult & { allowance: CrownAllowance },
  ) => void,
  readGuard: AtomicReadGuard,
  now: Date,
): Promise<PointsMutationResult & { allowance?: CrownAllowance }> {
  const uid = params.targetUid;
  const window = crownAllowanceWindow(now);
  const ref = db.collection(CROWN_DAILY_ALLOWANCES).doc(uid).collection('days').doc(window.day);
  let allowance: CrownAllowance | undefined;
  const result = await creditPointsResolved(
    params,
    async (tx) => {
      const [counter, user, subscription] = await Promise.all([
        tx.get(ref),
        tx.get(db.collection('users').doc(uid)),
        tx.get(db.collection('subscriptions').doc(uid)),
      ]);
      if (!user.exists || isRestricted(toUserAccessState(user.data()))) {
        throw new HttpsError('permission-denied', 'Account cannot collect crowns.');
      }
      let earned = counter.data()?.earned as number | undefined;
      if (!counter.exists) {
        // Lazy migration includes crowns collected earlier on rollout day. A
        // single-field timestamp query needs no new composite index. The ledger
        // balance read by creditPointsResolved serializes this with other awards.
        const entries = await tx.get(
          db
            .collection('pointsLedger')
            .doc(uid)
            .collection('entries')
            .where('createdAt', '>=', Timestamp.fromDate(window.startsAt))
            .where('createdAt', '<', Timestamp.fromDate(window.resetsAt)),
        );
        earned = entries.docs.reduce((sum, entry) => {
          const data = entry.data();
          return data.source === 'crown_hunt' &&
            data.transactionType === 'earn' &&
            (data.crownAllowanceDay == null || data.crownAllowanceDay === window.day)
            ? sum + data.amount
            : sum;
        }, 0);
      }
      // Corrupt counters or migration totals must never award points or become
      // an unclassified INTERNAL error. Leave the stored state untouched.
      if (typeof earned !== 'number' || !Number.isSafeInteger(earned) || earned < 0) {
        throw new HttpsError('failed-precondition', 'Crown allowance is unavailable.');
      }
      const paid = isPaidSubscriptionTier(
        effectiveSubscriptionTierFromStoredRecord(subscription.data(), uid),
      );
      allowance = crownAllowance(paid, earned, now);
      if (allowance.remaining === 0) throw new CrownAllowanceReached(allowance);
      const amount = Math.min(params.amount, allowance.remaining);
      allowance = crownAllowance(paid, allowance.earned + amount, now);
      return { amount };
    },
    (tx, mutation) => {
      tx.set(ref, { earned: allowance!.earned, updatedAt: Timestamp.fromDate(now) });
      // Pin the request's server day even if commit crosses midnight. A later
      // lazy migration must not charge that award to a second local day.
      tx.set(
        db.collection('pointsLedger').doc(uid).collection('entries').doc(mutation.entryId),
        { crownAllowanceDay: window.day },
        { merge: true },
      );
      extraWrites(tx, { ...mutation, allowance: allowance! });
    },
    readGuard,
  );
  // An idempotent ledger replay returns the original award without touching
  // counters. Omit allowance on that rare replay rather than fabricate state.
  return { ...result, ...(!result.alreadyApplied && allowance ? { allowance } : {}) };
}
