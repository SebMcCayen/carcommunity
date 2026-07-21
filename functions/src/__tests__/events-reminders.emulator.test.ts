/**
 * Event RSVP reminder sweep emulator tests
 * (functions/src/events/eventReminders.ts).
 *
 * Drives the exported runEventReminders runner (and the claimEventReminder
 * transaction directly, for the races a sweep cannot stage) at a deterministic
 * instant against seeded events + RSVPs, covering: a going attendee getting one
 * reminder, maybe/not_going being ignored, the 2h window edges, terminal/draft
 * statuses, idempotency across runs, the sticky marker under an edited start
 * time, and the going-list fan-out spanning the concurrency chunk.
 *
 * Events + RSVPs + recipient user docs are seeded through the Admin SDK rather
 * than the callables so start instants can be placed precisely relative to the
 * sweep instant.
 *
 * ISOLATION NOTE: emulator test files share one Firestore, and this sweep queries
 * the whole `events` collection for published events starting within 2h of NOW.
 * Every assertion is therefore scoped to THIS file's own event / user ids and
 * their documents, never to a run's global summary counts, which another file's
 * fixtures could perturb. This file's NOW is a distinct far-future instant, so no
 * other file's fixtures fall inside its 2h window in practice.
 *
 * Requires the Firestore emulator — run via:
 *   pnpm emulators:test
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import { claimEventReminder, runEventReminders } from '../events/eventReminders';
import { EVENT_REMINDER_LEAD_MS, eventReminderNotificationId } from '../events/eventReminders-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'events-reminders-tests');
const adminDb = getAdminFirestore(adminApp);

/** The fixed instant every test in this file sweeps at. */
const NOW = new Date('2027-09-12T09:00:00.000Z');
const HOUR = 60 * 60 * 1000;
const MINUTE = 60 * 1000;

let seq = 0;
function uniqueSuffix(): string {
  seq += 1;
  return `${Date.now()}-${seq}`;
}

interface SeedOptions {
  status?: string;
  startsAt: Date;
  title?: string;
}

/** Seeds one events/{id} teaser document and returns its id. */
async function seedEvent(options: SeedOptions): Promise<string> {
  const ref = adminDb.collection('events').doc();
  await ref.set({
    title: options.title ?? 'Reminder fixture',
    summary: null,
    startsAt: Timestamp.fromDate(options.startsAt),
    endsAt: Timestamp.fromDate(new Date(options.startsAt.getTime() + 2 * HOUR)),
    approximateArea: 'Kungsbacka',
    isOfficial: false,
    status: options.status ?? 'published',
    cancelledAt: null,
    rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
    createdByUserId: 'seed-uid',
    createdByRole: 'member',
    createdAt: Timestamp.fromDate(new Date(options.startsAt.getTime() - 24 * HOUR)),
    updatedAt: Timestamp.fromDate(new Date(options.startsAt.getTime() - 24 * HOUR)),
  });
  return ref.id;
}

/** Seeds a deliverable (non-suspended, non-deleted) recipient and returns its uid. */
async function seedUser(prefix: string): Promise<string> {
  const uid = `${prefix}-${uniqueSuffix()}`;
  await adminDb.collection('users').doc(uid).set({
    role: 'user',
    activeMember: true,
    suspended: false,
    deleted: false,
  });
  return uid;
}

async function seedRsvp(eventId: string, uid: string, status: string): Promise<void> {
  await adminDb
    .collection('events')
    .doc(eventId)
    .collection('rsvps')
    .doc(uid)
    .set({ status, updatedAt: Timestamp.fromDate(NOW) });
}

async function readEvent(eventId: string): Promise<FirebaseFirestore.DocumentData> {
  return (await adminDb.collection('events').doc(eventId).get()).data()!;
}

/** The event-reminder inbox item for (event, uid), or null if absent. */
async function reminderItem(
  uid: string,
  eventId: string,
): Promise<FirebaseFirestore.DocumentData | null> {
  const snap = await adminDb
    .collection('notifications')
    .doc(uid)
    .collection('items')
    .doc(eventReminderNotificationId(eventId))
    .get();
  return snap.exists ? snap.data()! : null;
}

/** An event starting inside the 2h reminder window. */
const inWindow = () => ({ startsAt: new Date(NOW.getTime() + HOUR) });

describe('event reminder sweep', () => {
  it('reminds a going attendee once, with an open_event deep-link and the marker set', async () => {
    const going = await seedUser('rem-going');
    const eventId = await seedEvent({ ...inWindow(), title: 'Bilträff Kungsbacka' });
    await seedRsvp(eventId, going, 'going');

    await runEventReminders(NOW);

    const item = await reminderItem(going, eventId);
    expect(item).not.toBeNull();
    expect(item?.category).toBe('event_reminder');
    expect(item?.actionType).toBe('open_event');
    expect(item?.relatedEntityId).toBe(eventId);
    expect(item?.previewText).toContain('Bilträff Kungsbacka');

    // Per-event marker claimed.
    expect((await readEvent(eventId)).eventReminderSentAt).toBeDefined();
    expect((await readEvent(eventId)).eventReminderSentAt).not.toBeNull();
  });

  it('reminds only going attendees, never maybe or not_going', async () => {
    const going = await seedUser('rem-g');
    const maybe = await seedUser('rem-m');
    const notGoing = await seedUser('rem-n');
    const eventId = await seedEvent(inWindow());
    await seedRsvp(eventId, going, 'going');
    await seedRsvp(eventId, maybe, 'maybe');
    await seedRsvp(eventId, notGoing, 'not_going');

    await runEventReminders(NOW);

    expect(await reminderItem(going, eventId)).not.toBeNull();
    expect(await reminderItem(maybe, eventId)).toBeNull();
    expect(await reminderItem(notGoing, eventId)).toBeNull();
  });

  it('is idempotent: a second sweep neither re-delivers nor re-stamps', async () => {
    const going = await seedUser('rem-idem');
    const eventId = await seedEvent(inWindow());
    await seedRsvp(eventId, going, 'going');

    await runEventReminders(NOW);
    const firstStamp = (await readEvent(eventId)).eventReminderSentAt as Timestamp;
    const firstItem = await reminderItem(going, eventId);
    expect(firstItem).not.toBeNull();

    await runEventReminders(NOW);
    const secondStamp = (await readEvent(eventId)).eventReminderSentAt as Timestamp;
    const secondItem = await reminderItem(going, eventId);

    // Marker untouched by the second run.
    expect(secondStamp.toMillis()).toBe(firstStamp.toMillis());
    // The single inbox item's createdAt is unchanged — not re-created.
    expect((secondItem?.createdAt as Timestamp).toMillis()).toBe(
      (firstItem?.createdAt as Timestamp).toMillis(),
    );
  });

  it('does not remind an event starting beyond the 2h window', async () => {
    const going = await seedUser('rem-far');
    const eventId = await seedEvent({ startsAt: new Date(NOW.getTime() + EVENT_REMINDER_LEAD_MS + 5 * MINUTE) });
    await seedRsvp(eventId, going, 'going');

    await runEventReminders(NOW);

    expect(await reminderItem(going, eventId)).toBeNull();
    expect((await readEvent(eventId)).eventReminderSentAt).toBeUndefined();
  });

  it('does not remind an event that already started', async () => {
    const going = await seedUser('rem-past');
    const eventId = await seedEvent({ startsAt: new Date(NOW.getTime() - MINUTE) });
    await seedRsvp(eventId, going, 'going');

    await runEventReminders(NOW);

    expect(await reminderItem(going, eventId)).toBeNull();
    expect((await readEvent(eventId)).eventReminderSentAt).toBeUndefined();
  });

  it('never reminds draft or cancelled events inside the window', async () => {
    const g1 = await seedUser('rem-draft');
    const g2 = await seedUser('rem-cancel');
    const draftId = await seedEvent({ ...inWindow(), status: 'draft' });
    const cancelledId = await seedEvent({ ...inWindow(), status: 'cancelled' });
    await seedRsvp(draftId, g1, 'going');
    await seedRsvp(cancelledId, g2, 'going');

    await runEventReminders(NOW);

    expect(await reminderItem(g1, draftId)).toBeNull();
    expect(await reminderItem(g2, cancelledId)).toBeNull();
  });

  it('does not double-remind after the start time is edited (marker is sticky)', async () => {
    const going = await seedUser('rem-edit');
    const eventId = await seedEvent(inWindow());
    await seedRsvp(eventId, going, 'going');

    await runEventReminders(NOW);
    const firstItem = await reminderItem(going, eventId);
    expect(firstItem).not.toBeNull();

    // Organiser pushes the event later, back into a future window relative to a
    // later sweep. The marker must keep it from firing again.
    const laterNow = new Date(NOW.getTime() + 3 * HOUR);
    await adminDb
      .collection('events')
      .doc(eventId)
      .update({ startsAt: Timestamp.fromDate(new Date(laterNow.getTime() + HOUR)) });

    await runEventReminders(laterNow);

    const secondItem = await reminderItem(going, eventId);
    expect((secondItem?.createdAt as Timestamp).toMillis()).toBe(
      (firstItem?.createdAt as Timestamp).toMillis(),
    );
  });

  it('reminds every going attendee when the list exceeds the fan-out chunk', async () => {
    const eventId = await seedEvent(inWindow());
    // FANOUT_CONCURRENCY is 15, so 40 attendees span three chunks — the chunk
    // boundary must not drop or duplicate anyone.
    const uids: string[] = [];
    for (let i = 0; i < 40; i += 1) {
      const uid = await seedUser(`rem-bulk-${String(i).padStart(3, '0')}`);
      uids.push(uid);
      await seedRsvp(eventId, uid, 'going');
    }

    await runEventReminders(NOW);

    for (const uid of uids) {
      expect(await reminderItem(uid, eventId)).not.toBeNull();
    }
  });
});

describe('reminder sweep – candidate cap paging (reduced final-page limit)', () => {
  // Each test uses its OWN far-future NOW so only its seeded events fall inside
  // the 2h window — the sweep scans the whole `events` collection, so a shared
  // window would let sibling fixtures perturb these summary-count assertions.
  const baseLimits = { leadMs: EVENT_REMINDER_LEAD_MS, concurrency: 15 };

  async function seedWindowEvents(now: Date, count: number): Promise<void> {
    for (let i = 0; i < count; i += 1) {
      // Distinct startsAt inside (now, now+2h] so the ordered page is stable.
      await seedEvent({ startsAt: new Date(now.getTime() + (i + 1) * MINUTE) });
    }
  }

  it('flags capped + logs when the cap binds via a reduced final-page limit', async () => {
    // 5 candidates, pageSize 2, cap 3. The final page limit is reduced to the
    // remaining budget (3 - 2 = 1); Firestore returns a FULL page of 1. That
    // short page must NOT be misread as exhaustion: the cap was hit and events
    // 4-5 were never examined, so `capped` must be true.
    const CAP_NOW = new Date('2028-03-15T09:00:00.000Z');
    await seedWindowEvents(CAP_NOW, 5);

    const summary = await runEventReminders(CAP_NOW, {
      ...baseLimits,
      pageSize: 2,
      maxCandidates: 3,
    });

    expect(summary.capped).toBe(true);
    expect(summary.candidates).toBe(3);
  });

  it('does NOT flag capped when a reduced final page is genuine exhaustion', async () => {
    // 3 candidates, pageSize 2, cap 100. The second page requests 2 and gets 1
    // (all that remain) — a short page shorter than the requested limit, i.e.
    // real end-of-results, not the cap. `capped` must stay false.
    const EXH_NOW = new Date('2028-06-20T09:00:00.000Z');
    await seedWindowEvents(EXH_NOW, 3);

    const summary = await runEventReminders(EXH_NOW, {
      ...baseLimits,
      pageSize: 2,
      maxCandidates: 100,
    });

    expect(summary.capped).toBe(false);
    expect(summary.candidates).toBe(3);
  });
});

describe('claimEventReminder – concurrent change between query and claim', () => {
  // The sweep reads candidates, then claims each in its own transaction. These
  // exercise the window BETWEEN those two steps, which runEventReminders cannot
  // stage on its own (its query would simply stop returning the event).

  it('claims a due event exactly once', async () => {
    const eventId = await seedEvent(inWindow());

    expect(await claimEventReminder(eventId, NOW, EVENT_REMINDER_LEAD_MS)).toBe(true);
    // A second claim sees the marker set and declines.
    expect(await claimEventReminder(eventId, NOW, EVENT_REMINDER_LEAD_MS)).toBe(false);
  });

  it('declines when the event was cancelled after being picked as a candidate', async () => {
    const eventId = await seedEvent(inWindow());
    await adminDb.collection('events').doc(eventId).update({ status: 'cancelled' });

    expect(await claimEventReminder(eventId, NOW, EVENT_REMINDER_LEAD_MS)).toBe(false);
    expect((await readEvent(eventId)).eventReminderSentAt).toBeUndefined();
  });

  it('declines when the start was pushed out of the window after the query', async () => {
    const eventId = await seedEvent(inWindow());
    await adminDb
      .collection('events')
      .doc(eventId)
      .update({ startsAt: Timestamp.fromDate(new Date(NOW.getTime() + 48 * HOUR)) });

    expect(await claimEventReminder(eventId, NOW, EVENT_REMINDER_LEAD_MS)).toBe(false);
    expect((await readEvent(eventId)).eventReminderSentAt).toBeUndefined();
  });

  it('declines for an event deleted from under the sweep', async () => {
    const eventId = await seedEvent(inWindow());
    await adminDb.collection('events').doc(eventId).delete();

    expect(await claimEventReminder(eventId, NOW, EVENT_REMINDER_LEAD_MS)).toBe(false);
  });
});
