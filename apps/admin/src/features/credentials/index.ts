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
const EXPIRING_SOON_MS = EXPIRING_SOON_DAYS * 24 * 60 * 60 * 1000;

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
export type CredentialStatus = 'expired' | 'expiring-soon' | 'ok' | 'no-expiry';

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
 * - `expired`     — `expiresAt` is strictly before `now`.
 * - `expiring-soon` — `expiresAt` is within EXPIRING_SOON_DAYS from `now`
 *   (inclusive of the exact 30-day boundary).
 * - `ok`          — `expiresAt` is more than EXPIRING_SOON_DAYS away.
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
  if (Number.isNaN(expiry.getTime())) return 'no-expiry';
  const diff = expiry.getTime() - now.getTime();
  if (diff < 0) return 'expired';
  if (diff <= EXPIRING_SOON_MS) return 'expiring-soon';
  return 'ok';
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

/**
 * Converts an optional `YYYY-MM-DD` (or any Date-parseable) string to a
 * Firestore Timestamp, or null. Throws ApiError(400) on an unparseable value
 * so the page can map it to an i18n message.
 */
function toTimestampOrNull(value: string | null, code: string): Timestamp | null {
  if (value == null || value.trim() === '') return null;
  const date = new Date(value);
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

/** Maps a managedCredentials/{id} document to the admin view model. */
function toAdminCredential(id: string, data: DocumentData): AdminManagedCredential {
  return {
    id,
    name: typeof data.name === 'string' ? data.name : '',
    description: typeof data.description === 'string' ? data.description : '',
    category: isCredentialCategory(data.category) ? data.category : 'other',
    expiresAt: toIso(data.expiresAt),
    lastRotatedAt: toIso(data.lastRotatedAt),
    notes: typeof data.notes === 'string' ? data.notes : '',
    createdAt: toIso(data.createdAt),
    updatedAt: toIso(data.updatedAt),
  };
}

/**
 * Sort comparator ordering credentials by soonest expiry first. Credentials
 * with no expiry (or an unparseable one) sort last, since they have no
 * renewal deadline to surface.
 */
function bySoonestExpiry(a: AdminManagedCredential, b: AdminManagedCredential): number {
  const at = a.expiresAt ? new Date(a.expiresAt).getTime() : null;
  const bt = b.expiresAt ? new Date(b.expiresAt).getTime() : null;
  const av = at != null && !Number.isNaN(at) ? at : null;
  const bv = bt != null && !Number.isNaN(bt) ? bt : null;
  if (av == null && bv == null) return a.name.localeCompare(b.name, 'sv');
  if (av == null) return 1;
  if (bv == null) return -1;
  return av - bv;
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
