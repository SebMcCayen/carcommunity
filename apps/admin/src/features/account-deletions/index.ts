/**
 * Admin account-deletions feature module (Phase 13o — Firebase migration).
 *
 * Backed by Firebase (no legacy REST route existed — this replaces the
 * placeholder):
 *  - Lists `accountDeletionRequests` directly (admin-readable per
 *    firestore.rules). Document ID == the requesting user's UID; documents
 *    are written by the account.deleteAccount callable (Phase 9p) with
 *    { userId, reason (nullable), status: 'pending', createdAt } and flipped
 *    to { status: 'processed', processedAt } by the scheduled
 *    account-purgeDeleted hard purge after the 30-day retention window.
 *  - Pending requests are listed OLDEST FIRST — queue semantics matching the
 *    purge sweep's own ordering. The `status ASC + createdAt ASC` composite
 *    index (firebase/firestore.indexes.json) serves the filtered queries.
 *  - markAccountDeletionProcessed applies a direct, shape-minimal status
 *    update — { status: 'processed', processedAt: serverTimestamp() },
 *    the exact fields the scheduled purge writes — inside a Firestore
 *    transaction, so the read + conditional write are atomic: if the
 *    scheduled purge (or another admin) processes the request between the
 *    read and the write, the transaction retries and lands in the graceful
 *    already-processed no-op instead of overwriting the purge's
 *    processedAt stamp. This is the one admin module that mutates
 *    Firestore directly: the rules explicitly grant admins `update` on
 *    this collection ("Admins may read all requests and update status
 *    (e.g. mark as processed)") with no field validation on the admin
 *    path, so no callable exists or is needed.
 *
 * Operational note: the scheduled purge only sweeps status == 'pending'
 * requests. Marking a request processed therefore REMOVES it from the
 * automatic purge queue — it is for requests an admin has handled manually
 * (or verified as purged); the page's confirm dialog spells this out.
 */

import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit as fsLimit,
  orderBy,
  query,
  runTransaction,
  serverTimestamp,
  where,
} from 'firebase/firestore';

import { ApiError } from '../../lib/errors';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

export { ApiError };

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

/** Mirrors DELETION_RETENTION_DAYS in functions/src/account/deletion-core.ts. */
export const DELETION_RETENTION_DAYS = 30;

const DAY_MS = 24 * 60 * 60 * 1000;

export const ACCOUNT_DELETION_STATUSES = ['pending', 'processed'] as const;
export type AccountDeletionStatus = (typeof ACCOUNT_DELETION_STATUSES)[number];

/** List filter — the two stored statuses plus the unfiltered view. */
export type AccountDeletionStatusFilter = AccountDeletionStatus | 'all';

export interface AdminAccountDeletionRequest {
  /**
   * The requesting user's Firebase UID — taken from the DOCUMENT ID, which
   * is authoritative (deleteAccount writes the request at
   * `accountDeletionRequests/{uid}`). Never sourced from the stored
   * `userId` field: markAccountDeletionProcessed operates on the doc id, so
   * a malformed/hand-edited doc whose `userId` field disagreed with its id
   * would otherwise mark-process (or display) the wrong account.
   */
  userId: string;
  /** Optional free-text reason (max 500 chars, backend-validated). */
  reason: string | null;
  status: AccountDeletionStatus;
  /** ISO string; null only for malformed/hand-edited documents. */
  createdAt: string | null;
  /** Stamped by the scheduled purge (or an admin mark-processed). */
  processedAt: string | null;
  /** createdAt + 30 days — when the scheduled purge becomes eligible. */
  purgeDueAt: string | null;
}

/** Page size for the deletion-requests list — never load all requests at once. */
const LIST_LIMIT = 50;

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design
 * (the subscription-module precedent) — accepts a Firestore Timestamp
 * (toDate()), a native Date, or an already-serialized date string, and
 * returns null only when the value is absent or unparseable.
 */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    const date = (value as { toDate: () => Date }).toDate();
    return date instanceof Date && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
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
 * Coerces a stored status to the known set. Unknown values fall back to
 * 'pending' — the fail-safe direction for a deletion queue: once fetched, an
 * unrecognized request displays as pending rather than as an empty/blank
 * status. Note this coercion is display-only: the pending/processed views
 * query Firestore with `where('status', '==', ...)` against the STORED value,
 * so a doc with an unknown status is never returned there — it surfaces only
 * under the unfiltered `all` view (which coerces it to 'pending' for display).
 */
function coerceStatus(raw: unknown): AccountDeletionStatus {
  return (ACCOUNT_DELETION_STATUSES as readonly string[]).includes(raw as string)
    ? (raw as AccountDeletionStatus)
    : 'pending';
}

/** createdAt + the 30-day retention window, as an ISO string. */
export function purgeDueAtIso(createdAtIso: string | null): string | null {
  if (!createdAtIso) return null;
  const created = new Date(createdAtIso);
  if (Number.isNaN(created.getTime())) return null;
  return new Date(created.getTime() + DELETION_RETENTION_DAYS * DAY_MS).toISOString();
}

/**
 * Whole days until the scheduled purge may pick the request up. 0 or
 * negative means the request is already past the retention window (the
 * next 03:30 sweep will purge it). Null when createdAt is missing.
 */
export function daysUntilPurge(createdAtIso: string | null, now: Date = new Date()): number | null {
  const dueIso = purgeDueAtIso(createdAtIso);
  if (!dueIso) return null;
  return Math.ceil((new Date(dueIso).getTime() - now.getTime()) / DAY_MS);
}

export function toAdminAccountDeletionRequest(
  id: string,
  data: Record<string, unknown>,
): AdminAccountDeletionRequest {
  const createdAt = toIso(data.createdAt);
  return {
    // The doc id is authoritative — the stored userId field is deliberately
    // not consulted (see AdminAccountDeletionRequest.userId).
    userId: id,
    reason: typeof data.reason === 'string' && data.reason.length > 0 ? data.reason : null,
    status: coerceStatus(data.status),
    createdAt,
    processedAt: toIso(data.processedAt),
    purgeDueAt: purgeDueAtIso(createdAt),
  };
}

// ---------------------------------------------------------------------------
// Reads (direct Firestore, admin rules-gated)
// ---------------------------------------------------------------------------

/**
 * Lists deletion requests, oldest first (queue semantics — the same order
 * the purge sweep drains them in). Status filters use the existing
 * `status ASC + createdAt ASC` composite index; 'all' is a plain
 * single-field createdAt ordering.
 */
export async function adminListAccountDeletionRequests(
  filter: AccountDeletionStatusFilter = 'pending',
  pageSize: number = LIST_LIMIT,
): Promise<AdminAccountDeletionRequest[]> {
  const requests = collection(getAdminFirestore(), 'accountDeletionRequests');
  const constraints =
    filter === 'all'
      ? [orderBy('createdAt', 'asc'), fsLimit(pageSize)]
      : [where('status', '==', filter), orderBy('createdAt', 'asc'), fsLimit(pageSize)];
  const snapshot = await getDocs(query(requests, ...constraints));
  return snapshot.docs.map((d) => toAdminAccountDeletionRequest(d.id, d.data()));
}

/** Reads a single request by the requesting user's UID; null when absent. */
export async function adminGetAccountDeletionRequest(
  uid: string,
): Promise<AdminAccountDeletionRequest | null> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'accountDeletionRequests', uid));
  if (!snapshot.exists()) return null;
  return toAdminAccountDeletionRequest(snapshot.id, snapshot.data() as Record<string, unknown>);
}

// ---------------------------------------------------------------------------
// Mark processed (direct rules-granted admin update)
// ---------------------------------------------------------------------------

export interface MarkProcessedResult {
  userId: string;
  status: 'processed';
  /** True when the request was already processed — no write was performed. */
  alreadyProcessed: boolean;
}

/**
 * Marks a request processed — a shape-minimal status update writing exactly
 * the fields the scheduled purge writes ({ status, processedAt }). The read
 * and the conditional write run in a single Firestore transaction, so the
 * already-processed no-op is atomic under contention: if the scheduled purge
 * (or another admin) flips the request between our read and write, the
 * transaction retries against the fresh snapshot and the purge's processedAt
 * stamp is never overwritten.
 *
 * NOTE: this removes the request from the scheduled purge's pending queue —
 * use it only for requests handled (or verified purged) manually.
 */
export async function markAccountDeletionProcessed(uid: string): Promise<MarkProcessedResult> {
  const db = getAdminFirestore();
  const ref = doc(db, 'accountDeletionRequests', uid);
  return runTransaction(db, async (transaction) => {
    const snapshot = await transaction.get(ref);
    if (!snapshot.exists()) {
      throw new ApiError(404, 'not-found', 'Deletion request not found.');
    }
    const current = toAdminAccountDeletionRequest(
      snapshot.id,
      snapshot.data() as Record<string, unknown>,
    );
    if (current.status === 'processed') {
      return { userId: uid, status: 'processed' as const, alreadyProcessed: true };
    }
    transaction.update(ref, { status: 'processed', processedAt: serverTimestamp() });
    return { userId: uid, status: 'processed' as const, alreadyProcessed: false };
  });
}

// ---------------------------------------------------------------------------
// Never-onboarded purge (one-off maintenance — admin.purgeNeverOnboarded)
// ---------------------------------------------------------------------------

/**
 * The sentinel a REAL purge must carry (mirrors PURGE_CONFIRM_TOKEN in
 * functions/src/admin/purgeNeverOnboarded-core.ts). The maintenance UI requires
 * the operator to type this before the delete button is enabled.
 */
export const NEVER_ONBOARDED_CONFIRM_TOKEN = 'PURGE';

/** One dry-run candidate — non-sensitive identifiers only (never name/email). */
export interface NeverOnboardedCandidate {
  uid: string;
  createdAt: string | null;
  hasUserPrivate: boolean;
}

export interface NeverOnboardedPreview {
  dryRun: true;
  candidateCount: number;
  candidates: NeverOnboardedCandidate[];
  excludedAdminOwnerCount: number;
  capped: boolean;
}

export interface NeverOnboardedPurgeResult {
  dryRun: false;
  purgedCount: number;
  purgedUids: string[];
  failures: { uid: string; error: string }[];
  excludedAdminOwnerCount: number;
  capped: boolean;
}

/**
 * PREVIEW (dryRun) the never-onboarded cleanup — deletes NOTHING, returns the
 * candidate count and per-candidate non-sensitive identifiers, plus the number
 * of admin/owner accounts excluded (so the operator can confirm their own
 * account was protected).
 */
export function previewNeverOnboardedPurge(): Promise<NeverOnboardedPreview> {
  return callAdmin<NeverOnboardedPreview>('admin-purgeNeverOnboarded', { dryRun: true });
}

/**
 * RUN the real never-onboarded purge. Requires the confirm sentinel — the
 * backend refuses (failed-precondition) if it does not match. Deletes each
 * selected account via the existing account-deletion cascade and writes an
 * adminAuditEvents record.
 */
export function runNeverOnboardedPurge(confirmToken: string): Promise<NeverOnboardedPurgeResult> {
  return callAdmin<NeverOnboardedPurgeResult>('admin-purgeNeverOnboarded', {
    dryRun: false,
    confirmToken,
  });
}
