/**
 * events.listChatReports / events.resolveChatReport — admin callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-listChatReports` and
 * `events-resolveChatReport`.
 *
 * The chat-report moderation queue. events/{eventId}/messageReports is
 * backend-only (firestore.rules: read,write:false) so the admin app cannot
 * read or transition reports directly — these callables are the moderation
 * surface, matching the legacy services/api moderation endpoints:
 *  - listChatReports: cross-event queue (Admin SDK collectionGroup scan;
 *    reporter identities are returned only to admins). Bounded + newest-first.
 *  - resolveChatReport: transitions one report to under_review / resolved /
 *    dismissed, stamping reviewedByUserId + reviewedAt and writing an
 *    adminAuditEvents record. (Removing the offending message is the separate
 *    events.removeChatMessage callable, which also auto-resolves open reports.)
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, type Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { parseListChatReportsInput, parseResolveChatReportInput } from './chat-core';
import { MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** Bounded scan for the MVP moderation queue (open reports drain as they resolve). */
const MAX_REPORT_SCAN = 200;
const DEFAULT_PAGE_SIZE = 20;

export interface AdminChatReportSummary {
  id: string;
  eventId: string;
  messageId: string;
  /** Reporter uid — surfaced to admins only (this callable is admin-gated). */
  reporterUserId: string | null;
  reason: string;
  details: string | null;
  status: string;
  createdAt: string;
  reviewedAt: string | null;
  reviewedByUserId: string | null;
}

function toIso(value: unknown): string | null {
  const ts = value as Timestamp | undefined;
  return ts && typeof ts.toDate === 'function' ? ts.toDate().toISOString() : null;
}

export const listChatReports = onCall(
  CALLABLE_OPTS,
  async (request): Promise<{ reports: AdminChatReportSummary[]; meta: { page: number; pageSize: number; total: number; hasNext: boolean } }> => {
    await requireAdminActor(request);

    const parsed = parseListChatReportsInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const pageSize = parsed.input.pageSize ?? DEFAULT_PAGE_SIZE;

    // Admin SDK collectionGroup scan bypasses the backend-only rule. Ordered
    // newest-first by the collectionGroup index (firestore.indexes.json:
    // messageReports/createdAt DESC) so the bounded scan is guaranteed to hold
    // the newest reports — never an arbitrary subset. Status is refined in
    // memory over that newest window (an optional filter, not the primary view).
    const snapshot = await db
      .collectionGroup('messageReports')
      .orderBy('createdAt', 'desc')
      .limit(MAX_REPORT_SCAN)
      .get();
    let reports: AdminChatReportSummary[] = snapshot.docs.map((doc) => {
      const data = doc.data();
      return {
        id: doc.id,
        eventId: doc.ref.parent.parent?.id ?? '',
        messageId: (data.messageId as string | undefined) ?? '',
        reporterUserId: (data.reporterUserId as string | null | undefined) ?? null,
        reason: (data.reason as string | undefined) ?? 'other',
        details: (data.details as string | null | undefined) ?? null,
        status: (data.status as string | undefined) ?? 'new',
        createdAt: toIso(data.createdAt) ?? new Date(0).toISOString(),
        reviewedAt: toIso(data.reviewedAt),
        reviewedByUserId: (data.reviewedByUserId as string | null | undefined) ?? null,
      };
    });

    if (parsed.input.status) {
      reports = reports.filter((r) => r.status === parsed.input.status);
    }
    const page = reports.slice(0, pageSize);

    return {
      reports: page,
      meta: { page: 1, pageSize, total: reports.length, hasNext: reports.length > pageSize },
    };
  },
);

export const resolveChatReport = onCall(
  CALLABLE_OPTS,
  async (request): Promise<{ reportId: string; status: string }> => {
    const actor = await requireAdminActor(request);

    const parsed = parseResolveChatReportInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { eventId, reportId, status } = parsed.input;

    const reportRef = db
      .collection('events')
      .doc(eventId)
      .collection('messageReports')
      .doc(reportId);

    await db.runTransaction(async (tx) => {
      const existing = await tx.get(reportRef);
      if (!existing.exists) {
        throw new HttpsError('not-found', 'Report not found.');
      }
      tx.update(reportRef, {
        status,
        reviewedByUserId: actor.uid,
        reviewedAt: FieldValue.serverTimestamp(),
      });
    });

    await db
      .collection('adminAuditEvents')
      .doc()
      .set(
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'events.resolveChatReport',
            targetType: 'chatReport',
            targetId: reportId,
            reason: `Report ${status}`,
            details: { eventId, status },
          },
          () => FieldValue.serverTimestamp(),
        ),
      );

    return { reportId, status };
  },
);
