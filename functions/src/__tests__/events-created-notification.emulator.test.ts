/**
 * "New event" community notification emulator tests
 * (functions/src/events/onEventPublished.ts).
 *
 * Drives the exported runEventCreatedFanOut runner directly (the same test seam
 * the reminder sweep uses) against seeded active-member user docs + a published
 * event, covering: a broadcast to every active member, the creator being
 * excluded, an inactive (non-member) user being excluded, a per-category opt-out
 * being honoured, idempotency across re-runs, and a cancelled event aborting the
 * announcement. One end-to-end test then writes a published event through the
 * Admin SDK and waits for the onEventPublished TRIGGER to deliver.
 *
 * ISOLATION NOTE: emulator test files share one Firestore, and this fan-out
 * queries the WHOLE users collection for activeMember==true — so it necessarily
 * writes an item to every active member other files have seeded too. Every
 * assertion is therefore scoped to THIS file's own uids and the per-event
 * deterministic id (event-created-{eventId}); NEVER to an absolute inbox size or
 * the run's global delivered/skipped counts, which other files' fixtures perturb
 * (the same discipline events-reminders and notifications-admin follow).
 *
 * Requires the Firestore + Functions emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import { runEventCreatedFanOut } from '../events/onEventPublished';
import { eventCreatedNotificationId } from '../events/eventCreatedNotification-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'events-created-notif-tests');
const adminDb = getAdminFirestore(adminApp);

let seq = 0;
function uniqueSuffix(): string {
  seq += 1;
  return `${Date.now()}-${seq}`;
}

interface SeedUserOptions {
  activeMember?: boolean;
  suspended?: boolean;
  deleted?: boolean;
  notificationPreferences?: Record<string, unknown>;
}

/** Seeds a user doc and returns its uid. Active member by default. */
async function seedUser(prefix: string, options: SeedUserOptions = {}): Promise<string> {
  const uid = `${prefix}-${uniqueSuffix()}`;
  await adminDb
    .collection('users')
    .doc(uid)
    .set({
      role: 'user',
      activeMember: options.activeMember ?? true,
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

/** Seeds one published events/{id} teaser doc and returns its id. */
async function seedPublishedEvent(
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
    status: options.status ?? 'published',
    cancelledAt: null,
    rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
    createdByUserId,
    createdByRole: 'member',
    createdAt: Timestamp.fromDate(now),
    updatedAt: Timestamp.fromDate(now),
  });
  return ref.id;
}

/** The event_created inbox item for (uid, eventId), or null if absent. */
async function createdItem(
  uid: string,
  eventId: string,
): Promise<FirebaseFirestore.DocumentData | null> {
  const snap = await adminDb
    .collection('notifications')
    .doc(uid)
    .collection('items')
    .doc(eventCreatedNotificationId(eventId))
    .get();
  return snap.exists ? snap.data()! : null;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('pollUntil timed out');
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
}

describe('event-created fan-out (runEventCreatedFanOut)', () => {
  it('notifies an active member with an open_event deep-link to the event', async () => {
    const creator = await seedUser('ec-creator');
    const member = await seedUser('ec-member');
    const eventId = await seedPublishedEvent(creator, { title: 'Cars & Coffee KBA' });

    await runEventCreatedFanOut(eventId);

    const item = await createdItem(member, eventId);
    expect(item).not.toBeNull();
    expect(item?.category).toBe('event_created');
    expect(item?.actionType).toBe('open_event');
    expect(item?.relatedEntityId).toBe(eventId);
    expect(item?.previewText).toContain('Cars & Coffee KBA');
  });

  it('does NOT notify the event creator about their own event', async () => {
    const creator = await seedUser('ec-self-creator');
    const other = await seedUser('ec-self-other');
    const eventId = await seedPublishedEvent(creator);

    await runEventCreatedFanOut(eventId);

    expect(await createdItem(creator, eventId)).toBeNull();
    expect(await createdItem(other, eventId)).not.toBeNull();
  });

  it('does NOT notify an inactive (non-member) user — active members only', async () => {
    const creator = await seedUser('ec-inactive-creator');
    const inactive = await seedUser('ec-inactive', { activeMember: false });
    const eventId = await seedPublishedEvent(creator);

    await runEventCreatedFanOut(eventId);

    expect(await createdItem(inactive, eventId)).toBeNull();
  });

  it('honours a per-category event_created opt-out', async () => {
    const creator = await seedUser('ec-optout-creator');
    const optedOut = await seedUser('ec-optout', {
      notificationPreferences: { event_created: { inApp: false } },
    });
    const eventId = await seedPublishedEvent(creator);

    await runEventCreatedFanOut(eventId);

    expect(await createdItem(optedOut, eventId)).toBeNull();
  });

  it('is idempotent: a second run does not re-create the inbox item', async () => {
    const creator = await seedUser('ec-idem-creator');
    const member = await seedUser('ec-idem-member');
    const eventId = await seedPublishedEvent(creator);

    await runEventCreatedFanOut(eventId);
    const first = await createdItem(member, eventId);
    expect(first).not.toBeNull();

    await runEventCreatedFanOut(eventId);
    const second = await createdItem(member, eventId);
    // Same document — createdAt unchanged, never re-created (deterministic id).
    expect((second?.createdAt as Timestamp).toMillis()).toBe(
      (first?.createdAt as Timestamp).toMillis(),
    );
  });

  it('aborts when the event is no longer published (a cancel raced the publish)', async () => {
    const creator = await seedUser('ec-cancel-creator');
    const member = await seedUser('ec-cancel-member');
    const eventId = await seedPublishedEvent(creator, { status: 'cancelled' });

    await runEventCreatedFanOut(eventId);

    expect(await createdItem(member, eventId)).toBeNull();
  });
});

describe('onEventPublished trigger (end-to-end)', () => {
  it('delivers on the published transition of an event written through Firestore', async () => {
    const creator = await seedUser('ec-trig-creator');
    const member = await seedUser('ec-trig-member');
    // A member-created event is written already `published` — the create write
    // is itself the published transition the trigger fires on.
    const eventId = await seedPublishedEvent(creator, { title: 'Trigger meetup' });

    const item = await pollUntil(async () => (await createdItem(member, eventId)) ?? undefined);
    expect(item.category).toBe('event_created');
    expect(item.relatedEntityId).toBe(eventId);
  });
});
