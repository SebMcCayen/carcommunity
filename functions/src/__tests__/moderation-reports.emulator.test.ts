/**
 * Moderation-report emulator integration tests.
 *
 * Exercises the three deployed-in-emulator report callables end-to-end plus the
 * moderationReports rules:
 * - `chatchannels-reportMessage` (community + convoy; eligibility mirrors each
 *   channel's read rule, self-report rejected, snapshot captured, dedup)
 * - `dm-reportMessage` (participant-only, not-found for an outsider, snapshot
 *   captured — the only way an admin can ever see the reported line)
 * - `moderation-reportUser` (self rejected, dedup per (reporter, target)
 *   IGNORING the reason, tally, per-target distinct-reporter aggregate)
 * - blocking does not disarm reporting, in either direction
 * - rules: moderationReports is admin-read-only with NO client writes.
 *
 * DISPLAY NAMES: every user created here carries a `Mod` suffix. The emulator
 * suite shares ONE Firestore across test files, so a displayName reused by
 * another file makes nickname/profile lookups ambiguous and flakes both.
 *
 * Requires the Auth + Functions + Firestore emulators — run via:
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
import {
  connectFirestoreEmulator,
  doc,
  getDoc,
  getFirestore,
  setDoc,
  type Firestore,
} from 'firebase/firestore';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  moderationMessageReportId,
  moderationUserReportId,
} from '../moderation/moderation-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'moderation-emulator-tests');
const adminDb = getAdminFirestore(adminApp);
const adminAuth = getAdminAuth(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
let firestore: Firestore;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 250));
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

let userSeq = 0;

/** `Mod`-suffixed display names keep this file's users distinct suite-wide. */
async function newMember(displayName: string): Promise<TestUser> {
  userSeq += 1;
  const email = `mod-${userSeq}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  await adminAuth.setCustomUserClaims(uid, { activeMember: true });
  await adminDb
    .collection('users')
    .doc(uid)
    .set(
      { activeMember: true, displayName, avatarPath: `profileImages/${uid}/a.jpg` },
      { merge: true },
    );
  return { uid, email, password };
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

async function makeFriends(a: TestUser, b: TestUser): Promise<void> {
  await adminDb
    .collection('users')
    .doc(a.uid)
    .collection('friends')
    .doc(b.uid)
    .set({ friendUid: b.uid, displayName: 'X', avatarPath: null, createdAt: new Date() });
  await adminDb
    .collection('users')
    .doc(b.uid)
    .collection('friends')
    .doc(a.uid)
    .set({ friendUid: a.uid, displayName: 'Y', avatarPath: null, createdAt: new Date() });
}

async function readReport(reportId: string): Promise<Record<string, unknown>> {
  const snap = await adminDb.collection('moderationReports').doc(reportId).get();
  expect(snap.exists).toBe(true);
  return snap.data() as Record<string, unknown>;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'moderation-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, EMULATOR_HOST, 8080);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('chatchannels-reportMessage (community)', () => {
  it('rejects unauthenticated callers', async () => {
    await auth.signOut();
    expect(
      await callableErrorCode(
        call('chatchannels-reportMessage', {
          channel: 'community',
          messageId: 'nope',
          reason: 'spam',
        }),
      ),
    ).toBe('functions/unauthenticated');
  });

  it('files a report that SNAPSHOTS the message, and dedups a repeat', async () => {
    const author = await newMember('AuthorCommMod');
    const reporter = await newMember('ReporterCommMod');

    await signInAs(author);
    const marker = `modreport-${Date.now()}-${Math.random()}`;
    const posted = (await call('communityChat-post', { text: marker })).data as {
      messageId: string;
    };

    await signInAs(reporter);
    const response = (
      await call('chatchannels-reportMessage', {
        channel: 'community',
        messageId: posted.messageId,
        reason: 'harassment',
        details: '  unpleasant  ',
      })
    ).data as { reported: boolean };
    expect(response.reported).toBe(true);

    const reportId = moderationMessageReportId({
      surface: 'community',
      scopeId: 'global',
      messageId: posted.messageId,
      reporterUserId: reporter.uid,
      reason: 'harassment',
    });
    const report = await readReport(reportId);
    expect(report).toMatchObject({
      reportedBy: reporter.uid,
      targetType: 'message',
      targetId: posted.messageId,
      reportedUserId: author.uid,
      surface: 'community',
      scopeId: 'global',
      reason: 'harassment',
      details: 'unpleasant',
      status: 'pending',
      occurrences: 1,
    });
    // The evidence, not a pointer to it: the message is TTL-deleted at 120 days.
    expect(report.snapshot).toMatchObject({
      text: marker,
      authorUserId: author.uid,
      authorDisplayName: 'AuthorCommMod',
    });

    // A repeat with the same reason silently refreshes the note — no second row,
    // and the response gives away nothing about the earlier report.
    const repeat = (
      await call('chatchannels-reportMessage', {
        channel: 'community',
        messageId: posted.messageId,
        reason: 'harassment',
        details: 'corrected note',
      })
    ).data as { reported: boolean };
    expect(repeat.reported).toBe(true);
    expect((await readReport(reportId)).details).toBe('corrected note');

    // A DIFFERENT reason from the same reporter IS a separate judgement about
    // the message, so it does get its own row.
    await call('chatchannels-reportMessage', {
      channel: 'community',
      messageId: posted.messageId,
      reason: 'spam',
    });
    await readReport(
      moderationMessageReportId({
        surface: 'community',
        scopeId: 'global',
        messageId: posted.messageId,
        reporterUserId: reporter.uid,
        reason: 'spam',
      }),
    );
  });

  it('rejects reporting your own message, and an unknown message', async () => {
    const author = await newMember('SelfReportCommMod');
    await signInAs(author);
    const posted = (await call('communityChat-post', { text: `self-${Date.now()}` })).data as {
      messageId: string;
    };
    expect(
      await callableErrorCode(
        call('chatchannels-reportMessage', {
          channel: 'community',
          messageId: posted.messageId,
          reason: 'spam',
        }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('chatchannels-reportMessage', {
          channel: 'community',
          messageId: 'does-not-exist',
          reason: 'spam',
        }),
      ),
    ).toBe('functions/not-found');
  });

  it('rejects a convoyId on the community channel and a missing one on convoy', async () => {
    const member = await newMember('ArgShapeMod');
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('chatchannels-reportMessage', {
          channel: 'community',
          convoyId: 'c1',
          messageId: 'm1',
          reason: 'spam',
        }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('chatchannels-reportMessage', { channel: 'convoy', messageId: 'm1', reason: 'spam' }),
      ),
    ).toBe('functions/invalid-argument');
  });
});

describe('chatchannels-reportMessage (convoy)', () => {
  it('admits an accepted member and hides the convoy from an outsider', async () => {
    const owner = await newMember('ConvoyOwnerMod');
    const rider = await newMember('ConvoyRiderMod');
    const outsider = await newMember('ConvoyOutsiderMod');
    await makeFriends(owner, rider);

    await signInAs(owner);
    const created = (await call('convoy-create', { inviteeUids: [rider.uid], title: 'Mod run' }))
      .data as { convoy: { convoyId: string } };
    const convoyId = created.convoy.convoyId;

    await signInAs(rider);
    await call('convoy-respond', { convoyId, action: 'accept' });
    const posted = (await call('convoyChat-post', { convoyId, text: `convoy-${Date.now()}` }))
      .data as { messageId: string };

    // The owner (an accepted member) can report the rider's message.
    await signInAs(owner);
    const response = (
      await call('chatchannels-reportMessage', {
        channel: 'convoy',
        convoyId,
        messageId: posted.messageId,
        reason: 'unsafe_driving',
      })
    ).data as { reported: boolean };
    expect(response.reported).toBe(true);
    const report = await readReport(
      moderationMessageReportId({
        surface: 'convoy',
        scopeId: convoyId,
        messageId: posted.messageId,
        reporterUserId: owner.uid,
        reason: 'unsafe_driving',
      }),
    );
    expect(report).toMatchObject({ surface: 'convoy', scopeId: convoyId, reportedUserId: rider.uid });

    // An outsider gets not-found — the report endpoint must not become a way to
    // probe whether a convoy exists (parity with convoyChat.post/list).
    await signInAs(outsider);
    expect(
      await callableErrorCode(
        call('chatchannels-reportMessage', {
          channel: 'convoy',
          convoyId,
          messageId: posted.messageId,
          reason: 'spam',
        }),
      ),
    ).toBe('functions/not-found');
  });
});

describe('dm-reportMessage', () => {
  it('lets a participant report, snapshots the line, and hides the conversation from outsiders', async () => {
    const alice = await newMember('DmAliceMod');
    const bob = await newMember('DmBobMod');
    const stranger = await newMember('DmStrangerMod');
    await makeFriends(alice, bob);

    await signInAs(bob);
    const text = `dm-report-${Date.now()}`;
    const sent = (await call('dm-sendMessage', { toUid: alice.uid, text })).data as {
      conversationId: string;
      messageId: string;
    };

    await signInAs(alice);
    const response = (
      await call('dm-reportMessage', {
        conversationId: sent.conversationId,
        messageId: sent.messageId,
        reason: 'harassment',
        details: 'kept messaging after I asked them to stop',
      })
    ).data as { reported: boolean };
    expect(response.reported).toBe(true);

    const report = await readReport(
      moderationMessageReportId({
        surface: 'dm',
        scopeId: sent.conversationId,
        messageId: sent.messageId,
        reporterUserId: alice.uid,
        reason: 'harassment',
      }),
    );
    expect(report).toMatchObject({
      reportedBy: alice.uid,
      targetType: 'message',
      surface: 'dm',
      scopeId: sent.conversationId,
      reportedUserId: bob.uid,
    });
    // Admins have NO read path into conversations, so without this snapshot the
    // report would be unactionable.
    expect(report.snapshot).toMatchObject({
      text,
      authorUserId: bob.uid,
      authorDisplayName: 'DmBobMod',
    });

    // A non-participant gets not-found, never permission-denied.
    await signInAs(stranger);
    expect(
      await callableErrorCode(
        call('dm-reportMessage', {
          conversationId: sent.conversationId,
          messageId: sent.messageId,
          reason: 'spam',
        }),
      ),
    ).toBe('functions/not-found');

    // Reporting your own message is rejected.
    await signInAs(bob);
    expect(
      await callableErrorCode(
        call('dm-reportMessage', {
          conversationId: sent.conversationId,
          messageId: sent.messageId,
          reason: 'spam',
        }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('stays available after a block — you block someone BECAUSE they are abusive', async () => {
    const victim = await newMember('DmVictimMod');
    const abuser = await newMember('DmAbuserMod');
    await makeFriends(victim, abuser);

    await signInAs(abuser);
    const sent = (await call('dm-sendMessage', { toUid: victim.uid, text: `blocked-${Date.now()}` }))
      .data as { conversationId: string; messageId: string };

    await signInAs(victim);
    await call('blocking-block', { targetUserId: abuser.uid });
    // Blocking first and reporting second is the normal order; a block must not
    // disarm the report button.
    const response = (
      await call('dm-reportMessage', {
        conversationId: sent.conversationId,
        messageId: sent.messageId,
        reason: 'harassment',
      })
    ).data as { reported: boolean };
    expect(response.reported).toBe(true);
  });
});

describe('moderation-reportUser', () => {
  it('rejects self-reports and unknown users', async () => {
    const member = await newMember('SelfUserMod');
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('moderation-reportUser', { reportedUserId: member.uid, reason: 'spam' }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('moderation-reportUser', { reportedUserId: 'no-such-user', reason: 'spam' }),
      ),
    ).toBe('functions/not-found');
  });

  it('dedups per (reporter, target) IGNORING reason, tallies, and counts distinct reporters', async () => {
    const target = await newMember('ReportedPersonMod');
    const first = await newMember('FirstAccuserMod');
    const second = await newMember('SecondAccuserMod');

    await signInAs(first);
    await call('moderation-reportUser', {
      reportedUserId: target.uid,
      reason: 'harassment',
      details: 'followed me around the map',
    });

    const reportId = moderationUserReportId(first.uid, target.uid);
    const report = await readReport(reportId);
    expect(report).toMatchObject({
      reportedBy: first.uid,
      targetType: 'user',
      targetId: target.uid,
      reportedUserId: target.uid,
      status: 'pending',
      occurrences: 1,
      surface: null,
    });
    // Bounded and public-only: the profile projection, never a history.
    expect(report.snapshot).toEqual({
      displayName: 'ReportedPersonMod',
      avatarPath: `profileImages/${target.uid}/a.jpg`,
    });

    // Cycling the reason must NOT mint a second row — that would be a one-click
    // way for a single person to manufacture a pile-on.
    await call('moderation-reportUser', {
      reportedUserId: target.uid,
      reason: 'spam',
      details: 'also spamming',
    });
    const afterRepeat = await readReport(reportId);
    expect(afterRepeat).toMatchObject({
      occurrences: 2,
      reason: 'spam',
      details: 'also spamming',
      status: 'pending',
    });

    let summary = (
      await adminDb.collection('moderationUserSummaries').doc(target.uid).get()
    ).data()!;
    expect(summary).toMatchObject({ reporterCount: 1, totalSubmissions: 2 });

    // A genuinely different reporter DOES advance the distinct count.
    await signInAs(second);
    await call('moderation-reportUser', { reportedUserId: target.uid, reason: 'hate_or_abuse' });
    summary = (await adminDb.collection('moderationUserSummaries').doc(target.uid).get()).data()!;
    expect(summary).toMatchObject({ reporterCount: 2, totalSubmissions: 3 });
    await readReport(moderationUserReportId(second.uid, target.uid));
  });

  it('rejects a suspended reporter', async () => {
    const suspended = await newMember('SuspendedReporterMod');
    const target = await newMember('SuspendedTargetMod');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(
      await callableErrorCode(
        call('moderation-reportUser', { reportedUserId: target.uid, reason: 'spam' }),
      ),
    ).toBe('functions/permission-denied');
  });
});

describe('moderationReports rules', () => {
  it('is admin-read-only and denies every client write', async () => {
    const member = await newMember('RulesProbeMod');
    const target = await newMember('RulesTargetMod');
    await signInAs(member);
    await call('moderation-reportUser', { reportedUserId: target.uid, reason: 'spam' });
    const reportId = moderationUserReportId(member.uid, target.uid);

    // The reporter cannot read their own report back, nor edit it, nor forge one.
    await expect(getDoc(doc(firestore, 'moderationReports', reportId))).rejects.toThrow();
    await expect(
      setDoc(doc(firestore, 'moderationReports', `forged-${member.uid}`), {
        reportedBy: member.uid,
        targetType: 'user',
        targetId: target.uid,
        reason: 'spam',
        status: 'pending',
        createdAt: new Date(),
      }),
    ).rejects.toThrow();
    // Nor read the per-target aggregate.
    await expect(
      getDoc(doc(firestore, 'moderationUserSummaries', target.uid)),
    ).rejects.toThrow();
  });
});
