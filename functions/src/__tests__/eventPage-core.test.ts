/**
 * Unit tests for the public event page pure logic (public/eventPage-core.ts)
 * plus the handler's raw-document mapping (toPageSource). No emulators.
 *
 * The privacy tests are the load-bearing ones: the rendered public HTML/ICS
 * must NEVER carry attendee names, the organizer/creator identity, or the
 * member-gated street address, no matter what the raw documents contain.
 */

import { describe, expect, it } from 'vitest';
import {
  buildEventIcs,
  calendarUtcStamp,
  decidePublicPageState,
  escapeHtml,
  escapeIcsText,
  googleCalendarUrl,
  mapboxStaticImageUrl,
  metaDescription,
  parseEventPagePath,
  renderEndedEventPage,
  renderLiveEventPage,
  renderNotFoundPage,
  toPageSource,
  type PublicEventModel,
  type PublicPageEventSource,
  type StoredEventDoc,
} from '../public/eventPage-core';
import { AUTO_CLOSE_GRACE_MS } from '../events/events-core';

/** Minimal structural Timestamp — keeps this suite free of firebase-admin init. */
const ts = (iso: string) => ({ toDate: () => new Date(iso) });

const NOW = new Date('2026-08-12T10:00:00Z');

function source(overrides: Partial<PublicPageEventSource> = {}): PublicPageEventSource {
  return {
    publicSiteEnabled: true,
    status: 'published',
    title: 'Kvällscruising',
    startsAt: new Date('2026-08-20T17:00:00Z'),
    endsAt: new Date('2026-08-20T21:00:00Z'),
    locationName: 'Kungsbacka station',
    latitude: 57.487,
    longitude: 12.076,
    goingCount: 7,
    ...overrides,
  };
}

function liveModel(overrides: Partial<PublicEventModel> = {}): PublicEventModel {
  const state = decidePublicPageState(source(), 'event-1', NOW, 'Lång beskrivning av träffen.');
  if (state.kind !== 'live') throw new Error('expected live');
  return { ...state.model, ...overrides };
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

describe('decidePublicPageState', () => {
  it('serves a live page for an enabled, published, not-yet-ended event', () => {
    const state = decidePublicPageState(source(), 'e1', NOW, 'Beskrivning');
    expect(state.kind).toBe('live');
    if (state.kind === 'live') {
      expect(state.model.title).toBe('Kvällscruising');
      expect(state.model.goingCount).toBe(7);
      expect(state.model.description).toBe('Beskrivning');
    }
  });

  it('stays live DURING the event and through the autoClose grace', () => {
    const running = new Date('2026-08-20T19:00:00Z');
    expect(decidePublicPageState(source(), 'e1', running, null).kind).toBe('live');
    const insideGrace = new Date(
      new Date('2026-08-20T21:00:00Z').getTime() + AUTO_CLOSE_GRACE_MS - 60_000,
    );
    expect(decidePublicPageState(source(), 'e1', insideGrace, null).kind).toBe('live');
  });

  it('preserves the existing inclusive public-page grace boundary', () => {
    const boundary = new Date(source().endsAt!.getTime() + AUTO_CLOSE_GRACE_MS);
    expect(decidePublicPageState(source(), 'e1', boundary, null).kind).toBe('live');
    expect(decidePublicPageState(
      source(), 'e1', new Date(boundary.getTime() + 1), null,
    ).kind).toBe('ended');
  });

  it('flips to ended once the effective end + grace has passed, even while still `published`', () => {
    const pastGrace = new Date(
      new Date('2026-08-20T21:00:00Z').getTime() + AUTO_CLOSE_GRACE_MS + 60_000,
    );
    const state = decidePublicPageState(source(), 'e1', pastGrace, null);
    expect(state).toMatchObject({ kind: 'ended', cancelled: false, title: 'Kvällscruising' });
  });

  it('shows ended for completed and cancelled events (cancelled says so)', () => {
    expect(decidePublicPageState(source({ status: 'completed' }), 'e1', NOW, null)).toMatchObject({
      kind: 'ended',
      cancelled: false,
    });
    expect(decidePublicPageState(source({ status: 'cancelled' }), 'e1', NOW, null)).toMatchObject({
      kind: 'ended',
      cancelled: true,
    });
  });

  it('answers not-found for missing, never-enabled and draft events — indistinguishably', () => {
    expect(decidePublicPageState(null, 'e1', NOW, null)).toEqual({ kind: 'not-found' });
    expect(decidePublicPageState(source({ publicSiteEnabled: false }), 'e1', NOW, null)).toEqual({
      kind: 'not-found',
    });
    expect(decidePublicPageState(source({ status: 'draft' }), 'e1', NOW, null)).toEqual({
      kind: 'not-found',
    });
  });

  it('answers not-found for a COMPLETED never-enabled event (the flag always gates)', () => {
    expect(
      decidePublicPageState(source({ publicSiteEnabled: false, status: 'completed' }), 'e1', NOW, null),
    ).toEqual({ kind: 'not-found' });
  });
});

// ---------------------------------------------------------------------------
// Privacy / sanitization
// ---------------------------------------------------------------------------

describe('sanitization — attendee/organizer/address can never reach the page', () => {
  // A raw teaser document deliberately stuffed with everything that must NOT
  // become public. The mapping's OUTPUT type simply has no slot for these.
  const rawDoc = {
    publicSiteEnabled: true,
    status: 'published',
    title: 'Träff',
    startsAt: ts('2026-08-20T17:00:00Z'),
    endsAt: ts('2026-08-20T21:00:00Z'),
    locationName: 'Kungsbacka station',
    latitude: 57.487,
    longitude: 12.076,
    rsvpCounts: { going: 5, maybe: 2, not_going: 1 },
    // Must-never-leak fields:
    createdByUserId: 'creator-uid-SECRET',
    createdByRole: 'member',
    address: 'Hemliga Gatan 13',
    attendees: ['Anna Andersson', 'Bertil Bengtsson'],
  } as unknown as StoredEventDoc;

  it('toPageSource structurally drops creator identity, address and rosters', () => {
    const mapped = toPageSource(rawDoc);
    expect(Object.keys(mapped).sort()).toEqual([
      'endsAt',
      'goingCount',
      'latitude',
      'locationName',
      'longitude',
      'publicSiteEnabled',
      'startsAt',
      'status',
      'title',
    ]);
    expect(mapped.goingCount).toBe(5);
  });

  it('the rendered HTML and ICS contain none of the private values', () => {
    const state = decidePublicPageState(toPageSource(rawDoc), 'e1', NOW, 'Publik beskrivning');
    expect(state.kind).toBe('live');
    if (state.kind !== 'live') return;
    const html = renderLiveEventPage(state.model, {
      mapImageUrl: mapboxStaticImageUrl(state.model, 'pk.token'),
    });
    const ics = buildEventIcs(state.model, NOW);
    for (const rendered of [html, ics]) {
      expect(rendered).not.toContain('creator-uid-SECRET');
      expect(rendered).not.toContain('Hemliga Gatan');
      expect(rendered).not.toContain('Anna Andersson');
      expect(rendered).not.toContain('Bertil Bengtsson');
    }
    // The COUNT is public; the roster never is.
    expect(html).toContain('5 kommer');
  });

  it('shows the attendee count only when someone is going', () => {
    const html = renderLiveEventPage(liveModel({ goingCount: 0 }), { mapImageUrl: null });
    expect(html).not.toContain('kommer</span>');
  });
});

// ---------------------------------------------------------------------------
// Escaping (creator-authored text must never inject markup)
// ---------------------------------------------------------------------------

describe('escaping', () => {
  it('HTML-escapes title and description in the rendered page', () => {
    const html = renderLiveEventPage(
      liveModel({
        title: `<script>alert('x')</script>`,
        description: `"quotes" & <img src=x onerror=alert(1)>`,
      }),
      { mapImageUrl: null },
    );
    expect(html).not.toContain('<script>alert');
    expect(html).not.toContain('<img src=x');
    expect(html).toContain('&lt;script&gt;');
    expect(html).toContain('&quot;quotes&quot; &amp;');
  });

  it('HTML-escapes the ended page title', () => {
    const html = renderEndedEventPage(`<b>fet</b>`, false);
    expect(html).not.toContain('<b>fet</b>');
    expect(html).toContain('&lt;b&gt;fet&lt;/b&gt;');
  });

  it('keeps a hostile title inside valid JSON-LD without allowing script injection', () => {
    const title = '</script><script>alert("x")</script> & <b>Träff</b>';
    const html = renderLiveEventPage(liveModel({ title }), { mapImageUrl: null });
    expect(html).not.toContain('</script><script>');
    const json = html.match(/<script type="application\/ld\+json">(.*?)<\/script>/s)![1]!;
    expect(JSON.parse(json).name).toBe(title);
    expect(json).not.toContain('<');
  });

  it('ICS-escapes commas, semicolons, backslashes and newlines', () => {
    expect(escapeIcsText('a,b;c\\d\ne')).toBe('a\\,b\\;c\\\\d\\ne');
    const ics = buildEventIcs(
      liveModel({ title: 'Träff, med; specialtecken', description: 'rad1\nrad2' }),
      NOW,
    );
    expect(ics).toContain('SUMMARY:Träff\\, med\\; specialtecken');
    expect(ics).toContain('DESCRIPTION:rad1\\nrad2');
  });

  it('escapeHtml covers all five significant characters', () => {
    expect(escapeHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;');
  });
});

// ---------------------------------------------------------------------------
// Routing, calendar, map, meta
// ---------------------------------------------------------------------------

describe('parseEventPagePath', () => {
  it('parses page and ics paths', () => {
    expect(parseEventPagePath('/e/abc-123_X')).toEqual({ eventId: 'abc-123_X', ics: false });
    expect(parseEventPagePath('/e/abc-123_X.ics')).toEqual({ eventId: 'abc-123_X', ics: true });
  });

  it('rejects junk paths and invalid ids', () => {
    expect(parseEventPagePath('/e/')).toBeNull();
    expect(parseEventPagePath('/e/a/b')).toBeNull();
    expect(parseEventPagePath('/other')).toBeNull();
    expect(parseEventPagePath('/e/nope%20id')).toBeNull();
    expect(parseEventPagePath(`/e/${'x'.repeat(129)}`)).toBeNull();
  });
});

describe('calendar artifacts', () => {
  it('formats UTC stamps the ICS/Google way', () => {
    expect(calendarUtcStamp(new Date('2026-08-20T17:05:30Z'))).toBe('20260820T170530Z');
  });

  it('builds a well-formed VCALENDAR with CRLF line ends and stable UID', () => {
    const ics = buildEventIcs(liveModel(), NOW);
    expect(ics.startsWith('BEGIN:VCALENDAR\r\n')).toBe(true);
    expect(ics.endsWith('END:VCALENDAR\r\n')).toBe(true);
    expect(ics).toContain('UID:event-1@kcc-events');
    expect(ics).toContain('DTSTART:20260820T170000Z');
    expect(ics).toContain('DTEND:20260820T210000Z');
    expect(ics).toContain('LOCATION:Kungsbacka station');
  });

  it('links Google Calendar with the event window and location', () => {
    const url = googleCalendarUrl(liveModel());
    expect(url).toContain('https://calendar.google.com/calendar/render?');
    expect(url).toContain('dates=20260820T170000Z%2F20260820T210000Z');
    expect(url).toContain('location=Kungsbacka+station');
  });
});

describe('mapboxStaticImageUrl', () => {
  it('builds a pinned static image URL with the token', () => {
    const url = mapboxStaticImageUrl(liveModel(), 'pk.test');
    expect(url).toContain('https://api.mapbox.com/styles/v1/mapbox/streets-v12/static/');
    expect(url).toContain('pin-l+c9922e(12.076000,57.487000)');
    expect(url).toContain('access_token=pk.test');
  });

  it('degrades to null without coordinates or without a token', () => {
    expect(mapboxStaticImageUrl(liveModel({ latitude: null, longitude: null }), 'pk.test')).toBeNull();
    expect(mapboxStaticImageUrl(liveModel(), '')).toBeNull();
  });
});

describe('page rendering', () => {
  it('emits OG tags for link previews (image only when a map exists)', () => {
    const withMap = renderLiveEventPage(liveModel(), { mapImageUrl: 'https://api.mapbox.com/x.png' });
    expect(withMap).toContain('<meta property="og:title" content="Kvällscruising">');
    expect(withMap).toContain('og:image');
    expect(withMap).toContain('https://kcc-events.web.app/e/event-1');

    const withoutMap = renderLiveEventPage(liveModel(), { mapImageUrl: null });
    expect(withoutMap).not.toContain('og:image');
  });

  it('offers both calendar actions and the app-promo section without a store link', () => {
    const html = renderLiveEventPage(liveModel(), { mapImageUrl: null });
    expect(html).toContain('href="/e/event-1.ics"');
    expect(html).toContain('calendar.google.com');
    expect(html).toContain('Appen kommer snart till Google Play');
    expect(html).not.toContain('play.google.com');
  });

  it('renders the full same-day Stockholm interval on the page', () => {
    const html = renderLiveEventPage(liveModel(), { mapImageUrl: null });
    // 17:00 UTC in August is 19:00 local.
    expect(html).toContain('torsdag 20 augusti 2026 kl 19:00–23:00');
  });

  it.each([
    {
      label: 'overnight',
      start: '2026-08-20T20:00:00Z',
      end: '2026-08-21T01:00:00Z',
      interval: 'torsdag 20 augusti 2026 kl 22:00 – fredag 21 augusti 2026 kl 03:00',
    },
    {
      label: 'winter',
      start: '2026-01-20T17:00:00Z',
      end: '2026-01-20T19:00:00Z',
      interval: 'tisdag 20 januari 2026 kl 18:00–20:00',
    },
    {
      label: 'spring DST gap',
      start: '2026-03-29T00:30:00Z',
      end: '2026-03-29T01:30:00Z',
      interval: 'söndag 29 mars 2026 kl 01:30 (UTC+1)–03:30 (UTC+2)',
    },
    {
      label: 'autumn repeated hour',
      start: '2026-10-25T00:30:00Z',
      end: '2026-10-25T01:30:00Z',
      interval: 'söndag 25 oktober 2026 kl 02:30 (UTC+2)–02:30 (UTC+1)',
    },
  ])('keeps visible dates, JSON-LD and calendars consistent: $label', ({ start, end, interval }) => {
    const model = liveModel({ startsAt: new Date(start), endsAt: new Date(end), description: null });
    const html = renderLiveEventPage(model, { mapImageUrl: null });
    expect(html).toContain(`<p class="meta">📅 ${interval}</p>`);
    expect(html).toContain(`<meta property="og:description" content="${interval} – Kungsbacka station">`);
    const json = html.match(/<script type="application\/ld\+json">(.*?)<\/script>/s)![1]!;
    expect(JSON.parse(json)).toMatchObject({
      '@type': 'Event',
      startDate: model.startsAt.toISOString(),
      endDate: model.endsAt.toISOString(),
    });
    const ics = buildEventIcs(model, NOW);
    expect(ics).toContain(`DTSTART:${calendarUtcStamp(model.startsAt)}\r\n`);
    expect(ics).toContain(`DTEND:${calendarUtcStamp(model.endsAt)}\r\n`);
    expect(new URL(googleCalendarUrl(model)).searchParams.get('dates')).toBe(
      `${calendarUtcStamp(model.startsAt)}/${calendarUtcStamp(model.endsAt)}`,
    );
  });

  it.each([null, undefined])('uses the effective end in every output for legacy endsAt=%s', (endsAt) => {
    const mapped = toPageSource({
      publicSiteEnabled: true,
      status: 'published',
      startsAt: ts('2026-10-25T00:30:00Z'),
      endsAt,
    });
    const state = decidePublicPageState(mapped, 'legacy', NOW, null);
    expect(state.kind).toBe('live');
    if (state.kind !== 'live') throw new Error('expected live');
    expect(state.model.endsAt.toISOString()).toBe('2026-10-25T22:59:59.999Z');
    const html = renderLiveEventPage(state.model, { mapImageUrl: null });
    expect(html).toContain('02:30 (UTC+2)–23:59 (UTC+1)');
    expect(html).toContain('"endDate":"2026-10-25T22:59:59.999Z"');
    expect(buildEventIcs(state.model, NOW)).toContain('DTEND:20261025T225959Z');
  });

  it('renders distinct ended / cancelled / not-found messages, all noindexed', () => {
    const ended = renderEndedEventPage('Träff', false);
    const cancelled = renderEndedEventPage('Träff', true);
    const notFound = renderNotFoundPage();
    expect(ended).toContain('har redan ägt rum');
    expect(cancelled).toContain('är inställt');
    expect(notFound).toContain('Eventet hittades inte');
    for (const html of [ended, cancelled, notFound]) {
      expect(html).toContain('<meta name="robots" content="noindex">');
    }
  });
});

describe('metaDescription', () => {
  it('collapses whitespace and truncates with an ellipsis', () => {
    expect(metaDescription('rad1\n\nrad2')).toBe('rad1 rad2');
    const long = metaDescription('y'.repeat(500));
    expect(long.length).toBe(200);
    expect(long.endsWith('…')).toBe(true);
  });
});
