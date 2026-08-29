/**
 * Admin moderation-reports feature module (Phase 13q — Firebase migration).
 *
 * Backed by direct Firestore access per firestore.rules
 * (`moderationReports/{reportId}`: read = isAdmin(), and an admin `update`
 * restricted to the single `status` field moving through the review
 * vocabulary `['pending','reviewed','dismissed']`). This mirrors the
 * account-deletions module's rules-sanctioned direct-update approach: the
 * rules explicitly grant admins the mutation, so no callable exists or is
 * needed. NO deletes, and NO field other than `status` may ever be written —
 * `reportedBy`, `targetType`, `targetId`, `reason`, `details`, `createdAt`
 * are immutable (the rules enforce `affectedKeys().hasOnly(['status'])`).
 *
 * Document shape (Phase 9o — client create is field-validated by the rules):
 *   { reportedBy: uid, targetType: 'user'|'message'|'event',
 *     targetId: string (1..300), reason: string (1..100),
 *     details?: string (<=2000), status: 'pending' (initial),
 *     createdAt: Timestamp (server) }
 *
 * Ordering:
 *  - The only composite index for this collection is
 *    `status ASC + createdAt DESC` (firebase/firestore.indexes.json), so the
 *    per-status queues are served NEWEST-FIRST. An oldest-first queue drain
 *    would be a defensible alternative default, but it would need a second
 *    index (status ASC + createdAt ASC); we deliberately reuse the existing
 *    index and surface newest reports first for every status.
 *  - The unfiltered `all` view orders by `createdAt desc` (single-field
 *    index), also newest-first, for a consistent presentation.
 *  - Load-more uses an opaque Firestore snapshot cursor (`startAfter`).
 *
 * Security notes:
 *  - Reads and the status update are both rules-gated to admins.
 *  - resolveModerationReport writes exactly `{ status }` — the one field the
 *    rules permit — and only to a value in the review vocabulary.
 *  - The resolve write is NOT audited: unlike the callable-backed admin
 *    actions, a direct Firestore update writes no adminAuditEvents record.
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
  startAfter,
  where,
  type DocumentData,
  type QueryConstraint,
  type QueryDocumentSnapshot,
} from 'firebase/firestore';

import { ApiError } from '../../lib/errors';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

export { ApiError };

const COLLECTION = 'moderationReports';

/**
 * Page size for the moderation queue. Cursor-paginated (load-more), so this
 * caps each request rather than the whole queue — matching the audit-log
 * cursor-list convention (AUDIT_LOG_PAGE_SIZE = 25) rather than the flat
 * LIST_LIMIT = 50 used by the non-paginated admin lists.
 */
export const MODERATION_REPORTS_PAGE_SIZE = 25;

/** The status vocabulary the rules permit (`status in [...]`). */
export const MODERATION_REPORT_STATUSES = ['pending', 'reviewed', 'dismissed'] as const;
export type ModerationReportStatus = (typeof MODERATION_REPORT_STATUSES)[number];

/** List filter — the three stored statuses plus the unfiltered view. */
export type ModerationReportStatusFilter = ModerationReportStatus | 'all';

/** The target kinds a report may point at (validModerationReport rules). */
export const MODERATION_REPORT_TARGET_TYPES = ['user', 'message', 'event'] as const;
export type ModerationReportTargetType = (typeof MODERATION_REPORT_TARGET_TYPES)[number];

/**
 * Statuses resolveModerationReport is allowed to write. Includes 'pending' so
 * an admin can reopen an already-actioned report; the rules allow all three.
 */
export const RESOLVABLE_STATUSES = ['reviewed', 'dismissed', 'pending'] as const;
export type ResolvableModerationReportStatus = (typeof RESOLVABLE_STATUSES)[number];

export interface AdminModerationReport {
  /**
   * The report's document ID — authoritative and canonical. resolve acts on
   * this id, so display and action always agree on the same report even for a
   * malformed/hand-edited document.
   */
  id: string;
  /** Reporting user's UID (stored `reportedBy` field). */
  reportedBy: string;
  /** What the report targets: user | message | event (raw value preserved). */
  targetType: string;
  /** Opaque id of the reported thing (stored `targetId` field). */
  targetId: string;
  /** Short reason code/text (stored `reason` field, <=100 chars). */
  reason: string;
  /** Optional free-text detail (<=2000 chars); null when absent/blank. */
  details: string | null;
  /** Review status; unknown stored values coerce to 'pending' for display. */
  status: ModerationReportStatus;
  /** ISO string; null only for malformed/hand-edited documents. */
  createdAt: string | null;
  /**
   * Which chat the reported message lives on ('community' | 'convoy' | 'dm'),
   * or null for a legacy/person report. Added by the callable-backed report
   * path (moderation-core buildMessageReportDocument). The community-message
   * DELETE action is offered only when this is 'community'.
   */
  surface: string | null;
  /**
   * The reported message, snapshotted at report time (the evidence a moderator
   * judges — channel messages are TTL-deleted, so the report carries its own
   * copy). null for a person report or a legacy document with no snapshot.
   */
  snapshotText: string | null;
  snapshotAuthorUserId: string | null;
  snapshotAuthorDisplayName: string | null;
}

/**
 * The deployed callable name for the admin community-message delete
 * (grouped export `domain-action`, contracts/functions/functions.json).
 */
export const ADMIN_DELETE_COMMUNITY_MESSAGE_CALLABLE = 'chatchannels-adminDeleteMessage';

/** Opaque load-more cursor (a Firestore document snapshot). */
export type ModerationReportCursor = QueryDocumentSnapshot;

export interface ModerationReportsPage {
  reports: AdminModerationReport[];
  /** Pass back to list the next page; null = no more rows. */
  cursor: ModerationReportCursor | null;
}

export interface ListModerationReportsOptions {
  filter?: ModerationReportStatusFilter;
  /** Cursor from a previous page. Must come from a same-filter query. */
  cursor?: ModerationReportCursor | null;
  pageSize?: number;
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design
 * (the PR #288 precedent) — accepts a Firestore Timestamp (toDate()), a native
 * Date, or an already-serialized date string, and returns null only when the
 * value is absent or unparseable. The toDate() branch is guarded so a
 * malformed Timestamp-like value whose toDate() throws or yields an invalid
 * Date degrades to null instead of breaking the whole page's mapping.
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

/** Coerces an expected-string field, tolerating missing/corrupt values. */
function toStringField(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/**
 * Coerces a stored status to the known set. Unknown values fall back to
 * 'pending' — the fail-safe direction for a moderation queue: an
 * unrecognized report surfaces for attention rather than disappearing. This
 * coercion is display-only; the per-status queries filter on the STORED value,
 * so an unknown-status doc only ever appears under the unfiltered `all` view.
 */
function coerceStatus(raw: unknown): ModerationReportStatus {
  return (MODERATION_REPORT_STATUSES as readonly string[]).includes(raw as string)
    ? (raw as ModerationReportStatus)
    : 'pending';
}

/** Coerces an optional string field to a non-empty string, else null. */
function toNullableString(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

export function toAdminModerationReport(id: string, data: DocumentData): AdminModerationReport {
  const details = toStringField(data.details);
  // The message snapshot is a nested object on message reports; tolerate its
  // absence (person reports / legacy docs) and any corrupt shape.
  const snapshot =
    data.snapshot != null && typeof data.snapshot === 'object'
      ? (data.snapshot as Record<string, unknown>)
      : {};
  return {
    // The doc id is authoritative (see AdminModerationReport.id).
    id,
    reportedBy: toStringField(data.reportedBy),
    targetType: toStringField(data.targetType),
    targetId: toStringField(data.targetId),
    reason: toStringField(data.reason),
    details: details.length > 0 ? details : null,
    status: coerceStatus(data.status),
    createdAt: toIso(data.createdAt),
    surface: toNullableString(data.surface),
    snapshotText: toNullableString(snapshot.text),
    snapshotAuthorUserId: toNullableString(snapshot.authorUserId),
    snapshotAuthorDisplayName: toNullableString(snapshot.authorDisplayName),
  };
}

/**
 * True when a report targets a GLOBAL community-chat message — the only reports
 * the admin can act on with the delete-message action below. A convoy/DM message
 * report or a person report is not deletable from here (convoy/DM messages are
 * not admin-reachable, and a person report has no single message to remove).
 *
 * Also requires a non-empty `targetId`: toAdminModerationReport coerces a
 * missing/corrupt targetId to '', and offering a Delete button for such a
 * malformed row would only ever produce an invalid-argument round-trip. Gating
 * here keeps the button off rows the callable could never act on.
 */
export function isCommunityMessageReport(report: AdminModerationReport): boolean {
  return (
    report.targetType === 'message' &&
    report.surface === 'community' &&
    report.targetId.trim() !== ''
  );
}

// ---------------------------------------------------------------------------
// Reads (direct Firestore, admin rules-gated)
// ---------------------------------------------------------------------------

/**
 * Lists moderation reports newest-first, optionally filtered to one status,
 * with cursor-based load-more. Status filters use the existing
 * `status ASC + createdAt DESC` composite index; 'all' is a single-field
 * `createdAt desc` ordering. A full page returns the last snapshot as the
 * next-page cursor; a short page returns null.
 */
export async function adminListModerationReports(
  options: ListModerationReportsOptions = {},
): Promise<ModerationReportsPage> {
  const filter = options.filter ?? 'pending';
  const pageSize = options.pageSize ?? MODERATION_REPORTS_PAGE_SIZE;

  const constraints: QueryConstraint[] = [];
  if (filter !== 'all') {
    constraints.push(where('status', '==', filter));
  }
  constraints.push(orderBy('createdAt', 'desc'));
  if (options.cursor) {
    constraints.push(startAfter(options.cursor));
  }
  constraints.push(fsLimit(pageSize));

  const snapshot = await getDocs(
    query(collection(getAdminFirestore(), COLLECTION), ...constraints),
  );

  const reports = snapshot.docs.map((d) => toAdminModerationReport(d.id, d.data()));
  const cursor =
    (snapshot.docs.length === pageSize ? snapshot.docs[snapshot.docs.length - 1] : null) ?? null;
  return { reports, cursor };
}

/** Reads a single report by its document id; null when absent. */
export async function adminGetModerationReport(
  reportId: string,
): Promise<AdminModerationReport | null> {
  const snapshot = await getDoc(doc(getAdminFirestore(), COLLECTION, reportId));
  if (!snapshot.exists()) return null;
  return toAdminModerationReport(snapshot.id, snapshot.data());
}

// ---------------------------------------------------------------------------
// Resolve (direct rules-granted admin update — status ONLY)
// ---------------------------------------------------------------------------

export interface ResolveModerationReportResult {
  id: string;
  status: ResolvableModerationReportStatus;
  /** True when the report was already at the target status — no write done. */
  alreadyResolved: boolean;
}

/**
 * Moves a report's `status` — the ONLY field the rules let an admin write — to
 * a value in the review vocabulary ('reviewed' | 'dismissed', or 'pending' to
 * reopen). A shape-minimal `{ status }` write; the read + conditional write
 * run in one Firestore transaction, so the already-at-status no-op is atomic
 * under contention (a concurrent admin flipping the report between our read and
 * write makes the transaction retry against the fresh snapshot).
 *
 * NOTE: this write is NOT audited — a direct update produces no
 * adminAuditEvents record (a possible follow-up if audit parity is wanted).
 */
export async function resolveModerationReport(
  reportId: string,
  status: ResolvableModerationReportStatus,
): Promise<ResolveModerationReportResult> {
  if (!(RESOLVABLE_STATUSES as readonly string[]).includes(status)) {
    throw new ApiError(
      400,
      'moderation-report/invalid-status',
      `Cannot set moderation report status to "${status}".`,
    );
  }
  const db = getAdminFirestore();
  const ref = doc(db, COLLECTION, reportId);
  return runTransaction(db, async (transaction) => {
    const snapshot = await transaction.get(ref);
    if (!snapshot.exists()) {
      throw new ApiError(404, 'not-found', 'Moderation report not found.');
    }
    // Compare the RAW stored status, not the coerced one: coerceStatus() maps
    // an unknown/corrupt stored value to 'pending', which would make a reset
    // to 'pending' look like an already-resolved no-op and leave the bad value
    // in place forever. Using the raw value lets an admin always write a
    // malformed report back into the allowed vocabulary.
    const current = (snapshot.data() as DocumentData).status;
    if (current === status) {
      return { id: reportId, status, alreadyResolved: true };
    }
    // Exactly the one writable field — nothing else (rules: hasOnly(['status'])).
    transaction.update(ref, { status });
    return { id: reportId, status, alreadyResolved: false };
  });
}

// ---------------------------------------------------------------------------
// Delete a reported community-chat message (callable-backed admin action)
// ---------------------------------------------------------------------------

export interface DeleteCommunityMessageResult {
  messageId: string;
  /** True when a message document was actually deleted by this call. */
  deleted: boolean;
  /** How many open reports the backend moved to 'reviewed'. */
  resolvedReports: number;
}

/**
 * HARD-deletes a reported GLOBAL community-chat message via the
 * chatchannels.adminDeleteMessage callable, which also resolves every open
 * moderationReports document that targets it and writes an adminAuditEvents
 * record preserving the original text.
 *
 * Unlike resolveModerationReport (a rules-granted direct Firestore status write),
 * this goes through a callable: deleting a message from another collection is not
 * something the moderationReports rules can express, the backend must independently
 * re-verify the admin claim, and the delete is an audited admin action. The
 * callable is idempotent — a re-delete of an already-gone message returns
 * { deleted: false } rather than failing.
 */
export async function adminDeleteCommunityMessage(
  messageId: string,
  reason?: string,
): Promise<DeleteCommunityMessageResult> {
  return callAdmin<DeleteCommunityMessageResult>(ADMIN_DELETE_COMMUNITY_MESSAGE_CALLABLE, {
    messageId,
    ...(reason ? { reason } : {}),
  });
}
