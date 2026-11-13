/** Real authenticated callable + Firestore-rules regressions for Android's
 * combined public-chat moderation. Run against the complete demo-test suite;
 * this file neither starts emulators nor clears shared collections.
 */
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { createHash, randomUUID } from 'node:crypto';
import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  getAuth,
  signInWithEmailAndPassword,
  signOut,
  type Auth,
} from 'firebase/auth';
import {
  collection,
  connectFirestoreEmulator,
  deleteDoc,
  doc,
  getDocFromServer,
  getDocs,
  getFirestore,
  setDoc,
  type Firestore,
} from 'firebase/firestore';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps, initializeApp as initializeServerApp } from 'firebase-admin/app';
import { getAuth as getServerAuth } from 'firebase-admin/auth';
import { getFirestore as getServerFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const PROJECT = 'demo-test';
const [authHost, authPort] = process.env.FIREBASE_AUTH_EMULATOR_HOST!.split(':');
const [firestoreHost, firestorePort] = process.env.FIRESTORE_EMULATOR_HOST!.split(':');
const functionsPort = Number(process.env.KCC_FUNCTIONS_EMULATOR_PORT ?? 5001);
const prefix = `android-chat-${randomUUID().slice(0, 8)}`;
const onboardingTime = Timestamp.fromMillis(Date.now() - 86400_000);
const serverApp =
  getApps().find((app) => app.name === 'android-chat-moderation-tests') ??
  initializeServerApp({ projectId: PROJECT }, 'android-chat-moderation-tests');
const db = getServerFirestore(serverApp);
const authAdmin = getServerAuth(serverApp);

interface Person {
  uid: string;
  email: string;
  password: string;
  role: 'user' | 'admin' | 'owner';
  claim: boolean;
}
const person = (name: string, role: Person['role'], claim = role !== 'user'): Person => ({
  uid: `${prefix}-${name}`,
  email: `${prefix}-${name}@example.com`,
  password: 'Local-chat-moderation-12345',
  role,
  claim,
});
const moderator = person('admin', 'admin');
const member = person('member', 'user');
const peer = person('peer', 'user');
const owner = person('owner', 'owner');
const unclaimedAdmin = person('unclaimed', 'admin', false);
const people = [moderator, member, peer, owner, unclaimedAdmin];

interface Target {
  source: 'community' | 'event';
  eventId?: string;
  messageId: string;
}
interface Context {
  actorUid: string;
  authorUid: string;
  version: string;
  message: { authorName: string; text: string };
  pauseUntilMillis: number | null;
  serverNowMillis: number;
  maxPauseDays: number;
  canRemove: boolean;
  canPause: boolean;
  canWarn: boolean;
}
interface Decision {
  alreadyApplied: boolean;
  warningId: string | null;
  pauseUntilMillis: number | null;
  cleanup: { redacted: number; hasMore: boolean };
}
interface Fixture {
  target: Target;
  reason: string;
  text: string;
  eventId: string;
  convoyId: string;
}
let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
let firestore: Firestore;

const messageRef = (target: Target) =>
  target.source === 'event'
    ? db.doc(`events/${target.eventId}/messages/${target.messageId}`)
    : db.doc(`communityChat/global/messages/${target.messageId}`);
const restrictionRef = (uid = member.uid) => db.doc(`carCommunityChatRestrictions/${uid}`);
const warnings = (uid = member.uid) => db.collection(`userWarnings/${uid}/warnings`);

async function call<T = Decision>(name: string, data: unknown = {}): Promise<T> {
  return (await httpsCallable<unknown, T>(functions, name)(data)).data;
}
async function signIn(user: Person) {
  // Emulator worker reloads can briefly interrupt the local Auth transport.
  // Retry only setup network failures, never authorization or callable failures.
  for (let attempt = 0; ; attempt++) {
    try {
      if (auth.currentUser?.uid !== user.uid)
        await signInWithEmailAndPassword(auth, user.email, user.password);
      await auth.currentUser!.getIdToken();
      return;
    } catch (error) {
      if (attempt >= 2 || (error as { code?: string }).code !== 'auth/network-request-failed')
        throw error;
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
}
const context = async (target: unknown) =>
  (await call('admin-getChatModerationContext', { target })) as unknown as Context;
const decide = (data: unknown) => call('admin-moderateChatMessage', data);

async function fixture(source: Target['source'] = 'community', authorUid = member.uid) {
  const id = randomUUID();
  const target: Target = {
    source,
    messageId: `${prefix}-${id}`,
    ...(source === 'event' ? { eventId: `${prefix}-event-${id}` } : {}),
  };
  const value: Fixture = {
    target,
    reason: `Local moderation ${id}`,
    text: 'Vi ses vid stationen.',
    eventId: target.eventId ?? `${prefix}-event-${id}`,
    convoyId: `${prefix}-convoy-${id}`,
  };
  const batch = db.batch();
  batch.set(messageRef(target), {
    ...(source === 'event'
      ? {
          authorUserId: authorUid,
          authorDisplayName: 'Member',
          message: value.text,
          moderationState: 'visible',
        }
      : { senderUid: authorUid, senderDisplayName: 'Member', text: value.text }),
    // Historical evidence must not consume the event rolling send window.
    createdAt: Timestamp.fromMillis(Date.now() - 120_000),
  });
  batch.set(db.doc(`events/${value.eventId}`), { status: 'published' });
  batch.set(db.doc(`events/${value.eventId}/rsvps/${member.uid}`), { status: 'going' });
  batch.set(db.doc(`convoys/${value.convoyId}`), {
    memberUids: [member.uid, peer.uid],
    members: {
      [member.uid]: { inviteStatus: 'accepted' },
      [peer.uid]: { inviteStatus: 'accepted' },
    },
    status: 'active',
  });
  await batch.commit();
  return value;
}

async function payload(f: Fixture, extra: Record<string, unknown> = {}) {
  const c = await context(f.target);
  return {
    target: f.target,
    expectedActorUid: moderator.uid,
    expectedVersion: c.version,
    action: 'remove',
    reason: f.reason,
    warn: false,
    pause: null,
    idempotencyKey: randomUUID(),
    ...extra,
  };
}
async function auditCount(reason: string) {
  return (await db.collection('adminAuditEvents').where('reason', '==', reason).get()).size;
}
async function assertUnchanged(f: Fixture) {
  const message = await messageRef(f.target).get();
  expect(message.exists).toBe(true);
  expect(message.get(f.target.source === 'event' ? 'message' : 'text')).toBe(f.text);
  expect((await restrictionRef().get()).exists).toBe(false);
  expect((await warnings().get()).empty).toBe(true);
  expect(await auditCount(f.reason)).toBe(0);
}
async function assertRemoved(f: Fixture) {
  const message = await messageRef(f.target).get();
  if (f.target.source === 'event')
    expect(message.data()).toMatchObject({ message: '', moderationState: 'removed' });
  else {
    expect(message.exists).toBe(false);
    expect((await db.doc(`communityChatDeletedMessages/${f.target.messageId}`).get()).exists).toBe(
      true,
    );
  }
}

beforeAll(async () => {
  if (
    process.env.GCLOUD_PROJECT !== PROJECT ||
    !/^(127\.0\.0\.1|localhost):\d+$/.test(process.env.FIRESTORE_EMULATOR_HOST ?? '') ||
    !/^(127\.0\.0\.1|localhost):\d+$/.test(process.env.FIREBASE_AUTH_EMULATOR_HOST ?? '')
  )
    throw Error('Only the complete loopback demo-test Emulator Suite may run this test.');
  app = initializeApp(
    { projectId: PROJECT, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    `${prefix}-client`,
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${authHost}:${authPort}`, { disableWarnings: true });
  functions = getFunctions(app, 'europe-west1');
  connectFunctionsEmulator(functions, '127.0.0.1', functionsPort);
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, firestoreHost!, Number(firestorePort));
  await db.doc('carCommunityPolicy/access').set({ enforceInitialized: true });
  await Promise.all(
    people.map(async (p) => {
      await authAdmin.createUser({ uid: p.uid, email: p.email, password: p.password });
      await authAdmin.setCustomUserClaims(p.uid, { admin: p.claim });
      // Let the normal on-create profile finish before assigning test roles.
      const deadline = Date.now() + 30_000;
      while (!(await db.doc(`users/${p.uid}`).get()).exists) {
        if (Date.now() > deadline) throw Error(`Auth profile trigger did not provision ${p.uid}`);
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
    }),
  );
}, 120_000);

beforeEach(async () => {
  const batch = db.batch();
  for (const p of people) {
    batch.set(
      db.doc(`users/${p.uid}`),
      {
        role: p.role,
        displayName: p.uid,
        activeMember: true,
        suspended: false,
        deleted: false,
        onboardingCompletedAt: onboardingTime,
      },
      { merge: true },
    );
    batch.set(db.doc(`carCommunityAccess/${p.uid}`), {
      initialized: true,
      suspended: false,
      inheritedBlocked: false,
      explicitBlacklistId: null,
      version: 1,
    });
    batch.delete(restrictionRef(p.uid));
    batch.delete(db.doc(`chatSendGuards/${p.uid}`));
  }
  batch.set(db.doc(`users/${member.uid}/friends/${peer.uid}`), { uid: peer.uid });
  batch.set(db.doc(`users/${peer.uid}/friends/${member.uid}`), { uid: member.uid });
  await batch.commit();
  for (const p of people) await db.recursiveDelete(db.doc(`userWarnings/${p.uid}`));
  await signIn(moderator);
});

afterAll(async () => {
  if (app) await deleteApp(app);
  // All fixture IDs are scoped to this run; do not clear other suites' state.
});

describe('Android public chat moderation authorization and atomic decisions', () => {
  it('requires an authenticated admin claim AND a current admin role', async () => {
    const f = await fixture();
    const command = await payload(f, { warn: true, pause: { amount: 5, unit: 'minutes' } });
    await signOut(auth);
    await expect(context(f.target)).rejects.toMatchObject({ code: 'functions/unauthenticated' });
    await expect(decide(command)).rejects.toMatchObject({ code: 'functions/unauthenticated' });
    await signIn(member);
    await expect(context(f.target)).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await expect(decide({ ...command, admin: true, role: 'owner' })).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    await signIn(unclaimedAdmin);
    await expect(context(f.target)).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await expect(decide(command)).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await assertUnchanged(f);
  });

  it('rejects a stale admin token after persisted role downgrade, including receipt replay', async () => {
    const f = await fixture();
    const command = await payload(f, {
      action: 'pause',
      warn: true,
      pause: { amount: 2, unit: 'days' },
    });
    const first = await decide(command);
    const auditBefore = await auditCount(f.reason);
    const restrictionBefore = (await restrictionRef().get()).data();
    expect((await auth.currentUser!.getIdTokenResult()).claims.admin).toBe(true);
    // Keep the valid, already-issued admin token while revoking server authority.
    await db.doc(`users/${moderator.uid}`).update({ role: 'user' });
    await expect(context(f.target)).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await expect(decide(command)).rejects.toMatchObject({ code: 'functions/permission-denied' });
    await expect(decide({ ...command, idempotencyKey: randomUUID() })).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    expect(first.warningId).toBeTruthy();
    expect((await warnings().get()).size).toBe(1);
    expect((await restrictionRef().get()).data()).toEqual(restrictionBefore);
    expect(await auditCount(f.reason)).toBe(auditBefore);
  });

  it.each(['suspended', 'deleted', 'product-suspended', 'identity-blocked'])(
    'rejects an admin whose current access becomes %s',
    async (restriction) => {
      const f = await fixture();
      const command = await payload(f, { warn: true, pause: { amount: 1, unit: 'days' } });
      if (restriction === 'suspended' || restriction === 'deleted')
        await db.doc(`users/${moderator.uid}`).update({ [restriction]: true });
      else if (restriction === 'product-suspended')
        await db.doc(`carCommunityAccess/${moderator.uid}`).update({ suspended: true });
      else {
        const identity = createHash('sha256').update(`${prefix}-blocked-identity`).digest('hex');
        await db.doc(`carCommunityIdentityBlocks/${identity}`).set({ blocked: true, version: 1 });
        await db.doc(`carCommunityAccess/${moderator.uid}`).update({ googleKey: identity });
      }
      await expect(context(f.target)).rejects.toMatchObject({
        code: 'functions/permission-denied',
      });
      await expect(decide(command)).rejects.toMatchObject({ code: 'functions/permission-denied' });
      await assertUnchanged(f);
    },
  );

  it('rejects actor substitution and client-supplied target identities', async () => {
    const f = await fixture();
    const command = await payload(f, { warn: true });
    await expect(decide({ ...command, expectedActorUid: peer.uid })).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    await expect(decide({ ...command, targetUid: peer.uid })).rejects.toMatchObject({
      code: 'functions/invalid-argument',
    });
    await expect(decide({ ...command, authorUid: peer.uid })).rejects.toMatchObject({
      code: 'functions/invalid-argument',
    });
    await assertUnchanged(f);
    expect((await warnings(peer.uid).get()).empty).toBe(true);
  });

  it.each([
    { source: 'dm', conversationId: 'private', messageId: 'known' },
    { source: 'convoy', convoyId: 'private', messageId: 'known' },
    { source: 'club', clubId: 'private', messageId: 'known' },
    { source: 'community', messageId: '../private' },
    { source: 'event', eventId: '../private', messageId: 'known' },
    { source: 'community', eventId: 'unexpected', messageId: 'known' },
    { source: 'event', messageId: 'known' },
    { source: 'community', messageId: 'known', path: 'conversations/private/messages/known' },
  ])('allowlists the public locator and rejects $source / $messageId', async (badTarget) => {
    const f = await fixture();
    const command = await payload(f);
    await expect(context(badTarget)).rejects.toMatchObject({ code: 'functions/invalid-argument' });
    await expect(decide({ ...command, target: badTarget })).rejects.toMatchObject({
      code: 'functions/invalid-argument',
    });
    await assertUnchanged(f);
  });

  it('never sanctions self or owner as a side effect of otherwise permitted deletion', async () => {
    for (const protectedPerson of [moderator, owner]) {
      const f = await fixture('event', protectedPerson.uid);
      const c = await context(f.target);
      expect(c).toMatchObject({ canRemove: true, canWarn: false, canPause: false });
      const command = await payload(f);
      for (const extras of [
        { warn: true },
        { pause: { amount: 1, unit: 'days' } },
        { warn: true, pause: { amount: 1, unit: 'days' } },
      ]) {
        await expect(decide({ ...command, ...extras })).rejects.toMatchObject({
          code: expect.stringMatching(/^functions\/(permission-denied|failed-precondition)$/),
        });
        expect((await messageRef(f.target).get()).get('message')).toBe(f.text);
        expect((await restrictionRef(protectedPerson.uid).get()).exists).toBe(false);
        expect((await warnings(protectedPerson.uid).get()).empty).toBe(true);
        expect(await auditCount(f.reason)).toBe(0);
      }
      await decide(command);
      await assertRemoved(f);
    }
  });

  it.each(
    (['community', 'event'] as const).flatMap((source) =>
      [false, true].flatMap((warn) => [false, true].map((pause) => ({ source, warn, pause }))),
    ),
  )(
    'atomically removes $source with warning=$warn and pause=$pause; retry applies once',
    async (v) => {
      const f = await fixture(v.source);
      const command = await payload(f, {
        warn: v.warn,
        pause: v.pause ? { amount: 7, unit: 'minutes' } : null,
      });
      const start = Date.now();
      const first = await decide(command);
      expect(first.alreadyApplied).toBe(false);
      await assertRemoved(f);
      const storedWarnings = await warnings().get();
      expect(storedWarnings.size).toBe(v.warn ? 1 : 0);
      if (v.warn) {
        expect(first.warningId).toBeTruthy();
        expect(storedWarnings.docs[0]!.data()).toMatchObject({
          reason: f.reason,
          actorUserId: moderator.uid,
        });
      } else expect(first.warningId).toBeNull();
      const restriction = await restrictionRef().get();
      expect(restriction.exists).toBe(v.pause);
      if (v.pause) {
        const until = (restriction.get('pauseUntil') as Timestamp).toMillis();
        expect(until).toBeGreaterThanOrEqual(start + 7 * 60_000);
        expect(until).toBeLessThanOrEqual(Date.now() + 7 * 60_000);
        expect(first.pauseUntilMillis).toBe(until);
        expect(restriction.get('reason')).toBe(f.reason);
      } else expect(first.pauseUntilMillis).toBeNull();
      const firstAuditCount = await auditCount(f.reason);
      expect(firstAuditCount).toBeGreaterThan(0);
      const repeat = await decide(command);
      expect(repeat).toMatchObject({
        alreadyApplied: true,
        warningId: first.warningId,
        pauseUntilMillis: first.pauseUntilMillis,
      });
      expect((await warnings().get()).size).toBe(storedWarnings.size);
      expect((await restrictionRef().get()).data()).toEqual(restriction.data());
      expect(await auditCount(f.reason)).toBe(firstAuditCount);
      await expect(decide({ ...command, reason: `${f.reason} changed` })).rejects.toMatchObject({
        code: 'functions/failed-precondition',
      });
    },
  );

  it('pauses for a day duration without deleting the source and can optionally warn', async () => {
    const f = await fixture();
    const command = await payload(f, {
      action: 'pause',
      warn: true,
      pause: { amount: 2, unit: 'days' },
    });
    const start = Date.now();
    const result = await decide(command);
    expect(result.pauseUntilMillis).toBeGreaterThanOrEqual(start + 2 * 86400_000);
    expect(result.pauseUntilMillis).toBeLessThanOrEqual(Date.now() + 2 * 86400_000);
    expect((await messageRef(f.target).get()).get('text')).toBe(f.text);
    expect((await warnings().get()).size).toBe(1);
  });

  it.each([false, true])(
    'does not redeliver a late receipt after its pause/warning is inactive (warning=%s)',
    async (warn) => {
      const f = await fixture();
      const command = await payload(f, {
        action: 'pause',
        warn,
        pause: { amount: 5, unit: 'minutes' },
      });
      const first = await decide(command);
      const operationId = createHash('sha256')
        .update(JSON.stringify(['chat', moderator.uid, command.idempotencyKey]))
        .digest('hex');
      const notice = db.doc(`notifications/${member.uid}/items/chat-moderation-${operationId}`);
      expect((await notice.get()).exists).toBe(true);
      if (warn)
        await warnings().doc(first.warningId!).update({ revokedAt: Timestamp.now(), version: 2 });
      else await restrictionRef().update({ pauseUntil: Timestamp.fromMillis(Date.now() - 1) });
      await notice.delete();
      const auditBefore = await auditCount(f.reason);
      const repeat = await decide(command);
      expect(repeat).toMatchObject({ alreadyApplied: true, notificationStatus: 'skipped' });
      expect((await notice.get()).exists).toBe(false);
      expect(await auditCount(f.reason)).toBe(auditBefore);
    },
  );

  it.each([
    { amount: 0, unit: 'minutes' },
    { amount: -1, unit: 'days' },
    { amount: 1.5, unit: 'minutes' },
    { amount: '2', unit: 'days' },
    { amount: 31, unit: 'days' },
    { amount: 43201, unit: 'minutes' },
    { amount: Number.MAX_SAFE_INTEGER, unit: 'days' },
    { amount: 5, unit: 'hours' },
    { amount: 1, unit: 'minutes', pauseUntilMillis: 1 },
  ])('rejects invalid duration $amount $unit without partial removal or warning', async (pause) => {
    const f = await fixture();
    const command = await payload(f, { warn: true, pause });
    await expect(decide(command)).rejects.toMatchObject({ code: 'functions/invalid-argument' });
    await assertUnchanged(f);
  });

  it('requires a reason, a pause for the pause action, and a bounded idempotency key', async () => {
    const f = await fixture();
    const command = await payload(f);
    for (const changes of [
      { reason: '   ' },
      { action: 'pause', pause: null },
      { action: 'warn' },
      { action: 'mute' },
      { warn: 'true' },
      { idempotencyKey: 'short' },
      { idempotencyKey: '../arbitrary/path' },
      { pauseUntilMillis: Date.now() + 86400_000 },
    ])
      await expect(decide({ ...command, ...changes })).rejects.toMatchObject({
        code: 'functions/invalid-argument',
      });
    await assertUnchanged(f);
  });

  it('rejects a changed message, changed author and a newer pause version before mutation', async () => {
    const f = await fixture();
    const command = await payload(f, { warn: true, pause: { amount: 5, unit: 'minutes' } });
    await messageRef(f.target).update({ text: 'A different message' });
    await expect(decide(command)).rejects.toMatchObject({ code: 'functions/failed-precondition' });
    const refreshed = await payload(f, { warn: true, pause: { amount: 5, unit: 'minutes' } });
    await messageRef(f.target).update({ senderUid: peer.uid });
    await expect(decide(refreshed)).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    expect((await warnings(peer.uid).get()).empty).toBe(true);
    await messageRef(f.target).update({ senderUid: member.uid });
    const olderPause = await payload(f, { action: 'pause', pause: { amount: 1, unit: 'minutes' } });
    await restrictionRef().set({
      pauseUntil: Timestamp.fromMillis(Date.now() + 86400_000),
      reason: 'Newer independent decision',
      version: 42,
      updatedAt: Timestamp.now(),
    });
    await expect(decide(olderPause)).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    expect((await restrictionRef().get()).get('version')).toBe(42);
    expect((await warnings().get()).empty).toBe(true);
    expect(await auditCount(f.reason)).toBe(0);
  });

  it('serializes two combined decisions using the same reviewed message', async () => {
    const f = await fixture('event');
    const command = await payload(f, { warn: true, pause: { amount: 15, unit: 'minutes' } });
    const results = await Promise.allSettled([
      decide(command),
      decide({ ...command, idempotencyKey: randomUUID() }),
    ]);
    expect(results.filter((r) => r.status === 'fulfilled')).toHaveLength(1);
    const failure = results.find((r) => r.status === 'rejected');
    expect(failure).toMatchObject({
      status: 'rejected',
      reason: { code: 'functions/failed-precondition' },
    });
    expect((await warnings().get()).size).toBe(1);
    expect((await restrictionRef().get()).get('version')).toBe(1);
    await assertRemoved(f);
  });

  it('redacts community reply quotes after deletion and does not inspect private quotes', async () => {
    const f = await fixture();
    const publicReply = db.doc(`communityChat/global/messages/${prefix}-reply-${randomUUID()}`);
    const privateReply = db.doc(`convoyChats/${f.convoyId}/messages/${randomUUID()}`);
    await publicReply.set({
      senderUid: peer.uid,
      text: 'Reply body stays',
      createdAt: Timestamp.now(),
      replyTo: { messageId: f.target.messageId, senderUid: member.uid, textPreview: f.text },
    });
    await privateReply.set({ replyTo: { messageId: f.target.messageId, textPreview: 'PRIVATE' } });
    await decide(await payload(f));
    expect((await publicReply.get()).get('text')).toBe('Reply body stays');
    expect((await publicReply.get()).get('replyTo.textPreview')).not.toBe(f.text);
    expect((await privateReply.get()).get('replyTo.textPreview')).toBe('PRIVATE');
  });
});

describe('public-only writing pause and client rules', () => {
  it('denies new Community/event sends but still actually sends PM and convoy messages', async () => {
    const f = await fixture();
    const result = await decide(
      await payload(f, { action: 'pause', pause: { amount: 15, unit: 'minutes' } }),
    );
    await signIn(member);
    const publicId = randomUUID();
    const eventId = randomUUID();
    const pausedError = {
      code: 'functions/permission-denied',
      details: { reason: 'chat-write-paused', pauseUntilMillis: result.pauseUntilMillis },
    };
    await expect(
      call('communityChat-post', { text: 'Denied public send', clientId: publicId }),
    ).rejects.toMatchObject(pausedError);
    await expect(
      call('events-postChatMessage', {
        eventId: f.eventId,
        message: 'Denied event send',
        clientId: eventId,
      }),
    ).rejects.toMatchObject(pausedError);
    expect((await db.doc(`communityChat/global/messages/${publicId}`).get()).exists).toBe(false);
    expect((await db.doc(`events/${f.eventId}/messages/${eventId}`).get()).exists).toBe(false);
    // Failed public sends do not consume the shared flood budget.
    expect((await db.doc(`chatSendGuards/${member.uid}`).get()).exists).toBe(false);
    const dmId = randomUUID();
    const convoyId = randomUUID();
    const direct = await call<{ conversationId: string; messageId: string }>('dm-sendMessage', {
      toUid: peer.uid,
      text: 'PM remains available',
      clientId: dmId,
    });
    expect(direct.messageId).toBe(dmId);
    expect(
      (await db.doc(`conversations/${direct.conversationId}/messages/${dmId}`).get()).get('text'),
    ).toBe('PM remains available');
    const convoy = await call<{ messageId: string }>('convoyChat-post', {
      convoyId: f.convoyId,
      text: 'Convoy remains available',
      clientId: convoyId,
    });
    expect(convoy.messageId).toBe(convoyId);
    expect((await db.doc(`convoyChats/${f.convoyId}/messages/${convoyId}`).get()).get('text')).toBe(
      'Convoy remains available',
    );
    // Reading remains allowed during this writing-only restriction.
    const publicRead = await getDocFromServer(doc(firestore, messageRef(f.target).path));
    expect(publicRead.get('text')).toBe(f.text);
  });

  it('uses stored server expiry; no TTL deletion is needed before public writes resume', async () => {
    const f = await fixture();
    await decide(await payload(f, { action: 'pause', pause: { amount: 1, unit: 'days' } }));
    await signIn(member);
    // A client-supplied claim of expiry cannot change the authoritative record.
    await expect(
      call('communityChat-post', {
        text: 'Forged clock',
        clientId: randomUUID(),
        nowMillis: Date.now() + 40 * 86400_000,
      }),
    ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
    await expect(
      call('communityChat-post', { text: 'Still paused', clientId: randomUUID() }),
    ).rejects.toMatchObject({ code: 'functions/permission-denied' });
    // This changes only the SDK client's wall clock. The Functions worker is
    // a different process and must continue to use its own server clock.
    const clientClock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 40 * 86400_000);
    try {
      await expect(
        call('communityChat-post', { text: 'Client clock in future', clientId: randomUUID() }),
      ).rejects.toMatchObject({
        code: 'functions/permission-denied',
        details: { reason: 'chat-write-paused' },
      });
    } finally {
      clientClock.mockRestore();
    }
    await restrictionRef().update({ pauseUntil: Timestamp.fromMillis(Date.now() - 1) });
    const publicId = randomUUID();
    const eventId = randomUUID();
    await call('communityChat-post', { text: 'After expiry', clientId: publicId });
    await call('events-postChatMessage', {
      eventId: f.eventId,
      message: 'Event after expiry',
      clientId: eventId,
    });
    expect((await db.doc(`communityChat/global/messages/${publicId}`).get()).get('text')).toBe(
      'After expiry',
    );
    expect((await db.doc(`events/${f.eventId}/messages/${eventId}`).get()).get('message')).toBe(
      'Event after expiry',
    );
    expect((await restrictionRef().get()).exists).toBe(true);
  });

  it('returns only the signed-in member status with server time and rejects target spoofing', async () => {
    const f = await fixture();
    const decision = await decide(
      await payload(f, { action: 'pause', pause: { amount: 1, unit: 'days' } }),
    );
    await signIn(member);
    const before = Date.now();
    const status = await call<{
      pauseUntilMillis: number | null;
      serverNowMillis: number;
      reason: string;
    }>('moderation-getMyChatWriteStatus');
    expect(status.pauseUntilMillis).toBe(decision.pauseUntilMillis);
    expect(status.reason).toBe(f.reason);
    expect(status.serverNowMillis).toBeGreaterThanOrEqual(before);
    expect(status.serverNowMillis).toBeLessThanOrEqual(Date.now());
    expect(Object.keys(status).sort()).toEqual(['pauseUntilMillis', 'reason', 'serverNowMillis']);
    for (const fields of [{ uid: peer.uid }, { targetUid: peer.uid }, { admin: true }])
      await expect(call('moderation-getMyChatWriteStatus', fields)).rejects.toMatchObject({
        code: 'functions/invalid-argument',
      });
    await signIn(peer);
    expect(
      (await call<{ pauseUntilMillis: number | null }>('moderation-getMyChatWriteStatus'))
        .pauseUntilMillis,
    ).toBeNull();
    await signOut(auth);
    await expect(call('moderation-getMyChatWriteStatus')).rejects.toMatchObject({
      code: 'functions/unauthenticated',
    });
  });

  it('allows only own restriction GET and denies forged restrictions, audit or message writes', async () => {
    const f = await fixture();
    await decide(await payload(f, { action: 'pause', pause: { amount: 1, unit: 'minutes' } }));
    await signIn(member);
    const own = doc(firestore, restrictionRef().path);
    const status = await getDocFromServer(own);
    expect(status.get('pauseUntil')).toBeTruthy();
    expect(status.get('reason')).toBe(f.reason);
    expect(status.get('actorUid')).toBeUndefined();
    await expect(setDoc(own, { pauseUntil: null })).rejects.toMatchObject({
      code: 'permission-denied',
    });
    await expect(deleteDoc(own)).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(
      getDocs(collection(firestore, 'carCommunityChatRestrictions')),
    ).rejects.toMatchObject({
      code: 'permission-denied',
    });
    await expect(
      setDoc(doc(firestore, `adminAuditEvents/${prefix}-forged`), {
        adminId: moderator.uid,
        action: 'publicChat.remove',
      }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await expect(
      setDoc(doc(firestore, `communityChat/global/messages/${prefix}-forged`), {
        senderUid: member.uid,
        text: 'Bypass function',
      }),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    await signIn(peer);
    await expect(getDocFromServer(own)).rejects.toMatchObject({ code: 'permission-denied' });
    await signIn(moderator);
    await expect(setDoc(own, { pauseUntil: null })).rejects.toMatchObject({
      code: 'permission-denied',
    });
    await signOut(auth);
    await expect(getDocFromServer(own)).rejects.toMatchObject({ code: 'permission-denied' });
  });
});
