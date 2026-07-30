/**
 * errors.reportClientError — AUTHENTICATED callable
 * (contracts/functions/functions.json).
 *
 * The single client-side error-reporting entry point. A signed-in
 * (non-suspended, non-deleted — no member entitlement required) user's app
 * reports a genuine RUNTIME error (e.g. the Messages inbox listener failing).
 * The callable:
 *
 *  1. rate-limits per user (30 / hour) INSIDE the write transaction so a
 *     crash-loop cannot spam the pipeline (mirrors feedback.reportIssue);
 *  2. persists the PRIVATE record of record `clientErrorReports/{reportId}`
 *     (admin-only read; carries the uid) so the report is never lost;
 *  3. writes an `adminAuditEvents` entry (action `client.error`) so the error
 *     shows up in the KCC admin Audit Log alongside admin actions.
 *
 * The DEDUPLICATED PUBLIC GitHub issue is filed asynchronously by the
 * errors-onClientErrorReport Firestore trigger (which owns the GITHUB_ISSUE_TOKEN
 * secret) — keeping this callable off the GitHub latency path and out of the
 * secret's blast radius.
 *
 * PRIVACY: the uid is written only to the admin-only clientErrorReports doc and
 * the admin-only audit-event details. It is NEVER placed on the public issue.
 * No secrets are logged.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  CLIENT_ERROR_AUDIT_ACTION,
  CLIENT_ERROR_AUDIT_TARGET_TYPE,
  CLIENT_ERROR_RATE_LIMIT_WINDOW_MS,
  CLIENT_ERROR_REPORTS_COLLECTION,
  buildClientErrorAuditDetails,
  buildClientErrorReportDocument,
  isClientErrorRateLimited,
  parseReportClientErrorInput,
} from './clientErrors-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface ReportClientErrorResponse {
  reportId: string;
}

export const reportClientError = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ReportClientErrorResponse> => {
    // Auth REQUIRED (unauthenticated → `unauthenticated`); suspended/deleted
    // rejected. No member entitlement required — errors must be reportable even
    // by non-members. Pre-authentication failures use diagnostics.submitReport.
    const actor = await requireActiveActor(request);

    const parsed = parseReportClientErrorInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const report = parsed.input;

    const windowStart = Timestamp.fromMillis(Date.now() - CLIENT_ERROR_RATE_LIMIT_WINDOW_MS);
    const reports = db.collection(CLIENT_ERROR_REPORTS_COLLECTION);
    const ref = reports.doc();

    // Rate-limit read + write serialize in one transaction: concurrent
    // submissions can never race the cap. Composite index: uid ASC, createdAt ASC.
    await db.runTransaction(async (tx) => {
      const countSnap = await tx.get(
        reports.where('uid', '==', actor.uid).where('createdAt', '>=', windowStart).count(),
      );
      if (isClientErrorRateLimited(countSnap.data().count)) {
        throw new HttpsError(
          'resource-exhausted',
          'Too many error reports — please wait a while before reporting again.',
        );
      }
      tx.set(
        ref,
        buildClientErrorReportDocument(report, actor.uid, () => FieldValue.serverTimestamp()),
      );
    });

    // Audit-log entry (adminAuditEvents) — makes the error visible in the admin
    // Audit Log. Best-effort: a failure here must not fail the report (already
    // durably captured above). The uid is the audit event's adminId (admin-only
    // read); the fingerprint is the targetId so related errors group together.
    try {
      await db
        .collection('adminAuditEvents')
        .doc()
        .set(
          buildAdminAuditEvent(
            {
              adminId: actor.uid,
              action: CLIENT_ERROR_AUDIT_ACTION,
              targetType: CLIENT_ERROR_AUDIT_TARGET_TYPE,
              targetId: report.fingerprint,
              reason: report.feature,
              details: buildClientErrorAuditDetails(report),
            },
            () => FieldValue.serverTimestamp(),
          ),
        );
    } catch {
      // Swallow: the private clientErrorReports record is the source of truth
      // and the onClientErrorReport trigger still files the deduped issue.
    }

    return { reportId: ref.id };
  },
);
