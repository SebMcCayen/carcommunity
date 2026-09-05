/**
 * Lane integration tests: real Firestore rules + callable handlers, with
 * authoritative subscription documents. Run against local Firestore/RTDB only.
 * Handler .run bypasses transport/App Check, not the production access guards.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  updateDoc,
  deleteDoc,
} from 'firebase/firestore';
import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { listAttendees } from '../events/listAttendees';
import { checkIn } from '../events/checkIn';
import { create } from '../events/manageEvent';
import { showOfferCode } from '../partners/manageOffer';
import { adminSend } from '../notifications/adminSend';
import { runEventCreatedFanOut } from '../events/onEventPublished';
import { attendanceDocId } from '../points/points-economy-core';

let env: RulesTestEnvironment;
const projectId = process.env.GCLOUD_PROJECT ?? 'demo-test';
const request = (uid: string, data: unknown) =>
  ({ auth: { uid, token: { activeMember: true, admin: true } }, data }) as never;
const sub = (uid: string, tier = 'plus', status = 'active') =>
  db.doc('subscriptions/' + uid).set({ userId: uid, entitlement: 'member_monthly', tier, status });
const sample = (eventId = 'access-event') => ({
  eventId,
  latitude: 57.49,
  longitude: 12.08,
  accuracyMeters: 5,
  capturedAt: new Date().toISOString(),
});

beforeAll(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST) {
    throw new Error('Local Firestore and RTDB emulator hosts are required');
  }
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(':');
  env = await initializeTestEnvironment({
    projectId,
    firestore: {
      host,
      port: Number(port),
      rules: readFileSync(resolve(__dirname, '../../../firebase/firestore.rules'), 'utf8'),
    },
  });
  for (const uid of [
    'free',
    'plus',
    'supporter',
    'expired',
    'admin',
    'suspended',
    'deleted',
    'optout',
    'creator',
  ]) {
    await db.doc('users/' + uid).set({
      displayName: uid + ' identity',
      role: uid === 'admin' ? 'admin' : 'user',
      activeMember: false,
      suspended: uid === 'suspended',
      deleted: uid === 'deleted',
    });
  }
  await sub('plus');
  await sub('supporter', 'supporter');
  await sub('expired', 'plus', 'expired');
  await sub('suspended');
  await sub('deleted');
  await db
    .doc('userPrivate/optout')
    .set({ notificationPreferences: { event_created: { inApp: false } } });
  await db.doc('events/access-event').set({
    title: 'Free full details',
    status: 'published',
    createdByUserId: 'creator',
    startsAt: Timestamp.fromMillis(Date.now() - 30 * 60_000),
    endsAt: Timestamp.fromMillis(Date.now() + 60 * 60_000),
    latitude: 57.49,
    longitude: 12.08,
    rsvpCounts: { going: 1, maybe: 0, not_going: 0 },
  });
  await db
    .doc('events/access-event/details/private')
    .set({ description: 'Full description', address: 'Exact street 1' });
  await db
    .doc('events/access-event/rsvps/plus')
    .set({ status: 'going', updatedAt: Timestamp.now() });
  await db.doc('offers/access-offer').set({ status: 'active', title: 'Free teaser' });
  await db.doc('offers/access-offer/details/member').set({ description: 'Paid terms' });
  await db.doc('offers/access-offer/secret/code').set({ discountCode: 'PAIDCODE' });
  await db
    .doc('announcements/access-announcement')
    .set({ title: 'Admin message', body: 'All users', active: true });
});
afterAll(async () => {
  await env?.cleanup();
});

describe('events/offers permanent access policy', () => {
  it('free authenticated users read full published details and change their own RSVP, but cannot list identities', async () => {
    const fs = env.authenticatedContext('free', { activeMember: false }).firestore();
    expect((await getDoc(doc(fs, 'events/access-event/details/private'))).data()?.address).toBe(
      'Exact street 1',
    );
    await assertSucceeds(
      setDoc(doc(fs, 'events/access-event/rsvps/free'), {
        status: 'going',
        updatedAt: serverTimestamp(),
      }),
    );
    await assertSucceeds(
      updateDoc(doc(fs, 'events/access-event/rsvps/free'), {
        status: 'maybe',
        updatedAt: serverTimestamp(),
      }),
    );
    await assertFails(getDocs(collection(fs, 'events/access-event/rsvps')));
    await assertFails(getDoc(doc(fs, 'events/access-event/rsvps/plus')));
    await assertFails(setDoc(doc(fs, 'events/access-event/rsvps/plus'), { status: 'going' }));
    const event = (await getDoc(doc(fs, 'events/access-event'))).data()!;
    expect(event.rsvpCounts).toEqual({ going: 1, maybe: 0, not_going: 0 });
    expect(event).not.toHaveProperty('attendees');
    const anon = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(anon, 'events/access-event/details/private')));
    await assertFails(setDoc(doc(anon, 'events/access-event/rsvps/free'), { status: 'going' }));
  });

  it.each([undefined, false, true])(
    'protected content is paid even when legacy flags are %s',
    async (flag) => {
      if (flag === undefined) await db.doc('config/featureFlags').delete();
      else
        await db
          .doc('config/featureFlags')
          .set({ eventDetailsRequirePaid: flag, partnerMemberOffersRequirePaid: flag });
      for (const uid of ['free', 'expired']) {
        const fs = env.authenticatedContext(uid, { activeMember: true }).firestore();
        await assertSucceeds(getDoc(doc(fs, 'offers/access-offer')));
        await assertFails(getDoc(doc(fs, 'offers/access-offer/details/member')));
        await assertFails(getDoc(doc(fs, 'offers/access-offer/secret/code')));
        expect(await listAttendees.run(request(uid, { eventId: 'access-event' }))).toEqual({
          attendees: [],
          requiresPaid: true,
        });
        await expect(
          showOfferCode.run(request(uid, { offerId: 'access-offer' })),
        ).rejects.toMatchObject({ code: 'permission-denied' });
        await expect(checkIn.run(request(uid, sample()))).rejects.toMatchObject({
          code: 'permission-denied',
        });
      }
      for (const uid of ['plus', 'supporter', 'admin']) {
        const fs = env.authenticatedContext(uid, { admin: uid === 'admin' }).firestore();
        await assertSucceeds(getDoc(doc(fs, 'offers/access-offer/details/member')));
        await assertFails(getDoc(doc(fs, 'offers/access-offer/secret/code')));
        const roster = await listAttendees.run(request(uid, { eventId: 'access-event' }));
        expect(roster.requiresPaid).toBe(false);
        expect(roster.attendees.some((a) => a.userId === 'plus')).toBe(true);
        expect(await showOfferCode.run(request(uid, { offerId: 'access-offer' }))).toMatchObject({
          code: 'PAIDCODE',
        });
      }
      // Admin moderation is not a free personal attendance entitlement.
      await expect(checkIn.run(request('admin', sample()))).rejects.toMatchObject({
        code: 'permission-denied',
      });
    },
  );

  it('enforces entitlement again between the arrival and confirmation stages', async () => {
    const uid = 'downgrade';
    await db
      .doc('users/' + uid)
      .set({ role: 'user', activeMember: true, suspended: false, deleted: false });
    await sub(uid);
    const first = await checkIn.run(request(uid, sample()));
    expect(first.verified).toBe(false);
    const ref = db.doc('eventAttendance/' + attendanceDocId('access-event', uid));
    const before = (await ref.get()).data()!;
    expect(before.sampleCount).toBe(1);
    await sub(uid, 'plus', 'expired');
    await expect(checkIn.run(request(uid, sample()))).rejects.toMatchObject({
      code: 'permission-denied',
    });
    expect((await ref.get()).data()).toEqual(before);
    // Advance only stored emulator evidence; do not wait ten minutes.
    await sub(uid, 'supporter');
    await ref.update({
      samples: [{ ...before.samples[0], capturedAtMs: Date.now() - 11 * 60_000 }],
    });
    const confirmed = await checkIn.run(request(uid, sample()));
    expect(confirmed.verified).toBe(true);
  });

  it('restricted users cannot use paid callables and suspended users cannot RSVP', async () => {
    for (const uid of ['suspended', 'deleted']) {
      await expect(
        listAttendees.run(request(uid, { eventId: 'access-event' })),
      ).rejects.toMatchObject({ code: 'permission-denied' });
      await expect(
        showOfferCode.run(request(uid, { offerId: 'access-offer' })),
      ).rejects.toMatchObject({ code: 'permission-denied' });
      await expect(checkIn.run(request(uid, sample()))).rejects.toMatchObject({
        code: 'permission-denied',
      });
    }
    const fs = env.authenticatedContext('suspended', { suspended: true }).firestore();
    await assertFails(getDoc(doc(fs, 'events/access-event/details/private')));
    await assertFails(
      setDoc(doc(fs, 'events/access-event/rsvps/suspended'), {
        status: 'going',
        updatedAt: serverTimestamp(),
      }),
    );
  });

  it('manual announcements remain admin-only while free users read them and automatic notices', async () => {
    const fs = env.authenticatedContext('free').firestore();
    await assertSucceeds(getDoc(doc(fs, 'announcements/access-announcement')));
    await assertFails(setDoc(doc(fs, 'announcements/spam'), { title: 'Spam' }));
    await assertFails(updateDoc(doc(fs, 'announcements/access-announcement'), { body: 'Spam' }));
    await assertFails(deleteDoc(doc(fs, 'announcements/access-announcement')));
    await expect(adminSend.run(request('free', {}))).rejects.toMatchObject({
      code: 'permission-denied',
    });
    const admin = env.authenticatedContext('admin', { admin: true }).firestore();
    await assertSucceeds(setDoc(doc(admin, 'announcements/admin-written'), { title: 'Admin' }));
    const input = {
      title: 'Member-created event',
      startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    };
    const created = await create.run(request('creator', input));
    expect((await db.doc('events/' + created.eventId).get()).get('status')).toBe('published');
    await runEventCreatedFanOut(created.eventId);
    await runEventCreatedFanOut(created.eventId);
    for (const uid of ['free', 'plus', 'supporter']) {
      const items = await db
        .collection('notifications/' + uid + '/items')
        .where('relatedEntityId', '==', created.eventId)
        .get();
      expect(items.size).toBe(1);
      expect(items.docs[0]!.get('actionType')).toBe('open_event');
    }
    for (const uid of ['creator', 'suspended', 'deleted', 'optout']) {
      expect(
        (await db.doc('notifications/' + uid + '/items/event-created-' + created.eventId).get())
          .exists,
      ).toBe(false);
    }
    const notice = 'notifications/free/items/event-created-' + created.eventId;
    await assertSucceeds(getDoc(doc(fs, notice)));
    await assertFails(setDoc(doc(fs, notice), { title: 'Forged system notice' }));
  });
});
