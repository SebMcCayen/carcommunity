/**
 * points-detectDailyCapReached emulator integration test.
 *
 * Drives the exported `runDailyCapDetection` runner directly against the
 * Firestore emulator (mirroring how the crown-lag test drives
 * `runClaimLagDetection`), seeding the per-member `pointsDailyTotals` docs the
 * award engine already writes and asserting the runner reads them back, counts
 * the members at/over the cap, and files ONCE per day through the shared pipeline
 * WITHOUT THROWING.
 *
 * GitHub is never reached from the emulator (createGitHubIssue short-circuits to
 * null under FUNCTIONS_EMULATOR), so a freshly-filed day resolves to a `failed`
 * (retriable) outcome rather than a real issue — the point is that exactly one
 * filing is attempted for a whole day of reachers, and that a day whose issue
 * link already exists DEDUPES (no new filing) rather than filing again.
 *
 * The exhaustive fingerprint/payload/PII assertions live in the db-free unit test
 * (daily-cap-issue-core.test.ts). Each case uses its OWN historical Stockholm day
 * and its OWN uids so the shared (no-isolation) pointsDailyTotals collection
 * cannot cross-contaminate, and cleanup targets only our seeds.
 *
 * CI ONLY. Requires the Firestore emulator (a JVM). Excluded from the default
 * unit suite by vitest.config.ts.
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';
// Force createGitHubIssue's emulator short-circuit: it returns null BEFORE using
// the token, so no run of this test can ever reach api.github.com even though a
// non-empty token is passed below.
process.env.FUNCTIONS_EMULATOR = 'true';

import {
  deleteApp,
  getApps as getAdminApps,
  initializeApp as initializeAdminApp,
} from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, FieldValue } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runDailyCapDetection } from '../points/dailyCapDetector';
import {
  DAILY_CAP_ISSUE_LINKS_COLLECTION,
  buildDailyCapFingerprint,
} from '../points/daily-cap-issue-core';
import { DAILY_POINTS_CAP, stockholmDayKey } from '../points/points-economy-core';

const PROJECT_ID = 'demo-test';
// A DEDICATED named app owned solely by this file, so disposing it in afterAll
// (below) cannot tear down the shared default admin app other emulator test
// files reuse. `find` by name keeps a re-import from throwing "app already
// exists" without falling back to someone else's app.
const APP_NAME = 'daily-cap-emulator';
const adminApp =
  getAdminApps().find((app) => app.name === APP_NAME) ??
  initializeAdminApp({ projectId: PROJECT_ID }, APP_NAME);
const adminDb = getAdminFirestore(adminApp);

// Distinct historical Stockholm days (winter → UTC+1, noon UTC is safely mid-day),
// so each case owns its fingerprint and its slice of the shared collection.
const NOW_DETECT = new Date('2023-02-01T12:00:00.000Z');
const NOW_DEDUP = new Date('2023-02-02T12:00:00.000Z');
const NOW_NOTOKEN = new Date('2023-02-03T12:00:00.000Z');
const DAY_DETECT = stockholmDayKey(NOW_DETECT);
const DAY_DEDUP = stockholmDayKey(NOW_DEDUP);
const DAY_NOTOKEN = stockholmDayKey(NOW_NOTOKEN);

const PREFIX = 'dailycap-test';
const seededTotalDocIds: string[] = [];
const seededLinkIds: string[] = [];

async function seedTotal(uid: string, day: string, total: number): Promise<void> {
  const id = `${uid}__${day}`;
  await adminDb.collection('pointsDailyTotals').doc(id).set({ userId: uid, day, total });
  seededTotalDocIds.push(id);
}

beforeAll(async () => {
  // Case 1 — two members AT/OVER the cap, one just under (must not count).
  await seedTotal(`${PREFIX}-detect-a`, DAY_DETECT, DAILY_POINTS_CAP);
  await seedTotal(`${PREFIX}-detect-b`, DAY_DETECT, DAILY_POINTS_CAP + 250);
  await seedTotal(`${PREFIX}-detect-under`, DAY_DETECT, DAILY_POINTS_CAP - 1);

  // Case 2 — one member over the cap, plus a pre-existing `created` issue link
  // for that day (simulating "the day's issue was already filed").
  await seedTotal(`${PREFIX}-dedup-a`, DAY_DEDUP, DAILY_POINTS_CAP + 40);
  const dedupFp = buildDailyCapFingerprint('points', DAY_DEDUP);
  await adminDb
    .collection(DAILY_CAP_ISSUE_LINKS_COLLECTION)
    .doc(dedupFp)
    .set({
      fingerprint: dedupFp,
      capType: 'points',
      capValue: DAILY_POINTS_CAP,
      day: DAY_DEDUP,
      status: 'created',
      issueNumber: 42,
      issueUrl: 'https://github.com/SebMcCayen/carcommunity/issues/42',
      count: 1,
      firstSeenAt: FieldValue.serverTimestamp(),
      lastSeenAt: FieldValue.serverTimestamp(),
    });
  seededLinkIds.push(dedupFp);

  // Case 3 — one member over the cap, filing exercised with no token.
  await seedTotal(`${PREFIX}-notoken-a`, DAY_NOTOKEN, DAILY_POINTS_CAP + 5);
});

afterAll(async () => {
  const batch = adminDb.batch();
  for (const id of seededTotalDocIds) batch.delete(adminDb.collection('pointsDailyTotals').doc(id));
  for (const fp of seededLinkIds) {
    batch.delete(adminDb.collection(DAILY_CAP_ISSUE_LINKS_COLLECTION).doc(fp));
  }
  // The detect case's fresh create rolls itself back (count 1 → deleted), but
  // delete its fingerprint too in case a prior run left it in another state.
  batch.delete(
    adminDb
      .collection(DAILY_CAP_ISSUE_LINKS_COLLECTION)
      .doc(buildDailyCapFingerprint('points', DAY_DETECT)),
  );
  await batch.commit();
  // Dispose the dedicated admin app so no Firestore handle leaks between files.
  await deleteApp(adminApp);
});

describe('runDailyCapDetection (emulator)', () => {
  it('counts distinct members at/over the cap and files exactly one issue for the day', async () => {
    const result = await runDailyCapDetection(NOW_DETECT, { token: 'emulator-test-token' });

    // Both cap-reachers counted; the under-cap member excluded.
    expect(result.membersOverCap).toBe(2);
    expect(result.totalsScanned).toBeGreaterThanOrEqual(3);

    // Exactly ONE filing was attempted for the whole day (all reachers collapse
    // onto one fingerprint). GitHub is short-circuited in the emulator, so the
    // fresh create resolves to `failed` (retriable) rather than `created` — the
    // invariant under test is "one filing attempt for the day", not a real issue.
    expect(result.filed + result.deduped + result.budgetSkipped + result.failed).toBe(1);
    expect(result.filed).toBe(0); // createGitHubIssue returns null under the emulator
    expect(result.failed).toBe(1);
    expect(result.filingSkippedMissingToken).toBe(false);
  });

  it('a second detection the same day DEDUPES onto the existing issue — files none', async () => {
    const result = await runDailyCapDetection(NOW_DEDUP, { token: 'emulator-test-token' });

    expect(result.membersOverCap).toBe(1);
    // The day's issue link already exists (`created`), so this pass only bumps
    // the tally — no new issue, no GitHub call.
    expect(result.deduped).toBe(1);
    expect(result.filed).toBe(0);
    expect(result.failed).toBe(0);
    expect(result.budgetSkipped).toBe(0);

    // The occurrence tally on the link was incremented from 1 to 2.
    const link = await adminDb
      .collection(DAILY_CAP_ISSUE_LINKS_COLLECTION)
      .doc(buildDailyCapFingerprint('points', DAY_DEDUP))
      .get();
    expect(link.data()?.count).toBe(2);
    expect(link.data()?.status).toBe('created');
  });

  it('skips filing entirely when the token is missing (budget-safe)', async () => {
    // With no token, filing must NOT run at all (fileAutoIssue would charge the
    // global hourly budget before bailing), but detection still runs.
    const result = await runDailyCapDetection(NOW_NOTOKEN, { token: '' });

    expect(result.membersOverCap).toBeGreaterThanOrEqual(1);
    expect(result.filingSkippedMissingToken).toBe(true);
    expect(result.filed).toBe(0);
    expect(result.deduped).toBe(0);
    expect(result.failed).toBe(0);
    expect(result.budgetSkipped).toBe(0);

    // And no issue link was written for that day.
    const link = await adminDb
      .collection(DAILY_CAP_ISSUE_LINKS_COLLECTION)
      .doc(buildDailyCapFingerprint('points', DAY_NOTOKEN))
      .get();
    expect(link.exists).toBe(false);
  });
});
