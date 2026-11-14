import { beforeAll, beforeEach, afterAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  initializeTestEnvironment,
  assertFails,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { Timestamp } from 'firebase-admin/firestore';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { MessageReactionEngine, eraseUserReactions } from './engine';
import { reactionAuthority } from './index';
import { parse, setSchema, type ReactionInput, type ReactionRoom } from './core';
import { cleanReactionParents } from './lifecycle';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, createUserWithEmailAndPassword } from 'firebase/auth';
import { getFunctions, connectFunctionsEmulator, httpsCallable } from 'firebase/functions';

const actor = 'reaction-actor',
  other = 'reaction-other';
const community: ReactionRoom = { scope: 'community', roomId: 'global' };
const parent = 'communityChat/global/messages/message';
const request = (uid: string) => ({ auth: { uid, token: {} } }) as CallableRequest;
const engine = (uid = actor) => new MessageReactionEngine(db, reactionAuthority(request(uid)));
const input = (extra: Partial<ReactionInput> = {}) =>
  parse(setSchema, {
    ...community,
    messageId: 'message',
    emojiId: '1f44d',
    expectedVersion: 0,
    operationId: 'reaction-operation-001',
    ...extra,
  });
let rules: RulesTestEnvironment;
beforeAll(async () => {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST ?? '127.0.0.1:8088').split(':');
  rules = await initializeTestEnvironment({
    projectId: process.env.GCLOUD_PROJECT ?? 'demo-chat-reactions',
    firestore: {
      host,
      port: Number(port),
      rules: readFileSync(resolve('../firebase/firestore.rules'), 'utf8'),
    },
  });
});

describe.runIf(!!process.env.FIREBASE_AUTH_EMULATOR_HOST)(
  'deployed callable contract through Auth and Functions emulators',
  () => {
    it('requires sign-in and invokes both named endpoints with a canonical authenticated actor', async () => {
      const projectId = process.env.GCLOUD_PROJECT ?? 'demo-test';
      const app = initializeApp({ projectId, apiKey: 'demo-key' }, `reaction-http-${Date.now()}`);
      try {
        const auth = getAuth(app);
        connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
          disableWarnings: true,
        });
        const functions = getFunctions(app, 'europe-west1');
        connectFunctionsEmulator(functions, '127.0.0.1', 5001);
        const readMine = httpsCallable(functions, 'chatReactions-getMine');
        await expect(readMine({ ...community, messageIds: ['message'] })).rejects.toMatchObject({
          code: 'functions/unauthenticated',
        });
        const credential = await createUserWithEmailAndPassword(
          auth,
          `reaction-${Date.now()}@example.com`,
          'fixture-password-123',
        );
        await db
          .doc(`users/${credential.user.uid}`)
          .set(
            { suspended: false, deleted: false, onboardingCompletedAt: Timestamp.now() },
            { merge: true },
          );
        await db
          .doc(`carCommunityAccess/${credential.user.uid}`)
          .set({ initialized: true }, { merge: true });
        const response = await httpsCallable(functions, 'chatReactions-set')(input());
        expect(response.data).toMatchObject({ messageId: 'message', emojiId: '1f44d', version: 1 });
        const mine = await readMine({ ...community, messageIds: ['message'] });
        expect(mine.data).toMatchObject({
          items: [{ messageId: 'message', emojiId: '1f44d', version: 1 }],
        });
      } finally {
        await deleteApp(app);
      }
    });
  },
);
afterAll(async () => {
  await rules.cleanup();
});
beforeEach(async () => {
  await rules.clearFirestore();
  const batch = db.batch();
  for (const uid of [actor, other]) {
    batch.set(db.doc(`users/${uid}`), {
      suspended: false,
      deleted: false,
      onboardingCompletedAt: Timestamp.now(),
    });
    batch.set(db.doc(`carCommunityAccess/${uid}`), { initialized: true });
  }
  batch.set(db.doc('carCommunityPolicy/access'), { enforceInitialized: true });
  batch.set(db.doc('config/featureFlags'), { chatReactions: true, chat: true });
  batch.set(db.doc(parent), {
    senderUid: other,
    text: 'Synthetic test message',
    createdAt: Timestamp.now(),
  });
  await batch.commit();
});

describe('server-only message reaction transactions', () => {
  it('sets, replaces and removes one choice; replay of an older receipt never resurrects it', async () => {
    const first = input();
    expect((await engine().set(actor, first)).version).toBe(1);
    expect((await engine().set(actor, first)).counts).toEqual({ '1f44d': 1 });
    await engine().set(
      actor,
      input({ emojiId: '2764-fe0f', expectedVersion: 1, operationId: 'reaction-operation-002' }),
    );
    await engine().set(
      actor,
      input({ emojiId: null, expectedVersion: 2, operationId: 'reaction-operation-003' }),
    );
    const replay = await engine().set(actor, first);
    expect(replay).toMatchObject({ emojiId: null, version: 3, counts: {} });
    expect((await db.doc(parent).get()).get('createdAt')).toBeInstanceOf(Timestamp);
    expect((await db.doc(parent).get()).get('text')).toBe('Synthetic test message');
    expect((await engine().getMine(actor, community, ['message'])).items).toEqual([
      { messageId: 'message', emojiId: null, version: 3 },
    ]);
  });
  it('rejects version conflicts and operation payload substitution', async () => {
    await engine().set(actor, input());
    await expect(engine().set(actor, input({ emojiId: '1f602' }))).rejects.toMatchObject({
      code: 'already-exists',
    });
    await expect(
      engine().set(actor, input({ operationId: 'reaction-operation-002' })),
    ).rejects.toMatchObject({ code: 'aborted' });
  });
  it('serializes two-device writes against one actor version', async () => {
    const results = await Promise.allSettled([
      engine().set(actor, input()),
      engine().set(actor, input({ emojiId: '1f602', operationId: 'reaction-operation-002' })),
    ]);
    expect(results.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    const counts = (await db.doc(parent).get()).get('reactionCounts') as Record<string, number>;
    expect(Object.values(counts).reduce((a, b) => a + b, 0)).toBe(1);
  });
  it('rechecks account, product restrictions, flag and canonical block edges on both mutation and replay', async () => {
    await engine().set(actor, input());
    for (const patch of [{ suspended: true }, { deleted: true }]) {
      await db.doc(`users/${actor}`).update(patch);
      await expect(engine().set(actor, input())).rejects.toBeDefined();
      await db.doc(`users/${actor}`).update({ suspended: false, deleted: false });
    }
    for (const path of [
      `userBlocks/${actor}/blocked/${other}`,
      `userBlocks/${other}/blocked/${actor}`,
    ]) {
      await db.doc(path).set({});
      await expect(engine().getMine(actor, community, ['message'])).rejects.toMatchObject({
        code: 'not-found',
      });
      await expect(engine().set(actor, input())).rejects.toMatchObject({ code: 'not-found' });
      await db.doc(path).delete();
    }
    await db.doc(`carCommunityAccess/${actor}`).update({ suspended: true });
    await expect(engine().set(actor, input())).rejects.toMatchObject({ code: 'not-found' });
    await db.doc(`carCommunityAccess/${actor}`).update({ suspended: false });
    await db.doc('config/featureFlags').update({ chatReactions: false });
    await expect(engine().set(actor, input())).rejects.toMatchObject({
      code: 'failed-precondition',
    });
  });
  it('honors a public writing pause while allowing only own removal under current read access', async () => {
    await engine().set(actor, input());
    await db
      .doc(`carCommunityChatRestrictions/${actor}`)
      .set({ pauseUntil: Timestamp.fromMillis(Date.now() + 60_000) });
    await expect(
      engine().set(
        actor,
        input({ emojiId: '1f602', expectedVersion: 1, operationId: 'reaction-operation-002' }),
      ),
    ).rejects.toMatchObject({ code: 'permission-denied' });
    expect(
      (
        await engine().set(
          actor,
          input({ emojiId: null, expectedVersion: 1, operationId: 'reaction-operation-003' }),
        )
      ).counts,
    ).toEqual({});
  });
  it('requires DM membership/friendship, accepted convoy membership and event RSVP in the same transaction', async () => {
    const cases: [ReactionRoom, string, Record<string, unknown>][] = [
      [
        { scope: 'dm', roomId: 'pair' },
        'conversations/pair/messages/message',
        { senderUid: other, text: 'Private fixture', createdAt: Timestamp.now() },
      ],
      [
        { scope: 'convoy', roomId: 'convoy' },
        'convoyChats/convoy/messages/message',
        { senderUid: other, text: 'Convoy fixture', createdAt: Timestamp.now() },
      ],
      [
        { scope: 'event', roomId: 'event' },
        'events/event/messages/message',
        {
          authorUserId: other,
          message: 'Event fixture',
          moderationState: 'visible',
          createdAt: Timestamp.now(),
        },
      ],
    ];
    await db.doc('conversations/pair').set({ members: [actor, other] });
    await db.doc(`users/${actor}/friends/${other}`).set({});
    await db
      .doc('convoys/convoy')
      .set({ memberUids: [actor], members: { [actor]: { inviteStatus: 'accepted' } } });
    await db.doc('events/event').set({ status: 'published' });
    await db.doc(`events/event/rsvps/${actor}`).set({ status: 'going' });
    for (const [room, path, data] of cases) {
      await db.doc(path).set(data);
      const payload = input({ ...room, operationId: `reaction-${room.scope}-operation-01` });
      await engine().set(actor, payload);
      await expect(engine(other).getMine(other, room, ['message'])).rejects.toBeDefined();
      if (room.scope === 'dm') await db.doc(`users/${actor}/friends/${other}`).delete();
      if (room.scope === 'convoy') await db.doc('convoys/convoy').update({ memberUids: [] });
      if (room.scope === 'event')
        await db.doc(`events/event/rsvps/${actor}`).update({ status: 'not_going' });
      await expect(engine().set(actor, payload)).rejects.toBeDefined();
    }
    await expect(
      engine().set(actor, input({ scope: 'club', roomId: 'club' })),
    ).rejects.toMatchObject({ code: 'not-found' });
  });
  it('denies all direct SDK access to records, receipts, budgets and parent writes, even to the owner', async () => {
    await engine().set(actor, input());
    const context = rules.authenticatedContext(actor).firestore();
    for (const collection of [
      'chatMessageReactions',
      'chatReactionOperations',
      'chatReactionRateLimits',
      'chatReactionCleanup',
    ]) {
      const row = (await db.collection(collection).limit(1).get()).docs[0];
      await assertFails(getDoc(doc(context, row?.ref.path ?? `${collection}/missing`)));
      await assertFails(setDoc(doc(context, `${collection}/forged`), { uid: actor }));
    }
    await assertFails(
      setDoc(doc(context, parent), { reactionCounts: { '1f44d': 100 } }, { merge: true }),
    );
  });
  it('erases own reactions and private receipts idempotently, including orphaned parent records', async () => {
    await engine().set(actor, input());
    await eraseUserReactions(db, actor);
    await eraseUserReactions(db, actor);
    expect((await db.doc(parent).get()).get('reactionCounts')).toEqual({});
    await engine().set(actor, input({ operationId: 'reaction-operation-002' }));
    const epoch = (await db.collection('chatMessageReactions').limit(1).get()).docs[0]!.get(
      'parentEpoch',
    );
    await db.doc(parent).delete();
    await expect(
      engine().set(
        actor,
        input({ emojiId: null, expectedVersion: 1, operationId: 'reaction-operation-003' }),
      ),
    ).rejects.toMatchObject({ code: 'not-found' });
    await db.doc('chatReactionCleanup/job').set({ parentPath: parent, parentEpoch: epoch });
    await cleanReactionParents(db);
    expect((await db.collection('chatMessageReactions').get()).empty).toBe(true);
    expect((await db.doc(parent).get()).exists).toBe(false);
  });
  it('shares mutation and request quotas between rooms without charging duplicate commits twice', async () => {
    await engine().set(actor, input());
    const rate = (await db.doc(`chatReactionRateLimits/${actor}`).get()).data()!;
    expect(rate.changes).toBe(1);
    await engine().set(actor, input());
    expect((await db.doc(`chatReactionRateLimits/${actor}`).get()).get('changes')).toBe(1);
    await db.doc(`chatReactionRateLimits/${actor}`).update({ changes: 20 });
    await expect(
      engine().set(
        actor,
        input({ emojiId: null, expectedVersion: 1, operationId: 'reaction-operation-002' }),
      ),
    ).rejects.toMatchObject({ code: 'resource-exhausted' });
    expect((await db.doc(parent).get()).get('reactionCounts')).toEqual({ '1f44d': 1 });
  });
  it('rejects exhausted read quotas before resolving any supplied target', async () => {
    await db
      .doc(`chatReactionRateLimits/${actor}`)
      .set({ windowStart: Date.now(), requests: 120, changes: 0 });
    await expect(engine().getMine(actor, community, ['missing-target'])).rejects.toMatchObject({
      code: 'resource-exhausted',
    });
    expect((await db.doc(`chatReactionRateLimits/${actor}`).get()).get('requests')).toBe(120);
  });
});
