/**
 * Public-site publishing — pure logic for the community-homepage event feed.
 *
 * An event's CREATOR can opt their event onto the public community homepage
 * (kungsbacka-car-community-homepage — a static site deployed from its main
 * branch via cPanel sync) and, with it, onto the event's public web page. One
 * creator-controlled flag on the teaser doc drives both:
 *
 * - `publicSiteEnabled: boolean` — set at creation (events.create) or toggled
 *   afterwards by the creator or an admin (events.setPublicSite). Admins keep a
 *   moderation safety valve: they can unset the flag on any event.
 * - `publicSiteEnabledAt: Timestamp | null` — when it was last enabled (the
 *   operator trace; null while disabled).
 *
 * The homepage stores NO MORE THAN NEEDED (its data/app-events.json holds only
 * UPCOMING enabled events with minimal public fields — details live in the
 * app/public page):
 * - field names deliberately match the hand-edited data/events.json the
 *   homepage already renders (title/date/time/place/desc/url), so the site
 *   merges both files with one code path;
 * - date/time are Europe/Stockholm wall-clock (the site's audience);
 * - `place` is the event's public location name only (locationName, falling
 *   back to the coarse approximateArea) — NEVER the member-gated street
 *   address;
 * - `desc` is truncated to [HOMEPAGE_DESC_MAX_LENGTH] characters;
 * - `url` links to the event's public page (see [publicEventUrl]);
 * - NO attendee data, NO creator identity, NO internal ids.
 *
 * Everything here is pure (no Firestore/HTTP imports) so the mapping,
 * filtering, ordering and the change-detection that decides whether a GitHub
 * commit is needed are all unit-testable.
 */

import { z } from 'zod';
import type { EventStatus, GuardResult } from './events-core';

// ---------------------------------------------------------------------------
// The public event page (served on the dedicated public hosting site)
// ---------------------------------------------------------------------------

/**
 * Origin of the dedicated PUBLIC Firebase Hosting site that serves the
 * per-event pages at /e/{eventId} (isolated from the admin site). The site id
 * `kcc-events` must be created once by the operator (Hosting → Add site); if
 * that id is taken globally, pick another and update this constant.
 */
export const PUBLIC_EVENT_BASE_URL = 'https://kcc-events.web.app';

/** Absolute URL of one event's public page. */
export function publicEventUrl(eventId: string): string {
  return `${PUBLIC_EVENT_BASE_URL}/e/${eventId}`;
}

// ---------------------------------------------------------------------------
// events.setPublicSite input + guards
// ---------------------------------------------------------------------------

const setPublicSiteInputSchema = z
  .object({
    eventId: z.string().trim().min(1),
    enabled: z.boolean(),
  })
  .strict();

export type SetPublicSiteInput = z.infer<typeof setPublicSiteInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseSetPublicSiteInput(data: unknown): ParseResult<SetPublicSiteInput> {
  const result = setPublicSiteInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: 'Expected { eventId, enabled } (setPublicSiteRequest, contracts/schemas/events.schema.json).',
    };
  }
  return { ok: true, input: result.data };
}

/**
 * Who may flip an event's public-site flag: the member who created it, or an
 * admin. Mirrors guardCompleteActor — `createdByUserId` is callable-written
 * and never client-writable, so it is a trustworthy owner record. Any OTHER
 * member is refused: publishing someone else's meetup to the open web is not
 * theirs to decide, and unpublishing is a takedown-shaped action that stays
 * with the organiser and the admins.
 */
export function guardPublicSiteActor(
  actor: { uid: string; isAdmin: boolean },
  event: { createdByUserId?: string | null },
): GuardResult {
  if (actor.isAdmin) {
    return { ok: true };
  }
  if (event.createdByUserId && event.createdByUserId === actor.uid) {
    return { ok: true };
  }
  return {
    ok: false,
    code: 'permission-denied',
    message: 'Only the event creator or an admin can change the public page setting.',
  };
}

/**
 * ENABLING is only meaningful for a draft or published event (a draft's flag
 * simply waits — the feed and the page both require `published` too), so
 * enabling on a cancelled/completed event is refused rather than silently
 * stored on a dead document. DISABLING is always allowed: it must never be
 * possible to get stuck publicly listed.
 */
export function guardPublicSiteTogglable(status: EventStatus, enabled: boolean): GuardResult {
  if (enabled && (status === 'cancelled' || status === 'completed')) {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Cannot enable the public page for a cancelled or completed event.',
    };
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Europe/Stockholm rendering (the homepage's audience is local)
// ---------------------------------------------------------------------------

const STOCKHOLM_TIME_ZONE = 'Europe/Stockholm';

/** `YYYY-MM-DD` and `HH:MM` wall-clock fields for an instant, in Stockholm. */
export function stockholmDateTimeParts(instant: Date): { date: string; time: string } {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: STOCKHOLM_TIME_ZONE,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(instant);
  const field: Record<string, string> = {};
  for (const part of parts) {
    if (part.type !== 'literal') field[part.type] = part.value;
  }
  return {
    date: `${field.year}-${field.month}-${field.day}`,
    time: `${field.hour}:${field.minute}`,
  };
}

// ---------------------------------------------------------------------------
// Event → homepage entry mapping
// ---------------------------------------------------------------------------

/** Max characters of `desc` shipped to the homepage (a card teaser, not the story). */
export const HOMEPAGE_DESC_MAX_LENGTH = 160;

/**
 * The trimmed value when it has any non-whitespace content, else null. The
 * event schemas bound string LENGTH only, so '' and '   ' are storable — a
 * fallback chain must treat those as absent or a blank value beats a real
 * fallback (nullish-coalescing alone would emit `place: ""` while a usable
 * approximateArea exists).
 */
function trimmedOrNull(text: string | null | undefined): string | null {
  const trimmed = text?.trim() ?? '';
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * Collapses all whitespace runs (including newlines) to single spaces and
 * truncates to [HOMEPAGE_DESC_MAX_LENGTH] with a single-character ellipsis.
 * The homepage renders desc as a one-line card teaser; the full text lives in
 * the app and on the public event page.
 */
export function homepageDesc(text: string | null | undefined): string {
  const collapsed = (text ?? '').replace(/\s+/g, ' ').trim();
  if (collapsed.length <= HOMEPAGE_DESC_MAX_LENGTH) {
    return collapsed;
  }
  return `${collapsed.slice(0, HOMEPAGE_DESC_MAX_LENGTH - 1).trimEnd()}…`;
}

/** The public-safe slice of one event the homepage feed is built from. */
export interface PublicSiteEventSource {
  eventId: string;
  status: EventStatus;
  title: string;
  startsAt: Date;
  /** Public place name from the teaser doc (never the street address). */
  locationName: string | null;
  /** Coarse area fallback when no place name is set (also public teaser data). */
  approximateArea: string | null;
  /** Short public summary from the teaser doc. */
  summary: string | null;
  /**
   * Long description (member-gated in the app; the creator opted this event
   * onto the public site, where the public page shows it). Used only as the
   * desc fallback when no summary exists.
   */
  description: string | null;
}

/** One entry of data/app-events.json — mirrors the hand-edited events.json shape. */
export interface HomepageEvent {
  title: string;
  /** Europe/Stockholm calendar date, YYYY-MM-DD. */
  date: string;
  /** Europe/Stockholm wall-clock time, HH:MM. */
  time: string;
  place: string;
  desc: string;
  /** Absolute URL of the event's public page. */
  url: string;
  /** Marks the entry as backend-generated (the homepage renders an app note). */
  source: 'app';
}

/**
 * The UPCOMING publicly-enabled events, mapped and sorted for the homepage:
 * `published` status, a start strictly in the future at `now`, ascending by
 * start. Past events simply fall out of the generated file — the homepage
 * must never carry more than needed.
 */
export function selectHomepageEvents(
  events: PublicSiteEventSource[],
  now: Date,
): HomepageEvent[] {
  return events
    .filter((event) => event.status === 'published' && event.startsAt.getTime() > now.getTime())
    .sort((a, b) => a.startsAt.getTime() - b.startsAt.getTime())
    .map((event) => {
      const { date, time } = stockholmDateTimeParts(event.startsAt);
      return {
        title: event.title,
        date,
        time,
        // Trimmed-non-empty preference on BOTH fallback chains: '' and '   '
        // are storable (schemas bound length only), so a blank value must
        // never beat a real fallback (see trimmedOrNull).
        place: trimmedOrNull(event.locationName) ?? trimmedOrNull(event.approximateArea) ?? '',
        desc: homepageDesc(trimmedOrNull(event.summary) ?? event.description),
        url: publicEventUrl(event.eventId),
        source: 'app' as const,
      };
    });
}

// ---------------------------------------------------------------------------
// data/app-events.json content (the agreed cross-repo contract)
// ---------------------------------------------------------------------------

/** Human warning stored in the file so nobody hand-edits generated content. */
export const HOMEPAGE_FILE_NOTICE =
  'Denna fil skrivs automatiskt av appens backend. Redigera INTE för hand — ändringar skrivs över. Manuella event läggs i data/events.json som vanligt.';

/**
 * Serializes the full data/app-events.json content. Key order and 2-space
 * indentation are fixed so the same events always produce byte-identical
 * output (bar generatedAt) and diffs in the homepage repo stay readable.
 */
export function buildHomepageEventsFile(events: HomepageEvent[], generatedAt: Date): string {
  return `${JSON.stringify(
    {
      _generated: HOMEPAGE_FILE_NOTICE,
      generatedAt: generatedAt.toISOString(),
      events,
    },
    null,
    2,
  )}\n`;
}

/**
 * Whether two file contents describe the SAME feed — equal apart from the
 * `generatedAt` stamp. This is what makes "skip the commit when unchanged"
 * real: generatedAt differs on every run by construction, so a byte compare
 * would commit daily forever even with zero event changes.
 *
 * A missing/unparseable existing file is never equivalent (the first
 * generation, or a hand-mangled file, must be [re]written).
 */
export function homepageFileEquivalent(existing: string | null, next: string): boolean {
  if (existing === null) {
    return false;
  }
  let existingParsed: unknown;
  try {
    existingParsed = JSON.parse(existing);
  } catch {
    return false;
  }
  const nextParsed = JSON.parse(next) as Record<string, unknown>;
  if (typeof existingParsed !== 'object' || existingParsed === null) {
    return false;
  }
  const a = { ...(existingParsed as Record<string, unknown>) };
  const b = { ...nextParsed };
  delete a.generatedAt;
  delete b.generatedAt;
  return JSON.stringify(a) === JSON.stringify(b);
}

// ---------------------------------------------------------------------------
// Change-relevance (the events/{eventId} trigger's cheap pre-filter)
// ---------------------------------------------------------------------------

/**
 * Teaser-doc fields whose change can alter the generated homepage file. The
 * trigger regenerates only when one of these moved on a publicly-enabled
 * event; every other write (an rsvpCounts bump, a reminder marker, chat
 * counters) is a no-op. `description` is deliberately absent — it lives on
 * the details/private subdocument, which this trigger never sees; the daily
 * sweep picks up a pure description edit within a day, which is acceptable
 * for a 160-char card teaser fallback.
 */
export const HOMEPAGE_SYNC_FIELDS = [
  'publicSiteEnabled',
  'status',
  'startsAt',
  'endsAt',
  'title',
  'summary',
  'locationName',
  'approximateArea',
] as const;

/** Value comparison that understands Firestore Timestamps (compares instants). */
function fieldEquals(a: unknown, b: unknown): boolean {
  const aMillis = (a as { toMillis?: () => number } | null)?.toMillis;
  const bMillis = (b as { toMillis?: () => number } | null)?.toMillis;
  if (typeof aMillis === 'function' && typeof bMillis === 'function') {
    return (a as { toMillis: () => number }).toMillis() === (b as { toMillis: () => number }).toMillis();
  }
  return a === b;
}

/**
 * Whether a write to events/{eventId} can change the generated homepage file.
 *
 * - Neither side publicly enabled → never relevant (the overwhelmingly common
 *   case: events that never opted in).
 * - A create/delete of an enabled event → relevant.
 * - Otherwise → relevant only when one of [HOMEPAGE_SYNC_FIELDS] changed.
 *   The flag flipping in either direction is itself one of those fields, so
 *   enable/disable, cancel, complete (the auto-close sweep included), a
 *   reschedule and a retitle all regenerate; unrelated counter writes do not.
 */
export function isHomepageSyncRelevant(
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
): boolean {
  const enabledBefore = before?.publicSiteEnabled === true;
  const enabledAfter = after?.publicSiteEnabled === true;
  if (!enabledBefore && !enabledAfter) {
    return false;
  }
  if (before === undefined || after === undefined) {
    return true;
  }
  return HOMEPAGE_SYNC_FIELDS.some((field) => !fieldEquals(before[field], after[field]));
}
