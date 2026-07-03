/**
 * Events domain — pure input validation, business-rule guards, and document
 * builders (Phase 9b).
 *
 * Mirrors the legacy semantics in services/api/src/lib/event-service.ts and
 * the contracts in contracts/schemas/events.schema.json. The exact location
 * (locationName, address, latitude, longitude) and long description are
 * member-only in the legacy API, so the Firestore model splits every event
 * into two documents:
 *
 * - `events/{eventId}` — teaser-safe + operational fields; readable by any
 *   authenticated user while published, by admins always.
 * - `events/{eventId}/details/private` — member-gated fields; readable by
 *   active members while the event is published, by admins always.
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
    approximateArea: eventFieldsSchema.approximateArea,
    locationName: eventFieldsSchema.locationName.optional(),
    address: eventFieldsSchema.address.optional(),
    latitude: eventFieldsSchema.latitude.optional(),
    longitude: eventFieldsSchema.longitude.optional(),
    isOfficial: eventFieldsSchema.isOfficial.optional(),
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
    'Expected createEventRequest (contracts/schemas/events.schema.json): { title, startsAt, approximateArea, ...optional fields }.',
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
// Business-rule guards (legacy event-service.ts parity)
// ---------------------------------------------------------------------------

export type GuardResult =
  | { ok: true }
  | { ok: false; code: 'invalid-argument' | 'failed-precondition' | 'not-found'; message: string };

/** endsAt, when present, must be strictly after startsAt. */
export function guardEventTimes(startsAt: string, endsAt: string | null | undefined): GuardResult {
  if (endsAt && new Date(endsAt).getTime() <= new Date(startsAt).getTime()) {
    return { ok: false, code: 'invalid-argument', message: 'endsAt must be after startsAt.' };
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

/** Only future-starting drafts with title + approximateArea can be published. */
export function guardPublishable(
  event: { status: EventStatus; title: string; approximateArea: string; startsAt: Date },
  now: Date,
): GuardResult {
  if (event.status !== 'draft') {
    return { ok: false, code: 'failed-precondition', message: 'Only draft events can be published.' };
  }
  if (!event.title || !event.approximateArea) {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Event must have title and approximateArea before publishing.',
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

// ---------------------------------------------------------------------------
// Document builders — the teaser/private split
// ---------------------------------------------------------------------------

/** Field names stored on the member-gated events/{eventId}/details/private doc. */
export const PRIVATE_DETAIL_FIELDS = [
  'description',
  'locationName',
  'address',
  'latitude',
  'longitude',
] as const;

export interface EventDocuments {
  /** events/{eventId} — teaser-safe + operational fields. */
  eventDoc: Record<string, unknown>;
  /** events/{eventId}/details/private — member-gated fields. */
  privateDoc: Record<string, unknown>;
}

/** Builds both documents for a newly created (draft) event. */
export function buildEventDocuments(
  input: CreateEventInput,
  createdByUserId: string,
  serverTimestamp: () => unknown,
): EventDocuments {
  return {
    eventDoc: {
      title: input.title,
      summary: input.summary ?? null,
      startsAt: new Date(input.startsAt),
      endsAt: input.endsAt ? new Date(input.endsAt) : null,
      approximateArea: input.approximateArea,
      isOfficial: input.isOfficial ?? false,
      status: 'draft',
      cancelledAt: null,
      rsvpCounts: { going: 0, maybe: 0, not_going: 0 },
      createdByUserId,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    },
    privateDoc: {
      description: input.description ?? null,
      locationName: input.locationName ?? null,
      address: input.address ?? null,
      latitude: input.latitude ?? null,
      longitude: input.longitude ?? null,
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
  if (input.isOfficial !== undefined) assign(eventDoc, 'isOfficial', input.isOfficial);

  if (input.description !== undefined) assign(privateDoc, 'description', input.description);
  if (input.locationName !== undefined) assign(privateDoc, 'locationName', input.locationName);
  if (input.address !== undefined) assign(privateDoc, 'address', input.address);
  if (input.latitude !== undefined) assign(privateDoc, 'latitude', input.latitude);
  if (input.longitude !== undefined) assign(privateDoc, 'longitude', input.longitude);

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
