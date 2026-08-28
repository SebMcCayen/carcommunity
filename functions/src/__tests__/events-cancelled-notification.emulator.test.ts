/**
 * "Event cancelled" attendee notification emulator tests
 * (functions/src/events/onEventCancelled.ts).
 *
 * Drives the exported runEventCancelledFanOut runner directly (the same test
 * seam events-created-notification and events-reminders use) against seeded
 * going-RSVP docs + a cancelled event, covering: a notice to each going
 * attendee, the creator being excluded, non-going RSVPs being excluded, a
 * per-category opt-out being honoured, idempotency across re-runs, and a
 * still-published event notifying nobody.
 *
 * Like the event-created fan-out test, this exercises the RUNNER — the part with
 * the logic — rather than waiting on onDocumentWritten trigger propagation
 * (which the trigger deliberately skips under the emulator). The trigger glue
 * (isEventCancelledTransition -> runEventCancelledFanOut) is covered by the pure
 * unit test in events/eventCancelledNotification-core.test.ts plus CI loading
 * the events-onEventCancelled function.
 *
 * ISOLATION NOTE: emulator test files share one Firestore. This fan-out reads
 * only the target event's OWN rsvps subcollection (not a global query), so it
 * touches nothing other files seeded; every assertion is still scoped to this
 * file's own uids and the per-event deterministic id (event-cancelled-{eventId}).
 *
 * Requires the Firestore + Functions emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import { runEventCancelledFanOut } from '../events/onEventCancelled';
import { eventCancelledNotificationId } from '../events/eventCancelledNotification-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'events-cancelled-notif-tests');
const adminDb = getAdminFirestore(adminApp);

let seq = 0;
function uniqueSuffix(): string {
  seq += 1;
  return `${Date.now()}-${seq}`;
}

interface SeedUserOptions {
  suspended?: boolean;
  deleted?: boolean;
  notificationPreferences?: Record<string, unknown>;
}

/** Seeds a user doc and returns its uid. */
async function seedUser(prefix: string, options: SeedUserOptions = {}): Promise<string> {
  const uid = `${prefix}-${uniqueSuffix()}`;
  await adminDb
    .collection('users')
    .doc(uid)
    .set({
      role: 'user',
      activeMember: true,
      suspended: options.suspended ?? false,
      deleted: options.deleted ?? false,
    });
  if (options.notificationPreferences) {
    await adminDb
      .collection('userPrivate')
      .doc(uid)
      .set({ notificationPreferences: options.notificationPreferences });
  }
  return uid;
}

/** Seeds a cancelled events/{id} teaser doc (or another status) and returns its id. */
async function seedCancelledEvent(
  createdByUserId: string,
  options: { title?: string; status?: string } = {},
): Promise<string> {
  const ref = adminDb.collection('events').doc();
  const now = new Date();
  await ref.set({
    title: options.title ?? 'Bilträff Kungsbacka',
    summary: null,
    startsAt: Timestamp.fromDate(new Date(now.getTime() + 24 * 60 * 60 * 1000)),
    endsAt: Timestamp.fromDate(new Date(now.getTime() + 26 * 60 * 60 * 1000)),
    approximateArea: 'Kungsbacka',
    isOfficial: false,
    status: options.status ?? 'cancelled',
    cancelledAt: Timestamp.fromDate(now),
    rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
    createdByUserId,
    createdByRole: 'member',
    createdAt: Timestamp.fromDate(now),
    updatedAt: Timestamp.fromDate(now),
  });
  return ref.id;
}

/** Seeds an RSVP doc for (uid, eventId). */
async function seedRsvp(eventId: string, uid: string, status: string): Promise<void> {
  await adminDb
    .collection('events')
    .doc(eventId)
    .collection('rsvps')
    .doc(uid)
    .set({ status, updatedAt: Timestamp.now() });
}

/** The event_cancelled inbox item for (uid, eventId), or null if absent. */
async function cancelledItem(
  uid: string,
  eventId: string,
): Promise<FirebaseFirestore.DocumentData | null> {
  const snap = await adminDb
    .collection('notifications')
    .doc(uid)
    .collection('items')
    .doc(eventCancelledNotificationId(eventId))
    .get();
  return snap.exists ? snap.data()! : null;
}

describe('event-cancelled fan-out (runEventCancelledFanOut)', () => {
  it('notifies a going attendee with an open_event deep-link to the event', async () => {
    const creator = await seedUser('xc-creator');
    const attendee = await seedUser('xc-attendee');
    const eventId = await seedCancelledEvent(creator, { title: 'Cars & Coffee KBA' });
    await seedRsvp(eventId, attendee, 'going');

    await runEventCancelledFanOut(eventId);

    const item = await cancelledItem(attendee, eventId);
    expect(item).not.toBeNull();
    expect(item?.category).toBe('event_cancelled');
    expect(item?.actionType).toBe('open_event');
    expect(item?.relatedEntityId).toBe(eventId);
    expect(item?.previewText).toContain('Cars & Coffee KBA');
  });

  it('does NOT notify the event creator, even though they RSVP’d going', async () => {
    const creator = await seedUser('xc-self-creator');
    const attendee = await seedUser('xc-self-attendee');
    const eventId = await seedCancelledEvent(creator);
    // The creator is auto-RSVP'd going on create — they must NOT be told their
    // own event is off.
    await seedRsvp(eventId, creator, 'going');
    await seedRsvp(eventId, attendee, 'going');

    await runEventCancelledFanOut(eventId);

    expect(await cancelledItem(creator, eventId)).toBeNull();
    expect(await cancelledItem(attendee, eventId)).not.toBeNull();
  });

  it('does NOT notify maybe / not_going RSVPs — going attendees only', async () => {
    const creator = await seedUser('xc-status-creator');
    const going = await seedUser('xc-going');
    const maybe = await seedUser('xc-maybe');
    const notGoing = await seedUser('xc-notgoing');
    const eventId = await seedCancelledEvent(creator);
    await seedRsvp(eventId, going, 'going');
    await seedRsvp(eventId, maybe, 'maybe');
    await seedRsvp(eventId, notGoing, 'not_going');

    await runEventCancelledFanOut(eventId);

    expect(await cancelledItem(going, eventId)).not.toBeNull();
    expect(await cancelledItem(maybe, eventId)).toBeNull();
    expect(await cancelledItem(notGoing, eventId)).toBeNull();
  });

  it('honours a per-category event_cancelled opt-out', async () => {
    const creator = await seedUser('xc-optout-creator');
    const optedOut = await seedUser('xc-optout', {
      notificationPreferences: { event_cancelled: { inApp: false } },
    });
    const eventId = await seedCancelledEvent(creator);
    await seedRsvp(eventId, optedOut, 'going');

    await runEventCancelledFanOut(eventId);

    expect(await cancelledItem(optedOut, eventId)).toBeNull();
  });

  it('is idempotent: a second run does not re-create the inbox item', async () => {
    const creator = await seedUser('xc-idem-creator');
    const attendee = await seedUser('xc-idem-attendee');
    const eventId = await seedCancelledEvent(creator);
    await seedRsvp(eventId, attendee, 'going');

    await runEventCancelledFanOut(eventId);
    const first = await cancelledItem(attendee, eventId);
    expect(first).not.toBeNull();

    await runEventCancelledFanOut(eventId);
    const second = await cancelledItem(attendee, eventId);
    // Same document — createdAt unchanged, never re-created (deterministic id).
    expect((second?.createdAt as Timestamp).toMillis()).toBe(
      (first?.createdAt as Timestamp).toMillis(),
    );
  });

  it('notifies nobody when the event is not actually cancelled (still published)', async () => {
    const creator = await seedUser('xc-live-creator');
    const attendee = await seedUser('xc-live-attendee');
    const eventId = await seedCancelledEvent(creator, { status: 'published' });
    await seedRsvp(eventId, attendee, 'going');

    const summary = await runEventCancelledFanOut(eventId);

    expect(summary.delivered).toBe(0);
    expect(await cancelledItem(attendee, eventId)).toBeNull();
  });
});
