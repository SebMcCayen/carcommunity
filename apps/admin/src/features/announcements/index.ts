/**
 * Admin announcements feature module (Phase 13p — Firebase migration).
 *
 * Backed by direct Firestore access per firestore.rules
 * (`announcements/{id}`: read = isAuthenticated(), write = isAdmin()).
 * This is an intentional, rules-sanctioned exception to the usual admin
 * pattern (lib/firestore.ts: reads direct, mutations via callables): the
 * rules grant admins full direct write on this collection and no
 * field-validating callable exists for it, so mutations here are direct
 * SDK writes rather than callables.
 *
 * Document shape mirrors docs/firebase-data-model.md ("announcements —
 * community announcements"):
 *   { title: string, body: string, active: boolean,
 *     createdAt: Timestamp (server), updatedAt: Timestamp (server) }
 *
 * The member-facing query is `active == true` ordered by `createdAt desc`
 * (composite index `active ASC + createdAt DESC` in firestore.indexes.json),
 * so the field names `active` and `createdAt` are load-bearing — Android will
 * read this collection with exactly that shape. Do not rename them.
 *
 * Security/validation notes:
 *  - firestore.rules has NO field validation for this collection, so this
 *    module is the shape gatekeeper: every write validates and normalizes
 *    the document before it leaves the client.
 *  - "Retract" is a deactivate (active=false) — the primary way to pull an
 *    announcement. Hard delete exists but the page gates it behind an
 *    explicit confirm.
 */

import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDocs,
  limit as fsLimit,
  orderBy,
  query,
  serverTimestamp,
  updateDoc,
  type DocumentData,
} from 'firebase/firestore';

import { ApiError } from '../../lib/errors';
import { getAdminFirestore } from '../../lib/firestore';

export { ApiError };

export const ANNOUNCEMENT_TITLE_MAX_LENGTH = 120;
export const ANNOUNCEMENT_BODY_MAX_LENGTH = 2000;

const COLLECTION = 'announcements';

/**
 * Admin list cap (first page only), matching the flat-list convention in
 * features/users (LIST_LIMIT = 50). Announcements are low-volume and
 * newest-first, so the newest 50 covers the operational view.
 */
const LIST_LIMIT = 50;

export interface AdminAnnouncement {
  id: string;
  title: string;
  body: string;
  active: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface AnnouncementInput {
  title: string;
  body: string;
  active: boolean;
}

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design —
 * old/partial/hand-edited docs may hold a Firestore Timestamp (toDate()), a
 * native Date, or an already-serialized date string; returns null only when
 * the value is absent or unparseable.
 */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    // A hand-edited doc can make toDate() throw or return a non-Date/invalid
    // Date — guard it so a single bad field can't break the whole listing.
    try {
      const date = (value as { toDate: () => Date }).toDate();
      return date instanceof Date && !Number.isNaN(date.getTime())
        ? date.toISOString()
        : null;
    } catch {
      return null;
    }
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value.toISOString();
  }
  if (typeof value === 'string') {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
  }
  return null;
}

/**
 * Validates and normalizes announcement content. Rules perform no field
 * validation on this collection, so this is the single write-side gate:
 * title/body must be non-empty after trimming and within the length caps.
 * Throws ApiError(400) with a stable code so the page can map it to i18n.
 */
export function validateAnnouncementContent(
  title: string,
  body: string,
): { title: string; body: string } {
  const trimmedTitle = title.trim();
  const trimmedBody = body.trim();
  if (!trimmedTitle) {
    throw new ApiError(400, 'announcement/title-required', 'Announcement title is required.');
  }
  if (trimmedTitle.length > ANNOUNCEMENT_TITLE_MAX_LENGTH) {
    throw new ApiError(
      400,
      'announcement/title-too-long',
      `Announcement title must be at most ${ANNOUNCEMENT_TITLE_MAX_LENGTH} characters.`,
    );
  }
  if (!trimmedBody) {
    throw new ApiError(400, 'announcement/body-required', 'Announcement body is required.');
  }
  if (trimmedBody.length > ANNOUNCEMENT_BODY_MAX_LENGTH) {
    throw new ApiError(
      400,
      'announcement/body-too-long',
      `Announcement body must be at most ${ANNOUNCEMENT_BODY_MAX_LENGTH} characters.`,
    );
  }
  return { title: trimmedTitle, body: trimmedBody };
}

/** Maps an announcements/{id} document to the admin view model. */
function toAdminAnnouncement(id: string, data: DocumentData): AdminAnnouncement {
  return {
    id,
    title: typeof data.title === 'string' ? data.title : '',
    body: typeof data.body === 'string' ? data.body : '',
    active: data.active === true,
    createdAt: toIso(data.createdAt),
    updatedAt: toIso(data.updatedAt),
  };
}

/**
 * Lists announcements (active and inactive) newest-first, capped at the
 * newest LIST_LIMIT documents per the admin-list convention (first page
 * only). Admin sees active and inactive alike; the member app applies the
 * `active == true` filter (the composite index exists for that query, not
 * this one).
 */
export async function adminListAnnouncements(): Promise<AdminAnnouncement[]> {
  const db = getAdminFirestore();
  const snapshot = await getDocs(
    query(collection(db, COLLECTION), orderBy('createdAt', 'desc'), fsLimit(LIST_LIMIT)),
  );
  return snapshot.docs.map((d) => toAdminAnnouncement(d.id, d.data()));
}

/**
 * Creates an announcement with validated content, the given active flag, and
 * server timestamps for createdAt/updatedAt. Returns the new document id.
 */
export async function adminCreateAnnouncement(input: AnnouncementInput): Promise<string> {
  const { title, body } = validateAnnouncementContent(input.title, input.body);
  const db = getAdminFirestore();
  const ref = await addDoc(collection(db, COLLECTION), {
    title,
    body,
    active: input.active === true,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  return ref.id;
}

/**
 * Updates an announcement's title/body/active with validated content and a
 * fresh server updatedAt. createdAt is never touched (it anchors the
 * member-facing ordering).
 */
export async function adminUpdateAnnouncement(
  announcementId: string,
  input: AnnouncementInput,
): Promise<void> {
  const { title, body } = validateAnnouncementContent(input.title, input.body);
  const db = getAdminFirestore();
  await updateDoc(doc(db, COLLECTION, announcementId), {
    title,
    body,
    active: input.active === true,
    updatedAt: serverTimestamp(),
  });
}

/**
 * Activates or retracts (deactivates) an announcement. Deactivation is the
 * primary "retract" action — the document stays for the audit trail and can
 * be re-activated later.
 */
export async function adminSetAnnouncementActive(
  announcementId: string,
  active: boolean,
): Promise<void> {
  const db = getAdminFirestore();
  await updateDoc(doc(db, COLLECTION, announcementId), {
    // Strict boolean coercion, consistent with the create/update paths —
    // the stored `active` field must always be a real boolean.
    active: active === true,
    updatedAt: serverTimestamp(),
  });
}

/**
 * Permanently deletes an announcement. Prefer adminSetAnnouncementActive
 * (retract) — this is irreversible and the page must gate it behind an
 * explicit confirm.
 */
export async function adminDeleteAnnouncement(announcementId: string): Promise<void> {
  const db = getAdminFirestore();
  await deleteDoc(doc(db, COLLECTION, announcementId));
}
