/**
 * Events feature module for the admin portal (Phase 13 vertical).
 *
 * Migrated from the legacy `apiRequest` REST client to Firebase, following
 * the 13a pattern (feature-flags / points):
 *  - READS are direct rules-gated Firestore reads. `events/{id}` is admin
 *    readable (`isAdmin()` in firestore.rules), as is the member-gated
 *    `events/{id}/details/private` subcollection — so the admin detail view
 *    merges the teaser doc with its private location fields.
 *  - MUTATIONS go through the audited `events-*` callables. Those callables
 *    return only `{ eventId, status }`, so this adapter re-reads the event
 *    afterwards to keep returning the full `AdminEventResponse` the pages
 *    already consume.
 *
 * Exported signatures and response-envelope types are unchanged, so the
 * pages in src/app/events/ keep working — this module is the adapter layer.
 *
 * Scope (matches the points adapter's pragmatism): the admin list is served
 * as a single bounded, start-time-ordered window with the status/official/
 * upcoming filters applied client-side. This keeps the query on a single
 * auto-created index (no new composite index); richer server-side filtering
 * and deep pagination are a follow-up.
 */

import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  type DocumentData,
  type Timestamp,
} from 'firebase/firestore';
import {
  type AdminEventDetail,
  type AdminEventResponse,
  type AdminEventsResponse,
  type AdminEventSummary,
  type CancelEventRequest,
  type CreateEventRequest,
  type EventRsvpSummary,
  type EventStatus,
  type UpdateEventRequest,
} from '@carcommunity/shared/events';

import { ApiError } from '../../lib/api';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

export type {
  AdminEventDetail,
  AdminEventSummary,
  CancelEventRequest,
  CreateEventRequest,
  EventStatus,
  UpdateEventRequest,
};
export { ApiError };

// ---------------------------------------------------------------------------
// Swedish status labels (pure helpers — unchanged)
// ---------------------------------------------------------------------------

/** Returns a human-readable Swedish label for an event status. */
export function formatEventStatus(status: EventStatus): string {
  switch (status) {
    case 'draft':
      return 'Utkast';
    case 'published':
      return 'Publicerat';
    case 'cancelled':
      return 'Inställt';
    case 'completed':
      return 'Genomfört';
  }
}

/** Returns a CSS class name fragment based on status for badge styling. */
export function eventStatusVariant(
  status: EventStatus,
): 'draft' | 'published' | 'cancelled' | 'completed' {
  return status;
}

/** Returns true if the event is upcoming (starts in the future and is published). */
export function isUpcomingEvent(event: Pick<AdminEventSummary, 'status' | 'startsAt'>): boolean {
  return event.status === 'published' && new Date(event.startsAt) > new Date();
}

// ---------------------------------------------------------------------------
// Firestore mapping helpers
// ---------------------------------------------------------------------------

/** Single bounded window for the admin list (see the module scope note). */
const EVENTS_WINDOW = 200;

/** Firestore Timestamp → ISO string (nullable). */
function toIso(value: Timestamp | null | undefined): string | null {
  return value ? value.toDate().toISOString() : null;
}

function readRsvpCounts(data: DocumentData): EventRsvpSummary {
  const counts = (data.rsvpCounts ?? {}) as Partial<EventRsvpSummary>;
  return {
    going: counts.going ?? 0,
    maybe: counts.maybe ?? 0,
    not_going: counts.not_going ?? 0,
  };
}

function toSummary(id: string, data: DocumentData): AdminEventSummary {
  return {
    id,
    title: (data.title as string) ?? '',
    status: data.status as EventStatus,
    isOfficial: (data.isOfficial as boolean) ?? false,
    startsAt: toIso(data.startsAt as Timestamp) ?? '',
    endsAt: toIso(data.endsAt as Timestamp | null),
    approximateArea: (data.approximateArea as string) ?? '',
    rsvpCounts: readRsvpCounts(data),
    cancelledAt: toIso(data.cancelledAt as Timestamp | null),
    createdAt: toIso(data.createdAt as Timestamp) ?? '',
    updatedAt: toIso(data.updatedAt as Timestamp) ?? '',
  };
}

function toDetail(id: string, event: DocumentData, priv: DocumentData): AdminEventDetail {
  return {
    id,
    title: (event.title as string) ?? '',
    summary: (event.summary as string | null) ?? null,
    description: (priv.description as string | null) ?? null,
    status: event.status as EventStatus,
    startsAt: toIso(event.startsAt as Timestamp) ?? '',
    endsAt: toIso(event.endsAt as Timestamp | null),
    approximateArea: (event.approximateArea as string) ?? '',
    locationName: (priv.locationName as string | null) ?? null,
    address: (priv.address as string | null) ?? null,
    latitude: (priv.latitude as number | null) ?? null,
    longitude: (priv.longitude as number | null) ?? null,
    isOfficial: (event.isOfficial as boolean) ?? false,
    createdByUserId: (event.createdByUserId as string | null) ?? null,
    createdAt: toIso(event.createdAt as Timestamp) ?? '',
    updatedAt: toIso(event.updatedAt as Timestamp) ?? '',
    cancelledAt: toIso(event.cancelledAt as Timestamp | null),
    rsvpCounts: readRsvpCounts(event),
  };
}

// ---------------------------------------------------------------------------
// Reads (direct Firestore)
// ---------------------------------------------------------------------------

export interface LoadAdminEventsParams {
  page?: number;
  pageSize?: number;
  status?: EventStatus;
  upcoming?: boolean;
  isOfficial?: boolean;
  token?: string;
}

/**
 * Loads the admin event list — a single start-time-ordered window with the
 * status/official/upcoming filters applied client-side (see module scope).
 */
export async function loadAdminEvents(
  params: LoadAdminEventsParams = {},
): Promise<AdminEventsResponse> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'events'),
      orderBy('startsAt', 'desc'),
      limit(EVENTS_WINDOW),
    ),
  );

  let events = snapshot.docs.map((entry) => toSummary(entry.id, entry.data()));
  if (params.status !== undefined) {
    events = events.filter((event) => event.status === params.status);
  }
  if (params.isOfficial !== undefined) {
    events = events.filter((event) => event.isOfficial === params.isOfficial);
  }
  if (params.upcoming) {
    events = events.filter((event) => isUpcomingEvent(event));
  }

  const pageSize = params.pageSize ?? events.length;
  return {
    ok: true,
    data: { events },
    meta: { total: events.length, page: 1, pageSize },
  };
}

/**
 * Loads a single event for the admin detail/edit view — the teaser doc
 * merged with its member-gated `details/private` fields.
 */
export async function loadAdminEvent(eventId: string, _token?: string): Promise<AdminEventResponse> {
  const db = getAdminFirestore();
  const [eventSnap, privateSnap] = await Promise.all([
    getDoc(doc(db, 'events', eventId)),
    getDoc(doc(db, 'events', eventId, 'details', 'private')),
  ]);
  if (!eventSnap.exists()) {
    throw new ApiError(404, 'not-found', 'Event not found.');
  }
  return {
    ok: true,
    data: { event: toDetail(eventId, eventSnap.data(), privateSnap.data() ?? {}) },
  };
}

// ---------------------------------------------------------------------------
// Mutations (audited callables, then re-read for the full detail envelope)
// ---------------------------------------------------------------------------

interface EventIdResponse {
  eventId: string;
  status: EventStatus;
}

/**
 * Creates a new draft event via the audited `events-create` callable, then
 * returns the freshly-read detail. Status and creator are set server-side.
 */
export async function createAdminEvent(
  data: CreateEventRequest,
  _token?: string,
): Promise<AdminEventResponse> {
  const { eventId } = await callAdmin<EventIdResponse>('events-create', data);
  return loadAdminEvent(eventId);
}

/**
 * Updates an existing draft or published event via `events-update`.
 * Status changes must use publishAdminEvent or cancelAdminEvent.
 */
export async function updateAdminEvent(
  eventId: string,
  data: UpdateEventRequest,
  _token?: string,
): Promise<AdminEventResponse> {
  await callAdmin<EventIdResponse>('events-update', { eventId, ...data });
  return loadAdminEvent(eventId);
}

/** Publishes a draft event via `events-publish` (backend validates fields + start time). */
export async function publishAdminEvent(
  eventId: string,
  _token?: string,
): Promise<AdminEventResponse> {
  await callAdmin<EventIdResponse>('events-publish', { eventId });
  return loadAdminEvent(eventId);
}

/**
 * Cancels an event via `events-cancel` (mandatory reason). The event is
 * preserved — never hard-deleted.
 */
export async function cancelAdminEvent(
  eventId: string,
  request: CancelEventRequest,
  _token?: string,
): Promise<AdminEventResponse> {
  await callAdmin<EventIdResponse>('events-cancel', { eventId, reason: request.reason });
  return loadAdminEvent(eventId);
}
