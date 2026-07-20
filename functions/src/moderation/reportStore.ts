/**
 * Firestore I/O for the moderation-report domain: the rate-limited,
 * deduplicating upsert shared by chatchannels.reportMessage,
 * dm.reportMessage and moderation.reportUser.
 *
 * Every report goes through one of the two functions below so the three
 * callables cannot drift apart on the things that matter — the per-reporter
 * cap, the dedup grain, and the fact that a repeat never resets a report a
 * moderator has already actioned. The callables above this layer own only
 * their own eligibility check (channel membership / conversation participation)
 * and the snapshot they hand in.
 *
 * RATE LIMIT. Enforced with a count() aggregate read INSIDE the transaction
 * that writes the report, exactly as feedback.reportIssue does: the windowed
 * count and the write serialize together, so concurrent submissions cannot race
 * the cap. It is checked only on the path that CREATES a new report document —
 * refreshing the details on a report you already filed is not a new row in
 * anyone's queue, and locking a user out of correcting their own note would be
 * a worse failure than the one the cap prevents.
 *
 * REQUIRES A COMPOSITE INDEX: moderationReports (reportedBy ASC,
 * createdAt ASC) — firebase/firestore.indexes.json (`reportedBy` is the legacy
 * field name for the reporter's uid; see moderation-core.ts). Without it the limiter
 * query fails FAILED_PRECONDITION at call time (a missing composite index
 * errors, it does not silently return empty), so the index must be deployed
 * with `firebase deploy --only firestore:indexes` BEFORE these callables go
 * live.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  MODERATION_REPORTS_COLLECTION,
  MODERATION_REPORT_RATE_LIMIT_WINDOW_MS,
  MODERATION_USER_SUMMARIES_COLLECTION,
  RATE_LIMITED_MESSAGE,
  buildMessageReportDocument,
  buildMessageReportRepeatUpdate,
  buildUserReportDocument,
  buildUserReportRepeatUpdate,
  buildUserSummaryUpdate,
  isModerationRateLimited,
  moderationMessageReportId,
  moderationUserReportId,
  type ModerationMessageSurface,
  type ModerationReportReason,
  type ReportedMessageSnapshot,
  type ReportedUserSnapshot,
} from './moderation-core';

/** The uniform response of all three report callables (events parity). */
export interface ReportResponse {
  reported: true;
}

function reportsCollection() {
  return db.collection(MODERATION_REPORTS_COLLECTION);
}

/** Windowed count of this reporter's report documents, read inside a tx. */
async function reporterCountInWindow(
  tx: FirebaseFirestore.Transaction,
  reporterUserId: string,
): Promise<number> {
  const windowStart = Timestamp.fromMillis(Date.now() - MODERATION_REPORT_RATE_LIMIT_WINDOW_MS);
  const snap = await tx.get(
    reportsCollection()
      .where('reportedBy', '==', reporterUserId)
      .where('createdAt', '>=', windowStart)
      .count(),
  );
  return snap.data().count;
}

/**
 * Files a MESSAGE report. Deduplicates per
 * (surface, scope, message, reporter, reason): a repeat silently refreshes the
 * reporter's note and the response never reveals whether a previous report
 * existed (events.reportChatMessage parity — telling a reporter "you already
 * reported this" leaks nothing useful and invites probing).
 */
export async function fileMessageReport(input: {
  surface: ModerationMessageSurface;
  scopeId: string;
  messageId: string;
  reporterUserId: string;
  reason: ModerationReportReason;
  details: string | undefined;
  snapshot: ReportedMessageSnapshot;
}): Promise<ReportResponse> {
  const reportRef = reportsCollection().doc(
    moderationMessageReportId({
      surface: input.surface,
      scopeId: input.scopeId,
      messageId: input.messageId,
      reporterUserId: input.reporterUserId,
      reason: input.reason,
    }),
  );

  await db.runTransaction(async (tx) => {
    const existing = await tx.get(reportRef);
    if (existing.exists) {
      tx.update(reportRef, buildMessageReportRepeatUpdate(input.details));
      return;
    }
    if (isModerationRateLimited(await reporterCountInWindow(tx, input.reporterUserId))) {
      throw new HttpsError('resource-exhausted', RATE_LIMITED_MESSAGE);
    }
    tx.set(
      reportRef,
      buildMessageReportDocument(input, () => FieldValue.serverTimestamp()),
    );
  });

  return { reported: true };
}

/**
 * Files a USER report. Deduplicates per (reporter, reportedUser) — see
 * moderationUserReportId for why the reason is deliberately not part of the
 * key — tallying `occurrences` on a repeat, and keeps the per-target
 * moderationUserSummaries aggregate in lock-step inside the same transaction so
 * the distinct-reporter count can never drift from the report documents.
 */
export async function fileUserReport(input: {
  reportedUserId: string;
  reporterUserId: string;
  reason: ModerationReportReason;
  details: string | undefined;
  snapshot: ReportedUserSnapshot;
}): Promise<ReportResponse> {
  const reportRef = reportsCollection().doc(
    moderationUserReportId(input.reporterUserId, input.reportedUserId),
  );
  const summaryRef = db
    .collection(MODERATION_USER_SUMMARIES_COLLECTION)
    .doc(input.reportedUserId);

  await db.runTransaction(async (tx) => {
    const existing = await tx.get(reportRef);
    const isNew = !existing.exists;
    if (isNew && isModerationRateLimited(await reporterCountInWindow(tx, input.reporterUserId))) {
      throw new HttpsError('resource-exhausted', RATE_LIMITED_MESSAGE);
    }

    if (isNew) {
      tx.set(
        reportRef,
        buildUserReportDocument(input, () => FieldValue.serverTimestamp()),
      );
    } else {
      tx.update(
        reportRef,
        buildUserReportRepeatUpdate(input, FieldValue.increment(1), () =>
          FieldValue.serverTimestamp(),
        ),
      );
    }

    tx.set(
      summaryRef,
      buildUserSummaryUpdate(
        { reportedUserId: input.reportedUserId, newReporter: isNew },
        (by) => FieldValue.increment(by),
        () => FieldValue.serverTimestamp(),
      ),
      { merge: true },
    );
  });

  return { reported: true };
}
