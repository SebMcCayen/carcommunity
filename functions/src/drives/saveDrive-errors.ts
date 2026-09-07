/**
 * drives.save error mapping — stops a transient/unexpected failure from
 * surfacing as an opaque INTERNAL/500 with no server log (GitHub #800).
 *
 * `drives-save` had no try/catch around `requireActiveActor`'s `users/{uid}`
 * read or `db.runTransaction`, so a transient Firestore exception (`UNAVAILABLE`
 * / `ABORTED` / `DEADLINE_EXCEEDED`) or any other unexpected throw became a
 * non-`HttpsError` → HTTP 500, which the Android SDK surfaces as code `INTERNAL`
 * with NO server log of the real cause — leaving us unable to tell a client
 * network drop from a server exception. This maps such a throw to a RETRYABLE
 * `HttpsError('unavailable')` and logs the root cause with triage context;
 * DELIBERATE `HttpsError` outcomes (`invalid-argument`, `permission-denied`, the
 * idempotency responses) pass through UNCHANGED.
 *
 * Pure of the Admin SDK (imports only the firebase-functions `logger` +
 * `HttpsError`), so it unit-tests without initialising Firebase — see the import
 * rule in firebase.ts.
 */

import { logger } from 'firebase-functions';
import { HttpsError } from 'firebase-functions/v2/https';
import { isDeliberateHttpsError } from '../errors/serverErrors-core';

/** Triage hints recorded (server-side only) when an unexpected failure is mapped. */
export interface SaveDriveErrorContext {
  uid?: string;
  sourceSessionId?: string;
  pointCount?: number;
}

/** Minimal logger surface, injectable so the mapping is unit-testable. */
export interface ErrorLogger {
  error: (message: string, data?: Record<string, unknown>) => void;
}

/**
 * Client-facing message for the retryable path. Deliberately generic — no
 * internal detail (message, stack, doc path, uid) ever reaches the caller; the
 * full cause goes to Cloud Logging only.
 */
export const SAVE_DRIVE_RETRYABLE_MESSAGE = 'Could not save the drive right now. Please try again.';

/**
 * Decides the error the saveDrive handler should throw for a caught value.
 *
 *  - A deliberate `HttpsError` (thrown by validation, the member gate, or the
 *    idempotency path) is returned UNCHANGED, so its code/message contract is
 *    preserved exactly.
 *  - Anything else is an UNEXPECTED failure: it is logged with root-cause detail
 *    and triage context, then converted to a RETRYABLE `HttpsError('unavailable')`
 *    (HTTP 503) instead of the opaque `INTERNAL`/500 a raw throw produced.
 *
 * Returns the error to throw (rather than throwing) so the call site reads as a
 * single `throw mapSaveDriveError(...)` and the mapping stays pure/total.
 */
export function mapSaveDriveError(
  error: unknown,
  context: SaveDriveErrorContext,
  log: ErrorLogger = logger,
): HttpsError {
  // `instanceof` catches the v2 class this module imports; the duck-typed
  // predicate additionally catches the v1 class and any duplicated module
  // instance from bundling — mirrors errors/serverErrors.isExpectedServerError.
  if (error instanceof HttpsError || isDeliberateHttpsError(error)) {
    return error as HttpsError;
  }

  const errorMessage = error instanceof Error ? error.message : String(error);
  const stack = error instanceof Error ? error.stack : undefined;

  // The missing observability #800 asked for: the real cause is now greppable in
  // Cloud Logging, keyed by uid/sourceSessionId, so a future occurrence tells us
  // whether it was a server exception or a client-side network drop.
  log.error('drives.save: unexpected failure, returning retryable error', {
    uid: context.uid,
    sourceSessionId: context.sourceSessionId,
    pointCount: context.pointCount,
    errorMessage,
    stack,
  });

  return new HttpsError('unavailable', SAVE_DRIVE_RETRYABLE_MESSAGE);
}
