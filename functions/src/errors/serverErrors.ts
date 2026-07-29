/**
 * Server-error reporting — the impure entry points used by function handlers.
 *
 * Two things, both designed so that adopting them cannot make a handler worse:
 *
 *  1. `reportServerError({ source, error, context })` — persists the FULL error
 *     detail to the private, admin-read-only `serverErrorReports/{id}` document.
 *     It NEVER throws and never rejects: a failure to report must not convert a
 *     handled error into a crash, and must not mask the original error.
 *
 *  2. `withServerErrorReporting(source, handler)` — wraps an async handler so an
 *     unexpected throw is reported and then RETHROWN unchanged. Rethrowing is
 *     essential: Cloud Scheduler retry policy, Firestore-trigger retries, Cloud
 *     Logging severity and any alerting all key off the function failing, so
 *     swallowing the error to "handle" it would silently change production
 *     semantics. Reporting is strictly additive.
 *
 * GitHub is deliberately NOT called here. The write to `serverErrorReports`
 * activates the `errors-onServerErrorReport` Firestore trigger, which does the
 * dedup claim, the global budget charge and the issue creation. Mirrors
 * errors-onClientErrorReport, for two reasons: GitHub's latency (and its
 * flakiness) stays off the critical path of the failing handler, and the
 * GITHUB_ISSUE_TOKEN secret stays bound to ONE function instead of being mounted
 * into all 15 scheduled jobs.
 *
 * Not reported: `HttpsError` (a deliberate, documented client-facing outcome —
 * see isDeliberateHttpsError in serverErrors-core.ts).
 */

import { logger } from 'firebase-functions';
import { HttpsError } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  SERVER_ERROR_REPORTS_COLLECTION,
  buildServerErrorReport,
  buildServerErrorReportDocument,
  isDeliberateHttpsError,
} from './serverErrors-core';

export interface ReportServerErrorArgs {
  /**
   * Stable, developer-authored identifier for the failing code path, in the
   * `domain.action` convention (e.g. `account.purgeDeleted`). Part of the dedup
   * fingerprint and rendered in the public issue, so it must be a hard-coded
   * constant — never interpolated from data.
   */
  source: string;
  /** The thrown value (any type; classification is total). */
  error: unknown;
  /**
   * Optional triage hints (scalars only, bounded, PRIVATE-only). Safe to include
   * ids here — this map never reaches the public issue.
   */
  context?: Record<string, unknown> | null;
}

/**
 * Records a server-side error. Returns the fingerprint on success, or null when
 * the error was skipped (expected) or the write failed. Never throws.
 */
export async function reportServerError(args: ReportServerErrorArgs): Promise<string | null> {
  try {
    if (isExpectedServerError(args.error)) return null;

    const report = buildServerErrorReport(args.source, args.error, args.context ?? null);

    await db
      .collection(SERVER_ERROR_REPORTS_COLLECTION)
      .add(buildServerErrorReportDocument(report, () => FieldValue.serverTimestamp()));

    // Structured log alongside the durable record, so the error is greppable in
    // Cloud Logging by the same fingerprint that keys the public issue.
    logger.error('serverError reported', {
      source: report.source,
      errorName: report.errorName,
      errorCode: report.errorCode,
      fingerprint: report.fingerprint,
    });

    return report.fingerprint;
  } catch (reportingError) {
    // Last resort: the reporting path itself failed. Log and swallow — the caller
    // is already dealing with a real error and must not be handed a second one.
    try {
      logger.error('reportServerError: failed to record server error', {
        source: String(args.source),
        error: String(reportingError),
      });
    } catch {
      /* logging must not throw either */
    }
    return null;
  }
}

/**
 * Whether an error is an EXPECTED outcome that must not be reported.
 *
 * Currently: `HttpsError` only (checked both by `instanceof`, for the v2 class
 * this module imports, and by the pure duck-typed predicate, which also catches
 * the v1 class and any duplicated module instance).
 */
export function isExpectedServerError(error: unknown): boolean {
  return error instanceof HttpsError || isDeliberateHttpsError(error);
}

/**
 * Wraps an async handler with server-error reporting.
 *
 * ```ts
 * export const purgeDeleted = onSchedule(
 *   { region: 'europe-west1', schedule: '30 3 * * *', ... },
 *   withServerErrorReporting('account.purgeDeleted', async () => {
 *     await runAccountPurge(new Date());
 *   }),
 * );
 * ```
 *
 * Generic in the handler's parameters and return type, so it is transparent to
 * `onSchedule`/`onDocumentCreated`/`onCall` signatures and trivially opt-in for
 * any handler that wants it. The wrapped handler's arguments are NEVER inspected
 * or recorded (a callable's `request` holds the caller's uid and payload).
 *
 * @param source hard-coded `domain.action` label for this handler.
 */
export function withServerErrorReporting<Args extends unknown[], Result>(
  source: string,
  handler: (...args: Args) => Promise<Result>,
): (...args: Args) => Promise<Result> {
  return async (...args: Args): Promise<Result> => {
    try {
      return await handler(...args);
    } catch (error) {
      // Report, then rethrow the ORIGINAL error untouched so retry/alerting
      // semantics are byte-for-byte what they were before wrapping.
      await reportServerError({ source, error });
      throw error;
    }
  };
}
