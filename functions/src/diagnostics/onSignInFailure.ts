/**
 * diagnostics-onSignInFailure — Firestore create trigger on
 * `diagnosticsReports/{reportId}`.
 *
 * Observability for pre-authentication Google Sign-In failures during testing.
 * Sign-in fails BEFORE auth, so the Android app can only report it through the
 * PUBLIC, unauthenticated `diagnostics.submitReport` callable. Creating the
 * public GitHub issue must therefore happen SERVER-SIDE (an unauthenticated
 * client filing public issues would be a spam vector) — this trigger does it.
 *
 * For `sign_in`-area reports ONLY (every other diagnostics doc is a cheap
 * no-op), it files ONE deduplicated public GitHub issue per unique fingerprint:
 *
 * - Dedup: a server-only `signInIssueLinks/{fingerprint}` doc holds the issue
 *   number/url + occurrence tally. A transaction atomically claims the
 *   fingerprint — the first trigger writes a `creating` placeholder and files
 *   the issue; every concurrent/repeat trigger only increments `count` and
 *   touches `lastSeenAt` (no duplicate issue, no per-occurrence comment).
 * - Issue creation goes through the shared GitHub helper (Node global `fetch`,
 *   no octokit), labelled `sign-in-failure` + `auto-generated`, using the
 *   GITHUB_ISSUE_TOKEN Secret Manager secret bound below.
 * - Public-safe body: only the sanitized error code/type, reason, app/build/OS
 *   version, device model, fingerprint, first-seen timestamp and occurrence
 *   count. No uid (unauthenticated → none), no PII (see signInIssues-core).
 * - Resilience: a GitHub failure NEVER throws the trigger into a crash-loop.
 *   Cleanup is concurrency-safe (a transaction re-reads the link): a pristine
 *   placeholder (`creating`, count 1) is deleted so the next occurrence retries;
 *   a placeholder a concurrent occurrence already incremented (count > 1) is
 *   kept and marked `failed` so no occurrence is lost and a future occurrence
 *   re-attempts creation. The diagnostics doc is left intact. Dedup by
 *   fingerprint bounds this to one create attempt per occurrence, so a failure
 *   storm can't hammer GitHub.
 * - Stuck-claim repair: if the issue is filed but the follow-up `created` write
 *   fails, the link is stranded in `creating` (issue URL lost). A `creating`
 *   claim older than SIGN_IN_ISSUE_STALE_CREATING_MS is treated as STALE, and the
 *   next occurrence re-attempts creation rather than incrementing forever. The
 *   retry re-claims with a fresh `creating` timestamp so only one occurrence
 *   re-files; the accepted, rare cost is a duplicate public issue in that narrow
 *   window (documented at the `created`-write failure below).
 */

import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { defineSecret } from 'firebase-functions/params';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { createGitHubIssue } from '../shared/githubIssues';
import {
  SIGN_IN_ISSUE_LINKS_COLLECTION,
  buildNewSignInIssueLink,
  buildSignInIssueLinkCreated,
  buildSignInIssueLinkFailed,
  buildSignInIssueLinkIncrement,
  buildSignInIssueLinkRetry,
  buildSignInIssuePayload,
  decideSignInIssueAction,
  extractSignInFailureReport,
  type SignInIssueLink,
} from './signInIssues-core';
import { MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';

/**
 * Fine-grained GitHub token with `issues: write` on SebMcCayen/carcommunity —
 * the SAME secret bound to feedback.reportIssue. Provided via
 * `firebase functions:secrets:set GITHUB_ISSUE_TOKEN`; never committed, logged,
 * or returned.
 */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

export const onSignInFailure = onDocumentCreated(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    document: 'diagnosticsReports/{reportId}',
    memory: '256MiB',
    timeoutSeconds: 30,
    secrets: [GITHUB_ISSUE_TOKEN],
  },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    // Ignore everything that is not a sign-in failure — a cheap no-op for the
    // overwhelming majority of diagnostics docs (crashes, other feature areas).
    const report = extractSignInFailureReport(snapshot.data());
    if (!report) return;

    const linkRef = db.collection(SIGN_IN_ISSUE_LINKS_COLLECTION).doc(report.fingerprint);

    // Atomically claim the fingerprint. Only the first trigger for a given
    // fingerprint gets 'create'; concurrent/repeat ones increment the tally.
    let action: 'create' | 'increment';
    try {
      action = await db.runTransaction(async (tx) => {
        const existing = await tx.get(linkRef);
        const link = existing.exists ? (existing.data() as SignInIssueLink) : null;
        // Last time this fingerprint was touched, used to detect a STALE
        // `creating` claim (a create that stranded in-flight). Only a real
        // Firestore Timestamp yields a value; otherwise the claim is treated as
        // fresh (never stale) so we never re-file on a missing/garbage timestamp.
        const lastActivityMs =
          link && link.lastSeenAt instanceof Timestamp ? link.lastSeenAt.toMillis() : null;
        const decision = decideSignInIssueAction(link, { nowMs: Date.now(), lastActivityMs });
        if (decision === 'increment') {
          tx.update(
            linkRef,
            buildSignInIssueLinkIncrement(FieldValue.increment(1), () =>
              FieldValue.serverTimestamp(),
            ),
          );
        } else if (link) {
          // Retry: re-claim an existing link for another create attempt. Covers
          // both a prior FAILED create AND a STALE `creating` claim (one that has
          // been in-flight past SIGN_IN_ISSUE_STALE_CREATING_MS — see the repair
          // note at the `created`-write failure below). Preserve the tally /
          // firstSeenAt and count this occurrence too. The patch flips status to
          // a FRESH `creating` (refreshing lastSeenAt), so concurrent occurrences
          // during the retry see a non-stale claim and increment instead of
          // double-filing — only THIS single occurrence re-files.
          tx.update(
            linkRef,
            buildSignInIssueLinkRetry(FieldValue.increment(1), () => FieldValue.serverTimestamp()),
          );
        } else {
          tx.set(
            linkRef,
            buildNewSignInIssueLink(report, () => FieldValue.serverTimestamp()),
          );
        }
        return decision;
      });
    } catch (error) {
      // A transaction failure must not crash-loop the trigger; the next
      // occurrence of this fingerprint will retry.
      logger.error('diagnostics.onSignInFailure: link transaction failed', {
        fingerprint: report.fingerprint,
        error: String(error),
      });
      return;
    }

    // Dedup: the issue already exists (or is being created by a concurrent
    // trigger) — the tally was bumped, nothing else to do.
    if (action === 'increment') return;

    // We claimed the fingerprint → file the single public issue for it. Read the
    // link back (a bounded single extra read, only on the create path) so the
    // issue body's "First seen"/"Occurrences" reflect the ACTUAL link doc: on a
    // retry of a `failed` link the preserved firstSeenAt/count carry over, and
    // any concurrent occurrence that incremented during the claim is included —
    // both of which a hardcoded `{ count: 1, now }` would misreport. The GitHub
    // call still happens OUTSIDE any transaction.
    const claimed = (await linkRef.get()).data();
    const count = typeof claimed?.count === 'number' ? claimed.count : 1;
    const firstSeenAt = claimed?.firstSeenAt;
    const firstSeenIso =
      firstSeenAt instanceof Timestamp
        ? firstSeenAt.toDate().toISOString()
        : new Date().toISOString();
    const issue = await createGitHubIssue(
      buildSignInIssuePayload(report, { firstSeenIso, count }),
      GITHUB_ISSUE_TOKEN.value(),
      'carcommunity-signin-bot',
      { fingerprint: report.fingerprint },
    );

    if (issue) {
      try {
        await linkRef.update(buildSignInIssueLinkCreated(issue));
      } catch (error) {
        // The issue exists; only the `created` link write failed, so we've lost
        // the issueNumber/issueUrl and the link is stranded in `status: creating`.
        // Dedup still holds in the near term (future occurrences see the doc and
        // increment). REPAIR: after SIGN_IN_ISSUE_STALE_CREATING_MS the link
        // counts as a STALE `creating` claim, and the next occurrence re-attempts
        // creation (decideSignInIssueAction → create) instead of incrementing
        // forever — otherwise admins would permanently lose the issue URL and the
        // dedup index would be stuck in-flight.
        // TRADEOFF: because we don't know the issueNumber GitHub already assigned,
        // that repair retry can file a DUPLICATE public issue in this rare
        // "issue created but link write failed" case. That is an accepted,
        // documented cost — a rare duplicate is strictly better than a
        // permanently-stuck dedup index with a lost issue URL. (We deliberately do
        // NOT search GitHub for the existing issue — keeping this simple.)
        logger.error('diagnostics.onSignInFailure: failed to record issue link', {
          fingerprint: report.fingerprint,
          issueNumber: issue.number,
          error: String(error),
        });
      }
      return;
    }

    // GitHub failed (already logged in the helper, no throw): clean up the
    // placeholder so a future occurrence retries. This MUST be concurrency-safe:
    // while we were awaiting GitHub, a concurrent occurrence of the same
    // fingerprint may have seen our `creating` placeholder and incremented
    // `count`/`lastSeenAt`. An unconditional delete would wipe that occurrence.
    // So re-read inside a transaction and decide atomically:
    //   - pristine placeholder (still `creating` AND count === 1, i.e. no
    //     concurrent increment) → delete it, matching the original rollback
    //     intent so the next occurrence creates a fresh issue;
    //   - a concurrent occurrence already bumped it (count > 1) → do NOT delete;
    //     mark the link `failed` (retriable) so the tally/lastSeenAt survive and
    //     a FUTURE occurrence re-attempts creation (decideSignInIssueAction →
    //     create). Marking `failed` (not leaving `creating`) is what lets the
    //     retry happen without looping: only the single occurrence that finds a
    //     `failed` link re-files; concurrent ones during that retry increment.
    // The GitHub network call already happened OUTSIDE this transaction.
    try {
      await db.runTransaction(async (tx) => {
        const current = await tx.get(linkRef);
        if (!current.exists) return;
        const link = current.data() as SignInIssueLink;
        // Only our own in-flight placeholder is eligible for cleanup. `created`
        // (a concurrent trigger somehow filed it) is left untouched.
        if (link.status !== 'creating') return;
        if (link.count === 1) {
          tx.delete(linkRef);
        } else {
          tx.update(linkRef, buildSignInIssueLinkFailed());
        }
      });
    } catch (error) {
      logger.error('diagnostics.onSignInFailure: failed to roll back placeholder link', {
        fingerprint: report.fingerprint,
        error: String(error),
      });
    }
  },
);
