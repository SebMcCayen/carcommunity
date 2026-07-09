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
 * - Resilience: a GitHub failure NEVER throws the trigger into a crash-loop —
 *   the placeholder link is rolled back so the next occurrence retries, and the
 *   diagnostics doc is left intact. Dedup by fingerprint bounds this to one
 *   create attempt per occurrence, so a failure storm can't hammer GitHub.
 */

import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { defineSecret } from 'firebase-functions/params';
import { logger } from 'firebase-functions';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { createGitHubIssue } from '../shared/githubIssues';
import {
  SIGN_IN_ISSUE_LINKS_COLLECTION,
  buildNewSignInIssueLink,
  buildSignInIssueLinkCreated,
  buildSignInIssueLinkIncrement,
  buildSignInIssuePayload,
  decideSignInIssueAction,
  extractSignInFailureReport,
  type SignInIssueLink,
} from './signInIssues-core';

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
        const decision = decideSignInIssueAction(
          existing.exists ? (existing.data() as SignInIssueLink) : null,
        );
        if (decision === 'increment') {
          tx.update(
            linkRef,
            buildSignInIssueLinkIncrement(FieldValue.increment(1), () =>
              FieldValue.serverTimestamp(),
            ),
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

    // We claimed the fingerprint → file the single public issue for it.
    const firstSeenIso = new Date().toISOString();
    const issue = await createGitHubIssue(
      buildSignInIssuePayload(report, { firstSeenIso, count: 1 }),
      GITHUB_ISSUE_TOKEN.value(),
      'carcommunity-signin-bot',
      { fingerprint: report.fingerprint },
    );

    if (issue) {
      try {
        await linkRef.update(buildSignInIssueLinkCreated(issue));
      } catch (error) {
        // The issue exists; only the link write failed. Log and move on — the
        // link stays `creating` (dedup still holds: future occurrences see the
        // doc and increment rather than re-filing).
        logger.error('diagnostics.onSignInFailure: failed to record issue link', {
          fingerprint: report.fingerprint,
          issueNumber: issue.number,
          error: String(error),
        });
      }
      return;
    }

    // GitHub failed (already logged in the helper, no throw): roll back the
    // placeholder so the NEXT occurrence retries. The diagnostics doc is left
    // intact; dedup still bounds retries to one create attempt per occurrence.
    try {
      await linkRef.delete();
    } catch (error) {
      logger.error('diagnostics.onSignInFailure: failed to roll back placeholder link', {
        fingerprint: report.fingerprint,
        error: String(error),
      });
    }
  },
);
