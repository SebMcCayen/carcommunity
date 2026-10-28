process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, signInWithEmailAndPassword } from 'firebase/auth';
import { getFunctions, connectFunctionsEmulator, httpsCallable } from 'firebase/functions';
import { getApps, initializeApp as adminApp } from 'firebase-admin/app';
import { getAuth as adminAuth } from 'firebase-admin/auth';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { beforeAll, beforeEach, afterAll, it, expect } from 'vitest';
const server = getApps()[0] ?? adminApp({ projectId: 'demo-test' }, 'public-moderation');
const db = getFirestore(server),
  authAdmin = adminAuth(server);
const app = initializeApp(
  { projectId: 'demo-test', apiKey: 'demo-key', appId: 'demo-id' },
  'public-moderation-client',
);
const auth = getAuth(app),
  functions = getFunctions(app, 'europe-west1');
connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
connectFunctionsEmulator(functions, '127.0.0.1', 5001);
const uid = 'public-moderator',
  member = 'public-member',
  event = 'public-test-event';
type Response = {
  items: Array<{ id: string; reportId: string }>;
  meta: { nextCursor: string | null };
  version: string;
  message: { authorUid: string; authorName: string; createdAt: string | null };
  alreadyApplied: boolean;
  cleanup: { redacted: number; hasMore: boolean };
};
const call = async (name: string, data: unknown = {}) =>
  (await httpsCallable(functions, name)(data)).data as Response;
const publicMessages = db.collection('communityChat').doc('global').collection('messages');
const eventMessages = db.collection('events').doc(event).collection('messages');
const reports = db.collection('moderationReports');
const target = { source: 'event', eventId: event, messageId: 'message', reportId: 'report' };
const community = { source: 'community', messageId: 'parent', reportId: 'public-report' };
const time = Timestamp.fromMillis(Date.now() - 100000);
async function review(t: unknown = target) {
  return call('admin-getPublicModerationCase', t);
}
async function decide(action: string, t: unknown = target, extra: Record<string, unknown> = {}) {
  const d = await review(t);
  return call('admin-decidePublicModeration', {
    target: t,
    expectedActorUid: uid,
    expectedVersion: d.version,
    action,
    reason: 'Reviewed local decision',
    idempotencyKey: crypto.randomUUID(),
    ...extra,
  });
}
beforeAll(async () => {
  if (process.env.GCLOUD_PROJECT !== 'demo-test') throw Error('Demo project required');
  try {
    await authAdmin.createUser({
      uid,
      email: 'public-admin@example.com',
      password: 'Local-public-12345',
    });
  } catch {
    /* isolated suite may reuse account */
  }
  await authAdmin.setCustomUserClaims(uid, { admin: true });
  await signInWithEmailAndPassword(auth, 'public-admin@example.com', 'Local-public-12345');
}, 60000);
beforeEach(async () => {
  await db.collection('users').doc(uid).set({
    role: 'admin',
    displayName: 'Moderator',
    deleted: false,
    suspended: false,
    onboardingCompleted: true,
  });
  await db.doc(`carCommunityAccess/${uid}`).set({ initialized: true, suspended: false });
  await db
    .doc(`users/${member}`)
    .set({ role: 'user', displayName: 'Member', deleted: false, suspended: false });
  await db.recursiveDelete(db.doc(`events/${event}`));
  await db.recursiveDelete(db.doc('communityChat/global'));
  for (const collection of [
    'moderationReports',
    'adminModerationOperations',
    'adminAuditEvents',
    'communityChatDeletedMessages',
  ])
    for (const d of (await db.collection(collection).get()).docs) await d.ref.delete();
  await eventMessages.doc('message').set({
    authorUserId: member,
    authorDisplayName: 'Member',
    message: 'Event body',
    moderationState: 'visible',
    createdAt: time,
  });
  await db.doc(`events/${event}/messageReports/report`).set({
    messageId: 'message',
    reason: 'spam',
    details: 'A report',
    status: 'new',
    createdAt: time,
  });
  await publicMessages
    .doc('parent')
    .set({ senderUid: member, senderDisplayName: 'Member', text: 'Public body', createdAt: time });
  await reports.doc('public-report').set({
    surface: 'community',
    scopeId: 'global',
    targetType: 'message',
    targetId: 'parent',
    status: 'pending',
    reason: 'spam',
    createdAt: time,
  });
});
afterAll(async () => {
  await deleteApp(app);
});
it('filters status before pagination and traverses timestamp ties across both sources without duplicates', async () => {
  const batch = db.batch();
  for (let i = 0; i < 225; i++)
    batch.set(db.doc(`events/${event}/messageReports/closed-${i}`), {
      messageId: 'message',
      status: 'resolved',
      reason: 'spam',
      createdAt: Timestamp.now(),
    });
  for (let i = 0; i < 44; i++)
    batch.set(db.doc(`events/${event}/messageReports/open-${String(i).padStart(3, '0')}`), {
      messageId: 'message',
      status: 'new',
      reason: 'spam',
      createdAt: time,
    });
  await batch.commit();
  const ids: string[] = [];
  let cursor: string | null = null;
  do {
    const p = await call('admin-listPublicReports', { status: 'open', pageSize: 13, cursor });
    ids.push(...p.items.map((r: { id: string; reportId: string }) => r.id));
    cursor = p.meta.nextCursor;
  } while (cursor);
  expect(ids).toHaveLength(46);
  expect(new Set(ids).size).toBe(46);
  expect(ids.some((id) => id.includes('closed-'))).toBe(false);
});
it('excludes DM and convoy reports and rejects a known private report locator', async () => {
  await reports.doc('private').set({
    surface: 'dm',
    scopeId: 'secret',
    targetType: 'message',
    targetId: 'parent',
    status: 'pending',
    reason: 'spam',
    snapshot: { text: 'PRIVATE' },
    createdAt: time,
  });
  const p = await call('admin-listPublicReports');
  expect(JSON.stringify(p)).not.toContain('PRIVATE');
  expect(p.items).toHaveLength(2);
  await expect(review({ ...community, reportId: 'private' })).rejects.toMatchObject({
    code: 'functions/not-found',
  });
  await expect(review({ ...target, messageId: 'wrong' })).rejects.toMatchObject({
    code: 'functions/not-found',
  });
});
it('lists unreported member messages from only canonical event and public community paths', async () => {
  await db
    .doc('convoyChats/private/messages/private')
    .set({ senderUid: member, text: 'PRIVATE', createdAt: time });
  const p = await call('admin-listMemberMessages', { targetUid: member });
  expect(p.items).toHaveLength(2);
  expect(JSON.stringify(p)).not.toContain('PRIVATE');
  await db
    .doc('conversations/private/messages/private')
    .set({ authorUserId: member, text: 'PRIVATE', createdAt: time });
  await expect(call('admin-listMemberMessages', { targetUid: member })).rejects.toMatchObject({
    code: 'functions/failed-precondition',
  });
  await db.doc('conversations/private/messages/private').delete();
});
it('binds cursors to source, member and status', async () => {
  const p = await call('admin-listPublicReports', { pageSize: 1 });
  expect(p.meta.nextCursor).toBeTruthy();
  await expect(
    call('admin-listPublicReports', { status: 'closed', pageSize: 1, cursor: p.meta.nextCursor }),
  ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
});
it('rejects stale evidence, a different actor, empty reason and private locators without writing', async () => {
  const d = await review();
  await eventMessages.doc('message').update({ message: 'Changed' });
  await expect(decide('remove', target, { expectedVersion: d.version })).rejects.toMatchObject({
    code: 'functions/failed-precondition',
  });
  await expect(decide('remove', target, { expectedActorUid: member })).rejects.toMatchObject({
    code: 'functions/permission-denied',
  });
  await expect(decide('remove', target, { reason: ' ' })).rejects.toMatchObject({
    code: 'functions/invalid-argument',
  });
  await expect(
    call('admin-getPublicModerationCase', { ...community, source: 'dm' }),
  ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
  expect((await db.collection('adminAuditEvents').get()).empty).toBe(true);
});
it('soft removes events, atomically resolves the selected report and audits exactly once on retry', async () => {
  const d = await review(),
    payload = {
      target,
      expectedActorUid: uid,
      expectedVersion: d.version,
      action: 'remove',
      reason: 'Safety',
      idempotencyKey: crypto.randomUUID(),
    };
  await call('admin-decidePublicModeration', payload);
  const repeat = await call('admin-decidePublicModeration', payload);
  expect(repeat.alreadyApplied).toBe(true);
  expect((await eventMessages.doc('message').get()).data()).toMatchObject({
    message: '',
    moderationState: 'removed',
  });
  expect((await db.doc(`events/${event}/messageReports/report`).get()).get('status')).toBe(
    'resolved',
  );
  expect((await db.collection('adminAuditEvents').get()).size).toBe(1);
  await expect(decide('allow')).rejects.toMatchObject({ code: 'functions/failed-precondition' });
  await expect(
    call('admin-decidePublicModeration', { ...payload, reason: 'Changed intent' }),
  ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
});
it('hard removes public text, resumes bounded quote redaction and never touches private quotes', async () => {
  const batch = db.batch();
  for (let i = 0; i < 205; i++)
    batch.set(publicMessages.doc(`reply-${i}`), {
      senderUid: member,
      text: 'Reply stays',
      createdAt: time,
      replyTo: { messageId: 'parent', senderUid: member, textPreview: 'Public body' },
    });
  await batch.commit();
  await db
    .doc('convoyChats/private/messages/quote')
    .set({ replyTo: { messageId: 'parent', textPreview: 'PRIVATE' } });
  const d = await review(community),
    payload = {
      target: community,
      expectedActorUid: uid,
      expectedVersion: d.version,
      action: 'remove',
      reason: 'Safety',
      idempotencyKey: crypto.randomUUID(),
    };
  const first = await call('admin-decidePublicModeration', payload);
  expect(first.cleanup).toMatchObject({ redacted: 200, hasMore: true });
  expect((await publicMessages.doc('parent').get()).exists).toBe(false);
  const removedCase = await review(community);
  expect(removedCase.message).toMatchObject({
    authorUid: member,
    authorName: 'Member',
    createdAt: time.toDate().toISOString(),
  });
  const second = await call('admin-decidePublicModeration', payload);
  expect(second).toMatchObject({ alreadyApplied: true, cleanup: { redacted: 5, hasMore: false } });
  expect((await publicMessages.where('replyTo.messageId', '==', 'parent').get()).size).toBe(0);
  expect((await publicMessages.doc('reply-0').get()).get('text')).toBe('Reply stays');
  expect(
    (await db.doc('convoyChats/private/messages/quote').get()).get('replyTo.textPreview'),
  ).toBe('PRIVATE');
  expect((await db.collection('adminAuditEvents').get()).size).toBe(1);
});
it('can resume quote cleanup from fresh evidence after reload, but refuses it while parent is visible', async () => {
  await expect(decide('cleanup', community)).rejects.toMatchObject({
    code: 'functions/failed-precondition',
  });
  await publicMessages.doc('parent').delete();
  await publicMessages
    .doc('later')
    .set({ text: 'Reply', replyTo: { messageId: 'parent', textPreview: 'old' } });
  const result = await decide('cleanup', community);
  expect(result.cleanup.redacted).toBe(1);
});
it('fresh canonical and identity authority applies to new decisions and receipt replay', async () => {
  const d = await review(),
    payload = {
      target,
      expectedActorUid: uid,
      expectedVersion: d.version,
      action: 'review',
      reason: 'Review',
      idempotencyKey: crypto.randomUUID(),
    };
  await call('admin-decidePublicModeration', payload);
  await db.doc(`users/${uid}`).update({ role: 'user' });
  for (const name of [
    'admin-listPublicReports',
    'admin-listMemberMessages',
    'admin-getPublicModerationCase',
  ])
    await expect(
      call(
        name,
        name === 'admin-listMemberMessages'
          ? { targetUid: member }
          : name === 'admin-getPublicModerationCase'
            ? target
            : {},
      ),
    ).rejects.toMatchObject({ code: 'functions/permission-denied' });
  await expect(call('admin-decidePublicModeration', payload)).rejects.toMatchObject({
    code: 'functions/permission-denied',
  });
  await db.doc(`users/${uid}`).update({ role: 'admin' });
  const key = 'a'.repeat(64);
  await db.doc(`carCommunityAccess/${uid}`).update({ googleKey: key });
  await db.doc(`carCommunityIdentityBlocks/${key}`).set({ blocked: true });
  await expect(call('admin-decidePublicModeration', payload)).rejects.toMatchObject({
    code: 'functions/permission-denied',
  });
  await db.doc(`carCommunityIdentityBlocks/${key}`).delete();
});
it('marks review and dismissal without changing the public message', async () => {
  await decide('review', community);
  expect((await reports.doc('public-report').get()).get('status')).toBe('under_review');
  const page = await call('admin-listPublicReports', { status: 'review' });
  expect(page.items.map((r: { id: string; reportId: string }) => r.reportId)).toContain(
    'public-report',
  );
  await decide('dismiss', community);
  expect((await publicMessages.doc('parent').get()).get('text')).toBe('Public body');
  expect((await reports.doc('public-report').get()).get('status')).toBe('dismissed');
});
it('serializes public reply snapshots with concurrent removal of their canonical parent', async () => {
  await db.doc('config/featureFlags').set({ chatReplies: true }, { merge: true });
  const d = await review(community);
  const removal = call('admin-decidePublicModeration', {
    target: community,
    expectedActorUid: uid,
    expectedVersion: d.version,
    action: 'remove',
    reason: 'Concurrent quote test',
    idempotencyKey: crypto.randomUUID(),
  });
  await Promise.all([
    removal,
    ...Array.from({ length: 3 }, (_, i) =>
      httpsCallable(
        functions,
        'communityChat-post',
      )({ text: `Racing reply ${i}`, replyToMessageId: 'parent', clientId: `race-reply-${i}` }),
    ),
  ]);
  expect((await publicMessages.where('replyTo.messageId', '==', 'parent').get()).size).toBe(0);
  for (let i = 0; i < 3; i++)
    expect((await publicMessages.doc(`race-reply-${i}`).get()).get('text')).toBe(
      `Racing reply ${i}`,
    );
});

it('rejects late send retries that reuse a removed public message id', async () => {
  await decide('remove', community);
  await expect(
    httpsCallable(functions, 'communityChat-post')({ text: 'Resurrected', clientId: 'parent' }),
  ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
  expect((await publicMessages.doc('parent').get()).exists).toBe(false);
});
