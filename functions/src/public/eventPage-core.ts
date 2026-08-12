/**
 * Public event pages — pure logic (issue #768).
 *
 * Every publicly-enabled event gets a server-rendered web page at
 * /e/{eventId} on the dedicated public hosting site, shareable with people
 * who do not use the app, live from publish through the event's effective end
 * (+ the same grace the autoClose sweep uses), then an "event has ended"
 * state. This module is everything that can be decided/rendered without I/O:
 *
 * SANITIZATION IS STRUCTURAL, NOT A FILTER. The renderer's input type
 * ([PublicEventModel]) simply has no field for the attendee list, the
 * organizer/creator identity, or the member-gated street address, so no code
 * path can leak them — the handler builds the model from the raw documents
 * ([decidePublicPageState]) and everything else consumes the model. What IS
 * public, per the decided v1 scope:
 *  - title, date & time (Europe/Stockholm), long description
 *  - "X kommer" attendee COUNT only (rsvpCounts.going — never who)
 *  - map pin + PLACE NAME only (locationName + coordinates are deliberately
 *    public teaser data; the street address stays member-only)
 *  - add-to-calendar (.ics at /e/{id}.ics + a Google Calendar link)
 *  - an app-promo section with a "coming soon on Google Play" note — no
 *    download link until open testing/production.
 *
 * All user-authored text (title, description, place name) is HTML-escaped at
 * render time and ICS-escaped in the calendar file — an event creator must
 * never be able to inject markup into a public page.
 */

import {
  AUTO_CLOSE_GRACE_MS,
  effectiveEndInstant,
  type EventStatus,
} from '../events/events-core';
import { PUBLIC_EVENT_BASE_URL, stockholmDateTimeParts } from '../events/publicSite-core';

// ---------------------------------------------------------------------------
// Escaping
// ---------------------------------------------------------------------------

/** HTML-escapes user-authored text for element content and attribute values. */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Escapes text for an iCalendar TEXT property value (RFC 5545 §3.3.11). */
export function escapeIcsText(text: string): string {
  return text
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

/** Collapses whitespace and truncates for meta/OG descriptions. */
export function metaDescription(text: string | null | undefined, maxLength = 200): string {
  const collapsed = (text ?? '').replace(/\s+/g, ' ').trim();
  if (collapsed.length <= maxLength) {
    return collapsed;
  }
  return `${collapsed.slice(0, maxLength - 1).trimEnd()}…`;
}

// ---------------------------------------------------------------------------
// Page state decision (per-request lifecycle, no cleanup job needed)
// ---------------------------------------------------------------------------

/** The sanitized, render-ready view of one publicly-live event. */
export interface PublicEventModel {
  eventId: string;
  title: string;
  startsAt: Date;
  /** Effective end: stored endsAt or the Stockholm end-of-day default. */
  endsAt: Date;
  description: string | null;
  /** Public place name (never the street address). */
  locationName: string | null;
  latitude: number | null;
  longitude: number | null;
  /** rsvpCounts.going — the COUNT is public, the roster never is. */
  goingCount: number;
}

export type PublicEventPageState =
  | { kind: 'live'; model: PublicEventModel }
  /** The page existed publicly but its moment has passed (or it was called off). */
  | { kind: 'ended'; title: string; cancelled: boolean }
  /** Missing, never publicly enabled, or an unpublished draft — indistinguishable on purpose. */
  | { kind: 'not-found' };

/** The raw teaser-document fields the state decision needs. */
export interface PublicPageEventSource {
  publicSiteEnabled: boolean;
  status: EventStatus;
  title: string;
  startsAt: Date;
  endsAt: Date | null;
  locationName: string | null;
  latitude: number | null;
  longitude: number | null;
  goingCount: number;
}

/**
 * Decides what /e/{eventId} serves at `now`.
 *
 * - No document, never publicly enabled, or still a draft → not-found. A draft
 *   or opted-out event must be indistinguishable from a nonexistent one so the
 *   URL leaks nothing before the creator publishes it publicly.
 * - Publicly enabled and `published`, and `now` is at or before the effective
 *   end + [AUTO_CLOSE_GRACE_MS] → live. The same grace the autoClose sweep
 *   uses, checked per request, so the page and the app close in step and no
 *   separate cleanup job exists.
 * - Otherwise → ended (a finished/auto-closed event, a published event past
 *   its grace that the sweep has not reached yet, or a cancelled one — the
 *   ended page says which). The flag intentionally KEEPS gating here: an
 *   event un-published from the public site returns not-found, full stop.
 *
 * `description` is not part of the source — it lives on the member-gated
 * details/private document and the handler fetches it ONLY for a live page.
 */
export function decidePublicPageState(
  source: PublicPageEventSource | null,
  eventId: string,
  now: Date,
  description: string | null,
): PublicEventPageState {
  if (!source || source.publicSiteEnabled !== true || source.status === 'draft') {
    return { kind: 'not-found' };
  }
  const endsAt = effectiveEndInstant(source.startsAt, source.endsAt);
  if (source.status === 'cancelled') {
    return { kind: 'ended', title: source.title, cancelled: true };
  }
  if (source.status === 'completed' || now.getTime() > endsAt.getTime() + AUTO_CLOSE_GRACE_MS) {
    return { kind: 'ended', title: source.title, cancelled: false };
  }
  return {
    kind: 'live',
    model: {
      eventId,
      title: source.title,
      startsAt: source.startsAt,
      endsAt,
      description,
      locationName: source.locationName,
      latitude: source.latitude,
      longitude: source.longitude,
      goingCount: source.goingCount,
    },
  };
}

/** Structural Firestore Timestamp (kept structural so this module stays pure). */
export interface TimestampLike {
  toDate(): Date;
}

/** The raw events/{eventId} teaser-document fields the page reads. */
export interface StoredEventDoc {
  publicSiteEnabled?: boolean;
  status: EventStatus;
  title?: string;
  startsAt: TimestampLike;
  endsAt?: TimestampLike | null;
  locationName?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  rsvpCounts?: { going?: number };
}

/**
 * Maps the raw teaser doc onto the pure decision input. This mapping is the
 * sanitization boundary: whatever extra fields the raw document carries
 * (createdByUserId/createdByRole, RSVP rosters, moderation state … — and the
 * street address never even leaves details/private), the OUTPUT type has no
 * slot for them, so nothing downstream can render them.
 */
export function toPageSource(data: StoredEventDoc): PublicPageEventSource {
  return {
    publicSiteEnabled: data.publicSiteEnabled === true,
    status: data.status,
    title: String(data.title ?? ''),
    startsAt: data.startsAt.toDate(),
    endsAt: data.endsAt?.toDate() ?? null,
    locationName: data.locationName ?? null,
    latitude: data.latitude ?? null,
    longitude: data.longitude ?? null,
    goingCount: typeof data.rsvpCounts?.going === 'number' ? data.rsvpCounts.going : 0,
  };
}

/** Valid Firestore-document-id shape for the URL path segment (anti-junk). */
export const EVENT_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

/** Parses /e/{id} and /e/{id}.ics. Returns null for anything else. */
export function parseEventPagePath(
  path: string,
): { eventId: string; ics: boolean } | null {
  const match = /^\/e\/([^/]+?)(\.ics)?$/.exec(path);
  if (!match) {
    return null;
  }
  const eventId = match[1]!;
  if (!EVENT_ID_PATTERN.test(eventId)) {
    return null;
  }
  return { eventId, ics: match[2] === '.ics' };
}

// ---------------------------------------------------------------------------
// Calendar (ics + Google Calendar)
// ---------------------------------------------------------------------------

/** `YYYYMMDDTHHMMSSZ` — the ICS/Google-Calendar UTC instant form. */
export function calendarUtcStamp(instant: Date): string {
  return instant.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
}

/** The .ics file for one live event (RFC 5545, METHOD:PUBLISH). */
export function buildEventIcs(model: PublicEventModel, now: Date): string {
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Kungsbacka Car Community//Event//SV',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    'BEGIN:VEVENT',
    `UID:${model.eventId}@kcc-events`,
    `DTSTAMP:${calendarUtcStamp(now)}`,
    `DTSTART:${calendarUtcStamp(model.startsAt)}`,
    `DTEND:${calendarUtcStamp(model.endsAt)}`,
    `SUMMARY:${escapeIcsText(model.title)}`,
    ...(model.locationName ? [`LOCATION:${escapeIcsText(model.locationName)}`] : []),
    ...(model.description ? [`DESCRIPTION:${escapeIcsText(model.description)}`] : []),
    `URL:${PUBLIC_EVENT_BASE_URL}/e/${model.eventId}`,
    'END:VEVENT',
    'END:VCALENDAR',
  ];
  // RFC 5545 line delimiter is CRLF.
  return `${lines.join('\r\n')}\r\n`;
}

/** Pre-filled Google Calendar "add event" link. */
export function googleCalendarUrl(model: PublicEventModel): string {
  const params = new URLSearchParams({
    action: 'TEMPLATE',
    text: model.title,
    dates: `${calendarUtcStamp(model.startsAt)}/${calendarUtcStamp(model.endsAt)}`,
  });
  if (model.locationName) {
    params.set('location', model.locationName);
  }
  if (model.description) {
    params.set('details', metaDescription(model.description, 500));
  }
  return `https://calendar.google.com/calendar/render?${params.toString()}`;
}

// ---------------------------------------------------------------------------
// Map (Mapbox Static Images — no JS, preview-friendly)
// ---------------------------------------------------------------------------

/**
 * Static map image with the event pin, or null when the event has no
 * coordinates or no token is configured (the page degrades to place name
 * only). Uses the public `pk.` token — the same public-by-design token the
 * clients ship — URL-embedded as Mapbox static images require.
 */
export function mapboxStaticImageUrl(model: PublicEventModel, token: string): string | null {
  if (model.latitude === null || model.longitude === null || !token) {
    return null;
  }
  const lng = model.longitude.toFixed(6);
  const lat = model.latitude.toFixed(6);
  return (
    `https://api.mapbox.com/styles/v1/mapbox/streets-v12/static/` +
    `pin-l+c9922e(${lng},${lat})/${lng},${lat},14,0/640x360@2x` +
    `?access_token=${encodeURIComponent(token)}`
  );
}

// ---------------------------------------------------------------------------
// HTML rendering
// ---------------------------------------------------------------------------

const APP_NAME = 'Kungsbacka Car Community';
const HOMEPAGE_URL = 'https://kungsbackacarcommunity.se/';

/** Swedish weekday/date line, e.g. "torsdag 20 augusti 2026". */
function stockholmLongDate(instant: Date): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Europe/Stockholm',
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(instant);
}

const PAGE_CSS = `
  :root { color-scheme: light; }
  * { box-sizing: border-box; margin: 0; }
  body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; background: #faf8f4; color: #1c1a17; line-height: 1.55; }
  main { max-width: 640px; margin: 0 auto; padding: 24px 20px 48px; }
  .card { background: #fff; border: 1px solid #e8e2d6; border-radius: 12px; padding: 24px; margin-top: 16px; }
  h1 { font-size: 1.6rem; line-height: 1.25; }
  .brand { color: #c9922e; font-weight: 600; font-size: 0.9rem; letter-spacing: 0.04em; text-transform: uppercase; }
  .meta { color: #5b554b; margin-top: 8px; }
  .going { display: inline-block; margin-top: 12px; background: #f4ead6; color: #7c5a17; border-radius: 999px; padding: 4px 12px; font-size: 0.9rem; font-weight: 600; }
  .desc { margin-top: 16px; white-space: pre-line; }
  .map { width: 100%; height: auto; border-radius: 8px; margin-top: 16px; border: 1px solid #e8e2d6; }
  .actions { margin-top: 20px; display: flex; flex-wrap: wrap; gap: 12px; }
  .btn { display: inline-block; padding: 10px 18px; border-radius: 8px; text-decoration: none; font-weight: 600; }
  .btn-primary { background: #c9922e; color: #fff; }
  .btn-secondary { border: 1px solid #c9922e; color: #7c5a17; }
  .app { margin-top: 24px; }
  .app h2 { font-size: 1.1rem; }
  .app p { margin-top: 8px; color: #5b554b; }
  .soon { display: inline-block; margin-top: 12px; border: 1px dashed #c9922e; color: #7c5a17; border-radius: 8px; padding: 6px 12px; font-size: 0.9rem; }
  footer { margin-top: 32px; text-align: center; font-size: 0.85rem; color: #8a8375; }
  footer a { color: #c9922e; }
`;

function pageShell(title: string, headExtra: string, body: string): string {
  return `<!DOCTYPE html>
<html lang="sv">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)} – ${APP_NAME}</title>
${headExtra}
<style>${PAGE_CSS}</style>
</head>
<body>
<main>
<p class="brand">${APP_NAME}</p>
${body}
<footer><a href="${HOMEPAGE_URL}">kungsbackacarcommunity.se</a></footer>
</main>
</body>
</html>
`;
}

/**
 * The app-promo section (decided v1 scope): what the app adds around an
 * event, with a "coming soon" note and DELIBERATELY no store link — swap in
 * the real Google Play link at open testing/production.
 */
function appPromoSection(): string {
  return `<section class="card app">
<h2>Mer i appen</h2>
<p>I ${APP_NAME}-appen kan du osa till träffar, chatta med andra deltagare, se eventet på kartan och köra dit i konvoj tillsammans med communityt.</p>
<span class="soon">Appen kommer snart till Google Play</span>
</section>`;
}

/** The live event page. `mapImageUrl`/links come from the handler. */
export function renderLiveEventPage(
  model: PublicEventModel,
  options: { mapImageUrl: string | null },
): string {
  const { date, time } = stockholmDateTimeParts(model.startsAt);
  const pageUrl = `${PUBLIC_EVENT_BASE_URL}/e/${model.eventId}`;
  const ogDescription =
    metaDescription(model.description) ||
    `${date} kl ${time}${model.locationName ? ` – ${model.locationName}` : ''}`;

  const headExtra = [
    `<meta name="description" content="${escapeHtml(ogDescription)}">`,
    `<meta property="og:type" content="website">`,
    `<meta property="og:site_name" content="${APP_NAME}">`,
    `<meta property="og:title" content="${escapeHtml(model.title)}">`,
    `<meta property="og:description" content="${escapeHtml(ogDescription)}">`,
    `<meta property="og:url" content="${pageUrl}">`,
    ...(options.mapImageUrl
      ? [`<meta property="og:image" content="${escapeHtml(options.mapImageUrl)}">`]
      : []),
    `<meta name="twitter:card" content="summary${options.mapImageUrl ? '_large_image' : ''}">`,
  ].join('\n');

  const whenLine = `${stockholmLongDate(model.startsAt)} kl ${time}`;
  const body = `<article class="card">
<h1>${escapeHtml(model.title)}</h1>
<p class="meta">📅 ${escapeHtml(whenLine)}</p>
${model.locationName ? `<p class="meta">📍 ${escapeHtml(model.locationName)}</p>` : ''}
${model.goingCount > 0 ? `<span class="going">${model.goingCount} kommer</span>` : ''}
${model.description ? `<p class="desc">${escapeHtml(model.description)}</p>` : ''}
${
  options.mapImageUrl
    ? `<img class="map" src="${escapeHtml(options.mapImageUrl)}" alt="Karta med eventets plats${
        model.locationName ? `: ${escapeHtml(model.locationName)}` : ''
      }" width="640" height="360" loading="lazy">`
    : ''
}
<div class="actions">
<a class="btn btn-primary" href="/e/${model.eventId}.ics">Lägg till i kalender</a>
<a class="btn btn-secondary" href="${escapeHtml(googleCalendarUrl(model))}" rel="noopener" target="_blank">Google Kalender</a>
</div>
</article>
${appPromoSection()}`;

  return pageShell(model.title, headExtra, body);
}

/** The "event has ended" / "event cancelled" page. */
export function renderEndedEventPage(title: string, cancelled: boolean): string {
  const message = cancelled ? 'Det här eventet är inställt.' : 'Det här eventet har redan ägt rum.';
  const headExtra = `<meta name="robots" content="noindex">`;
  const body = `<article class="card">
<h1>${escapeHtml(title)}</h1>
<p class="meta">${message} Håll utkik på <a href="${HOMEPAGE_URL}">hemsidan</a> efter kommande träffar.</p>
</article>
${appPromoSection()}`;
  return pageShell(title, headExtra, body);
}

/** The 404 page (missing, never public, or an unpublished draft — all identical). */
export function renderNotFoundPage(): string {
  const body = `<article class="card">
<h1>Eventet hittades inte</h1>
<p class="meta">Sidan finns inte, eller så är eventet inte publikt. Aktuella träffar hittar du på <a href="${HOMEPAGE_URL}">hemsidan</a>.</p>
</article>`;
  return pageShell('Eventet hittades inte', `<meta name="robots" content="noindex">`, body);
}
