/** Bounded transaction-only suite. Parent supplies a Firestore slot; never starts
 * emulators, Auth, Functions, Android or production clients. Product access's
 * unchanged provider/RTDB gate is disabled ONLY in this dedicated demo project.
 */
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import type { CallableRequest } from 'firebase-functions/v2/https';
import type { Firestore } from 'firebase-admin/firestore';
import { afterEach, beforeAll, describe, expect, test, vi } from 'vitest';

const enabled =
  process.env.KCC_TEST_NO_CLOUD === '1' &&
  process.env.GCLOUD_PROJECT === 'demo-chat-flood' &&
  /^(127\.0\.0\.1|localhost):\d+$/.test(process.env.FIRESTORE_EMULATOR_HOST ?? '');
const suite = enabled ? describe : describe.skip;
suite('chat flood real Firestore transactions', () => {
  let db: Firestore;
  let community: typeof import('../chatchannels/communityChat');
  let convoy: typeof import('../chatchannels/convoyChat');
  let dm: typeof import('../dm/manageDirectMessages');
  let event: typeof import('../events/postChatMessage');
  let Timestamp: typeof import('firebase-admin/firestore').Timestamp;
  const id = () => crypto.randomUUID().replaceAll('-', '');
  const request = (uid: string, data: unknown) =>
    ({ auth: { uid, token: {} }, data }) as CallableRequest;
  beforeAll(async () => {
    if (!enabled) throw new Error('Explicit demo-chat-flood loopback emulator required');
    ({ db } = await import('../firebase'));
    ({ Timestamp } = await import('firebase-admin/firestore'));
    [community, convoy, dm, event] = await Promise.all([
      import('../chatchannels/communityChat'),
      import('../chatchannels/convoyChat'),
      import('../dm/manageDirectMessages'),
      import('../events/postChatMessage'),
    ]);
    await db.doc('carCommunityPolicy/access').set({ enforceInitialized: false });
  });
  afterEach(() => vi.restoreAllMocks());
  async function fixture() {
    const uid = `flood_${id()}`,
      peer = `peer_${id()}`,
      room = id(),
      now = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(now); // deterministic refill under emulator contention
    const batch = db.batch();
    for (const person of [uid, peer])
      batch.set(db.doc(`users/${person}`), {
        displayName: person,
        role: 'admin',
        activeMember: true,
        onboardingCompletedAt: Timestamp.fromMillis(now),
      });
    batch.set(db.doc(`users/${uid}/friends/${peer}`), { uid: peer });
    batch.set(db.doc(`convoys/${room}`), {
      memberUids: [uid, peer],
      members: {
        [uid]: { inviteStatus: 'accepted' },
        [peer]: { inviteStatus: 'accepted' },
      },
    });
    batch.set(db.doc(`events/${room}`), { status: 'published' });
    batch.set(db.doc(`events/${room}/rsvps/${uid}`), { status: 'going' });
    await batch.commit();
    return { uid, peer, room, now };
  }
  test('simultaneous new IDs across Community, convoy, DM and event share six tokens; denial has no side effects', async () => {
    const { uid, peer, room, now } = await fixture();
    const calls = [
      () => community.post.run(request(uid, { text: '🚗', clientId: id(), mentionedUids: [peer] })),
      () => convoy.post.run(request(uid, { convoyId: room, text: 'Hej', clientId: id() })),
      () => dm.sendMessage.run(request(uid, { toUid: peer, text: 'Hej', clientId: id() })),
      () =>
        event.postChatMessage.run(request(uid, { eventId: room, message: 'Hej', clientId: id() })),
    ];
    // Two bursts of four keep lock contention bounded while crossing every channel.
    const results = [];
    for (let burst = 0; burst < 2; burst++)
      results.push(...(await Promise.allSettled(calls.map((call) => call()))));
    expect(results.filter((row) => row.status === 'fulfilled')).toHaveLength(6);
    for (const row of results)
      if (row.status === 'rejected')
        expect(row.reason).toMatchObject({
          code: 'resource-exhausted',
          details: { reason: 'chat-rate-limited', blockedUntilMillis: now + 5000 },
        });
    const guardRef = db.doc(`chatSendGuards/${uid}`),
      guard = await guardRef.get();
    const inbox = await db.collection(`notifications/${peer}/items`).get();
    const unread = await db.doc(`userPrivate/${peer}`).get();
    const conversation = await db.doc(`conversations/${[uid, peer].sort().join('__')}`).get();
    const rejectedId = id();
    await expect(
      dm.sendMessage.run(request(uid, { toUid: peer, text: 'denied', clientId: rejectedId })),
    ).rejects.toMatchObject({ code: 'resource-exhausted' });
    expect((await guardRef.get()).updateTime?.isEqual(guard.updateTime!)).toBe(true);
    expect(
      (await db.collection(`notifications/${peer}/items`).get()).docs.map((d) => d.data()),
    ).toEqual(inbox.docs.map((d) => d.data()));
    expect((await unread.ref.get()).data()).toEqual(unread.data());
    expect((await conversation.ref.get()).data()).toEqual(conversation.data());
    expect((await conversation.ref.collection('messages').doc(rejectedId).get()).exists).toBe(
      false,
    );
  }, 45000);

  test('same-ID races charge once per channel; old receipts replay during pause, authorization still runs', async () => {
    const { uid, peer, room } = await fixture();
    const pairs = [
      () => community.post.run(request(uid, { text: 'Hej', clientId: uid })),
      () => convoy.post.run(request(uid, { convoyId: room, text: 'Hej', clientId: uid })),
      () => dm.sendMessage.run(request(uid, { toUid: peer, text: 'Hej', clientId: uid })),
      () =>
        event.postChatMessage.run(request(uid, { eventId: room, message: 'Hej', clientId: uid })),
    ];
    for (const send of pairs) {
      const [a, b] = await Promise.all([send(), send()]);
      expect(a).toEqual(b);
    }
    expect((await db.doc(`chatSendGuards/${uid}`).get()).get('creditMs')).toBe(4000);
    for (let i = 0; i < 2; i++)
      await community.post.run(request(uid, { text: 'Hej', clientId: id() }));
    await expect(
      community.post.run(request(uid, { text: 'pause', clientId: id() })),
    ).rejects.toMatchObject({ code: 'resource-exhausted' });
    const guard = await db.doc(`chatSendGuards/${uid}`).get();
    for (const send of pairs) await send();
    expect((await guard.ref.get()).updateTime?.isEqual(guard.updateTime!)).toBe(true);
    // Collisions belong to another sender, never a receipt or a budget charge.
    await db.doc(`events/${room}/rsvps/${peer}`).set({ status: 'going' });
    await db.doc(`users/${peer}/friends/${uid}`).set({ uid });
    const collisions = [
      community.post.run(request(peer, { text: 'other', clientId: uid })),
      convoy.post.run(request(peer, { convoyId: room, text: 'other', clientId: uid })),
      dm.sendMessage.run(request(peer, { toUid: uid, text: 'other', clientId: uid })),
      event.postChatMessage.run(request(peer, { eventId: room, message: 'other', clientId: uid })),
    ];
    for (const collision of await Promise.allSettled(collisions))
      expect(collision).toMatchObject({ status: 'rejected', reason: { code: 'already-exists' } });
    expect((await db.doc(`chatSendGuards/${peer}`).get()).exists).toBe(false);
    await db.doc(`userBlocks/${peer}/blocked/${uid}`).set({});
    await expect(pairs[2]!()).rejects.toMatchObject({ code: 'failed-precondition' });
    await db.doc(`events/${room}/rsvps/${uid}`).delete();
    await expect(pairs[3]!()).rejects.toMatchObject({ code: 'permission-denied' });
    await db.doc(`convoys/${room}`).update({ memberUids: [peer] });
    await expect(pairs[1]!()).rejects.toMatchObject({ code: 'not-found' });
    await db.doc(`users/${uid}`).update({ suspended: true });
    await expect(pairs[0]!()).rejects.toMatchObject({ code: 'permission-denied' });
    expect((await guard.ref.get()).updateTime?.isEqual(guard.updateTime!)).toBe(true);
    await expect(
      community.post.run({ data: { text: 'x' } } as CallableRequest),
    ).rejects.toMatchObject({ code: 'unauthenticated' });
  }, 45000);

  test('event concurrency across events retains the stricter rolling five per thirty seconds and legacy no-key calls', async () => {
    const { uid, room } = await fixture(),
      second = id();
    await db.doc(`events/${second}`).set({ status: 'published' });
    await db.doc(`events/${second}/rsvps/${uid}`).set({ status: 'maybe' });
    const results = [];
    for (let burst = 0; burst < 2; burst++)
      results.push(
        ...(await Promise.allSettled(
          [room, second, room].map((eventId) =>
            event.postChatMessage.run(request(uid, { eventId, message: '🚗' })),
          ),
        )),
      );
    expect(results.filter((row) => row.status === 'fulfilled')).toHaveLength(5);
    const guard = await db.doc(`chatSendGuards/${uid}`).get();
    expect(guard.get('creditMs')).toBe(2000);
    const error = results.find((row) => row.status === 'rejected');
    expect(error).toMatchObject({
      status: 'rejected',
      reason: {
        code: 'resource-exhausted',
        message: 'Too many messages — wait a moment before posting again.',
      },
    });
  }, 45000);

  test('Community moderation tombstones win over receipt replay and never charge again', async () => {
    const { uid } = await fixture(),
      clientId = id();
    const send = () => community.post.run(request(uid, { text: 'Hej', clientId }));
    await send();
    const guard = await db.doc(`chatSendGuards/${uid}`).get();
    await db.doc(`communityChatDeletedMessages/${clientId}`).set({ senderUid: uid });
    await db.doc(`communityChat/global/messages/${clientId}`).delete();
    await expect(send()).rejects.toMatchObject({ code: 'failed-precondition' });
    expect((await guard.ref.get()).updateTime?.isEqual(guard.updateTime!)).toBe(true);
    expect((await db.doc(`communityChat/global/messages/${clientId}`).get()).exists).toBe(false);
  });

  test.skipIf(!process.env.CHAT_FLOOD_DRIVER_ROOT)(
    'actual CC and Driver engines contend on one UID document',
    async () => {
      const { uid, now } = await fixture();
      const root = process.env.CHAT_FLOOD_DRIVER_ROOT!;
      const driverRequire = createRequire(resolve(root, 'functions/package.json'));
      const { initializeApp } = driverRequire(
        'firebase-admin/app',
      ) as typeof import('firebase-admin/app');
      const { getFirestore, Timestamp: DriverTimestamp } = driverRequire(
        'firebase-admin/firestore',
      ) as typeof import('firebase-admin/firestore');
      // Each writer uses its own installed SDK, as in separately deployed functions.
      const driverDb = getFirestore(
        initializeApp({ projectId: 'demo-chat-flood' }, `driver-${id()}`),
      );
      const { SharedClubEngine } = await import(
        /* @vite-ignore */ pathToFileURL(resolve(root, 'functions/src/shared-clubs.ts')).href
      );
      const engine = new SharedClubEngine(driverDb, () => now);
      const clubId = id();
      await driverDb
        .doc('config/sharedClubs')
        .set({ enabled: true, activatedAt: DriverTimestamp.fromMillis(now - 1000) });
      await driverDb
        .doc(`driverClubs/${clubId}`)
        .set({ schemaVersion: 2, status: 'active', ownerUid: uid });
      await driverDb.doc(`driverClubs/${clubId}/members/${uid}`).set({ role: 'owner' });
      await driverDb.doc(`driverClubAccounts/${uid}`).set({ activeClubId: clubId, role: 'owner' });
      const ccSend = () => community.post.run(request(uid, { text: 'CC', clientId: id() }));
      const clubSend = () => engine.send(uid, { clubId, text: 'Driver', operationId: id() });
      const results = [];
      for (let burst = 0; burst < 2; burst++)
        results.push(...(await Promise.allSettled([ccSend(), clubSend(), ccSend(), clubSend()])));
      expect(results.filter((row) => row.status === 'fulfilled')).toHaveLength(6);
      for (const row of results)
        if (row.status === 'rejected')
          expect(row.reason).toMatchObject({
            code: 'resource-exhausted',
            details: { reason: 'chat-rate-limited' },
          });
      const guard = await db.doc(`chatSendGuards/${uid}`).get();
      await expect(clubSend()).rejects.toMatchObject({ code: 'resource-exhausted' });
      expect((await guard.ref.get()).updateTime?.isEqual(guard.updateTime!)).toBe(true);
      await driverDb.terminate();
    },
    45000,
  );
});
