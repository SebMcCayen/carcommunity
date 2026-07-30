/**
 * SERVER-error reporting emulator integration tests.
 *
 * Covers the half of the pipeline that unit tests cannot: real Firestore
 * transactions, the deployed `errors-onServerErrorReport` trigger, and the global
 * hourly issue budget.
 *
 *  - `withServerErrorReporting` reports AND rethrows (retry semantics unchanged),
 *    and skips a deliberate HttpsError;
 *  - `reportServerError` writes the private `serverErrorReports` record with the
 *    FULL message/stack/context, and the deployed trigger reconciles it;
 *  - the dedup claim is EXCLUSIVE: concurrent occurrences of a fingerprint whose
 *    claim is in flight (or already filed) only increment the tally, never open a
 *    second issue — and a failed create leaves the fingerprint retriable without
 *    losing occurrences (see the `per-fingerprint dedup` doc comment for why the
 *    invariant is stated that way and not as "one of two racers wins");
 *  - the GLOBAL hourly budget blocks creation past the cap and does NOT keep
 *    incrementing once exhausted.
 *
 * The GitHub REST call is never made here: GITHUB_ISSUE_TOKEN is unset in the
 * emulator, so createGitHubIssue logs and returns null. That is exactly the
 * "create failed" branch, so the rollback behaviour is covered for free; the
 * happy-path body content is covered by serverErrors-core.test.ts.
 *
 * CI + local. Requires the Firebase Emulator Suite (needs a JVM, JDK 21). Run via:
 *   pnpm --dir functions emulators:test
 * Excluded from the default `vitest run` unit suite by vitest.config.ts.
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { beforeAll, describe, expect, it } from 'vitest';
import { reportServerError, withServerErrorReporting } from '../errors/serverErrors';
import {
  SERVER_ERROR_ISSUE_LINKS_COLLECTION,
  SERVER_ERROR_REPORTS_COLLECTION,
  buildNewServerErrorIssueLink,
  buildServerErrorIssuePayload,
  buildServerErrorReport,
} from '../errors/serverErrors-core';
import { fileAutoIssue } from '../shared/autoIssueFiling';
import { consumeGitHubIssueBudget } from '../shared/issueBudget';
import {
  GITHUB_ISSUE_BUDGET_COLLECTION,
  GITHUB_ISSUE_BUDGET_PER_HOUR,
  issueBudgetBucketId,
} from '../shared/issueBudget-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'servererrors-emulator-tests');
const adminDb = getAdminFirestore(adminApp);

/**
 * The emulator suite shares ONE Firestore across test files, so every fingerprint
 * this file produces must be unique to this run — otherwise a link doc left by
 * another file (or a rerun) would already be claimed and the dedup assertions
 * would flip. Sources stay inside the allowlist pattern (letters/digits/dots).
 */
const RUN = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
const source = (name: string): string => `test.${name}${RUN}`;

/** A realistic server error whose text is full of things that must stay private. */
const PRIVATE_UID = 'emulTestUid0000000001';
function sensitiveError(): Error & { code?: string } {
  const error = new Error(
    `5 NOT_FOUND: no entity to update: document users/${PRIVATE_UID}/vehicles/v1 (57.4873,12.0759)`,
  ) as Error & { code?: string };
  error.name = 'FirebaseFirestoreError';
  error.code = 'not-found';
  return error;
}

async function pollUntil<T>(
  read: () => Promise<T | undefined>,
  timeoutMs = 30_000,
  intervalMs = 250,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

async function findReport(fingerprint: string): Promise<Record<string, unknown> | undefined> {
  const snap = await adminDb
    .collection(SERVER_ERROR_REPORTS_COLLECTION)
    .where('fingerprint', '==', fingerprint)
    .get();
  return snap.empty ? undefined : snap.docs[0]?.data();
}

beforeAll(async () => {
  // Fail fast with a useful message rather than a cryptic transport error.
  await adminDb.collection('__ping').doc('servererrors').set({ at: Date.now() });
});

// ---------------------------------------------------------------------------
// withServerErrorReporting
// ---------------------------------------------------------------------------

describe('withServerErrorReporting', () => {
  it('reports the failure AND rethrows it unchanged (Cloud Scheduler retry semantics)', async () => {
    const src = source('purge');
    const thrown = sensitiveError();
    const wrapped = withServerErrorReporting(src, async () => {
      throw thrown;
    });

    await expect(wrapped()).rejects.toBe(thrown);

    const expected = buildServerErrorReport(src, thrown);
    const report = await pollUntil(() => findReport(expected.fingerprint));

    // The PRIVATE record keeps the full detail the public issue must never carry.
    expect(report.source).toBe(src);
    expect(report.errorName).toBe('FirebaseFirestoreError');
    expect(report.errorCode).toBe('not-found');
    expect(String(report.message)).toContain(PRIVATE_UID);
    expect(String(report.stack ?? '')).toContain('FirebaseFirestoreError');
    expect(Array.isArray(report.frames)).toBe(true);
  });

  it('passes the handler result and arguments straight through on success', async () => {
    const wrapped = withServerErrorReporting(source('ok'), async (a: number, b: number) => a + b);
    await expect(wrapped(2, 3)).resolves.toBe(5);
  });

  it('does NOT report a deliberate HttpsError (client-facing outcome, not a bug)', async () => {
    const src = source('httpsSkip');
    const thrown = new HttpsError('not-found', 'nope');
    const wrapped = withServerErrorReporting(src, async () => {
      throw thrown;
    });

    await expect(wrapped()).rejects.toBe(thrown);

    // No report should ever appear. Give the (non-existent) write a chance to land.
    await new Promise((resolve) => setTimeout(resolve, 1500));
    const snap = await adminDb
      .collection(SERVER_ERROR_REPORTS_COLLECTION)
      .where('source', '==', src)
      .get();
    expect(snap.empty).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// reportServerError + the deployed errors-onServerErrorReport trigger
// ---------------------------------------------------------------------------

describe('reportServerError → errors-onServerErrorReport', () => {
  it('persists the private record and the deployed trigger reconciles it', async () => {
    const src = source('trigger');
    const fingerprint = await reportServerError({
      source: src,
      error: sensitiveError(),
      context: { uid: PRIVATE_UID, batchSize: 500 },
    });
    expect(fingerprint).toMatch(/^[0-9a-f]{64}$/);

    // The trigger patches githubIssueStatus away from the initial `pending`. With
    // no GITHUB_ISSUE_TOKEN in the emulator the create fails (`failed`); if a
    // previous test file already exhausted this hour's budget it is `skipped`.
    // Either way, observing the change proves the whole trigger flow ran.
    const settled = await pollUntil(async () => {
      const report = await findReport(fingerprint as string);
      const status = report?.githubIssueStatus;
      return status === 'failed' || status === 'skipped' ? status : undefined;
    });
    expect(['failed', 'skipped']).toContain(settled);

    // The private context survived; it is never in the public issue.
    const report = await findReport(fingerprint as string);
    expect(report?.context).toMatchObject({ uid: PRIVATE_UID, batchSize: '500' });

    // The global budget bucket for this hour exists — the trigger charged it.
    const bucket = await adminDb
      .collection(GITHUB_ISSUE_BUDGET_COLLECTION)
      .doc(issueBudgetBucketId(new Date()))
      .get();
    expect(bucket.exists).toBe(true);
    expect(bucket.data()?.count).toBeGreaterThanOrEqual(1);
  });

  it('never throws, even when the error is a hostile non-Error value', async () => {
    await expect(
      reportServerError({ source: source('hostile'), error: { name: 42, code: {}, stack: [] } }),
    ).resolves.not.toThrow();
  });
});

// ---------------------------------------------------------------------------
// Dedup: never more than ONE issue per fingerprint
// ---------------------------------------------------------------------------

/**
 * The invariant under test is "an in-flight or already-filed claim is never
 * granted a second time", NOT "of two racing occurrences exactly one claims".
 * The second phrasing is untestable against the emulator and, worse, it is not
 * what the design promises:
 *
 *  - the emulator ABORTS BOTH sides of a write-write conflict and the admin SDK
 *    then retries them behind an exponential backoff (~2.7s, then ~0.8s), so two
 *    `Promise.all` occurrences do not overlap at all — the first one finishes its
 *    entire claim → budget → create → reconcile cycle before the second one even
 *    re-reads the link;
 *  - and by design a create that did NOT produce an issue leaves the fingerprint
 *    RETRIABLE (a pristine claim is deleted, a bumped one goes back to `failed`),
 *    precisely so a transient GitHub outage cannot silence an error forever. With
 *    no GITHUB_ISSUE_TOKEN in the emulator every create "fails", so a second,
 *    strictly-later occurrence is SUPPOSED to claim again.
 *
 * So the tests below pin the link into each state that matters and prove the
 * decision taken from it, using real concurrent transactions where the state is
 * terminal (`creating`, `created`) and therefore contention-independent.
 */
describe('per-fingerprint dedup', () => {
  const filing = (fingerprint: string, src: string) => {
    const report = { ...buildServerErrorReport(src, sensitiveError()), fingerprint };
    return fileAutoIssue({
      pipeline: 'test.dedup',
      linkRef: adminDb.collection(SERVER_ERROR_ISSUE_LINKS_COLLECTION).doc(fingerprint),
      buildNewLink: (ts) => buildNewServerErrorIssueLink(report, ts),
      buildPayload: (meta) => buildServerErrorIssuePayload(report, meta),
      // Empty token → createGitHubIssue returns null without a network call.
      token: '',
      userAgent: 'carcommunity-emulator-test',
      logContext: { fingerprint },
    });
  };

  it('NEVER re-claims a fingerprint whose claim is still in flight', async () => {
    const src = source('inflight');
    const fingerprint = `inflight${RUN}`;
    const linkRef = adminDb.collection(SERVER_ERROR_ISSUE_LINKS_COLLECTION).doc(fingerprint);

    // Exactly the placeholder the winning occurrence writes before calling
    // GitHub: the claim is held, the issue does not exist yet.
    await linkRef.set({
      fingerprint,
      source: src,
      status: 'creating',
      issueNumber: null,
      issueUrl: null,
      count: 1,
      firstSeenAt: new Date(),
      lastSeenAt: new Date(),
    });

    // Three genuinely concurrent occurrences, contending on one document.
    const outcomes = await Promise.all([
      filing(fingerprint, src),
      filing(fingerprint, src),
      filing(fingerprint, src),
    ]);
    expect(outcomes.map((outcome) => outcome.status)).toEqual(['deduped', 'deduped', 'deduped']);

    // Every occurrence was tallied, nobody touched the in-flight claim, and no
    // second GitHub create was ever attempted.
    const link = await linkRef.get();
    expect(link.data()?.count).toBe(4);
    expect(link.data()?.status).toBe('creating');
    expect(link.data()?.issueNumber).toBeNull();
  });

  it('a repeat occurrence of an already-filed fingerprint increments instead of re-claiming', async () => {
    const src = source('repeat');
    const fingerprint = `repeat${RUN}`;
    const linkRef = adminDb.collection(SERVER_ERROR_ISSUE_LINKS_COLLECTION).doc(fingerprint);

    // Simulate a successfully filed issue (the emulator cannot reach GitHub).
    await linkRef.set({
      fingerprint,
      source: src,
      status: 'created',
      issueNumber: 4242,
      issueUrl: 'https://github.com/SebMcCayen/carcommunity/issues/4242',
      count: 1,
      firstSeenAt: new Date(),
      lastSeenAt: new Date(),
    });

    // Concurrent, so the increment path is exercised under real contention.
    const [first, second] = await Promise.all([filing(fingerprint, src), filing(fingerprint, src)]);

    expect(first.status).toBe('deduped');
    expect(second.status).toBe('deduped');
    expect(first.status === 'deduped' && first.issue?.number).toBe(4242);

    const link = await linkRef.get();
    expect(link.data()?.count).toBe(3);
    expect(link.data()?.status).toBe('created');
    // The issue reference is untouched — no second issue was opened.
    expect(link.data()?.issueNumber).toBe(4242);
  });

  it('deletes a PRISTINE claim when the create fails, so a later occurrence retries', async () => {
    const src = source('rollbackDelete');
    const fingerprint = `rollbackDelete${RUN}`;
    const linkRef = adminDb.collection(SERVER_ERROR_ISSUE_LINKS_COLLECTION).doc(fingerprint);

    const outcome = await filing(fingerprint, src);
    expect(outcome).toEqual({ status: 'failed', reason: 'github' });
    // Nothing was published, so nothing is left claiming the fingerprint.
    expect((await linkRef.get()).exists).toBe(false);

    // Retriable: the error is not silenced just because GitHub was unavailable.
    const retry = await filing(fingerprint, src);
    expect(retry).toEqual({ status: 'failed', reason: 'github' });
  });

  it('a failed create PRESERVES a tally rather than deleting occurrences', async () => {
    const src = source('rollbackKeep');
    const fingerprint = `rollbackKeep${RUN}`;
    const linkRef = adminDb.collection(SERVER_ERROR_ISSUE_LINKS_COLLECTION).doc(fingerprint);

    // A previously-failed claim that already absorbed four occurrences.
    const firstSeenAt = new Date('2026-07-30T03:30:00.000Z');
    await linkRef.set({
      fingerprint,
      source: src,
      status: 'failed',
      issueNumber: null,
      issueUrl: null,
      count: 4,
      firstSeenAt,
      lastSeenAt: firstSeenAt,
    });

    // `failed` is retriable, so this occurrence RE-claims rather than dedupes.
    const outcome = await filing(fingerprint, src);
    expect(outcome).toEqual({ status: 'failed', reason: 'github' });

    const link = await linkRef.get();
    expect(link.exists).toBe(true);
    expect(link.data()?.count).toBe(5);
    expect(link.data()?.status).toBe('failed');
    // The occurrence history is intact — first-seen is not reset by a retry.
    expect(link.data()?.firstSeenAt?.toDate?.().toISOString()).toBe(firstSeenAt.toISOString());
  });
});

// ---------------------------------------------------------------------------
// Global hourly issue budget
// ---------------------------------------------------------------------------

describe('global GitHub issue budget', () => {
  it(`allows exactly ${GITHUB_ISSUE_BUDGET_PER_HOUR} charges per hour bucket, then blocks`, async () => {
    // A pinned FUTURE bucket, so filling it to the cap cannot starve the
    // client-error trigger or other test files running in the same emulator.
    const pinned = new Date('2099-03-04T05:00:00.000Z');
    const bucketId = issueBudgetBucketId(pinned);
    const bucketRef = adminDb.collection(GITHUB_ISSUE_BUDGET_COLLECTION).doc(bucketId);
    await bucketRef.delete().catch(() => undefined);

    for (let i = 0; i < GITHUB_ISSUE_BUDGET_PER_HOUR; i += 1) {
      await expect(consumeGitHubIssueBudget('test.budget', pinned)).resolves.toBe(true);
    }

    // Past the cap: blocked, and the counter is NOT inflated further (a hot error
    // loop must not run the counter away).
    await expect(consumeGitHubIssueBudget('test.budget', pinned)).resolves.toBe(false);
    await expect(consumeGitHubIssueBudget('test.budget', pinned)).resolves.toBe(false);
    expect((await bucketRef.get()).data()?.count).toBe(GITHUB_ISSUE_BUDGET_PER_HOUR);

    // The next hour bucket is a clean slate.
    const nextHour = new Date('2099-03-04T06:00:00.000Z');
    await adminDb
      .collection(GITHUB_ISSUE_BUDGET_COLLECTION)
      .doc(issueBudgetBucketId(nextHour))
      .delete()
      .catch(() => undefined);
    await expect(consumeGitHubIssueBudget('test.budget', nextHour)).resolves.toBe(true);
  });

  it('REFUSES a bucket whose counter is not a usable number (fail closed)', async () => {
    // The counter is backend-only (`allow read, write: if false`), so this is
    // corruption rather than an attack path — but the limiter's whole contract is
    // that it fails CLOSED, and `NaN >= cap` is false, so an unguarded read would
    // treat a corrupt bucket as "budget available" for the rest of the hour and
    // publish without a working limiter to a world-readable repository.
    const pinned = new Date('2099-03-06T09:00:00.000Z');
    const bucketRef = adminDb
      .collection(GITHUB_ISSUE_BUDGET_COLLECTION)
      .doc(issueBudgetBucketId(pinned));

    await bucketRef.set({ bucketId: issueBudgetBucketId(pinned), count: Number.NaN });
    await expect(consumeGitHubIssueBudget('test.corruptBucket', pinned)).resolves.toBe(false);

    await bucketRef.set({ bucketId: issueBudgetBucketId(pinned), count: 'lots' });
    await expect(consumeGitHubIssueBudget('test.corruptBucket', pinned)).resolves.toBe(false);

    // A bucket document with no counter at all is NOT corrupt — it is a fresh
    // bucket, and must still be chargeable.
    await bucketRef.set({ bucketId: issueBudgetBucketId(pinned) });
    await expect(consumeGitHubIssueBudget('test.corruptBucket', pinned)).resolves.toBe(true);
    expect((await bucketRef.get()).data()?.count).toBe(1);
  });

  it('blocks the GitHub create and leaves the claim retriable when exhausted', async () => {
    const pinned = new Date('2099-03-05T07:00:00.000Z');
    const bucketRef = adminDb
      .collection(GITHUB_ISSUE_BUDGET_COLLECTION)
      .doc(issueBudgetBucketId(pinned));
    // Pre-exhaust the bucket directly.
    await bucketRef.set({
      bucketId: issueBudgetBucketId(pinned),
      count: GITHUB_ISSUE_BUDGET_PER_HOUR,
    });

    const src = source('budgetSkip');
    const fingerprint = `budget${RUN}`;
    const report = { ...buildServerErrorReport(src, sensitiveError()), fingerprint };
    const linkRef = adminDb.collection(SERVER_ERROR_ISSUE_LINKS_COLLECTION).doc(fingerprint);

    const outcome = await fileAutoIssue({
      pipeline: 'test.budgetSkip',
      linkRef,
      buildNewLink: (ts) => buildNewServerErrorIssueLink(report, ts),
      buildPayload: (meta) => buildServerErrorIssuePayload(report, meta),
      token: 'unused-because-the-budget-blocks-first',
      userAgent: 'carcommunity-emulator-test',
      logContext: { fingerprint },
      now: pinned,
    });

    expect(outcome).toEqual({ status: 'skipped', reason: 'budget' });

    // Nothing is lost: the occurrence is tallied and the link is retriable, so a
    // later occurrence in a fresh bucket files the issue.
    const link = await linkRef.get();
    expect(link.data()?.status).toBe('failed');
    expect(link.data()?.count).toBe(1);
    expect(link.data()?.issueNumber).toBeNull();
  });
});
