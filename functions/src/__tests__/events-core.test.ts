/**
 * Unit tests for the events domain pure logic (events-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  autoCloseCandidateCutoff,
  autoCloseDueAt,
  AUTO_CLOSE_GRACE_MS,
  buildEventDocuments,
  buildEventUpdates,
  computeRsvpCountDeltas,
  effectiveEndInstant,
  guardCancellable,
  guardCompletable,
  guardCompleteActor,
  guardCoordinatePair,
  guardEventTimes,
  guardPublishable,
  guardUpdatableStatus,
  initialEventStatus,
  isAutoCloseDue,
  isMemberEventRateLimited,
  isZeroDeltas,
  MAX_EVENT_DURATION_MS,
  MEMBER_EVENT_RATE_LIMIT_MAX,
  MEMBER_EVENT_RATE_LIMIT_WINDOW_MS,
  memberEventRateLimitWindowStart,
  parseCancelEventInput,
  parseCreateEventInput,
  parseEventIdInput,
  parseUpdateEventInput,
  stockholmEndOfDay,
  type UpdateEventInput,
} from '../events/events-core';

/**
 * Wall-clock time of an instant in the Europe/Stockholm zone, normalized to
 * 'YYYY-MM-DD HH:MM:SS'. Built from `formatToParts` numeric fields rather than
 * the raw `format()` string so assertions don't depend on locale punctuation,
 * which is not a stable contract across Node/ICU/CLDR updates.
 */
function stockholmParts(iso: string): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Stockholm',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(iso));
  const field: Record<string, string> = {};
  for (const part of parts) {
    if (part.type !== 'literal') field[part.type] = part.value;
  }
  return `${field.year}-${field.month}-${field.day} ${field.hour}:${field.minute}:${field.second}`;
}

const serverTimestamp = () => 'SERVER_TS';

const validCreate = {
  title: 'Sunset cruise',
  startsAt: '2027-06-01T18:00:00.000Z',
  approximateArea: 'Stockholm area',
};

describe('events-core input parsing', () => {
  it('accepts a minimal createEventRequest', () => {
    const result = parseCreateEventInput(validCreate);
    expect(result.ok).toBe(true);
  });

  it('accepts a createEventRequest without approximateArea (optional since 2026-08)', () => {
    // The member create form dropped its "Approximate area" input, so the
    // callable must accept a payload that omits it entirely.
    expect(parseCreateEventInput({ title: 'No area', startsAt: validCreate.startsAt }).ok).toBe(
      true,
    );
  });

  it('rejects create input missing required fields', () => {
    // title + startsAt remain required; approximateArea does not.
    expect(parseCreateEventInput({ title: 'No start time' }).ok).toBe(false);
    expect(parseCreateEventInput({ startsAt: validCreate.startsAt }).ok).toBe(false);
    expect(parseCreateEventInput(undefined).ok).toBe(false);
  });

  it('rejects unknown keys (strict contract)', () => {
    expect(parseCreateEventInput({ ...validCreate, hackField: 1 }).ok).toBe(false);
    expect(parseUpdateEventInput({ eventId: 'e1', hackField: 1 }).ok).toBe(false);
  });

  it('enforces legacy length limits', () => {
    expect(parseCreateEventInput({ ...validCreate, title: 'x'.repeat(201) }).ok).toBe(false);
    expect(
      parseCreateEventInput({ ...validCreate, approximateArea: 'x'.repeat(201) }).ok,
    ).toBe(false);
    expect(parseCreateEventInput({ ...validCreate, description: 'x'.repeat(10001) }).ok).toBe(
      false,
    );
  });

  it('enforces coordinate ranges', () => {
    expect(parseCreateEventInput({ ...validCreate, latitude: 91, longitude: 10 }).ok).toBe(false);
    expect(parseCreateEventInput({ ...validCreate, latitude: 59.3, longitude: 181 }).ok).toBe(
      false,
    );
  });

  it('requires a non-empty reason to cancel', () => {
    expect(parseCancelEventInput({ eventId: 'e1', reason: 'Weather warning.' }).ok).toBe(true);
    expect(parseCancelEventInput({ eventId: 'e1', reason: '' }).ok).toBe(false);
    expect(parseCancelEventInput({ eventId: 'e1' }).ok).toBe(false);
  });

  it('requires eventId for id-only inputs', () => {
    expect(parseEventIdInput({ eventId: 'e1' }).ok).toBe(true);
    expect(parseEventIdInput({}).ok).toBe(false);
  });
});

describe('events-core business guards', () => {
  it('rejects endsAt at or before startsAt', () => {
    const start = '2027-06-01T18:00:00.000Z';
    expect(guardEventTimes(start, '2027-06-01T17:00:00.000Z').ok).toBe(false);
    expect(guardEventTimes(start, start).ok).toBe(false);
    expect(guardEventTimes(start, '2027-06-01T19:00:00.000Z').ok).toBe(true);
    expect(guardEventTimes(start, null).ok).toBe(true);
  });

  it('accepts an end exactly 3 days after start but rejects a longer span', () => {
    const start = '2027-06-01T18:00:00.000Z';
    // Exactly 72 hours later is allowed.
    const threeDays = guardEventTimes(start, '2027-06-04T18:00:00.000Z');
    expect(threeDays.ok).toBe(true);
    // One millisecond past 3 days is rejected as invalid-argument.
    const justOver = guardEventTimes(start, '2027-06-04T18:00:00.001Z');
    expect(justOver.ok).toBe(false);
    if (!justOver.ok) expect(justOver.code).toBe('invalid-argument');
    // Clearly-too-long spans are rejected too.
    expect(guardEventTimes(start, '2027-06-10T18:00:00.000Z').ok).toBe(false);
  });

  it('requires latitude and longitude as a pair', () => {
    expect(guardCoordinatePair(59.3, null).ok).toBe(false);
    expect(guardCoordinatePair(null, 18.0).ok).toBe(false);
    expect(guardCoordinatePair(59.3, 18.0).ok).toBe(true);
    expect(guardCoordinatePair(null, null).ok).toBe(true);
  });

  it('blocks updates to cancelled and completed events', () => {
    expect(guardUpdatableStatus('draft').ok).toBe(true);
    expect(guardUpdatableStatus('published').ok).toBe(true);
    expect(guardUpdatableStatus('cancelled').ok).toBe(false);
    expect(guardUpdatableStatus('completed').ok).toBe(false);
  });

  it('publishes only future-starting drafts with a title', () => {
    const now = new Date('2027-01-01T00:00:00Z');
    const base = {
      status: 'draft' as const,
      title: 'T',
      startsAt: new Date('2027-06-01T18:00:00Z'),
    };
    // A draft with no approximateArea is publishable — the field is optional
    // since 2026-08 (the create form dropped its input).
    expect(guardPublishable(base, now).ok).toBe(true);
    expect(guardPublishable({ ...base, status: 'published' }, now).ok).toBe(false);
    expect(guardPublishable({ ...base, title: '' }, now).ok).toBe(false);
    expect(
      guardPublishable({ ...base, startsAt: new Date('2026-01-01T00:00:00Z') }, now).ok,
    ).toBe(false);
  });

  it('cancels drafts and published events only once, never completed ones', () => {
    expect(guardCancellable('draft').ok).toBe(true);
    expect(guardCancellable('published').ok).toBe(true);
    expect(guardCancellable('cancelled').ok).toBe(false);
    expect(guardCancellable('completed').ok).toBe(false);
  });

  it('completes published events only', () => {
    expect(guardCompletable('published').ok).toBe(true);
    expect(guardCompletable('draft').ok).toBe(false);
    expect(guardCompletable('cancelled').ok).toBe(false);
  });
});

describe('events-core stockholmEndOfDay', () => {
  it('returns 23:59:59.999 local on the summer (CEST, UTC+2) start day', () => {
    // 2027-06-01T18:00Z is 20:00 in Stockholm → same calendar day.
    const eod = stockholmEndOfDay('2027-06-01T18:00:00.000Z');
    expect(eod).toBe('2027-06-01T21:59:59.999Z');
    expect(stockholmParts(eod)).toBe('2027-06-01 23:59:59');
  });

  it('returns 23:59:59.999 local on the winter (CET, UTC+1) start day', () => {
    const eod = stockholmEndOfDay('2027-01-15T18:00:00.000Z');
    expect(eod).toBe('2027-01-15T22:59:59.999Z');
    expect(stockholmParts(eod)).toBe('2027-01-15 23:59:59');
  });

  it('uses the Stockholm calendar day, not the UTC day', () => {
    // 23:30Z is already 01:30 the next day in Stockholm (UTC+2 in summer).
    const eod = stockholmEndOfDay('2027-06-01T23:30:00.000Z');
    expect(stockholmParts(eod)).toBe('2027-06-02 23:59:59');
  });

  it('is correct across the spring-forward DST boundary (end-of-day is CEST)', () => {
    // Sweden springs forward on 2027-03-28; the day still ends at 23:59 CEST.
    const eod = stockholmEndOfDay('2027-03-28T10:00:00.000Z');
    expect(stockholmParts(eod)).toBe('2027-03-28 23:59:59');
    expect(eod).toBe('2027-03-28T21:59:59.999Z');
  });

  it('is correct across the fall-back DST boundary (end-of-day is CET)', () => {
    // Sweden falls back on 2027-10-31; the day still ends at 23:59 CET.
    const eod = stockholmEndOfDay('2027-10-31T10:00:00.000Z');
    expect(stockholmParts(eod)).toBe('2027-10-31 23:59:59');
    expect(eod).toBe('2027-10-31T22:59:59.999Z');
  });
});

describe('events-core document builders', () => {
  it('defaults a missing endsAt to the Stockholm end-of-day of startsAt', () => {
    const parsed = parseCreateEventInput(validCreate);
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc } = buildEventDocuments(parsed.input, 'admin-1', serverTimestamp);

    const endsAt = eventDoc.endsAt as Date;
    expect(endsAt).toBeInstanceOf(Date);
    // validCreate.startsAt is 2027-06-01T18:00Z → end of that Stockholm day.
    expect(endsAt.toISOString()).toBe('2027-06-01T21:59:59.999Z');
    expect(stockholmParts(endsAt.toISOString())).toBe('2027-06-01 23:59:59');
  });

  it('preserves an explicitly provided endsAt', () => {
    const parsed = parseCreateEventInput({
      ...validCreate,
      endsAt: '2027-06-02T10:00:00.000Z',
    });
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc } = buildEventDocuments(parsed.input, 'admin-1', serverTimestamp);
    expect((eventDoc.endsAt as Date).toISOString()).toBe('2027-06-02T10:00:00.000Z');
  });

  it('splits teaser-safe and member-gated fields on create', () => {
    const parsed = parseCreateEventInput({
      ...validCreate,
      summary: 'Open to all',
      description: 'Long member-only text',
      locationName: 'Exact spot',
      address: 'Street 1',
      latitude: 59.3,
      longitude: 18.0,
    });
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc, privateDoc } = buildEventDocuments(parsed.input, 'admin-1', serverTimestamp);

    expect(eventDoc.title).toBe('Sunset cruise');
    expect(eventDoc.summary).toBe('Open to all');
    expect(eventDoc.status).toBe('draft');
    expect(eventDoc.rsvpCounts).toEqual({ going: 0, maybe: 0, not_going: 0 });
    expect(eventDoc.createdByUserId).toBe('admin-1');
    // The map location (place name + coordinates) is PUBLIC teaser data now, so
    // every signed-in user can see the pin; the long description and the precise
    // street address stay member-only on the private document.
    expect(eventDoc.locationName).toBe('Exact spot');
    expect(eventDoc.latitude).toBe(59.3);
    expect(eventDoc.longitude).toBe(18.0);
    expect(eventDoc).not.toHaveProperty('description');
    expect(eventDoc).not.toHaveProperty('address');

    expect(privateDoc.description).toBe('Long member-only text');
    expect(privateDoc.address).toBe('Street 1');
    expect(privateDoc).not.toHaveProperty('locationName');
    expect(privateDoc).not.toHaveProperty('latitude');
    expect(privateDoc).not.toHaveProperty('longitude');
  });

  it('stores approximateArea as null when the create request omits it', () => {
    // The member create form no longer collects an area; the field is optional
    // and the stored teaser doc keeps the key present (null) so its shape stays
    // stable.
    const parsed = parseCreateEventInput({ title: 'No area', startsAt: validCreate.startsAt });
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc } = buildEventDocuments(parsed.input, 'admin-1', serverTimestamp);
    expect(eventDoc.approximateArea).toBeNull();
  });

  it('routes partial updates to the correct document and tracks changedFields', () => {
    const parsed = parseUpdateEventInput({
      eventId: 'e1',
      title: 'New title',
      address: 'New street 2',
      // Map location edits now land on the PUBLIC teaser document.
      locationName: 'Harbour car park',
      latitude: 57.5,
      longitude: 12.1,
    });
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc, privateDoc, changedFields } = buildEventUpdates(
      parsed.input,
      serverTimestamp,
    );

    expect(eventDoc.title).toBe('New title');
    expect(eventDoc.updatedAt).toBe('SERVER_TS');
    expect(eventDoc.locationName).toBe('Harbour car park');
    expect(eventDoc.latitude).toBe(57.5);
    expect(eventDoc.longitude).toBe(12.1);
    expect(eventDoc).not.toHaveProperty('address');
    expect(privateDoc.address).toBe('New street 2');
    expect(privateDoc).not.toHaveProperty('locationName');
    expect(changedFields.sort()).toEqual([
      'address',
      'latitude',
      'locationName',
      'longitude',
      'title',
    ]);
  });

  it('produces no writes for an empty update', () => {
    const parsed = parseUpdateEventInput({ eventId: 'e1' });
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc, privateDoc, changedFields } = buildEventUpdates(
      parsed.input,
      serverTimestamp,
    );
    expect(Object.keys(eventDoc)).toHaveLength(0);
    expect(Object.keys(privateDoc)).toHaveLength(0);
    expect(changedFields).toHaveLength(0);
  });
});

describe('events-core update-path endsAt defaulting/removal', () => {
  // The emulator suite (events.emulator.test.ts) exercises events.update but not
  // endsAt defaulting/removal, and it cannot run in every environment (no local
  // Firebase emulator). These tests reproduce the exact decision in
  // manageEvent.update — using the same production pure functions it calls
  // (buildEventUpdates + stockholmEndOfDay) — so the update-path default is
  // covered without an emulator.
  const RESOLVE_UNTOUCHED = Symbol('endsAt-untouched');

  /**
   * Mirrors the endsAt resolution in manageEvent.update: given the parsed
   * update input and the stored event times, returns the value that would be
   * written to eventDoc.endsAt (or RESOLVE_UNTOUCHED when endsAt is not written).
   */
  function resolveUpdateEndsAt(
    input: UpdateEventInput,
    stored: { startsAt: string; endsAt: string | null },
  ): Date | null | typeof RESOLVE_UNTOUCHED {
    const { eventDoc } = buildEventUpdates(input, serverTimestamp);

    const effectiveStartsAt = input.startsAt ?? stored.startsAt;
    const resolvedEndsAt = input.endsAt !== undefined ? input.endsAt : stored.endsAt;
    const timesTouched = input.startsAt !== undefined || input.endsAt !== undefined;

    if (timesTouched && resolvedEndsAt === null) {
      return new Date(stockholmEndOfDay(effectiveStartsAt));
    }
    if ('endsAt' in eventDoc) return eventDoc.endsAt as Date | null;
    return RESOLVE_UNTOUCHED;
  }

  it('buildEventUpdates carries an explicit endsAt removal (null) as a changed field', () => {
    const parsed = parseUpdateEventInput({ eventId: 'e1', endsAt: null });
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc, changedFields } = buildEventUpdates(parsed.input, serverTimestamp);
    expect(eventDoc.endsAt).toBeNull();
    expect(changedFields).toContain('endsAt');
  });

  it('defaults to Stockholm end-of-day when endsAt is explicitly removed', () => {
    const parsed = parseUpdateEventInput({ eventId: 'e1', endsAt: null });
    if (!parsed.ok) throw new Error('expected ok');
    // Stored start 2027-06-01T18:00Z (summer); clearing the end defaults it to
    // the end of that Stockholm day rather than leaving it null.
    const resolved = resolveUpdateEndsAt(parsed.input, {
      startsAt: '2027-06-01T18:00:00.000Z',
      endsAt: '2027-06-02T10:00:00.000Z',
    });
    expect(resolved).toBeInstanceOf(Date);
    expect((resolved as Date).toISOString()).toBe('2027-06-01T21:59:59.999Z');
  });

  it('defaults to end-of-day of the new start when startsAt is edited and stored end is null', () => {
    const parsed = parseUpdateEventInput({
      eventId: 'e1',
      startsAt: '2027-01-15T18:00:00.000Z',
    });
    if (!parsed.ok) throw new Error('expected ok');
    // Winter start with no stored end → end-of-day of the *new* start (CET).
    const resolved = resolveUpdateEndsAt(parsed.input, {
      startsAt: '2027-06-01T18:00:00.000Z',
      endsAt: null,
    });
    expect(resolved).toBeInstanceOf(Date);
    expect((resolved as Date).toISOString()).toBe('2027-01-15T22:59:59.999Z');
  });

  it('preserves an explicitly provided endsAt without defaulting', () => {
    const parsed = parseUpdateEventInput({
      eventId: 'e1',
      endsAt: '2027-06-02T10:00:00.000Z',
    });
    if (!parsed.ok) throw new Error('expected ok');
    const resolved = resolveUpdateEndsAt(parsed.input, {
      startsAt: '2027-06-01T18:00:00.000Z',
      endsAt: null,
    });
    expect(resolved).toBeInstanceOf(Date);
    expect((resolved as Date).toISOString()).toBe('2027-06-02T10:00:00.000Z');
  });

  it('does not retroactively default endsAt when only non-time fields are edited', () => {
    const parsed = parseUpdateEventInput({ eventId: 'e1', title: 'Renamed cruise' });
    if (!parsed.ok) throw new Error('expected ok');
    // Times untouched: a stored null end stays null (no retroactive defaulting).
    const resolved = resolveUpdateEndsAt(parsed.input, {
      startsAt: '2027-06-01T18:00:00.000Z',
      endsAt: null,
    });
    expect(resolved).toBe(RESOLVE_UNTOUCHED);
  });
});

describe('events-core RSVP count deltas', () => {
  it('increments on first RSVP', () => {
    expect(computeRsvpCountDeltas(undefined, 'going')).toEqual({
      going: 1,
      maybe: 0,
      not_going: 0,
    });
  });

  it('moves the count when the answer changes', () => {
    expect(computeRsvpCountDeltas('going', 'not_going')).toEqual({
      going: -1,
      maybe: 0,
      not_going: 1,
    });
  });

  it('decrements on deletion', () => {
    expect(computeRsvpCountDeltas('maybe', undefined)).toEqual({
      going: 0,
      maybe: -1,
      not_going: 0,
    });
  });

  it('is a no-op for unchanged status and for unknown values', () => {
    expect(isZeroDeltas(computeRsvpCountDeltas('going', 'going'))).toBe(true);
    expect(isZeroDeltas(computeRsvpCountDeltas('bogus', 'bogus'))).toBe(true);
    expect(computeRsvpCountDeltas('bogus', 'going')).toEqual({
      going: 1,
      maybe: 0,
      not_going: 0,
    });
  });
});

describe('events-core member-created events', () => {
  it('defaults to the admin creator role (Phase 9b behaviour unchanged)', () => {
    const parsed = parseCreateEventInput(validCreate);
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc } = buildEventDocuments(parsed.input, 'admin-1', serverTimestamp);

    expect(eventDoc.status).toBe('draft');
    expect(eventDoc.createdByRole).toBe('admin');
  });

  it('publishes a member-created event immediately and attributes it', () => {
    const parsed = parseCreateEventInput(validCreate);
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc } = buildEventDocuments(parsed.input, 'member-1', serverTimestamp, 'member');

    expect(eventDoc.status).toBe('published');
    expect(eventDoc.createdByUserId).toBe('member-1');
    expect(eventDoc.createdByRole).toBe('member');
  });

  it('forces isOfficial false for a member, honours it for an admin', () => {
    const parsed = parseCreateEventInput({ ...validCreate, isOfficial: true });
    if (!parsed.ok) throw new Error('expected ok');

    // A member must never be able to mint the club-sanctioned badge.
    expect(buildEventDocuments(parsed.input, 'm1', serverTimestamp, 'member').eventDoc.isOfficial).toBe(
      false,
    );
    expect(buildEventDocuments(parsed.input, 'a1', serverTimestamp, 'admin').eventDoc.isOfficial).toBe(
      true,
    );
  });

  it('maps creator role to the initial status', () => {
    expect(initialEventStatus('admin')).toBe('draft');
    expect(initialEventStatus('member')).toBe('published');
  });

  it('rate-limits a member at the cap, not below it', () => {
    expect(isMemberEventRateLimited(0)).toBe(false);
    expect(isMemberEventRateLimited(MEMBER_EVENT_RATE_LIMIT_MAX - 1)).toBe(false);
    expect(isMemberEventRateLimited(MEMBER_EVENT_RATE_LIMIT_MAX)).toBe(true);
    expect(isMemberEventRateLimited(MEMBER_EVENT_RATE_LIMIT_MAX + 1)).toBe(true);
  });

  it('computes the rate-limit window start one window back', () => {
    const now = new Date('2027-06-01T12:00:00.000Z');
    expect(memberEventRateLimitWindowStart(now).getTime()).toBe(
      now.getTime() - MEMBER_EVENT_RATE_LIMIT_WINDOW_MS,
    );
    expect(MEMBER_EVENT_RATE_LIMIT_WINDOW_MS).toBe(24 * 60 * 60 * 1000);
  });
});

describe('guardCompleteActor', () => {
  const creatorUid = 'creator-uid';

  it('lets the creator end their own event', () => {
    expect(
      guardCompleteActor({ uid: creatorUid, isAdmin: false }, { createdByUserId: creatorUid }),
    ).toEqual({ ok: true });
  });

  it('lets an admin end anyone else’s event', () => {
    expect(
      guardCompleteActor({ uid: 'admin-uid', isAdmin: true }, { createdByUserId: creatorUid }),
    ).toEqual({ ok: true });
  });

  it('refuses a member who did not create the event', () => {
    const result = guardCompleteActor(
      { uid: 'stranger-uid', isAdmin: false },
      { createdByUserId: creatorUid },
    );
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('permission-denied');
  });

  it('refuses a non-admin when the event has no recorded creator', () => {
    // A legacy/unattributed event must not become endable by every member:
    // an absent createdByUserId matches nobody rather than everybody.
    for (const createdByUserId of [null, undefined, '']) {
      const result = guardCompleteActor({ uid: 'someone', isAdmin: false }, { createdByUserId });
      expect(result.ok).toBe(false);
    }
    // ...but an admin can still end it.
    expect(guardCompleteActor({ uid: 'admin', isAdmin: true }, { createdByUserId: null })).toEqual({
      ok: true,
    });
  });
});

describe('event auto-close', () => {
  const start = new Date('2026-08-09T16:00:00.000Z');

  it('uses the explicit endsAt as the effective end', () => {
    const end = new Date('2026-08-09T18:00:00.000Z');
    expect(effectiveEndInstant(start, end)).toEqual(end);
  });

  it('falls back to the Stockholm end-of-day when endsAt is absent', () => {
    const expected = new Date(stockholmEndOfDay(start.toISOString()));
    expect(effectiveEndInstant(start, null)).toEqual(expected);
    expect(effectiveEndInstant(start, undefined)).toEqual(expected);
  });

  it('is due exactly one grace window after the effective end, not before', () => {
    const end = new Date('2026-08-09T18:00:00.000Z');
    const due = autoCloseDueAt(start, end);
    expect(due.getTime()).toBe(end.getTime() + AUTO_CLOSE_GRACE_MS);

    const event = { status: 'published' as const, startsAt: start, endsAt: end };
    // One millisecond before the due instant: still live.
    expect(isAutoCloseDue(event, new Date(due.getTime() - 1))).toBe(false);
    // At the due instant, and after: closable.
    expect(isAutoCloseDue(event, due)).toBe(true);
    expect(isAutoCloseDue(event, new Date(due.getTime() + 1))).toBe(true);
  });

  it('leaves an event that has ended but is still inside the grace window', () => {
    const end = new Date('2026-08-09T18:00:00.000Z');
    const justEnded = new Date(end.getTime() + 60_000);
    expect(isAutoCloseDue({ status: 'published', startsAt: start, endsAt: end }, justEnded)).toBe(
      false,
    );
  });

  it('never closes a future or in-progress event', () => {
    const end = new Date('2026-08-09T18:00:00.000Z');
    const beforeItStarts = new Date('2026-08-01T00:00:00.000Z');
    const midEvent = new Date('2026-08-09T17:00:00.000Z');
    expect(isAutoCloseDue({ status: 'published', startsAt: start, endsAt: end }, beforeItStarts))
      .toBe(false);
    expect(isAutoCloseDue({ status: 'published', startsAt: start, endsAt: end }, midEvent)).toBe(
      false,
    );
  });

  it('only ever closes published events (draft/cancelled/completed are skipped)', () => {
    const end = new Date('2026-08-09T18:00:00.000Z');
    const wellPast = new Date('2027-07-17T00:00:00.000Z');
    // Idempotency at the decision layer: an already-completed event is not due
    // a second time, and a cancelled event is never resurrected.
    for (const status of ['draft', 'cancelled', 'completed'] as const) {
      expect(isAutoCloseDue({ status, startsAt: start, endsAt: end }, wellPast)).toBe(false);
    }
    expect(isAutoCloseDue({ status: 'published', startsAt: start, endsAt: end }, wellPast)).toBe(
      true,
    );
  });

  it("closes Seb's stale 9 August event, end-of-day default and all", () => {
    // The reported bug: an event created for 9 August, no explicit end, still
    // sitting in the upcoming list the following July.
    const augustNinth = new Date('2026-08-09T16:00:00.000Z');
    const theFollowingJuly = new Date('2027-07-17T09:00:00.000Z');
    expect(
      isAutoCloseDue({ status: 'published', startsAt: augustNinth, endsAt: null }, theFollowingJuly),
    ).toBe(true);
  });

  it('pins the documented 6-hour grace and what it means in local time', () => {
    // AUTO_CLOSE_GRACE_MS's doc comment makes two concrete promises. Both are
    // pinned here so neither can silently drift away from the prose.
    expect(AUTO_CLOSE_GRACE_MS).toBe(6 * 60 * 60 * 1000);

    // Promise: an event with no explicit end (→ Stockholm end-of-day default)
    // closes at ~06:00 the next Stockholm morning — after the event, before
    // anyone opens the list, and inside the same 24h.
    const noExplicitEnd = { status: 'published' as const, startsAt: start, endsAt: null };
    const due = autoCloseDueAt(start, null);
    expect(stockholmParts(due.toISOString())).toBe('2026-08-10 05:59:59');
    // Still listed at 05:00 local the morning after; gone by 07:00 local.
    expect(isAutoCloseDue(noExplicitEnd, new Date('2026-08-10T03:00:00.000Z'))).toBe(false);
    expect(isAutoCloseDue(noExplicitEnd, new Date('2026-08-10T05:00:00.000Z'))).toBe(true);
    // And never more than 24h after the event's own day ends.
    expect(due.getTime() - new Date(stockholmEndOfDay(start.toISOString())).getTime()).toBeLessThan(
      24 * 60 * 60 * 1000,
    );
  });

  it('sets the candidate cutoff one grace window back from now', () => {
    const now = new Date('2027-07-17T09:00:00.000Z');
    expect(autoCloseCandidateCutoff(now).getTime()).toBe(now.getTime() - AUTO_CLOSE_GRACE_MS);
  });

  it('never lets the startsAt candidate cutoff hide a due event', () => {
    // The soundness property the sweep's query shape rests on (and the reason
    // it needs no `status, endsAt` index): for ANY event that guardEventTimes
    // accepts, due implies startsAt <= autoCloseCandidateCutoff(now). Swept
    // across durations from 1ms to the 3-day maximum, and across a range of
    // `now` values either side of the due instant.
    const now = new Date('2027-07-17T09:00:00.000Z');
    const cutoff = autoCloseCandidateCutoff(now);
    const durations = [1, 1_000, 60_000, 3 * 60 * 60 * 1000, MAX_EVENT_DURATION_MS];
    for (const durationMs of durations) {
      for (const offsetMs of [-1, 0, 1, 60_000, 30 * 24 * 60 * 60 * 1000]) {
        // Pin startsAt so the event's due instant lands at now + offsetMs.
        const startsAt = new Date(now.getTime() + offsetMs - AUTO_CLOSE_GRACE_MS - durationMs);
        const endsAt = new Date(startsAt.getTime() + durationMs);
        expect(guardEventTimes(startsAt.toISOString(), endsAt.toISOString())).toEqual({ ok: true });

        const due = isAutoCloseDue({ status: 'published', startsAt, endsAt }, now);
        if (due) {
          expect(startsAt.getTime()).toBeLessThanOrEqual(cutoff.getTime());
        }
      }
    }
  });
});
