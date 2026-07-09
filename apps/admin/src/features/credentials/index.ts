/**
 * Admin managed-credentials feature module (Token / credential renewal tracker).
 *
 * Backed by direct Firestore access per firestore.rules
 * (`managedCredentials/{id}`: read = isAdmin(), write = isAdmin()). This mirrors
 * the announcements module's rules-sanctioned exception to the usual
 * reads-direct / mutations-via-callable pattern: no field-validating callable
 * exists for this collection, so mutations here are direct SDK writes — and,
 * as with announcements, this module is therefore the single write-side shape
 * gatekeeper (the rules do no field validation).
 *
 * Unlike announcements this collection is admin-only on BOTH read and write
 * (internal operational data — tracked secrets/tokens — that members must
 * never see).
 *
 * Document shape (Firestore collection `managedCredentials`):
 *   { name: string, description: string, category: CredentialCategory,
 *     expiresAt: Timestamp | null, lastRotatedAt: Timestamp | null,
 *     notes: string, createdAt: Timestamp (server), updatedAt: Timestamp (server) }
 */

import {
  Timestamp,
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

import { ApiError } from '../../lib/api';
import { getAdminFirestore } from '../../lib/firestore';

export { ApiError };

export const CREDENTIAL_NAME_MAX_LENGTH = 120;
export const CREDENTIAL_DESCRIPTION_MAX_LENGTH = 500;
export const CREDENTIAL_NOTES_MAX_LENGTH = 2000;

/** Window (in days) before expiry within which a credential is "expiring soon". */
export const EXPIRING_SOON_DAYS = 30;

const COLLECTION = 'managedCredentials';

/**
 * Admin list cap (first page only), matching the flat-list convention in
 * features/users and features/announcements (LIST_LIMIT = 50). Managed
 * credentials are very low-volume, so the newest 50 covers the whole view.
 */
const LIST_LIMIT = 50;

/** Credential categories (closed enum — the write path rejects anything else). */
export const CREDENTIAL_CATEGORIES = [
  'github-pat',
  'mapbox-token',
  'signing-keystore',
  'api-key',
  'service-account',
  'other',
] as const;

export type CredentialCategory = (typeof CREDENTIAL_CATEGORIES)[number];

/** Renewal status derived from `expiresAt` relative to "now". */
export type CredentialStatus = 'expired' | 'expiring-soon' | 'ok' | 'no-expiry' | 'invalid';

/**
 * View-model marker for a stored `expiresAt` that is *present but unparseable*
 * (corrupt / hand-edited data). Kept distinct from `null` (no expiry set) so
 * the tracker surfaces bad data as a separate `'invalid'` status instead of
 * silently rendering it as an intentional "no expiry". It is an unparseable
 * string, so all the string-tolerant consumers (computeCredentialStatus,
 * formatDateOnly, isoToDateInput) degrade gracefully.
 */
export const INVALID_EXPIRY = '__invalid__' as const;

export interface AdminManagedCredential {
  id: string;
  name: string;
  description: string;
  category: CredentialCategory;
  expiresAt: string | null;
  lastRotatedAt: string | null;
  notes: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ManagedCredentialInput {
  name: string;
  description: string;
  category: CredentialCategory;
  /** `YYYY-MM-DD` date string, or null for no expiry. */
  expiresAt: string | null;
  /** `YYYY-MM-DD` date string, or null if never/unknown. */
  lastRotatedAt: string | null;
  notes: string;
}

/**
 * Derives the renewal status of a credential from its expiry.
 *
 * - `no-expiry`   — `expiresAt` is null (a token/secret with no rotation deadline).
 * - `invalid`     — `expiresAt` is a present-but-unparseable value (corrupt
 *   data). Surfaced explicitly rather than mapped to `no-expiry`, so bad data
 *   is visible to an operator instead of masquerading as an intentional
 *   "no expiry".
 * - `expired`     — `expiresAt` is strictly before `now` (instant comparison).
 * - `expiring-soon` — `expiresAt`'s local calendar day is within
 *   EXPIRING_SOON_DAYS *calendar days* of `now`'s local calendar day
 *   (inclusive of the exact 30-day boundary).
 * - `ok`          — `expiresAt` is more than EXPIRING_SOON_DAYS calendar days away.
 *
 * Since expiries are captured date-only (local midnight) and the tracker cares
 * about days — not time-of-day — "expiring soon" is classified by whole
 * calendar-day difference (local date parts) rather than a millisecond window.
 * This keeps the result DST-safe and independent of the current hour-of-day.
 * "expired" stays a strict instant check: a credential whose local-midnight
 * instant is before now is expired.
 *
 * Pure and side-effect free — `now` is injected so it is deterministically
 * testable. Accepts an ISO string (as stored on the view model) or null.
 */
export function computeCredentialStatus(
  expiresAt: string | null,
  now: Date = new Date(),
): CredentialStatus {
  if (expiresAt == null) return 'no-expiry';
  const expiry = new Date(expiresAt);
  if (Number.isNaN(expiry.getTime())) return 'invalid';
  if (expiry.getTime() < now.getTime()) return 'expired';
  if (daysBetweenLocalDates(now, expiry) <= EXPIRING_SOON_DAYS) return 'expiring-soon';
  return 'ok';
}

/**
 * Whole calendar-day difference between two instants, computed from their LOCAL
 * date parts (Y/M/D). Both instants are anchored to local midnight before
 * diffing, so the result is the number of calendar days between the two days
 * regardless of time-of-day. Rounding (Math.round) absorbs the ±1h wobble a DST
 * transition introduces between the two midnights, keeping the count exact.
 */
function daysBetweenLocalDates(a: Date, b: Date): number {
  const midnightA = new Date(a.getFullYear(), a.getMonth(), a.getDate()).getTime();
  const midnightB = new Date(b.getFullYear(), b.getMonth(), b.getDate()).getTime();
  return Math.round((midnightB - midnightA) / (24 * 60 * 60 * 1000));
}

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design
 * (mirrors features/announcements): a hand-edited doc may hold a Firestore
 * Timestamp (toDate()), a native Date, or an already-serialized string.
 * Returns null when absent or unparseable.
 */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    try {
      const date = (value as { toDate: () => Date }).toDate();
      return date instanceof Date && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
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

/** Type guard for the closed category enum. */
export function isCredentialCategory(value: unknown): value is CredentialCategory {
  return (
    typeof value === 'string' && (CREDENTIAL_CATEGORIES as readonly string[]).includes(value)
  );
}

/** Matches a date-only `YYYY-MM-DD` string (as emitted by `<input type="date">`). */
const DATE_ONLY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Parses a form date value into a Date. A bare date-only string (`YYYY-MM-DD`,
 * which is what `<input type="date">` produces) is interpreted at LOCAL
 * midnight — not UTC midnight, which is what `new Date("YYYY-MM-DD")` would do
 * per the ECMAScript spec. Parsing at local midnight keeps the stored day equal
 * to the operator's chosen calendar day regardless of timezone/DST (a UTC parse
 * renders/thresholds as the previous day for operators east of UTC). Any other
 * (date-time) string is delegated to the native parser. Returns an invalid Date
 * (NaN) on failure, including impossible date-only values (e.g. `2026-02-31`).
 */
function parseFormDate(value: string): Date {
  const match = DATE_ONLY_RE.exec(value);
  if (match) {
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    const date = new Date(year, month - 1, day);
    // Reject calendar rollover (e.g. 2026-02-31 → 2026-03-03) so impossible
    // date-only values still fail, matching the native parser's rejection.
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
      return new Date(NaN);
    }
    return date;
  }
  return new Date(value);
}

/**
 * Converts an optional `YYYY-MM-DD` (or any Date-parseable) string to a
 * Firestore Timestamp, or null. Date-only strings are stored at local midnight
 * (see parseFormDate). Throws ApiError(400) on an unparseable value so the page
 * can map it to an i18n message.
 */
function toTimestampOrNull(value: string | null, code: string): Timestamp | null {
  if (value == null || value.trim() === '') return null;
  const date = parseFormDate(value.trim());
  if (Number.isNaN(date.getTime())) {
    throw new ApiError(400, code, 'Invalid date.');
  }
  return Timestamp.fromDate(date);
}

/**
 * Validates and normalizes credential fields. The rules do no field
 * validation on this collection, so this is the single write-side gate:
 * name is required and length-capped, category must be a known enum,
 * description/notes are optional but length-capped, and the date fields
 * (if present) must parse. Returns the Firestore-ready payload fragment.
 */
export function validateCredentialInput(input: ManagedCredentialInput): {
  name: string;
  description: string;
  category: CredentialCategory;
  expiresAt: Timestamp | null;
  lastRotatedAt: Timestamp | null;
  notes: string;
} {
  const name = input.name.trim();
  if (!name) {
    throw new ApiError(400, 'credential/name-required', 'Credential name is required.');
  }
  if (name.length > CREDENTIAL_NAME_MAX_LENGTH) {
    throw new ApiError(
      400,
      'credential/name-too-long',
      `Credential name must be at most ${CREDENTIAL_NAME_MAX_LENGTH} characters.`,
    );
  }
  if (!isCredentialCategory(input.category)) {
    throw new ApiError(400, 'credential/category-invalid', 'Unknown credential category.');
  }
  const description = input.description.trim();
  if (description.length > CREDENTIAL_DESCRIPTION_MAX_LENGTH) {
    throw new ApiError(
      400,
      'credential/description-too-long',
      `Description must be at most ${CREDENTIAL_DESCRIPTION_MAX_LENGTH} characters.`,
    );
  }
  const notes = input.notes.trim();
  if (notes.length > CREDENTIAL_NOTES_MAX_LENGTH) {
    throw new ApiError(
      400,
      'credential/notes-too-long',
      `Notes must be at most ${CREDENTIAL_NOTES_MAX_LENGTH} characters.`,
    );
  }
  const expiresAt = toTimestampOrNull(input.expiresAt, 'credential/expires-invalid');
  const lastRotatedAt = toTimestampOrNull(input.lastRotatedAt, 'credential/rotated-invalid');
  return { name, description, category: input.category, expiresAt, lastRotatedAt, notes };
}

/**
 * Normalizes the stored `expiresAt` field for the view model. Unlike toIso(),
 * this preserves the distinction between "no expiry set" (field absent →
 * null) and "expiry present but corrupt/unparseable" (→ INVALID_EXPIRY), so
 * the tracker can flag bad data as 'invalid' instead of silently collapsing it
 * to 'no-expiry'.
 */
function toExpiryView(value: unknown): string | null {
  if (value == null) return null;
  return toIso(value) ?? INVALID_EXPIRY;
}

/** Maps a managedCredentials/{id} document to the admin view model. */
function toAdminCredential(id: string, data: DocumentData): AdminManagedCredential {
  return {
    id,
    name: typeof data.name === 'string' ? data.name : '',
    description: typeof data.description === 'string' ? data.description : '',
    category: isCredentialCategory(data.category) ? data.category : 'other',
    expiresAt: toExpiryView(data.expiresAt),
    lastRotatedAt: toIso(data.lastRotatedAt),
    notes: typeof data.notes === 'string' ? data.notes : '',
    createdAt: toIso(data.createdAt),
    updatedAt: toIso(data.updatedAt),
  };
}

/**
 * Sort comparator surfacing the credentials that need attention first:
 * credentials with a corrupt/unparseable expiry (group 0) sort first — bad
 * data must not hide — then dated credentials by soonest expiry (group 1),
 * then credentials with no expiry (group 2) last, since they have no renewal
 * deadline to surface. Ties within a group fall back to name order.
 */
function expirySortKey(item: AdminManagedCredential): { group: 0 | 1 | 2; time: number } {
  if (item.expiresAt == null) return { group: 2, time: 0 };
  const time = new Date(item.expiresAt).getTime();
  if (Number.isNaN(time)) return { group: 0, time: 0 };
  return { group: 1, time };
}

function bySoonestExpiry(a: AdminManagedCredential, b: AdminManagedCredential): number {
  const ka = expirySortKey(a);
  const kb = expirySortKey(b);
  if (ka.group !== kb.group) return ka.group - kb.group;
  if (ka.group === 1) return ka.time - kb.time;
  return a.name.localeCompare(b.name, 'sv');
}

/**
 * Lists managed credentials, ordered soonest-expiry-first, capped at the
 * newest LIST_LIMIT documents. Admin-only read per firestore.rules.
 *
 * The Firestore query orders by createdAt (a field present on every doc) to
 * apply the LIST_LIMIT cap deterministically; the operational
 * soonest-expiry-first ordering is applied client-side (nulls sort last),
 * which needs no composite index.
 */
export async function adminListManagedCredentials(): Promise<AdminManagedCredential[]> {
  const db = getAdminFirestore();
  const snapshot = await getDocs(
    query(collection(db, COLLECTION), orderBy('createdAt', 'desc'), fsLimit(LIST_LIMIT)),
  );
  return snapshot.docs.map((d) => toAdminCredential(d.id, d.data())).sort(bySoonestExpiry);
}

/**
 * Creates a managed credential with validated fields and server timestamps
 * for createdAt/updatedAt. Returns the new document id.
 */
export async function adminCreateManagedCredential(
  input: ManagedCredentialInput,
): Promise<string> {
  const fields = validateCredentialInput(input);
  const db = getAdminFirestore();
  const ref = await addDoc(collection(db, COLLECTION), {
    ...fields,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  return ref.id;
}

/**
 * Updates a managed credential's fields with validated content and a fresh
 * server updatedAt. createdAt is never touched.
 */
export async function adminUpdateManagedCredential(
  credentialId: string,
  input: ManagedCredentialInput,
): Promise<void> {
  const fields = validateCredentialInput(input);
  const db = getAdminFirestore();
  await updateDoc(doc(db, COLLECTION, credentialId), {
    ...fields,
    updatedAt: serverTimestamp(),
  });
}

/** Permanently deletes a managed credential. The page gates this behind a confirm. */
export async function adminDeleteManagedCredential(credentialId: string): Promise<void> {
  const db = getAdminFirestore();
  await deleteDoc(doc(db, COLLECTION, credentialId));
}
