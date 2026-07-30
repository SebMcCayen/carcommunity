/**
 * Shared auto-issue filing flow — claim, budget, create, reconcile.
 *
 * The client-error and server-error triggers do the identical five-step dance
 * around the shared link-doc state machine (shared/issueLinks-core.ts) and the
 * shared hourly budget (shared/issueBudget.ts). That dance is subtle enough —
 * concurrency-safe claim, fail-closed budget, rollback that does not lose
 * occurrences — that having two copies of it would be a bug factory, so it lives
 * here once:
 *
 *  1. CLAIM the fingerprint in a transaction. Exactly one concurrent occurrence
 *     gets `create`; everyone else gets `increment` and is done.
 *  2. CHARGE the global hourly issue budget. Over cap → skip the GitHub call.
 *  3. READ the claimed link back, so the issue body's first-seen/occurrences
 *     reflect the actual document rather than an optimistic guess.
 *  4. CREATE the issue (createGitHubIssue never throws; null means failure).
 *  5. RECONCILE the link: `created` with the issue ref, or rolled back to a
 *     retriable state so the failure is not permanently silenced.
 *
 * The caller keeps ownership of everything domain-specific: which collection the
 * link lives in, what the placeholder carries, and how the issue body is built.
 */

import { logger } from 'firebase-functions';
import { FieldValue, Timestamp, type DocumentReference } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { createGitHubIssue, type GitHubIssuePayload, type GitHubIssueResult } from './githubIssues';
import { consumeGitHubIssueBudget } from './issueBudget';
import {
  buildIssueLinkCreated,
  buildIssueLinkFailed,
  buildIssueLinkIncrement,
  buildIssueLinkRetry,
  decideIssueAction,
  type IssueLinkState,
} from './issueLinks-core';

export interface AutoIssueFilingSpec {
  /** Log label for the calling pipeline, e.g. `errors.onServerErrorReport`. */
  pipeline: string;
  /** `{collection}/{fingerprint}` link document. */
  linkRef: DocumentReference;
  /** Placeholder document written when this occurrence wins the claim. */
  buildNewLink: (serverTimestamp: () => unknown) => Record<string, unknown>;
  /** Issue payload, given the tally read back from the claimed link. */
  buildPayload: (meta: { firstSeenIso: string; count: number }) => GitHubIssuePayload;
  /** GITHUB_ISSUE_TOKEN value; never logged. */
  token: string;
  /** User-Agent identifying the caller to the GitHub API. */
  userAgent: string;
  /** Scalar context merged into logs — never PII, never the token. */
  logContext: Record<string, string | number>;
  /** Injected clock (the budget bucket is derived from it). */
  now?: Date;
}

export type AutoIssueFilingOutcome =
  /** Deduped: an issue already exists or is in flight; the tally was bumped. */
  | { status: 'deduped'; issue: GitHubIssueResult | null }
  /** This occurrence filed the issue. */
  | { status: 'created'; issue: GitHubIssueResult }
  /** The hourly global budget is exhausted; retriable next bucket. */
  | { status: 'skipped'; reason: 'budget' }
  /** The claim transaction or the GitHub create failed; retriable. */
  | { status: 'failed'; reason: 'claim' | 'github' };

/**
 * Runs the dedup + budget + create flow for one occurrence. Never throws; every
 * failure resolves to a `failed`/`skipped` outcome the caller can record.
 */
export async function fileAutoIssue(spec: AutoIssueFilingSpec): Promise<AutoIssueFilingOutcome> {
  const { linkRef, logContext, pipeline } = spec;

  // 1. Atomically claim the fingerprint.
  let decision: 'create' | 'increment';
  try {
    decision = await db.runTransaction(async (tx) => {
      const existing = await tx.get(linkRef);
      const link = existing.exists ? (existing.data() as IssueLinkState) : null;
      const action = decideIssueAction(link);
      if (action === 'increment') {
        tx.update(
          linkRef,
          buildIssueLinkIncrement(FieldValue.increment(1), () => FieldValue.serverTimestamp()),
        );
      } else if (link) {
        tx.update(
          linkRef,
          buildIssueLinkRetry(FieldValue.increment(1), () => FieldValue.serverTimestamp()),
        );
      } else {
        tx.set(
          linkRef,
          spec.buildNewLink(() => FieldValue.serverTimestamp()),
        );
      }
      return action;
    });
  } catch (error) {
    logger.error(`${pipeline}: link transaction failed`, { ...logContext, error: String(error) });
    return { status: 'failed', reason: 'claim' };
  }

  if (decision === 'increment') {
    const link = (await linkRef.get().catch(() => undefined))?.data();
    const number = typeof link?.issueNumber === 'number' ? link.issueNumber : null;
    const url = typeof link?.issueUrl === 'string' ? link.issueUrl : null;
    return {
      status: 'deduped',
      issue: number !== null && url !== null ? { number, url } : null,
    };
  }

  // 2. Charge the GLOBAL hourly budget. Fails closed.
  const allowed = await consumeGitHubIssueBudget(pipeline, spec.now ?? new Date());
  if (!allowed) {
    logger.warn(`${pipeline}: hourly GitHub issue budget exhausted, skipping issue`, logContext);
    // Leave the claim retriable WITHOUT losing the occurrence count, so the next
    // occurrence in a fresh bucket files the issue.
    await markLinkRetriable(linkRef, pipeline, logContext);
    return { status: 'skipped', reason: 'budget' };
  }

  // 3. Read the claimed link back for an accurate first-seen/occurrence count.
  const claimed = (await linkRef.get().catch(() => undefined))?.data();
  const count = typeof claimed?.count === 'number' ? claimed.count : 1;
  const firstSeenAt = claimed?.firstSeenAt;
  const firstSeenIso =
    firstSeenAt instanceof Timestamp
      ? firstSeenAt.toDate().toISOString()
      : new Date().toISOString();

  // 4. File the single public issue.
  const issue = await createGitHubIssue(
    spec.buildPayload({ firstSeenIso, count }),
    spec.token,
    spec.userAgent,
    logContext,
  );

  // 5. Reconcile.
  if (issue) {
    await linkRef.update(buildIssueLinkCreated(issue)).catch((error) => {
      logger.error(`${pipeline}: failed to record issue link`, {
        ...logContext,
        issueNumber: issue.number,
        error: String(error),
      });
    });
    return { status: 'created', issue };
  }

  // GitHub failed (already logged, no throw). Concurrency-safe rollback: a
  // pristine placeholder (count 1) is deleted so a future occurrence retries; a
  // placeholder a concurrent occurrence already bumped (count > 1) is marked
  // `failed` (retriable) so no occurrence is lost.
  try {
    await db.runTransaction(async (tx) => {
      const current = await tx.get(linkRef);
      if (!current.exists) return;
      const link = current.data() as IssueLinkState;
      if (link.status !== 'creating') return;
      if (link.count === 1) {
        tx.delete(linkRef);
      } else {
        tx.update(linkRef, buildIssueLinkFailed());
      }
    });
  } catch (error) {
    logger.error(`${pipeline}: failed to roll back placeholder link`, {
      ...logContext,
      error: String(error),
    });
  }
  return { status: 'failed', reason: 'github' };
}

/**
 * Flips an in-flight claim back to the retriable `failed` state, preserving
 * count/firstSeenAt. Used when the create was never attempted (budget skip), so
 * the occurrence history is worth keeping.
 */
async function markLinkRetriable(
  linkRef: DocumentReference,
  pipeline: string,
  logContext: Record<string, string | number>,
): Promise<void> {
  try {
    await db.runTransaction(async (tx) => {
      const current = await tx.get(linkRef);
      if (!current.exists) return;
      const link = current.data() as IssueLinkState;
      if (link.status !== 'creating') return;
      tx.update(linkRef, buildIssueLinkFailed());
    });
  } catch (error) {
    logger.error(`${pipeline}: failed to mark link retriable`, {
      ...logContext,
      error: String(error),
    });
  }
}
