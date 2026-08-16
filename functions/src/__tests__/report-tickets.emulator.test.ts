/**
 * Open-tickets (report-tickets PR1) emulator integration tests.
 *
 * Exercises:
 *  - feedback-interactWithIssue callable: flag gate, auth gate, issue-open
 *    validation, ONCE-per-(issue,user,type) dedup (a 2nd +1 rejected, a 2nd
 *    comment rejected, but a +1 AND a comment both allowed once), the app-facing
 *    plusOneCount/commentCount tally bump, the moderationReports mirror on a
 *    comment, and the per-user 5/hour rate limit.
 *  - runOpenTicketsSync (scheduled sync body, run in-process with an injected
 *    GitHub fetcher — the real GitHub call is short-circuited in the emulator):
 *    the issue→openTickets mapping, live-tally preservation, stale-ticket
 *    reconciliation on a genuine empty success, and the null-fetch no-change
 *    (outage) safety.
 *
 * The GitHub REST calls are NOT made in the emulator: createIssueComment
 * short-circuits to false and listOpenIssues to null (the same value it returns
 * on a real failure). So `posted` is false throughout and no network is hit.
 * The sync tests inject their own fetcher, so they exercise null vs [] directly.
 * Requires the Functions + Firestore emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, FirebaseError, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signInWithEmailAndPassword,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runOpenTicketsSync } from '../feedback/syncOpenTickets';
import type { GitHubOpenIssue } from '../shared/githubIssues';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'report-tickets-emulator-tests');
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000, intervalMs = 250): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

async function callableErrorCode(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    return 'no-error';
  } catch (error) {
    if (error instanceof FirebaseError) return error.code;
    throw error;
  }
}

async function createProvisionedUser(prefix: string): Promise<TestUser> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  return { uid, email, password };
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

async function setFlag(enabled: boolean): Promise<void> {
  await adminDb.collection('config').doc('featureFlags').set({ reportTicketsBrowser: enabled }, { merge: true });
}

async function seedOpenTicket(
  number: number,
  fields: Record<string, unknown> = {},
): Promise<void> {
  await adminDb
    .collection('openTickets')
    .doc(String(number))
    .set(
      {
        number,
        title: `[Android] Ticket ${number}`,
        summary: `Summary ${number}`,
        htmlUrl: `https://github.com/SebMcCayen/carcommunity/issues/${number}`,
        state: 'open',
        plusOneCount: 0,
        commentCount: 0,
        ...fields,
      },
      { merge: true },
    );
}

function fakeIssue(number: number, over: Partial<GitHubOpenIssue> = {}): GitHubOpenIssue {
  return {
    number,
    title: `[Android] Ticket ${number}`,
    body: `Body line for ${number}.\nsecond line`,
    html_url: `https://github.com/SebMcCayen/carcommunity/issues/${number}`,
    created_at: '2026-08-16T10:00:00.000Z',
    state: 'open',
    comments: 0,
    ...over,
  };
}

let reporter: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'report-tickets-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  reporter = await createProvisionedUser('rt-reporter');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('feedback-interactWithIssue', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(
      await callableErrorCode(call('feedback-interactWithIssue', { issueNumber: 1, type: 'plus_one', clientId: 'c1' })),
    ).toBe('functions/unauthenticated');
  });

  it('rejects when the reportTicketsBrowser flag is OFF', async () => {
    await setFlag(false);
    await seedOpenTicket(8001);
    await signInAs(reporter);
    expect(
      await callableErrorCode(call('feedback-interactWithIssue', { issueNumber: 8001, type: 'plus_one', clientId: 'c1' })),
    ).toBe('functions/failed-precondition');
  });

  it('rejects a missing or non-open issue when enabled', async () => {
    await setFlag(true);
    await signInAs(reporter);
    await seedOpenTicket(8002, { state: 'closed' });
    expect(
      await callableErrorCode(call('feedback-interactWithIssue', { issueNumber: 8002, type: 'plus_one', clientId: 'c1' })),
    ).toBe('functions/failed-precondition');
    expect(
      await callableErrorCode(call('feedback-interactWithIssue', { issueNumber: 999999, type: 'plus_one', clientId: 'c1' })),
    ).toBe('functions/failed-precondition');
  });

  it('records a +1 once, bumps the tally, and rejects a repeat +1', async () => {
    await setFlag(true);
    await signInAs(reporter);
    await seedOpenTicket(8001);

    const res = (await call('feedback-interactWithIssue', { issueNumber: 8001, type: 'plus_one', clientId: 'c1' }))
      .data as { issueNumber: number; type: string; posted: boolean };
    expect(res).toMatchObject({ issueNumber: 8001, type: 'plus_one', posted: false });

    const dedup = await adminDb.collection('issueInteractions').doc(`8001__${reporter.uid}__plus_one`).get();
    expect(dedup.exists).toBe(true);
    const ticket = (await adminDb.collection('openTickets').doc('8001').get()).data()!;
    expect(ticket.plusOneCount).toBe(1);

    // A second +1 on the same issue by the same user is rejected.
    expect(
      await callableErrorCode(call('feedback-interactWithIssue', { issueNumber: 8001, type: 'plus_one', clientId: 'c2' })),
    ).toBe('functions/failed-precondition');
    const ticketAfter = (await adminDb.collection('openTickets').doc('8001').get()).data()!;
    expect(ticketAfter.plusOneCount).toBe(1); // unchanged
  });

  it('allows a comment on the SAME issue (a +1 AND a comment), mirrors it to moderationReports, and rejects a repeat comment', async () => {
    await setFlag(true);
    await signInAs(reporter);
    // reporter already +1'd 8001 above — a comment is a distinct type, so allowed.

    const res = (await call('feedback-interactWithIssue', {
      issueNumber: 8001,
      type: 'comment',
      text: 'I have the same problem here.',
      clientId: 'c3',
    })).data as { type: string; posted: boolean };
    expect(res).toMatchObject({ type: 'comment', posted: false });

    const dedup = await adminDb.collection('issueInteractions').doc(`8001__${reporter.uid}__comment`).get();
    expect(dedup.exists).toBe(true);
    const ticket = (await adminDb.collection('openTickets').doc('8001').get()).data()!;
    expect(ticket.commentCount).toBe(1);
    expect(ticket.plusOneCount).toBe(1); // the earlier +1 still stands

    // The comment is mirrored into the shared moderation queue for triage.
    const mod = await adminDb
      .collection('moderationReports')
      .where('scopeId', '==', '8001')
      .where('reportedBy', '==', reporter.uid)
      .get();
    expect(mod.size).toBe(1);
    const modDoc = mod.docs[0]!.data();
    expect(modDoc.surface).toBe('ticket');
    expect(modDoc.targetType).toBe('message');
    expect(modDoc.reportedUserId).toBe(reporter.uid);
    expect((modDoc.snapshot as Record<string, unknown>).text).toBe('I have the same problem here.');

    // A second comment on the same issue by the same user is rejected.
    expect(
      await callableErrorCode(call('feedback-interactWithIssue', { issueNumber: 8001, type: 'comment', text: 'again', clientId: 'c4' })),
    ).toBe('functions/failed-precondition');
    const ticketAfter = (await adminDb.collection('openTickets').doc('8001').get()).data()!;
    expect(ticketAfter.commentCount).toBe(1); // unchanged
  });

  it('enforces the per-user 5/hour interaction cap', async () => {
    await setFlag(true);
    const burst = await createProvisionedUser('rt-burst');
    await signInAs(burst);
    for (let n = 8101; n <= 8106; n += 1) await seedOpenTicket(n);

    // Five distinct (issue, +1) interactions succeed.
    for (let n = 8101; n <= 8105; n += 1) {
      await call('feedback-interactWithIssue', { issueNumber: n, type: 'plus_one', clientId: `b${n}` });
    }
    // The sixth in the window is rate-limited.
    expect(
      await callableErrorCode(call('feedback-interactWithIssue', { issueNumber: 8106, type: 'plus_one', clientId: 'b6' })),
    ).toBe('functions/resource-exhausted');
  });
});

describe('runOpenTicketsSync', () => {
  it('mirrors fetched issues, preserves live tallies, and reconciles stale tickets out', async () => {
    // A ticket with a LIVE +1 tally, plus a stale ticket not in the fetch set.
    await seedOpenTicket(8201, { plusOneCount: 5, commentCount: 2 });
    await seedOpenTicket(8299);

    const result = await runOpenTicketsSync(async () => [fakeIssue(8201), fakeIssue(8202)]);
    expect(result.mirrored).toBe(2);
    expect(result.removed).toBeGreaterThanOrEqual(1);

    const t1 = (await adminDb.collection('openTickets').doc('8201').get()).data()!;
    expect(t1.title).toBe('[Android] Ticket 8201');
    expect(t1.summary).toBe('Body line for 8201.');
    expect(t1.plusOneCount).toBe(5); // preserved by increment(0)
    expect(t1.commentCount).toBe(2);
    expect(t1.syncedAt).toBeTruthy();

    const t2 = (await adminDb.collection('openTickets').doc('8202').get()).data()!;
    expect(t2.plusOneCount).toBe(0); // initialised on first insert
    expect(t2.commentCount).toBe(0);

    // The stale ticket is gone.
    expect((await adminDb.collection('openTickets').doc('8299').get()).exists).toBe(false);
  });

  it('makes NO changes on a null fetch (outage safety)', async () => {
    const before = (await adminDb.collection('openTickets').get()).size;
    expect(before).toBeGreaterThan(0);
    const result = await runOpenTicketsSync(async () => null);
    expect(result).toEqual({ fetched: 0, mirrored: 0, removed: 0 });
    const after = (await adminDb.collection('openTickets').get()).size;
    expect(after).toBe(before); // nothing touched
  });

  it('reconciles a GENUINE empty open set (successful fetch of []) by removing all stale docs', async () => {
    const before = (await adminDb.collection('openTickets').get()).size;
    expect(before).toBeGreaterThan(0);
    const result = await runOpenTicketsSync(async () => []);
    expect(result.mirrored).toBe(0);
    expect(result.removed).toBe(before); // an empty success is a real zero — reconcile out
    expect((await adminDb.collection('openTickets').get()).size).toBe(0);
  });
});
