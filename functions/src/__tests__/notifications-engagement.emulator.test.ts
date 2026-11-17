process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import { notifyFriendsLive, notifyNearbyEpic } from '../notifications/engagement';
import { engagementId, ENGAGEMENT_COOLDOWN_MS } from '../notifications/engagement-core';
import { geoCellKey } from '../incidents/incidents-core';
import { announceEpicClaim } from '../crownHunt/epicAnnouncement';
import { acknowledgeDisplayedWave, writeWaveNotice } from '../live/acknowledgeWave';
import { toChatMessageSummary } from '../chatchannels/chat-core';

const store = getFirestore();
const now = Date.parse('2026-11-17T00:00:00Z');
const runId = Date.now();
let seq = 0;
const unique = () => `eng-${runId}-${++seq}`;
const position = { latitude: 57.48, longitude: 12.08 };
async function user(prefs = {}) {
  const uid = unique();
  await store
    .doc(`users/${uid}`)
    .set({ role: 'user', activeMember: false, displayName: 'Testförare' });
  await store.doc(`userPrivate/${uid}`).set({ notificationPreferences: prefs });
  return uid;
}
async function live(uid: string, at = now, pos = position) {
  await store.doc(`liveSessions/${uid}`).set({
    ...pos,
    geoCell: geoCellKey(pos.latitude, pos.longitude),
    status: 'active',
    sessionId: unique(),
    refreshRequestedAtMs: at,
    expiresAt: Timestamp.fromMillis(at + 10 * 60000),
  });
  return (await store.doc(`liveSessions/${uid}`).get()).get('sessionId') as string;
}
async function friends(a: string, b: string) {
  await store.doc(`users/${a}/friends/${b}`).set({ uid: b });
  await store.doc(`users/${b}/friends/${a}`).set({ uid: a });
}
async function crown(at = now, rarity = 'epic') {
  const id = unique();
  await store.doc(`crownSpawns/${id}`).set({
    ...position,
    rarity,
    status: 'live',
    createdAt: Timestamp.fromMillis(at),
    expiresAt: Timestamp.fromMillis(at + 3600000),
  });
  return id;
}
const items = (uid: string) => store.collection(`notifications/${uid}/items`);
const epicItem = (uid: string, id: string) => items(uid).doc(`epic-crown-${engagementId(id)}`);

describe('engagement delivery with canonical state and concurrency', () => {
  it('notifies only mutual friends, honours both block directions, and hides the identity', async () => {
    const source = await user(),
      recipient = await user(),
      blocked = await user(),
      reverseBlocked = await user(),
      optedOut = await user({ friend_live: { inApp: false } }),
      stranger = await user();
    for (const uid of [recipient, blocked, reverseBlocked, optedOut]) await friends(source, uid);
    await store.doc(`userBlocks/${source}/blocked/${blocked}`).set({});
    await store.doc(`userBlocks/${reverseBlocked}/blocked/${source}`).set({});
    const session = await live(source);
    await Promise.all([
      notifyFriendsLive(store, source, session, now),
      notifyFriendsLive(store, source, session, now),
    ]);
    const rows = await items(recipient).get();
    expect(rows.size).toBe(1);
    const data = rows.docs[0]!.data();
    expect(data.category).toBe('friend_live');
    expect(data.relatedEntityId).toBeNull();
    expect(data.previewText).not.toContain('Testförare');
    expect(JSON.stringify(data)).not.toContain(source);
    for (const uid of [source, blocked, reverseBlocked, optedOut, stranger])
      expect((await items(uid).get()).empty).toBe(true);
    await live(source);
    await notifyFriendsLive(store, source, session, now); // Stale trigger for old session.
    expect((await items(recipient).get()).size).toBe(1);
  });
  it('rechecks removed friendship and stopped sessions', async () => {
    const source = await user(),
      recipient = await user();
    await friends(source, recipient);
    const session = await live(source);
    await store.doc(`users/${recipient}/friends/${source}`).delete();
    await notifyFriendsLive(store, source, session, now);
    expect((await items(recipient).get()).empty).toBe(true);
    await friends(source, recipient);
    await store.doc(`liveSessions/${source}`).delete();
    await notifyFriendsLive(store, source, session, now);
    expect((await items(recipient).get()).empty).toBe(true);
  });
  it('filters Epic recipients by exact range, freshness, restrictions and preferences', async () => {
    const near = await user(),
      far = await user(),
      stale = await user(),
      optedOut = await user({ epic_crown: { inApp: false } }),
      suspended = await user();
    await live(near);
    await live(far, now, { latitude: 57.52, longitude: 12.08 });
    await live(stale, now - 6 * 60000);
    await live(optedOut);
    await live(suspended);
    await store.doc(`users/${suspended}`).update({ suspended: true });
    const id = await crown();
    await Promise.all([notifyNearbyEpic(store, id, now), notifyNearbyEpic(store, id, now)]);
    expect((await epicItem(near, id).get()).exists).toBe(true);
    for (const uid of [far, stale, optedOut, suspended])
      expect((await epicItem(uid, id).get()).exists).toBe(false);
    const common = await crown(now, 'common');
    await notifyNearbyEpic(store, common, now);
    expect((await epicItem(near, common).get()).exists).toBe(false);
  });
  it('serializes simultaneous different spawns against one recipient budget', async () => {
    const uid = await user();
    await live(uid);
    const first = await crown(),
      second = await crown();
    await Promise.all([notifyNearbyEpic(store, first, now), notifyNearbyEpic(store, second, now)]);
    expect((await items(uid).get()).size).toBe(1);
    const later = now + ENGAGEMENT_COOLDOWN_MS;
    await live(uid, later);
    const third = await crown(later);
    await notifyNearbyEpic(store, third, later);
    expect((await items(uid).get()).size).toBe(2);
    await live(uid, later + ENGAGEMENT_COOLDOWN_MS);
    const fourth = await crown(later + ENGAGEMENT_COOLDOWN_MS);
    await notifyNearbyEpic(store, fourth, later + ENGAGEMENT_COOLDOWN_MS);
    expect((await items(uid).get()).size).toBe(2);
  });
  it('does not announce expired, captured or old crowns', async () => {
    const uid = await user();
    await live(uid);
    const id = await crown();
    await store.doc(`crownSpawns/${id}`).update({ status: 'claimed' });
    await notifyNearbyEpic(store, id, now);
    const old = await crown(now - 6 * 60000);
    await notifyNearbyEpic(store, old, now);
    expect((await items(uid).get()).empty).toBe(true);
  });
});

describe('displayed waves', () => {
  const input = { category: 'wave' as const, title: 'Vinkning', previewText: 'En vinkning' };
  it.each(['before', 'after', 'concurrent'] as const)(
    'acknowledges %s the persistent write without reading unrelated items',
    async (order) => {
      const uid = await user(),
        waveId = unique(),
        noticeId = unique();
      await store.doc(`liveWaves/${uid}/waves/${waveId}`).set({ notificationId: noticeId });
      await items(uid).doc('unrelated').set({ category: 'direct_message', read: false });
      const ack = () => acknowledgeDisplayedWave(store, uid, waveId);
      const write = () => writeWaveNotice(store, uid, waveId, input, noticeId);
      if (order === 'before') {
        await ack();
        await write();
      } else if (order === 'after') {
        await write();
        await ack();
      } else await Promise.all([ack(), write()]);
      expect((await items(uid).doc(noticeId).get()).get('read')).toBe(true);
      expect((await items(uid).doc('unrelated').get()).get('read')).toBe(false);
      await write(); // replay cannot make the notice unread again.
      expect((await items(uid).doc(noticeId).get()).get('read')).toBe(true);
    },
  );
  it('keeps unseen waves unread and cannot acknowledge another inbox', async () => {
    const uid = await user(),
      other = await user(),
      id = unique();
    await store.doc(`liveWaves/${uid}/waves/${id}`).set({ notificationId: id });
    await writeWaveNotice(store, uid, id, input, id);
    await acknowledgeDisplayedWave(store, other, id);
    expect((await items(uid).doc(id).get()).get('read')).toBe(false);
  });
});

describe('Epic system announcements', () => {
  it('publishes one backend-marked, profile-linked message per successful catch', async () => {
    const uid = await user(),
      id = unique(),
      spawn = unique();
    const data = {
      userId: uid,
      spawnId: spawn,
      rarity: 'epic',
      result: 'awarded',
      claimedAt: Timestamp.fromMillis(now),
    };
    await store.doc(`crownSpawnClaims/${id}`).set(data);
    await Promise.all([announceEpicClaim(store, id, now), announceEpicClaim(store, id, now)]);
    // A second claim key for the same catch also cannot duplicate the message.
    const replay = unique();
    await store.doc(`crownSpawnClaims/${replay}`).set(data);
    await announceEpicClaim(store, replay, now);
    const messages = await store
      .collection('communityChat/global/messages')
      .where('senderUid', '==', uid)
      .get();
    expect(messages.size).toBe(1);
    const message = messages.docs[0]!;
    expect(message.get('text')).toContain('Testförare');
    expect(message.get('systemEvent')).toBe('epic_crown_claimed');
    expect(message.get('mentionedUids')).toEqual([]);
    expect(
      toChatMessageSummary(message.id, message.data(), new Date(now).toISOString()).systemEvent,
    ).toBe('epic_crown_claimed');
    expect((await items(uid).get()).empty).toBe(true);
  });
  it('skips failed, non-Epic and deleted collectors', async () => {
    const uid = await user();
    for (const data of [
      { result: 'already_taken', rarity: 'epic' },
      { result: 'awarded', rarity: 'rare' },
      { result: 'awarded', rarity: 'epic' },
    ]) {
      if (data.rarity === 'epic' && data.result === 'awarded')
        await store.doc(`users/${uid}`).delete();
      const id = unique();
      await store
        .doc(`crownSpawnClaims/${id}`)
        .set({ ...data, userId: uid, spawnId: unique(), claimedAt: Timestamp.fromMillis(now) });
      await announceEpicClaim(store, id, now);
    }
    expect(
      (await store.collection('communityChat/global/messages').where('senderUid', '==', uid).get())
        .empty,
    ).toBe(true);
  });
});

describe('private last-known Epic location', () => {
  it('notifies a member who is not sharing live and stores no public marker', async () => {
    const { saveEpicLocation } = await import('../notifications/epicLocation');
    const uid = await user();
    await saveEpicLocation(store, uid, { ...position, recordedAtMillis: now }, now);
    expect((await store.doc(`liveSessions/${uid}`).get()).exists).toBe(false);
    const id = await crown();
    await notifyNearbyEpic(store, id, now);
    expect((await epicItem(uid, id).get()).exists).toBe(true);
    const data = (await store.doc(`userPrivate/${uid}`).get()).get('epicNotificationLocation');
    expect(data.expiresAt.toMillis() - data.recordedAt.toMillis()).toBe(24 * 3600000);
  });
  it('throttles updates, rejects stale fixes and prevents older devices overwriting newer ones', async () => {
    const { saveEpicLocation } = await import('../notifications/epicLocation');
    const uid = await user(),
      ref = store.doc(`userPrivate/${uid}`);
    await saveEpicLocation(store, uid, { ...position, recordedAtMillis: now }, now);
    await saveEpicLocation(
      store,
      uid,
      { latitude: 58, longitude: 13, recordedAtMillis: now + 1000 },
      now + 1000,
    );
    expect((await ref.get()).get('epicNotificationLocation.latitude')).toBe(position.latitude);
    await expect(
      saveEpicLocation(store, uid, { ...position, recordedAtMillis: now - 600000 }, now),
    ).rejects.toThrow();
    await saveEpicLocation(
      store,
      uid,
      { latitude: 58, longitude: 13, recordedAtMillis: now + 300000 },
      now + 300000,
    );
    expect((await ref.get()).get('epicNotificationLocation.latitude')).toBe(58);
  });
  it('honours opt-out and clearing without deleting unrelated private data', async () => {
    const { saveEpicLocation } = await import('../notifications/epicLocation');
    const uid = await user(),
      ref = store.doc(`userPrivate/${uid}`);
    await saveEpicLocation(store, uid, { ...position, recordedAtMillis: now }, now);
    await saveEpicLocation(store, uid, null, now);
    expect((await ref.get()).get('epicNotificationLocation')).toBeUndefined();
    expect((await ref.get()).exists).toBe(true);
    await ref.update({ 'notificationPreferences.epic_crown.inApp': false });
    await saveEpicLocation(store, uid, { ...position, recordedAtMillis: now }, now);
    expect((await ref.get()).get('epicNotificationLocation')).toBeUndefined();
  });
  it('skips expired private positions and clears only expired position fields', async () => {
    const { saveEpicLocation, clearExpiredEpicLocations } =
      await import('../notifications/epicLocation');
    const uid = await user();
    await saveEpicLocation(store, uid, { ...position, recordedAtMillis: now }, now);
    const later = now + 24 * 3600000 + 1,
      id = await crown(later);
    await notifyNearbyEpic(store, id, later);
    expect((await epicItem(uid, id).get()).exists).toBe(false);
    await clearExpiredEpicLocations(store, later);
    const data = await store.doc(`userPrivate/${uid}`).get();
    expect(data.exists).toBe(true);
    expect(data.get('notificationPreferences')).toEqual({});
    expect(data.get('epicNotificationLocation')).toBeUndefined();
  });
  it('prefers a fresh live position over an older nearby private fix', async () => {
    const { saveEpicLocation } = await import('../notifications/epicLocation');
    const uid = await user();
    await saveEpicLocation(store, uid, { ...position, recordedAtMillis: now }, now);
    await live(uid, now, { latitude: 58, longitude: 13 });
    const id = await crown();
    await notifyNearbyEpic(store, id, now);
    expect((await epicItem(uid, id).get()).exists).toBe(false);
  });
});
