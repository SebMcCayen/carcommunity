/**
 * Events feature module for the admin portal (Phase 13 vertical).
 *
 * Reads come straight from Firestore (admin rules-gated since Phase 9b):
 * the teaser-safe events/{eventId} document plus its member-gated
 * events/{eventId}/details/private subdocument (both admin-readable). All
 * lifecycle mutations exposed here go through the audited events.*
 * callables (create/update/publish/cancel); the events-complete callable
 * exists but has no admin-UI action, so it is intentionally not wrapped.
 * Exported signatures and the shared response envelope types are unchanged,
 * so pages keep working — this module is the adapter layer.
 *
 * Security notes (unchanged):
 *  - The backend is the sole authority for status transitions and audit
 *    records; clients never write event documents directly.
 *  - Client-side checks are for UX only, never a security boundary.
 */

import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit as fsLimit,
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

import { ApiError } from '../../lib/errors';
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
// Swedish status labels (unchanged pure helpers)
// ---------------------------------------------------------------------------

/**
 * Returns a human-readable Swedish label for an event status.
 */
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

/**
 * Returns a CSS class name fragment based on status for badge styling.
 */
export function eventStatusVariant(status: EventStatus): 'draft' | 'published' | 'cancelled' | 'completed' {
  return status;
}

/**
 * Returns true if the event is upcoming (starts in the future and is published).
 */
export function isUpcomingEvent(event: Pick<AdminEventSummary, 'status' | 'startsAt'>): boolean {
  return event.status === 'published' && new Date(event.startsAt) > new Date();
}

// ---------------------------------------------------------------------------
// Firestore document → contract mapping
// ---------------------------------------------------------------------------

const DEFAULT_PAGE_SIZE = 20;

/** Firestore Timestamp | Date | null → ISO string (or null). */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (value instanceof Date) return value.toISOString();
  const ts = value as Timestamp;
  if (typeof ts.toDate === 'function') return ts.toDate().toISOString();
  if (typeof value === 'string') return value;
  return null;
}

/** ISO string for a required timestamp field, falling back to epoch if absent. */
function toIsoRequired(value: unknown): string {
  return toIso(value) ?? new Date(0).toISOString();
}

function readRsvpCounts(data: DocumentData): EventRsvpSummary {
  const counts = (data.rsvpCounts ?? {}) as Partial<EventRsvpSummary>;
  return {
    going: counts.going ?? 0,
    maybe: counts.maybe ?? 0,
    not_going: counts.not_going ?? 0,
  };
}

/** Maps an events/{eventId} document to the admin summary contract. */
function toAdminEventSummary(id: string, data: DocumentData): AdminEventSummary {
  return {
    id,
    title: (data.title as string | undefined) ?? '',
    status: data.status as EventStatus,
    isOfficial: Boolean(data.isOfficial),
    startsAt: toIsoRequired(data.startsAt),
    endsAt: toIso(data.endsAt),
    approximateArea: (data.approximateArea as string | undefined) ?? '',
    rsvpCounts: readRsvpCounts(data),
    cancelledAt: toIso(data.cancelledAt),
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
  };
}

/**
 * Merges the teaser event document with its member-gated details/private
 * subdocument into the full admin detail contract.
 */
function toAdminEventDetail(
  id: string,
  data: DocumentData,
  privateData: DocumentData | undefined,
): AdminEventDetail {
  const priv = privateData ?? {};
  return {
    id,
    title: (data.title as string | undefined) ?? '',
    summary: (data.summary as string | null | undefined) ?? null,
    description: (priv.description as string | null | undefined) ?? null,
    status: data.status as EventStatus,
    startsAt: toIsoRequired(data.startsAt),
    endsAt: toIso(data.endsAt),
    approximateArea: (data.approximateArea as string | undefined) ?? '',
    locationName: (priv.locationName as string | null | undefined) ?? null,
    address: (priv.address as string | null | undefined) ?? null,
    latitude: (priv.latitude as number | null | undefined) ?? null,
    longitude: (priv.longitude as number | null | undefined) ?? null,
    isOfficial: Boolean(data.isOfficial),
    createdByUserId: (data.createdByUserId as string | null | undefined) ?? null,
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
    cancelledAt: toIso(data.cancelledAt),
    rsvpCounts: readRsvpCounts(data),
  };
}

// ---------------------------------------------------------------------------
// Read helpers (direct Firestore, admin rules-gated)
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
 * Loads the admin event list, newest start first. Firestore cursor
 * pagination replaces legacy page numbers; this adapter serves the first
 * page (deeper history lands with the full admin ledger view in the Phase
 * 13 checklist). Status/official/upcoming filters are applied over the
 * fetched page to avoid composite-index requirements.
 */
export async function loadAdminEvents(params: LoadAdminEventsParams = {}): Promise<AdminEventsResponse> {
  const pageSize = params.pageSize ?? DEFAULT_PAGE_SIZE;
  const snapshot = await getDocs(
    query(collection(getAdminFirestore(), 'events'), orderBy('startsAt', 'desc'), fsLimit(pageSize)),
  );

  const now = Date.now();
  let events = snapshot.docs.map((d) => toAdminEventSummary(d.id, d.data()));

  if (params.status !== undefined) {
    events = events.filter((e) => e.status === params.status);
  }
  if (params.isOfficial !== undefined) {
    events = events.filter((e) => e.isOfficial === params.isOfficial);
  }
  if (params.upcoming !== undefined) {
    events = events.filter((e) =>
      params.upcoming ? new Date(e.startsAt).getTime() > now : new Date(e.startsAt).getTime() <= now,
    );
  }

  return {
    ok: true,
    data: { events },
    meta: { total: events.length, page: 1, pageSize },
  };
}

/**
 * Loads a single event for admin detail/edit view — the teaser document
 * plus its member-gated details/private subdocument (both admin-readable).
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
    data: { event: toAdminEventDetail(eventSnap.id, eventSnap.data(), privateSnap.data()) },
  };
}

/**
 * Resolves an event creator's display name from the admin-safe `users/{uid}`
 * document (never `userPrivate/{uid}`). Best-effort: a missing document, an
 * empty/absent `displayName`, or a read failure all fall back to the uid so
 * the caller always has something to render. `displayName` on `users/{uid}`
 * is already admin-visible (surfaced by the users admin module).
 */
export async function resolveEventCreatorName(uid: string): Promise<string> {
  try {
    const snap = await getDoc(doc(getAdminFirestore(), 'users', uid));
    const stored = snap.data()?.displayName;
    return typeof stored === 'string' && stored.length > 0 ? stored : uid;
  } catch {
    // User doc not readable or missing — fall back to the raw uid.
    return uid;
  }
}

// ---------------------------------------------------------------------------
// Mutations (audited events.* callables) — re-read to return the fresh detail
// ---------------------------------------------------------------------------

interface EventIdResponse {
  eventId: string;
  status: EventStatus;
}

/**
 * Creates a new draft event via the audited events.create callable.
 * Status and creator are always set by the backend.
 */
export async function createAdminEvent(
  data: CreateEventRequest,
  _token?: string,
): Promise<AdminEventResponse> {
  const { eventId } = await callAdmin<EventIdResponse>('events-create', data);
  return loadAdminEvent(eventId);
}

/**
 * Updates an existing draft or published event via events.update.
 * Status changes must use publishAdminEvent / cancelAdminEvent.
 */
export async function updateAdminEvent(
  eventId: string,
  data: UpdateEventRequest,
  _token?: string,
): Promise<AdminEventResponse> {
  await callAdmin<EventIdResponse>('events-update', { eventId, ...data });
  return loadAdminEvent(eventId);
}

/**
 * Publishes a draft event via events.publish. The backend validates
 * required fields and that the start time is in the future.
 */
export async function publishAdminEvent(eventId: string, _token?: string): Promise<AdminEventResponse> {
  await callAdmin<EventIdResponse>('events-publish', { eventId });
  return loadAdminEvent(eventId);
}

/**
 * Cancels an event with a mandatory reason via events.cancel.
 * The event is preserved in the database — it is never hard-deleted.
 */
export async function cancelAdminEvent(
  eventId: string,
  request: CancelEventRequest,
  _token?: string,
): Promise<AdminEventResponse> {
  await callAdmin<EventIdResponse>('events-cancel', { eventId, reason: request.reason });
  return loadAdminEvent(eventId);
}
