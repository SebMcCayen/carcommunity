/**
 * Unit tests for the public-site homepage feed pure logic
 * (events/publicSite-core.ts). No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildHomepageEventsFile,
  guardPublicSiteActor,
  guardPublicSiteTogglable,
  HOMEPAGE_DESC_MAX_LENGTH,
  HOMEPAGE_FILE_NOTICE,
  homepageDesc,
  homepageFileEquivalent,
  isHomepageSyncRelevant,
  parseSetPublicSiteInput,
  PUBLIC_EVENT_BASE_URL,
  publicEventUrl,
  selectHomepageEvents,
  stockholmDateTimeParts,
  type PublicSiteEventSource,
} from '../events/publicSite-core';
import {
  buildEventDocuments,
  parseCreateEventInput,
  parseUpdateEventInput,
} from '../events/events-core';

const NOW = new Date('2026-08-12T10:00:00Z');

function source(overrides: Partial<PublicSiteEventSource> = {}): PublicSiteEventSource {
  return {
    eventId: 'event-1',
    status: 'published',
    title: 'Kvällscruising',
    startsAt: new Date('2026-08-20T17:00:00Z'),
    locationName: 'Kungsbacka station',
    approximateArea: 'Kungsbacka',
    summary: 'Samling vid stationen.',
    description: 'Lång medlemsbeskrivning.',
    ...overrides,
  };
}

describe('parseSetPublicSiteInput', () => {
  it('accepts { eventId, enabled }', () => {
    const parsed = parseSetPublicSiteInput({ eventId: 'e1', enabled: true });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.input).toEqual({ eventId: 'e1', enabled: true });
    }
  });

  it.each([
    ['missing enabled', { eventId: 'e1' }],
    ['missing eventId', { enabled: true }],
    ['blank eventId', { eventId: '   ', enabled: false }],
    ['non-boolean enabled', { eventId: 'e1', enabled: 'yes' }],
    ['unknown extra key', { eventId: 'e1', enabled: true, force: true }],
    ['no payload', undefined],
  ])('rejects %s', (_label, data) => {
    expect(parseSetPublicSiteInput(data).ok).toBe(false);
  });
});

describe('guardPublicSiteActor', () => {
  const event = { createdByUserId: 'creator-uid' };

  it('allows the creator', () => {
    expect(guardPublicSiteActor({ uid: 'creator-uid', isAdmin: false }, event).ok).toBe(true);
  });

  it('allows an admin who is not the creator', () => {
    expect(guardPublicSiteActor({ uid: 'admin-uid', isAdmin: true }, event).ok).toBe(true);
  });

  it('denies any other member', () => {
    const guard = guardPublicSiteActor({ uid: 'other-uid', isAdmin: false }, event);
    expect(guard).toMatchObject({ ok: false, code: 'permission-denied' });
  });

  it('denies a non-admin when the event has no creator record', () => {
    const guard = guardPublicSiteActor({ uid: 'other-uid', isAdmin: false }, {});
    expect(guard).toMatchObject({ ok: false, code: 'permission-denied' });
  });
});

describe('guardPublicSiteTogglable', () => {
  it('allows enabling on draft and published', () => {
    expect(guardPublicSiteTogglable('draft', true).ok).toBe(true);
    expect(guardPublicSiteTogglable('published', true).ok).toBe(true);
  });

  it('refuses enabling on cancelled/completed', () => {
    expect(guardPublicSiteTogglable('cancelled', true)).toMatchObject({
      ok: false,
      code: 'failed-precondition',
    });
    expect(guardPublicSiteTogglable('completed', true)).toMatchObject({
      ok: false,
      code: 'failed-precondition',
    });
  });

  it('always allows disabling', () => {
    for (const status of ['draft', 'published', 'cancelled', 'completed'] as const) {
      expect(guardPublicSiteTogglable(status, false).ok).toBe(true);
    }
  });
});

describe('publicEventUrl', () => {
  it('is the /e/{eventId} page on the public site', () => {
    expect(publicEventUrl('abc123')).toBe(`${PUBLIC_EVENT_BASE_URL}/e/abc123`);
  });
});

describe('homepageDesc', () => {
  it('passes short text through trimmed', () => {
    expect(homepageDesc('  Öppen träff.  ')).toBe('Öppen träff.');
  });

  it('collapses newlines and whitespace runs to single spaces', () => {
    expect(homepageDesc('Rad ett.\n\nRad  två.\tRad tre.')).toBe('Rad ett. Rad två. Rad tre.');
  });

  it('maps null/undefined to an empty string', () => {
    expect(homepageDesc(null)).toBe('');
    expect(homepageDesc(undefined)).toBe('');
  });

  it(`truncates to at most ${HOMEPAGE_DESC_MAX_LENGTH} chars ending in an ellipsis`, () => {
    const long = 'x'.repeat(400);
    const truncated = homepageDesc(long);
    expect(truncated.length).toBe(HOMEPAGE_DESC_MAX_LENGTH);
    expect(truncated.endsWith('…')).toBe(true);
  });

  it('keeps text exactly at the limit untouched', () => {
    const exact = 'y'.repeat(HOMEPAGE_DESC_MAX_LENGTH);
    expect(homepageDesc(exact)).toBe(exact);
  });
});

describe('stockholmDateTimeParts', () => {
  it('renders CEST (summer, UTC+2) wall-clock time', () => {
    // 17:00 UTC on an August day is 19:00 in Stockholm.
    expect(stockholmDateTimeParts(new Date('2026-08-20T17:00:00Z'))).toEqual({
      date: '2026-08-20',
      time: '19:00',
    });
  });

  it('renders CET (winter, UTC+1) wall-clock time', () => {
    expect(stockholmDateTimeParts(new Date('2026-12-05T18:30:00Z'))).toEqual({
      date: '2026-12-05',
      time: '19:30',
    });
  });

  it('rolls the calendar date across the local midnight boundary', () => {
    // 22:30 UTC in summer is 00:30 the NEXT local day.
    expect(stockholmDateTimeParts(new Date('2026-08-20T22:30:00Z'))).toEqual({
      date: '2026-08-21',
      time: '00:30',
    });
  });
});

describe('selectHomepageEvents', () => {
  it('maps an event to the agreed homepage contract entry', () => {
    const [entry] = selectHomepageEvents([source()], NOW);
    expect(entry).toEqual({
      title: 'Kvällscruising',
      startsAt: '2026-08-20T17:00:00.000Z',
      endsAt: '2026-08-20T21:59:59.999Z',
      date: '2026-08-20',
      time: '19:00',
      place: 'Kungsbacka station',
      desc: 'Samling vid stationen.',
      url: `${PUBLIC_EVENT_BASE_URL}/e/event-1`,
      source: 'app',
    });
  });

  it('includes upcoming and in-progress events, including one starting exactly now', () => {
    const events = selectHomepageEvents(
      [
        source({ eventId: 'past', startsAt: new Date('2026-08-01T10:00:00Z') }),
        source({ eventId: 'now', startsAt: NOW }),
        source({ eventId: 'running', startsAt: new Date('2026-08-12T09:00:00Z') }),
        source({ eventId: 'future', startsAt: new Date('2026-08-13T10:00:00Z') }),
      ],
      NOW,
    );
    expect(events.map((event) => event.url)).toEqual([
      publicEventUrl('running'),
      publicEventUrl('now'),
      publicEventUrl('future'),
    ]);
  });

  it('uses an explicit overnight end and removes the event exactly at that instant', () => {
    const event = source({
      startsAt: new Date('2026-08-20T20:00:00+02:00'),
      endsAt: new Date('2026-08-21T02:00:00+02:00'),
    });
    const [entry] = selectHomepageEvents([event], new Date('2026-08-20T23:59:59.999Z'));
    expect(entry).toMatchObject({
      startsAt: '2026-08-20T18:00:00.000Z',
      endsAt: '2026-08-21T00:00:00.000Z',
      date: '2026-08-20',
      time: '20:00',
    });
    expect(selectHomepageEvents([event], event.endsAt!)).toEqual([]);
    expect(selectHomepageEvents([event], new Date('2026-08-21T00:00:00.001Z'))).toEqual([]);
  });

  it.each([
    ['summer', '2026-08-20T17:00:00Z', '2026-08-20T21:59:59.999Z', '2026-08-20'],
    ['winter', '2026-01-20T17:00:00Z', '2026-01-20T22:59:59.999Z', '2026-01-20'],
    ['next Stockholm day', '2026-08-20T23:30:00Z', '2026-08-21T21:59:59.999Z', '2026-08-21'],
    ['DST spring change', '2026-03-29T00:30:00Z', '2026-03-29T21:59:59.999Z', '2026-03-29'],
    ['DST autumn change', '2026-10-25T00:30:00Z', '2026-10-25T22:59:59.999Z', '2026-10-25'],
  ])('defaults missing/null ends to Stockholm end-of-day: %s', (_label, start, end, date) => {
    for (const endsAt of [null, undefined]) {
      const event = source({ startsAt: new Date(start), endsAt });
      const [entry] = selectHomepageEvents([event], new Date(new Date(end).getTime() - 1));
      expect(entry).toMatchObject({ startsAt: new Date(start).toISOString(), endsAt: end, date });
      expect(selectHomepageEvents([event], new Date(end))).toEqual([]);
    }
  });

  it('includes ONLY published events even if a stale flag survives elsewhere', () => {
    const events = selectHomepageEvents(
      [
        source({ eventId: 'draft', status: 'draft' }),
        source({ eventId: 'cancelled', status: 'cancelled' }),
        source({ eventId: 'completed', status: 'completed' }),
        source({ eventId: 'live', status: 'published' }),
      ],
      NOW,
    );
    expect(events.map((event) => event.url)).toEqual([publicEventUrl('live')]);
  });

  it('sorts ascending by start time', () => {
    const events = selectHomepageEvents(
      [
        source({ eventId: 'c', startsAt: new Date('2026-10-01T10:00:00Z') }),
        source({ eventId: 'a', startsAt: new Date('2026-08-13T10:00:00Z') }),
        source({ eventId: 'b', startsAt: new Date('2026-09-01T10:00:00Z') }),
      ],
      NOW,
    );
    expect(events.map((event) => event.url)).toEqual([
      publicEventUrl('a'),
      publicEventUrl('b'),
      publicEventUrl('c'),
    ]);
  });

  it('keeps in-progress events sorted by start rather than end', () => {
    const events = selectHomepageEvents([
      source({
        eventId: 'upcoming',
        startsAt: new Date('2026-08-12T11:00:00Z'),
        endsAt: new Date('2026-08-12T12:00:00Z'),
      }),
      source({
        eventId: 'running',
        startsAt: new Date('2026-08-12T09:00:00Z'),
        endsAt: new Date('2026-08-13T01:00:00Z'),
      }),
    ], NOW);
    expect(events.map((event) => event.url)).toEqual([
      publicEventUrl('running'), publicEventUrl('upcoming'),
    ]);
  });

  it('falls back place → approximateArea → empty and desc summary → description', () => {
    const [noLocation] = selectHomepageEvents([source({ locationName: null })], NOW);
    expect(noLocation!.place).toBe('Kungsbacka');
    // An EMPTY/blank locationName must not beat a real approximateArea — the
    // schema allows '' (max-length only), so nullish-coalescing alone would
    // ship a blank place while a fallback exists (Copilot review find, PR #840).
    const [emptyLocation] = selectHomepageEvents([source({ locationName: '' })], NOW);
    expect(emptyLocation!.place).toBe('Kungsbacka');
    const [blankLocation] = selectHomepageEvents([source({ locationName: '   ' })], NOW);
    expect(blankLocation!.place).toBe('Kungsbacka');
    // A real place name is emitted trimmed.
    const [padded] = selectHomepageEvents([source({ locationName: '  Stationen  ' })], NOW);
    expect(padded!.place).toBe('Stationen');
    const [noPlaceAtAll] = selectHomepageEvents(
      [source({ locationName: null, approximateArea: null })],
      NOW,
    );
    expect(noPlaceAtAll!.place).toBe('');
    const [noSummary] = selectHomepageEvents([source({ summary: null })], NOW);
    expect(noSummary!.desc).toBe('Lång medlemsbeskrivning.');
    // Same rule for desc: a blank summary must not mask the description.
    const [blankSummary] = selectHomepageEvents([source({ summary: '  ' })], NOW);
    expect(blankSummary!.desc).toBe('Lång medlemsbeskrivning.');
    const [nothing] = selectHomepageEvents([source({ summary: null, description: null })], NOW);
    expect(nothing!.desc).toBe('');
  });

  it('NEVER emits attendee, creator or address data', () => {
    const [entry] = selectHomepageEvents([source()], NOW);
    expect(Object.keys(entry!).sort()).toEqual([
      'date',
      'desc',
      'endsAt',
      'place',
      'source',
      'startsAt',
      'time',
      'title',
      'url',
    ]);
  });
});

describe('buildHomepageEventsFile / homepageFileEquivalent', () => {
  const events = selectHomepageEvents([source()], NOW);

  it('serializes the agreed contract shape', () => {
    const parsed = JSON.parse(buildHomepageEventsFile(events, NOW)) as Record<string, unknown>;
    expect(parsed._generated).toBe(HOMEPAGE_FILE_NOTICE);
    expect(parsed.generatedAt).toBe(NOW.toISOString());
    expect(parsed.events).toEqual(events);
  });

  it('treats files differing ONLY in generatedAt as equivalent (skip the commit)', () => {
    const a = buildHomepageEventsFile(events, NOW);
    const b = buildHomepageEventsFile(events, new Date('2026-08-13T04:40:00Z'));
    expect(homepageFileEquivalent(a, b)).toBe(true);
  });

  it('treats different event sets as NOT equivalent', () => {
    const a = buildHomepageEventsFile(events, NOW);
    const b = buildHomepageEventsFile([], NOW);
    expect(homepageFileEquivalent(a, b)).toBe(false);
  });

  it('regenerates when only the end changes, including upgrading the legacy feed shape', () => {
    const current = buildHomepageEventsFile(events, NOW);
    const updated = selectHomepageEvents([
      source({ endsAt: new Date('2026-08-20T20:00:00Z') }),
    ], NOW);
    expect(homepageFileEquivalent(current, buildHomepageEventsFile(updated, NOW))).toBe(false);
    const legacy = JSON.parse(current);
    for (const entry of legacy.events) {
      delete entry.startsAt;
      delete entry.endsAt;
    }
    expect(homepageFileEquivalent(JSON.stringify(legacy), current)).toBe(false);
  });

  it('treats a missing or unparseable existing file as NOT equivalent', () => {
    const next = buildHomepageEventsFile(events, NOW);
    expect(homepageFileEquivalent(null, next)).toBe(false);
    expect(homepageFileEquivalent('not json {', next)).toBe(false);
  });
});

describe('isHomepageSyncRelevant', () => {
  const enabled = { publicSiteEnabled: true, status: 'published', title: 'T', rsvpCounts: { going: 0 } };

  it('ignores every write on never-enabled events (the common case)', () => {
    expect(isHomepageSyncRelevant({ status: 'draft' }, { status: 'published' })).toBe(false);
    expect(isHomepageSyncRelevant(undefined, { status: 'published' })).toBe(false);
  });

  it('fires on the flag flipping in either direction', () => {
    expect(isHomepageSyncRelevant({ ...enabled, publicSiteEnabled: false }, enabled)).toBe(true);
    expect(isHomepageSyncRelevant(enabled, { ...enabled, publicSiteEnabled: false })).toBe(true);
  });

  it('fires on create/delete of an enabled event', () => {
    expect(isHomepageSyncRelevant(undefined, enabled)).toBe(true);
    expect(isHomepageSyncRelevant(enabled, undefined)).toBe(true);
  });

  it('fires when a feed-relevant field changes on an enabled event', () => {
    expect(isHomepageSyncRelevant(enabled, { ...enabled, status: 'cancelled' })).toBe(true);
    expect(isHomepageSyncRelevant(enabled, { ...enabled, title: 'New title' })).toBe(true);
  });

  it('compares Firestore Timestamps by instant, not identity', () => {
    const ts = (millis: number) => ({ toMillis: () => millis });
    expect(
      isHomepageSyncRelevant({ ...enabled, startsAt: ts(1000) }, { ...enabled, startsAt: ts(1000) }),
    ).toBe(false);
    expect(
      isHomepageSyncRelevant({ ...enabled, startsAt: ts(1000) }, { ...enabled, startsAt: ts(2000) }),
    ).toBe(true);
  });

  it('syncs explicit end changes and resets, but ignores equal end instants', () => {
    const ts = (millis: number) => ({ toMillis: () => millis });
    const before = { ...enabled, endsAt: ts(1000) };
    expect(isHomepageSyncRelevant(before, { ...enabled, endsAt: ts(1000) })).toBe(false);
    expect(isHomepageSyncRelevant(before, { ...enabled, endsAt: ts(2000) })).toBe(true);
    expect(isHomepageSyncRelevant(before, { ...enabled, endsAt: null })).toBe(true);
    expect(isHomepageSyncRelevant({ ...enabled, endsAt: null }, before)).toBe(true);
    expect(isHomepageSyncRelevant(enabled, before)).toBe(true);
    expect(isHomepageSyncRelevant(before, enabled)).toBe(true);
    expect(isHomepageSyncRelevant(
      { publicSiteEnabled: false, endsAt: ts(1000) },
      { publicSiteEnabled: false, endsAt: ts(2000) },
    )).toBe(false);
  });

  it('ignores irrelevant writes (rsvp counter bumps) on an enabled event', () => {
    expect(isHomepageSyncRelevant(enabled, { ...enabled, rsvpCounts: { going: 3 } })).toBe(false);
  });
});

describe('creator flag on events.create (events-core integration)', () => {
  const base = { title: 'Träff', startsAt: '2026-09-01T10:00:00Z' };

  it('stores publicSiteEnabled + publicSiteEnabledAt when the creator opts in', () => {
    const { eventDoc } = buildEventDocuments(
      { ...base, publicSiteEnabled: true },
      'creator-uid',
      () => 'ts',
      'member',
    );
    expect(eventDoc.publicSiteEnabled).toBe(true);
    expect(eventDoc.publicSiteEnabledAt).toBe('ts');
  });

  it('defaults to NOT publicly enabled', () => {
    const { eventDoc } = buildEventDocuments(base, 'creator-uid', () => 'ts', 'member');
    expect(eventDoc.publicSiteEnabled).toBe(false);
    expect(eventDoc.publicSiteEnabledAt).toBeNull();
  });

  it('accepts publicSiteEnabled in the create input schema', () => {
    expect(parseCreateEventInput({ ...base, publicSiteEnabled: true }).ok).toBe(true);
  });

  it('REJECTS a caller trying to smuggle a served-state field into create', () => {
    // The schema is .strict(): the generated-feed state can never be forged
    // through the create payload under any other name.
    expect(parseCreateEventInput({ ...base, publicSiteEnabledAt: 'now' }).ok).toBe(false);
    expect(parseCreateEventInput({ ...base, publishedToHomepage: true }).ok).toBe(false);
  });

  it('REJECTS publicSiteEnabled through events.update — the toggle path is setPublicSite only', () => {
    expect(parseUpdateEventInput({ eventId: 'e1', publicSiteEnabled: true }).ok).toBe(false);
  });
});
