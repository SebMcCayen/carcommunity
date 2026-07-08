/**
 * Unit tests for the events domain pure logic (events-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildEventDocuments,
  buildEventUpdates,
  computeRsvpCountDeltas,
  guardCancellable,
  guardCompletable,
  guardCoordinatePair,
  guardEventTimes,
  guardPublishable,
  guardUpdatableStatus,
  isZeroDeltas,
  parseCancelEventInput,
  parseCreateEventInput,
  parseEventIdInput,
  parseUpdateEventInput,
  stockholmEndOfDay,
} from '../events/events-core';

/** Wall-clock time (y/m/d h:m:s.ms) of an instant in the Europe/Stockholm zone. */
function stockholmParts(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Stockholm',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(iso));
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

  it('rejects create input missing required fields', () => {
    expect(parseCreateEventInput({ title: 'No area or start' }).ok).toBe(false);
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

  it('publishes only future-starting drafts with required fields', () => {
    const now = new Date('2027-01-01T00:00:00Z');
    const base = {
      status: 'draft' as const,
      title: 'T',
      approximateArea: 'A',
      startsAt: new Date('2027-06-01T18:00:00Z'),
    };
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
    expect(stockholmParts(eod)).toBe('01/06/2027, 23:59:59');
  });

  it('returns 23:59:59.999 local on the winter (CET, UTC+1) start day', () => {
    const eod = stockholmEndOfDay('2027-01-15T18:00:00.000Z');
    expect(eod).toBe('2027-01-15T22:59:59.999Z');
    expect(stockholmParts(eod)).toBe('15/01/2027, 23:59:59');
  });

  it('uses the Stockholm calendar day, not the UTC day', () => {
    // 23:30Z is already 01:30 the next day in Stockholm (UTC+2 in summer).
    const eod = stockholmEndOfDay('2027-06-01T23:30:00.000Z');
    expect(stockholmParts(eod)).toBe('02/06/2027, 23:59:59');
  });

  it('is correct across the spring-forward DST boundary (end-of-day is CEST)', () => {
    // Sweden springs forward on 2027-03-28; the day still ends at 23:59 CEST.
    const eod = stockholmEndOfDay('2027-03-28T10:00:00.000Z');
    expect(stockholmParts(eod)).toBe('28/03/2027, 23:59:59');
    expect(eod).toBe('2027-03-28T21:59:59.999Z');
  });

  it('is correct across the fall-back DST boundary (end-of-day is CET)', () => {
    // Sweden falls back on 2027-10-31; the day still ends at 23:59 CET.
    const eod = stockholmEndOfDay('2027-10-31T10:00:00.000Z');
    expect(stockholmParts(eod)).toBe('31/10/2027, 23:59:59');
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
    expect(stockholmParts(endsAt.toISOString())).toBe('01/06/2027, 23:59:59');
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
    // Exact location must never appear on the teaser document.
    expect(eventDoc).not.toHaveProperty('description');
    expect(eventDoc).not.toHaveProperty('locationName');
    expect(eventDoc).not.toHaveProperty('address');
    expect(eventDoc).not.toHaveProperty('latitude');
    expect(eventDoc).not.toHaveProperty('longitude');

    expect(privateDoc.description).toBe('Long member-only text');
    expect(privateDoc.locationName).toBe('Exact spot');
    expect(privateDoc.latitude).toBe(59.3);
    expect(privateDoc.longitude).toBe(18.0);
  });

  it('routes partial updates to the correct document and tracks changedFields', () => {
    const parsed = parseUpdateEventInput({
      eventId: 'e1',
      title: 'New title',
      address: 'New street 2',
    });
    if (!parsed.ok) throw new Error('expected ok');
    const { eventDoc, privateDoc, changedFields } = buildEventUpdates(
      parsed.input,
      serverTimestamp,
    );

    expect(eventDoc.title).toBe('New title');
    expect(eventDoc.updatedAt).toBe('SERVER_TS');
    expect(eventDoc).not.toHaveProperty('address');
    expect(privateDoc.address).toBe('New street 2');
    expect(changedFields.sort()).toEqual(['address', 'title']);
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
