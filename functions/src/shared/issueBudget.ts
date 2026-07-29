/**
 * GLOBAL auto-issue creation budget — the transactional consumer.
 *
 * Companion to the pure shared/issueBudget-core.ts (which owns the cap, the
 * bucket-id derivation and the rationale). This module holds the single Firestore
 * side effect: atomically charging one issue against the current UTC-hour bucket.
 *
 * Contract (relied on by every caller):
 *  - the counter is incremented INSIDE the same transaction that reads it, so N
 *    concurrent function instances cannot all see `used = cap - 1` and each file
 *    an issue;
 *  - when the bucket is exhausted the counter is NOT incremented — a blocked
 *    attempt must not push the bucket further over the cap, or a hot error loop
 *    would inflate the counter without bound;
 *  - it NEVER throws, and it FAILS CLOSED: if the transaction itself errors, the
 *    answer is "not allowed". Skipping a public issue is recoverable (the private
 *    report is already durable and a later occurrence retries); publishing to a
 *    world-readable repo without a working limiter is not.
 */

import { logger } from 'firebase-functions';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  GITHUB_ISSUE_BUDGET_COLLECTION,
  GITHUB_ISSUE_BUDGET_PER_HOUR,
  isIssueBudgetExhausted,
  issueBudgetBucketId,
} from './issueBudget-core';

/**
 * Charges one auto-filed issue against the hourly budget.
 *
 * @param source  server-controlled label of the calling pipeline, for logs only.
 * @param now     injected clock (tests pin the bucket).
 * @returns true when the caller may create the GitHub issue; false when the
 *          budget is exhausted or the check could not be completed.
 */
export async function consumeGitHubIssueBudget(
  source: string,
  now: Date = new Date(),
): Promise<boolean> {
  const bucketId = issueBudgetBucketId(now);
  const bucketRef = db.collection(GITHUB_ISSUE_BUDGET_COLLECTION).doc(bucketId);

  try {
    return await db.runTransaction(async (tx) => {
      const snapshot = await tx.get(bucketRef);
      const used =
        snapshot.exists && typeof snapshot.data()?.count === 'number'
          ? (snapshot.data()?.count as number)
          : 0;

      if (isIssueBudgetExhausted(used)) {
        return false;
      }

      if (snapshot.exists) {
        tx.update(bucketRef, {
          count: FieldValue.increment(1),
          lastChargedAt: FieldValue.serverTimestamp(),
        });
      } else {
        tx.set(bucketRef, {
          bucketId,
          count: 1,
          cap: GITHUB_ISSUE_BUDGET_PER_HOUR,
          firstChargedAt: FieldValue.serverTimestamp(),
          lastChargedAt: FieldValue.serverTimestamp(),
        });
      }
      return true;
    });
  } catch (error) {
    // Fail closed: never publish to the public repo with a broken limiter.
    logger.error('consumeGitHubIssueBudget: budget transaction failed', {
      source,
      bucketId,
      error: String(error),
    });
    return false;
  }
}
