/**
 * Event auto-close sweep emulator tests (functions/src/events/scheduled.ts).
 *
 * Drives the exported runEventAutoClose runner at a deterministic instant
 * against seeded events, covering: a past event closing, a future one being
 * left alone, the grace window, idempotency, terminal statuses and that closing
 * an event credits NO attendance (the badge counts verified check-ins, not
 * RSVPs) — plus the two things a unit test cannot reach: that the candidate
 * PAGING advances past a full page of still-running events, and that a status
 * change racing the sweep (closeEvent) is honoured rather than overwritten.
 *
 * Events are seeded through the Admin SDK rather than the callables so start
 * and end instants can be placed in the past, which events.create rightly
 * refuses to do.
 *
 * ISOLATION NOTE: emulator test files share one Firestore, and this sweep
 * queries the whole `events` collection. Every assertion is therefore scoped
 * to this file's own event IDs, never to the run's total closure count, which
 * another file's fixtures could perturb. Conversely this sweep can only ever
 * touch `published` events whose start is already past — other event fixtures
 * use future starts or non-published statuses, so they are not candidates.
 *
 * Requires the Firestore emulator — run via:
 *   pnpm emulators:test
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { beforeAll, describe, expect, it } from 'vitest';
import { closeEvent, runEventAutoClose } from '../events/scheduled';
import { AUTO_CLOSE_GRACE_MS } from '../events/events-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'events-autoclose-tests');
const adminDb = getAdminFirestore(adminApp);

/** The fixed instant every test in this file sweeps at. */
const NOW = new Date('2027-07-17T09:00:00.000Z');
const HOUR = 60 * 60 * 1000;

interface SeedOptions {
  status?: string;
  startsAt: Date;
  endsAt: Date | null;
  createdByUserId?: string;
}

/** Seeds one events/{id} teaser document and returns its id. */
async function seedEvent(options: SeedOptions): Promise<string> {
  const ref = adminDb.collection('events').doc();
  await ref.set({
    title: 'Auto-close fixture',
    summary: null,
    startsAt: Timestamp.fromDate(options.startsAt),
    endsAt: options.endsAt ? Timestamp.fromDate(options.endsAt) : null,
    approximateArea: 'Kungsbacka',
    isOfficial: false,
    status: options.status ?? 'published',
    cancelledAt: null,
    rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
    createdByUserId: options.createdByUserId ?? 'seed-uid',
    createdByRole: 'member',
    createdAt: Timestamp.fromDate(new Date(options.startsAt.getTime() - 24 * HOUR)),
    updatedAt: Timestamp.fromDate(new Date(options.startsAt.getTime() - 24 * HOUR)),
  });
  return ref.id;
}

async function readEvent(eventId: string): Promise<FirebaseFirestore.DocumentData> {
  const snap = await adminDb.collection('events').doc(eventId).get();
  return snap.data()!;
}

/** An event that finished well before NOW — comfortably past the grace window. */
const longPast = { startsAt: new Date(NOW.getTime() - 30 * HOUR), endsAt: new Date(NOW.getTime() - 28 * HOUR) };

describe('events auto-close sweep', () => {
  it('completes a published event whose end is past the grace window', async () => {
    const eventId = await seedEvent(longPast);

    await runEventAutoClose(NOW);

    const event = await readEvent(eventId);
    expect(event.status).toBe('completed');
    // Stamped so an operator can tell an auto-close from an admin's click.
    expect(event.autoClosedAt).not.toBeNull();
    expect(event.autoClosedAt).toBeDefined();
  });

  it("closes Seb's stale 9 August event, which had no explicit end", async () => {
    // The reported bug, end to end: created for 9 August, never given an
    // endsAt, still `published` the following July.
    const eventId = await seedEvent({
      startsAt: new Date('2026-08-09T16:00:00.000Z'),
      endsAt: null,
    });

    await runEventAutoClose(NOW);

    expect((await readEvent(eventId)).status).toBe('completed');
  });

  it('leaves a future event published', async () => {
    const eventId = await seedEvent({
      startsAt: new Date(NOW.getTime() + 48 * HOUR),
      endsAt: new Date(NOW.getTime() + 50 * HOUR),
    });

    await runEventAutoClose(NOW);

    expect((await readEvent(eventId)).status).toBe('published');
  });

  it('leaves an event that has ended but is still inside the grace window', async () => {
    const eventId = await seedEvent({
      startsAt: new Date(NOW.getTime() - AUTO_CLOSE_GRACE_MS - HOUR),
      // Ended 5 minutes ago — the grace window has not elapsed.
      endsAt: new Date(NOW.getTime() - 5 * 60 * 1000),
    });

    await runEventAutoClose(NOW);

    expect((await readEvent(eventId)).status).toBe('published');
  });

  it('is idempotent: a second sweep neither re-closes nor re-stamps', async () => {
    const eventId = await seedEvent(longPast);

    await runEventAutoClose(NOW);
    const first = await readEvent(eventId);
    expect(first.status).toBe('completed');

    const secondRun = await runEventAutoClose(NOW);
    const second = await readEvent(eventId);
    expect(second.status).toBe('completed');
    // The autoClosedAt stamp is from the first sweep — untouched by the second.
    expect(second.autoClosedAt.toMillis()).toBe(first.autoClosedAt.toMillis());
    expect(second.updatedAt.toMillis()).toBe(first.updatedAt.toMillis());
    // Nothing this file seeded is closable a second time.
    expect(secondRun.closed).toBe(0);
  });

  it('never closes draft or cancelled events, however far past they are', async () => {
    const draftId = await seedEvent({ ...longPast, status: 'draft' });
    const cancelledId = await seedEvent({ ...longPast, status: 'cancelled' });

    await runEventAutoClose(NOW);

    expect((await readEvent(draftId)).status).toBe('draft');
    expect((await readEvent(cancelledId)).status).toBe('cancelled');
  });

  it('does NOT credit attendance to going attendees — the badge counts check-ins', async () => {
    // The attendance badge counts VERIFIED check-ins (points-onAttendanceVerified),
    // not RSVPs, so auto-closing an event must credit no one — an RSVP is a
    // statement of intent, not proof anyone showed up.
    const attendeeUid = `autoclose-attendee-${Date.now()}`;
    const eventId = await seedEvent(longPast);
    await adminDb
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .doc(attendeeUid)
      .set({ status: 'going', updatedAt: Timestamp.fromDate(NOW) });

    await runEventAutoClose(NOW);

    // The event closed, but the going-RSVP earned no attendance credit.
    expect((await readEvent(eventId)).status).toBe('completed');
    const progress = await adminDb.collection('badgeProgress').doc(attendeeUid).get();
    expect(progress.exists).toBe(false);
  });
});

describe('events auto-close – concurrent status change', () => {
  // The sweep reads candidates, then closes each in its own transaction. These
  // exercise the window BETWEEN those two steps, where another actor moves the
  // event out of `published` — a race runEventAutoClose cannot stage on its own
  // (its query would simply stop returning the event), so closeEvent is driven
  // directly, exactly as the sweep drives it once a candidate is in hand.

  it('does not resurrect an event cancelled after it was picked as a candidate', async () => {
    const eventId = await seedEvent(longPast);
    // Stand in for events.cancel landing in the race window.
    await adminDb
      .collection('events')
      .doc(eventId)
      .update({ status: 'cancelled', cancelledAt: Timestamp.fromDate(NOW) });

    expect(await closeEvent(eventId)).toBe(false);
    const event = await readEvent(eventId);
    expect(event.status).toBe('cancelled');
    expect(event.autoClosedAt).toBeUndefined();
  });

  it('does not double-close an event completed after it was picked as a candidate', async () => {
    const eventId = await seedEvent(longPast);
    // Stand in for events.complete (admin or creator) landing in the window.
    await adminDb.collection('events').doc(eventId).update({ status: 'completed' });

    // Already completed, so the sweep reports no closure and leaves it alone.
    expect(await closeEvent(eventId)).toBe(false);
    expect((await readEvent(eventId)).autoClosedAt).toBeUndefined();
  });

  it('reports no closure for an event deleted from under the sweep', async () => {
    const eventId = await seedEvent(longPast);
    await adminDb.collection('events').doc(eventId).delete();

    expect(await closeEvent(eventId)).toBe(false);
  });
});

describe('events auto-close – bounded work per run', () => {
  it('stops reading at the scanned-candidate cap even when nothing is due', async () => {
    // The cap bounds READS, which MAX_CLOSURES_PER_RUN cannot: these candidates
    // all match the coarse query (published, started before the cutoff) but none
    // is due, so a sweep without a scan bound would page through all of them.
    const notDueStart = new Date(NOW.getTime() - AUTO_CLOSE_GRACE_MS - 3 * HOUR);
    const ids: string[] = [];
    for (let i = 0; i < 8; i += 1) {
      ids.push(
        await seedEvent({
          startsAt: new Date(notDueStart.getTime() + i),
          // Still inside its grace window → matched by the query, skipped in memory.
          endsAt: new Date(NOW.getTime() - 60_000),
        }),
      );
    }

    const result = await runEventAutoClose(NOW, { maxClosures: 200, maxCandidatesScanned: 3 });

    // THE point of the cap: reads stop at the budget. Asserting only
    // `closed === 0` would pass with no cap at all, since none of these is due.
    expect(result.scanned).toBe(3);
    expect(result.closed).toBe(0);
    // All still published — the cap changed how much was read, not correctness.
    for (const id of ids) {
      expect((await readEvent(id)).status).toBe('published');
    }
  }, 60_000);

  it('honours the closure cap and drains the remainder on the next run', async () => {
    const dueIds: string[] = [];
    for (let i = 0; i < 4; i += 1) {
      dueIds.push(
        await seedEvent({
          startsAt: new Date(NOW.getTime() - 40 * HOUR + i),
          endsAt: new Date(NOW.getTime() - 38 * HOUR),
        }),
      );
    }

    const firstRun = await runEventAutoClose(NOW, { maxClosures: 2, maxCandidatesScanned: 2000 });
    expect(firstRun.closed).toBe(2);

    // Oldest-first: the sweep drains the rest on subsequent runs rather than
    // stranding them.
    await runEventAutoClose(NOW, { maxClosures: 2, maxCandidatesScanned: 2000 });
    await runEventAutoClose(NOW, { maxClosures: 2, maxCandidatesScanned: 2000 });
    for (const id of dueIds) {
      expect((await readEvent(id)).status).toBe('completed');
    }
  }, 90_000);
});

describe('events auto-close paging', () => {
  // PAGE_SIZE in scheduled.ts. A due event placed after this many non-due
  // candidates can only be reached by a sweep whose cursor advances.
  const PAGE_SIZE = 100;

  beforeAll(async () => {
    // Seed a full page of candidates that are NOT due: started before the
    // candidate cutoff (so the query returns them) but still inside their
    // grace window (so the in-memory test skips them). A sweep that re-queried
    // from the start instead of advancing a cursor would re-read these forever
    // and never see the due event behind them.
    const fillerStart = new Date(NOW.getTime() - AUTO_CLOSE_GRACE_MS - 2 * HOUR);
    const batch = adminDb.batch();
    for (let i = 0; i < PAGE_SIZE; i += 1) {
      batch.set(adminDb.collection('events').doc(), {
        title: `Auto-close paging filler ${i}`,
        // Distinct startsAt values, all before the due event's, so the fillers
        // deterministically occupy the first page of the startsAt-ordered query.
        startsAt: Timestamp.fromDate(new Date(fillerStart.getTime() + i)),
        endsAt: Timestamp.fromDate(new Date(NOW.getTime() - 60_000)),
        approximateArea: 'Kungsbacka',
        isOfficial: false,
        status: 'published',
        cancelledAt: null,
        rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
        createdByUserId: 'paging-seed-uid',
        createdByRole: 'member',
        createdAt: Timestamp.fromDate(fillerStart),
        updatedAt: Timestamp.fromDate(fillerStart),
      });
    }
    await batch.commit();
  }, 60_000);

  it('reaches a due event sitting behind a full page of still-running ones', async () => {
    // Starts after every filler, so it sorts onto the second page; ended long
    // enough ago to be due.
    const dueId = await seedEvent({
      startsAt: new Date(NOW.getTime() - AUTO_CLOSE_GRACE_MS - HOUR),
      endsAt: new Date(NOW.getTime() - AUTO_CLOSE_GRACE_MS - 30 * 60 * 1000),
    });

    await runEventAutoClose(NOW);

    expect((await readEvent(dueId)).status).toBe('completed');
  }, 60_000);
});
