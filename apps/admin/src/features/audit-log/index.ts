/**
 * Admin audit-log feature module (Phase 13n — Firebase migration).
 *
 * Read-only view over `adminAuditEvents` — the immutable, backend-written
 * audit trail (functions/src/admin/claims-core.ts `buildAdminAuditEvent`).
 * This replaces the legacy `GET /v1/admin/audit-log` placeholder with direct
 * rules-gated Firestore reads (`isAdmin()` read, NO client writes — not even
 * by admins — per firestore.rules).
 *
 * Queries:
 *  - Newest-first list: `orderBy(createdAt desc)` + `limit` — single-field
 *    default index.
 *  - Filter by target: `where(targetId ==)` + `orderBy(createdAt desc)` —
 *    served by the existing composite index (targetId ASC, createdAt DESC)
 *    in firebase/firestore.indexes.json.
 *  - Load-more uses a Firestore snapshot cursor (`startAfter`); the cursor is
 *    opaque to callers.
 *  - Action filtering is client-side over the loaded page (no composite
 *    index exists for action, and the action set is small).
 *
 * Security notes:
 *  - Strictly read-only: this module performs no writes and exposes none.
 *  - Audit records are immutable server-side; nothing here can alter them.
 */

import {
  collection,
  getDocs,
  limit,
  orderBy,
  query,
  startAfter,
  where,
  type QueryConstraint,
  type QueryDocumentSnapshot,
} from 'firebase/firestore';

import { ApiError } from '../../lib/api';
import { getAdminFirestore } from '../../lib/firestore';

export { ApiError };

export const AUDIT_LOG_PAGE_SIZE = 25;

/**
 * Distinct `action` values written to adminAuditEvents across
 * functions/src (grep for buildAdminAuditEvent call sites; templated
 * status actions expanded: activate | pause | end).
 */
export const KNOWN_AUDIT_ACTIONS = [
  'admin.setFeatureFlag',
  'badge.awardHelpfulMember',
  'billboards.activate',
  'billboards.create',
  'billboards.end',
  'billboards.pause',
  'billboards.update',
  'crownHunt.activatePoint',
  'crownHunt.createPoint',
  'crownHunt.pausePoint',
  'crownHunt.updatePoint',
  'event.cancel',
  'event.complete',
  'event.create',
  'event.publish',
  'event.update',
  'eventChat.removeMessage',
  'events.resolveChatReport',
  'notifications.adminSend',
  'partners.activateCompany',
  'partners.activateOffer',
  'partners.approveApplication',
  'partners.createCompany',
  'partners.createOffer',
  'partners.endCompany',
  'partners.endOffer',
  'partners.pauseCompany',
  'partners.pauseOffer',
  'partners.rejectApplication',
  'partners.startApplicationReview',
  'partners.updateCompany',
  'partners.updateOffer',
  'points.adminAdjust',
  'points.adminReverse',
  'subscription.grantEntitlement',
  'user.restoreAccess',
  'user.setAdminRole',
  'user.suspend',
  'user.warn',
] as const;

export type KnownAuditAction = (typeof KNOWN_AUDIT_ACTIONS)[number];

/**
 * i18n label key per known action. Keys are flat camelCase segments so the
 * dot-path `translate` lookup never splits a raw action value. Unknown
 * actions map to null — callers render the raw value as the fallback so a
 * new backend action is never hidden or mislabeled.
 */
const ACTION_LABEL_KEYS: Record<KnownAuditAction, string> = {
  'admin.setFeatureFlag': 'auditLog.action.adminSetFeatureFlag',
  'badge.awardHelpfulMember': 'auditLog.action.badgeAwardHelpfulMember',
  'billboards.activate': 'auditLog.action.billboardsActivate',
  'billboards.create': 'auditLog.action.billboardsCreate',
  'billboards.end': 'auditLog.action.billboardsEnd',
  'billboards.pause': 'auditLog.action.billboardsPause',
  'billboards.update': 'auditLog.action.billboardsUpdate',
  'crownHunt.activatePoint': 'auditLog.action.crownHuntActivatePoint',
  'crownHunt.createPoint': 'auditLog.action.crownHuntCreatePoint',
  'crownHunt.pausePoint': 'auditLog.action.crownHuntPausePoint',
  'crownHunt.updatePoint': 'auditLog.action.crownHuntUpdatePoint',
  'event.cancel': 'auditLog.action.eventCancel',
  'event.complete': 'auditLog.action.eventComplete',
  'event.create': 'auditLog.action.eventCreate',
  'event.publish': 'auditLog.action.eventPublish',
  'event.update': 'auditLog.action.eventUpdate',
  'eventChat.removeMessage': 'auditLog.action.eventChatRemoveMessage',
  'events.resolveChatReport': 'auditLog.action.eventsResolveChatReport',
  'notifications.adminSend': 'auditLog.action.notificationsAdminSend',
  'partners.activateCompany': 'auditLog.action.partnersActivateCompany',
  'partners.activateOffer': 'auditLog.action.partnersActivateOffer',
  'partners.approveApplication': 'auditLog.action.partnersApproveApplication',
  'partners.createCompany': 'auditLog.action.partnersCreateCompany',
  'partners.createOffer': 'auditLog.action.partnersCreateOffer',
  'partners.endCompany': 'auditLog.action.partnersEndCompany',
  'partners.endOffer': 'auditLog.action.partnersEndOffer',
  'partners.pauseCompany': 'auditLog.action.partnersPauseCompany',
  'partners.pauseOffer': 'auditLog.action.partnersPauseOffer',
  'partners.rejectApplication': 'auditLog.action.partnersRejectApplication',
  'partners.startApplicationReview': 'auditLog.action.partnersStartApplicationReview',
  'partners.updateCompany': 'auditLog.action.partnersUpdateCompany',
  'partners.updateOffer': 'auditLog.action.partnersUpdateOffer',
  'points.adminAdjust': 'auditLog.action.pointsAdminAdjust',
  'points.adminReverse': 'auditLog.action.pointsAdminReverse',
  'subscription.grantEntitlement': 'auditLog.action.subscriptionGrantEntitlement',
  'user.restoreAccess': 'auditLog.action.userRestoreAccess',
  'user.setAdminRole': 'auditLog.action.userSetAdminRole',
  'user.suspend': 'auditLog.action.userSuspend',
  'user.warn': 'auditLog.action.userWarn',
};

/**
 * Returns the i18n key for a known audit action, or null for unknown values
 * (callers fall back to rendering the raw action string).
 */
export function auditActionLabelKey(action: string): string | null {
  // hasOwn guard: a plain index lookup would resolve prototype keys (e.g. a
  // stored action of "constructor") to functions instead of undefined.
  return Object.hasOwn(ACTION_LABEL_KEYS, action)
    ? ACTION_LABEL_KEYS[action as KnownAuditAction]
    : null;
}

/** One adminAuditEvents record, normalized for display. */
export interface AdminAuditEventRow {
  id: string;
  /** Raw action code, e.g. "user.suspend" (may be a value this UI predates). */
  action: string;
  /** Acting admin's uid (`adminId` in the stored document). */
  adminId: string;
  targetType: string;
  targetId: string;
  reason: string;
  /** Optional structured details written by some actions; null when absent. */
  details: Record<string, unknown> | null;
  /** ISO timestamp, or null when absent/unparseable (permissive by design). */
  createdAt: string | null;
}

/** Opaque load-more cursor (a Firestore document snapshot). */
export type AuditLogCursor = QueryDocumentSnapshot;

export interface AuditLogPage {
  events: AdminAuditEventRow[];
  /** Pass back to listAdminAuditEvents to fetch the next page; null = no more. */
  cursor: AuditLogCursor | null;
}

export interface ListAuditEventsOptions {
  /** Exact targetId filter (uses the targetId+createdAt composite index). */
  targetId?: string;
  /** Cursor from a previous page. Must come from a same-filter query. */
  cursor?: AuditLogCursor | null;
  pageSize?: number;
}

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design —
 * audit rows may be old or partial, so it accepts a Firestore Timestamp
 * (toDate()), a native Date, or an already-serialized date string, and
 * returns null only when the value is absent or unparseable.
 */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    return (value as { toDate: () => Date }).toDate().toISOString();
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

function toDetails(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function mapAuditEventDoc(snapshot: QueryDocumentSnapshot): AdminAuditEventRow {
  const data = snapshot.data() as Record<string, unknown>;
  return {
    id: snapshot.id,
    action: toStringField(data.action),
    adminId: toStringField(data.adminId),
    targetType: toStringField(data.targetType),
    targetId: toStringField(data.targetId),
    reason: toStringField(data.reason),
    details: toDetails(data.details),
    createdAt: toIso(data.createdAt),
  };
}

/**
 * Lists adminAuditEvents newest-first, optionally filtered to one targetId,
 * with cursor-based load-more. Read-only; a full page returns the last
 * snapshot as the next-page cursor, a short page returns null.
 */
export async function listAdminAuditEvents(
  options: ListAuditEventsOptions = {},
): Promise<AuditLogPage> {
  const pageSize = options.pageSize ?? AUDIT_LOG_PAGE_SIZE;
  const target = options.targetId?.trim();

  const constraints: QueryConstraint[] = [];
  if (target) {
    constraints.push(where('targetId', '==', target));
  }
  constraints.push(orderBy('createdAt', 'desc'));
  if (options.cursor) {
    constraints.push(startAfter(options.cursor));
  }
  constraints.push(limit(pageSize));

  const snapshot = await getDocs(
    query(collection(getAdminFirestore(), 'adminAuditEvents'), ...constraints),
  );

  const events = snapshot.docs.map(mapAuditEventDoc);
  const cursor =
    (snapshot.docs.length === pageSize ? snapshot.docs[snapshot.docs.length - 1] : null) ?? null;
  return { events, cursor };
}

/**
 * Client-side action filter over loaded rows. An empty/blank filter returns
 * the rows unchanged; otherwise only exact action matches remain.
 */
export function filterEventsByAction(
  events: AdminAuditEventRow[],
  action: string,
): AdminAuditEventRow[] {
  const trimmed = action.trim();
  if (!trimmed) return events;
  return events.filter((event) => event.action === trimmed);
}
