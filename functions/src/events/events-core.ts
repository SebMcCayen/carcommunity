/**
 * Events domain — pure input validation, business-rule guards, and document
 * builders (Phase 9b).
 *
 * Mirrors the legacy semantics in services/api/src/lib/event-service.ts and
 * the contracts in contracts/schemas/events.schema.json. Every event is split
 * into two documents:
 *
 * - `events/{eventId}` — teaser-safe + operational fields, PLUS the event's
 *   map location (locationName, latitude, longitude); readable by any
 *   authenticated user while published, by admins always.
 * - `events/{eventId}/details/private` — member-gated fields (the long
 *   description and the precise street address); readable by active members
 *   while the event is published, by admins always.
 *
 * DELIBERATE PRIVACY CHANGE (2026-07): the coordinate pair and the place name
 * now live on the PUBLIC teaser, not the member-gated detail. Product decision
 * — every signed-in user sees event locations as pins on the community map, so
 * the map read must reach them without the member gate. Only the precise street
 * `address` and the long `description` remain member-only. Historically the
 * whole "exact location" was member-only; that is no longer the model.
 *
 * No Firebase Admin SDK imports — the server-timestamp sentinel is injected
 * so this module stays unit-testable without emulators.
 */

import { z } from 'zod';

export const EVENT_STATUSES = ['draft', 'published', 'cancelled', 'completed'] as const;
export type EventStatus = (typeof EVENT_STATUSES)[number];

export const RSVP_STATUSES = ['going', 'maybe', 'not_going'] as const;
export type RsvpStatus = (typeof RSVP_STATUSES)[number];

// ---------------------------------------------------------------------------
// Input schemas (limits mirror the legacy route schemas and the contracts)
// ---------------------------------------------------------------------------

const eventFieldsSchema = {
  title: z.string().trim().min(1).max(200),
  summary: z.string().max(2000).nullable(),
  description: z.string().max(10000).nullable(),
  startsAt: z.string().datetime(),
  endsAt: z.string().datetime().nullable(),
  approximateArea: z.string().trim().min(1).max(200),
  locationName: z.string().max(200).nullable(),
  address: z.string().max(400).nullable(),
  latitude: z.number().min(-90).max(90).nullable(),
  longitude: z.number().min(-180).max(180).nullable(),
  isOfficial: z.boolean(),
};

const createEventInputSchema = z
  .object({
    title: eventFieldsSchema.title,
    summary: eventFieldsSchema.summary.optional(),
    description: eventFieldsSchema.description.optional(),
    startsAt: eventFieldsSchema.startsAt,
    endsAt: eventFieldsSchema.endsAt.optional(),
    // Optional since 2026-08: the member create form dropped its "Approximate
    // area" input. Still bounded (min 1 / max 200) WHEN supplied by an
    // admin/programmatic caller; simply omitted otherwise.
    approximateArea: eventFieldsSchema.approximateArea.optional(),
    locationName: eventFieldsSchema.locationName.optional(),
    address: eventFieldsSchema.address.optional(),
    latitude: eventFieldsSchema.latitude.optional(),
    longitude: eventFieldsSchema.longitude.optional(),
    isOfficial: eventFieldsSchema.isOfficial.optional(),
    // Publish-on-create intent (2026-08). The community APP has no draft concept
    // and no publish UI — a publish step exists only in admin-web — so an
    // in-app-created event must become visible immediately. The app sends
    // `publishNow: true`; the callable then publishes even when the creator
    // happens to be an admin (whose events would otherwise start `draft` and be
    // invisible in BOTH the events list and the map, with no in-app way out).
    // Admin-web omits it and keeps its deliberate draft-then-publish flow; a
    // member is published either way, so the flag is a no-op for them.
    publishNow: z.boolean().optional(),
  })
  .strict();

const updateEventInputSchema = z
  .object({
    eventId: z.string().trim().min(1),
    title: eventFieldsSchema.title.optional(),
    summary: eventFieldsSchema.summary.optional(),
    description: eventFieldsSchema.description.optional(),
    startsAt: eventFieldsSchema.startsAt.optional(),
    endsAt: eventFieldsSchema.endsAt.optional(),
    approximateArea: eventFieldsSchema.approximateArea.optional(),
    locationName: eventFieldsSchema.locationName.optional(),
    address: eventFieldsSchema.address.optional(),
    latitude: eventFieldsSchema.latitude.optional(),
    longitude: eventFieldsSchema.longitude.optional(),
    isOfficial: eventFieldsSchema.isOfficial.optional(),
  })
  .strict();

const eventIdInputSchema = z.object({ eventId: z.string().trim().min(1) }).strict();

const cancelEventInputSchema = z
  .object({
    eventId: z.string().trim().min(1),
    reason: z.string().trim().min(1).max(2000),
  })
  .strict();

export type CreateEventInput = z.infer<typeof createEventInputSchema>;
export type UpdateEventInput = z.infer<typeof updateEventInputSchema>;
export type CancelEventInput = z.infer<typeof cancelEventInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export function parseCreateEventInput(data: unknown): ParseResult<CreateEventInput> {
  return parse(
    createEventInputSchema,
    data,
    'Expected createEventRequest (contracts/schemas/events.schema.json): { title, startsAt, ...optional fields }.',
  );
}

export function parseUpdateEventInput(data: unknown): ParseResult<UpdateEventInput> {
  return parse(
    updateEventInputSchema,
    data,
    'Expected { eventId } plus updateEventRequest fields (contracts/schemas/events.schema.json).',
  );
}

export function parseEventIdInput(data: unknown): ParseResult<{ eventId: string }> {
  return parse(eventIdInputSchema, data, 'Expected { eventId }.');
}

export function parseCancelEventInput(data: unknown): ParseResult<CancelEventInput> {
  return parse(
    cancelEventInputSchema,
    data,
    'Expected { eventId, reason } (cancelEventRequest, contracts/schemas/events.schema.json).',
  );
}

// ---------------------------------------------------------------------------
// Time helpers (Europe/Stockholm) — the app serves Kungsbacka, Sweden
// ---------------------------------------------------------------------------

/** An event may last at most 3 days (72 hours) from start to end. */
export const MAX_EVENT_DURATION_MS = 3 * 24 * 60 * 60 * 1000;

const STOCKHOLM_TIME_ZONE = 'Europe/Stockholm';

/** The Europe/Stockholm wall-clock fields (y/m/d h:m:s) for a given instant. */
function stockholmWallClock(instant: Date): {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
} {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: STOCKHOLM_TIME_ZONE,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(instant);
  const field: Record<string, number> = {};
  for (const part of parts) {
    if (part.type !== 'literal') field[part.type] = Number(part.value);
  }
  return {
    year: field.year ?? 0,
    month: field.month ?? 1,
    day: field.day ?? 1,
    hour: field.hour ?? 0,
    minute: field.minute ?? 0,
    second: field.second ?? 0,
  };
}

/**
 * The UTC offset (in minutes) of Europe/Stockholm at a given instant.
 * Sweden observes CET (UTC+1) in winter and CEST (UTC+2) in summer, so this
 * flips across DST boundaries. Derived from Intl so the transition dates stay
 * correct without a bundled tz database.
 */
function stockholmOffsetMinutes(instant: Date): number {
  const { year, month, day, hour, minute, second } = stockholmWallClock(instant);
  const asIfUtc = Date.UTC(year, month - 1, day, hour, minute, second);
  // Wall-clock parts have no sub-second component; round so the offset is the
  // exact whole-minute tz offset regardless of the instant's milliseconds.
  return Math.round((asIfUtc - instant.getTime()) / 60_000);
}

/**
 * Returns the ISO instant for 23:59:59.999 on the Europe/Stockholm calendar
 * day of `startsAtIso`. End-of-day is never near a DST transition (those happen
 * around 02:00–03:00 local), so a single offset correction from a UTC guess is
 * exact for both CET and CEST.
 */
export function stockholmEndOfDay(startsAtIso: string): string {
  const { year, month, day } = stockholmWallClock(new Date(startsAtIso));
  const localEndOfDayAsUtc = Date.UTC(year, month - 1, day, 23, 59, 59, 999);
  const offsetMinutes = stockholmOffsetMinutes(new Date(localEndOfDayAsUtc));
  return new Date(localEndOfDayAsUtc - offsetMinutes * 60_000).toISOString();
}

// ---------------------------------------------------------------------------
// Business-rule guards (legacy event-service.ts parity)
// ---------------------------------------------------------------------------

export type GuardResult =
  | { ok: true }
  | {
      ok: false;
      code: 'invalid-argument' | 'failed-precondition' | 'not-found' | 'permission-denied';
      message: string;
    };

/**
 * The effective end of an event: the explicit `endsAt` when provided, otherwise
 * the Europe/Stockholm end-of-day of `startsAt` (23:59:59.999 local). This is the
 * same end-of-day default the stored documents receive — applied on the create
 * path in `buildEventDocuments` and on the update path in `manageEvent.update` —
 * so guards and stored docs agree.
 */
export function effectiveEndsAt(startsAt: string, endsAt: string | null | undefined): string {
  return endsAt ?? stockholmEndOfDay(startsAt);
}

/**
 * The effective endsAt — the explicit value or the defaulted Stockholm
 * end-of-day — must be strictly after startsAt and no more than 3 days
 * (72 hours) after it. Guarding the effective (not just the input) endsAt keeps
 * the "endsAt after startsAt" invariant intact even for the defaulted value
 * (e.g. a startsAt already at 23:59:59.999 local).
 */
export function guardEventTimes(startsAt: string, endsAt: string | null | undefined): GuardResult {
  const startMs = new Date(startsAt).getTime();
  const endMs = new Date(effectiveEndsAt(startsAt, endsAt)).getTime();
  if (endMs <= startMs) {
    return { ok: false, code: 'invalid-argument', message: 'endsAt must be after startsAt.' };
  }
  if (endMs - startMs > MAX_EVENT_DURATION_MS) {
    return {
      ok: false,
      code: 'invalid-argument',
      message: 'endsAt cannot be more than 3 days after startsAt.',
    };
  }
  return { ok: true };
}

/** latitude and longitude must both be provided or both be null/omitted. */
export function guardCoordinatePair(
  latitude: number | null | undefined,
  longitude: number | null | undefined,
): GuardResult {
  if ((latitude != null) !== (longitude != null)) {
    return {
      ok: false,
      code: 'invalid-argument',
      message: 'latitude and longitude must both be provided or both omitted.',
    };
  }
  return { ok: true };
}

/** Draft and published events can be edited; cancelled/completed cannot. */
export function guardUpdatableStatus(status: EventStatus): GuardResult {
  if (status === 'cancelled' || status === 'completed') {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Cannot update a cancelled or completed event.',
    };
  }
  return { ok: true };
}

/**
 * Only future-starting drafts with a title can be published. `approximateArea`
 * is optional since 2026-08 (the create form dropped its input), so it is no
 * longer a publish precondition; a member-created event — which runs through
 * this same guard in `manageEvent` — may carry none.
 */
export function guardPublishable(
  event: { status: EventStatus; title: string; startsAt: Date },
  now: Date,
): GuardResult {
  if (event.status !== 'draft') {
    return { ok: false, code: 'failed-precondition', message: 'Only draft events can be published.' };
  }
  if (!event.title) {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Event must have a title before publishing.',
    };
  }
  if (event.startsAt.getTime() < now.getTime()) {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Cannot publish an event whose start time is in the past.',
    };
  }
  return { ok: true };
}

/** Draft and published events can be cancelled; not twice, not after completion. */
export function guardCancellable(status: EventStatus): GuardResult {
  if (status === 'cancelled') {
    return { ok: false, code: 'failed-precondition', message: 'Event is already cancelled.' };
  }
  if (status === 'completed') {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Completed events cannot be cancelled.',
    };
  }
  return { ok: true };
}

/** Only published events can be marked completed. */
export function guardCompletable(status: EventStatus): GuardResult {
  if (status !== 'published') {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Only published events can be completed.',
    };
  }
  return { ok: true };
}

/**
 * Who may mark an event completed ("ended"): an admin/owner, or the member who
 * created it. A member may only end THEIR OWN event — `createdByUserId` on the
 * teaser doc is written by events.create and never client-writable (rules deny
 * all client writes to events/{eventId}), so it is a trustworthy owner record.
 *
 * Deliberately NOT allowed: any other member, however active. Ending an event
 * closes its chat and its member-gated detail (both rules-gated on
 * `published`), so it is a takedown-shaped action — it stays with the organiser
 * and the admins.
 */
export function guardCompleteActor(
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
    message: 'Only the event creator or an admin can end this event.',
  };
}

// ---------------------------------------------------------------------------
// Auto-close (scheduled lifecycle)
// ---------------------------------------------------------------------------

/**
 * How long after an event's effective end it is left `published` before the
 * scheduled sweep completes it.
 *
 * Six hours, chosen against the real model rather than taste:
 * - Most events carry NO explicit `endsAt`, so [buildEventDocuments] defaults
 *   it to the Europe/Stockholm end-of-day (23:59:59.999 local). A grace of 6h
 *   closes those at ~06:00 the next local morning — after the event, before
 *   anyone opens the list that day, and still inside the same 24h so a past
 *   event never survives a full day in the upcoming list (Seb's report).
 * - Closing AT the end instant would be hostile to a running event: completing
 *   revokes member access to the exact location, the description and the event
 *   chat (all rules-gated on `published`), so an event that runs long would
 *   lose its own attendees' directions. 6h covers the overrun without letting
 *   a finished event linger.
 */
export const AUTO_CLOSE_GRACE_MS = 6 * 60 * 60 * 1000;

/**
 * The instant an event actually ends: the stored `endsAt`, or — defensively,
 * for any document written before the end-of-day default existed — the
 * Europe/Stockholm end-of-day of its start. Mirrors [effectiveEndsAt] on Date
 * values rather than ISO strings.
 */
export function effectiveEndInstant(startsAt: Date, endsAt: Date | null | undefined): Date {
  return endsAt ?? new Date(stockholmEndOfDay(startsAt.toISOString()));
}

/**
 * The instant at/after which a published event is due to be auto-completed:
 * its effective end plus [AUTO_CLOSE_GRACE_MS].
 */
export function autoCloseDueAt(startsAt: Date, endsAt: Date | null | undefined): Date {
  return new Date(effectiveEndInstant(startsAt, endsAt).getTime() + AUTO_CLOSE_GRACE_MS);
}

/**
 * The newest `startsAt` a due event can possibly have — the sweep's candidate
 * query bound (see [isAutoCloseDue] for the exact per-event test).
 *
 * Sound because [guardEventTimes] enforces `end > start` on every write path:
 * due means `end + grace <= now`, and `start < end`, therefore
 * `start < now - grace`. So no due event is ever excluded by filtering
 * `startsAt <= now - grace`, while future and in-progress events are cheaply
 * skipped server-side. This bound is what lets the sweep reuse the EXISTING
 * `status ASC, startsAt ASC` composite index instead of needing a new
 * `status, endsAt` one.
 */
export function autoCloseCandidateCutoff(now: Date): Date {
  return new Date(now.getTime() - AUTO_CLOSE_GRACE_MS);
}

/**
 * Whether a published event is due to be auto-completed at `now` — the precise
 * test, applied in memory to each candidate the query returns. Only `published`
 * events are ever due: `draft` events were never live, and `cancelled` /
 * `completed` are terminal, which is what makes the sweep idempotent.
 */
export function isAutoCloseDue(
  event: { status: EventStatus; startsAt: Date; endsAt: Date | null | undefined },
  now: Date,
): boolean {
  if (event.status !== 'published') {
    return false;
  }
  return now.getTime() >= autoCloseDueAt(event.startsAt, event.endsAt).getTime();
}

// ---------------------------------------------------------------------------
// Member-created events (moderation model)
// ---------------------------------------------------------------------------

/**
 * Who created an event — stored (client-immutable, callable-written) on
 * `events/{eventId}.createdByRole` next to `createdByUserId`, so every event
 * is attributable and an admin can tell an organiser-run event from a
 * member-submitted one.
 *
 * MODERATION MODEL — POST-moderation, deliberately: a member-created event is
 * PUBLISHED on creation (see [initialEventStatus]) and admins take it down
 * afterwards with the existing audited `events.cancel` callable. Rationale:
 * - The admin events listing is an unfiltered `events` query, so member
 *   events appear there the moment they exist — moderation needs no new
 *   surface, and cancel/update already work on them unchanged.
 * - Nothing renders `draft` events to anyone (rules: non-admins read
 *   published only), so an event parked as a draft is invisible even to its
 *   own creator, and community meetups are time-critical — an unstaffed queue
 *   silently kills them.
 *
 * The PRE-moderation alternative (member events start `draft`; an admin
 * publishes them) was REJECTED: it needs an admin approval-queue UI that does
 * not exist, makes one admin the bottleneck for every meetup, and buys little
 * safety here — creation already demands a paying, identified, non-suspended
 * member; [MEMBER_EVENT_RATE_LIMIT_MAX] caps the blast radius; takedown is one
 * audited click. Revisit if abuse actually appears: making [initialEventStatus]
 * always return 'draft' is the entire switch.
 */
export const EVENT_CREATOR_ROLES = ['admin', 'member'] as const;
export type EventCreatorRole = (typeof EVENT_CREATOR_ROLES)[number];

/** Max events one member may create per rolling [MEMBER_EVENT_RATE_LIMIT_WINDOW_MS]. */
export const MEMBER_EVENT_RATE_LIMIT_MAX = 3;
/** Rolling window width: 24 hours. */
export const MEMBER_EVENT_RATE_LIMIT_WINDOW_MS = 24 * 60 * 60 * 1000;

/** Start of the rate-limit window: events created at/after this instant count. */
export function memberEventRateLimitWindowStart(now: Date): Date {
  return new Date(now.getTime() - MEMBER_EVENT_RATE_LIMIT_WINDOW_MS);
}

/** True when one more member-created event would exceed the per-member cap. */
export function isMemberEventRateLimited(recentCount: number): boolean {
  return recentCount >= MEMBER_EVENT_RATE_LIMIT_MAX;
}

/**
 * The status a newly created event starts in.
 *
 * - member → always `published` immediately (see the [EVENT_CREATOR_ROLES]
 *   note); `publishNow` is irrelevant for a member and never demotes them.
 * - admin  → `draft` by default (an admin publishes explicitly via
 *   `events.publish` — the admin-web draft-then-publish flow, unchanged since
 *   Phase 9b), UNLESS `publishNow` is set, which the community APP passes so an
 *   admin creating an event in the app gets a published, visible event rather
 *   than a permanently-invisible draft (the app has no publish UI).
 *
 * `publishNow` only lifts an admin from draft to published; it can never turn a
 * would-be published event into a draft. A published result still has to clear
 * [guardPublishable] on the create path (title + future start), so this is not a
 * back door around those preconditions.
 */
export function initialEventStatus(
  creatorRole: EventCreatorRole,
  publishNow = false,
): EventStatus {
  if (creatorRole === 'member') return 'published';
  return publishNow ? 'published' : 'draft';
}

// ---------------------------------------------------------------------------
// Document builders — the teaser/private split
// ---------------------------------------------------------------------------

/**
 * Field names stored on the member-gated events/{eventId}/details/private doc.
 *
 * The map location (locationName, latitude, longitude) is deliberately NOT here
 * — it moved to the public teaser so every signed-in user can see event pins on
 * the map (see the file header). Only the long description and the precise
 * street address stay member-only.
 */
export const PRIVATE_DETAIL_FIELDS = ['description', 'address'] as const;

export interface EventDocuments {
  /** events/{eventId} — teaser-safe + operational fields. */
  eventDoc: Record<string, unknown>;
  /** events/{eventId}/details/private — member-gated fields. */
  privateDoc: Record<string, unknown>;
}

/**
 * Builds both documents for a newly created event.
 *
 * `creatorRole` (default 'admin' — the Phase 9b behaviour) decides two
 * things and nothing else:
 * - the starting status ([initialEventStatus]: admin → draft, member →
 *   published);
 * - `isOfficial`, which is FORCED false for a member-created event. The
 *   "official" badge marks club-sanctioned events, so a member must never be
 *   able to mint one by passing `isOfficial: true` — only an admin's event
 *   honours the input flag. (`events.update` is admin-only, so an admin can
 *   still promote a member event afterwards.)
 */
export function buildEventDocuments(
  input: CreateEventInput,
  createdByUserId: string,
  serverTimestamp: () => unknown,
  creatorRole: EventCreatorRole = 'admin',
  // Publish-on-create intent — see [initialEventStatus]. Defaults false so the
  // Phase 9b admin behaviour (created `draft`) is unchanged unless a caller
  // (the app) explicitly asks to publish. Ignored for a member (always
  // published). Falls back to `input.publishNow` when not passed explicitly, so
  // the parsed request field alone is enough.
  publishNow: boolean = input.publishNow ?? false,
): EventDocuments {
  return {
    eventDoc: {
      title: input.title,
      summary: input.summary ?? null,
      startsAt: new Date(input.startsAt),
      // When no explicit end is given, an event runs until the end of its
      // Europe/Stockholm start day (23:59:59.999 local).
      endsAt: new Date(effectiveEndsAt(input.startsAt, input.endsAt)),
      // Optional since 2026-08 (create form dropped its input) — stored null when
      // the caller supplies none.
      approximateArea: input.approximateArea ?? null,
      // Public map location (see the file header): the place name and the
      // coordinate pair live on the teaser so every signed-in user sees the pin.
      locationName: input.locationName ?? null,
      latitude: input.latitude ?? null,
      longitude: input.longitude ?? null,
      isOfficial: creatorRole === 'admin' ? (input.isOfficial ?? false) : false,
      status: initialEventStatus(creatorRole, publishNow),
      cancelledAt: null,
      rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
      createdByUserId,
      createdByRole: creatorRole,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    },
    privateDoc: {
      // Member-only: the long write-up and the precise street address.
      description: input.description ?? null,
      address: input.address ?? null,
      updatedAt: serverTimestamp(),
    },
  };
}

/**
 * Splits a partial update into per-document field updates. Returns the list
 * of changed field names for the audit record (legacy parity: changedFields).
 */
export function buildEventUpdates(
  input: UpdateEventInput,
  serverTimestamp: () => unknown,
): EventDocuments & { changedFields: string[] } {
  const eventDoc: Record<string, unknown> = {};
  const privateDoc: Record<string, unknown> = {};
  const changedFields: string[] = [];

  const assign = (target: Record<string, unknown>, key: string, value: unknown) => {
    target[key] = value;
    changedFields.push(key);
  };

  if (input.title !== undefined) assign(eventDoc, 'title', input.title);
  if (input.summary !== undefined) assign(eventDoc, 'summary', input.summary);
  if (input.startsAt !== undefined) assign(eventDoc, 'startsAt', new Date(input.startsAt));
  if (input.endsAt !== undefined)
    assign(eventDoc, 'endsAt', input.endsAt ? new Date(input.endsAt) : null);
  if (input.approximateArea !== undefined)
    assign(eventDoc, 'approximateArea', input.approximateArea);
  // Public map location — teaser fields now (see the file header).
  if (input.locationName !== undefined) assign(eventDoc, 'locationName', input.locationName);
  if (input.latitude !== undefined) assign(eventDoc, 'latitude', input.latitude);
  if (input.longitude !== undefined) assign(eventDoc, 'longitude', input.longitude);
  if (input.isOfficial !== undefined) assign(eventDoc, 'isOfficial', input.isOfficial);

  // Member-only fields stay on the private detail document.
  if (input.description !== undefined) assign(privateDoc, 'description', input.description);
  if (input.address !== undefined) assign(privateDoc, 'address', input.address);

  if (Object.keys(eventDoc).length > 0) {
    eventDoc.updatedAt = serverTimestamp();
  }
  if (Object.keys(privateDoc).length > 0) {
    privateDoc.updatedAt = serverTimestamp();
  }

  return { eventDoc, privateDoc, changedFields };
}

// ---------------------------------------------------------------------------
// RSVP count aggregation (events/{eventId}.rsvpCounts trigger)
// ---------------------------------------------------------------------------

export interface RsvpCountDeltas {
  going: number;
  maybe: number;
  not_going: number;
}

function isRsvpStatus(value: unknown): value is RsvpStatus {
  return typeof value === 'string' && (RSVP_STATUSES as readonly string[]).includes(value);
}

/**
 * Computes rsvpCounts increments for an RSVP document write. `before` and
 * `after` are the document's `status` field (undefined when the document did
 * not exist on that side). Unknown statuses contribute nothing, so a bad
 * historical value can never corrupt the counters.
 */
export function computeRsvpCountDeltas(before: unknown, after: unknown): RsvpCountDeltas {
  const deltas: RsvpCountDeltas = { going: 0, maybe: 0, not_going: 0 };
  if (isRsvpStatus(before)) {
    deltas[before] -= 1;
  }
  if (isRsvpStatus(after)) {
    deltas[after] += 1;
  }
  return deltas;
}

/** True when every delta is zero (no counter write needed). */
export function isZeroDeltas(deltas: RsvpCountDeltas): boolean {
  return deltas.going === 0 && deltas.maybe === 0 && deltas.not_going === 0;
}
