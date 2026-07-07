/**
 * diagnostics.submitReport — PUBLIC callable
 * (contracts/functions/functions.json).
 *
 * The only unauthenticated callable in the codebase, deliberately: crash
 * and sign-in-failure reports must be submittable before authentication
 * works (legacy optionalAuthHook parity). App Check still applies in
 * production. Authenticated callers get their UID attached; anonymous
 * reports store userId: null.
 *
 * All privacy guarantees run server-side in diagnostics-core:
 * metadata sanitization (tokens/credentials/coordinates/stack traces
 * stripped, bounded scalars only), no raw headers, dedup fingerprint.
 *
 * Rate limiting: 20 requests per 60 s per client IP. The counter is stored
 * in `diagnosticsRateLimits/{hashedIp_bucket}` and cleaned up by the hourly
 * scheduled sweep (diagnostics-cleanupRateLimits) and the monthly report
 * cleanup (diagnostics-cleanupExpired).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  DIAGNOSTICS_RATE_LIMIT_MAX,
  DIAGNOSTICS_RATE_LIMIT_WINDOW_MS,
  buildDiagnosticsReportDocument,
  extractClientIp,
  parseSubmitDiagnosticsReportInput,
  rateLimitDocId,
} from './diagnostics-core';

/**
 * Atomically increments the per-IP request counter for the current
 * 1-minute window. Returns `true` when the request is within the limit,
 * `false` when the limit has been reached. Uses a Firestore transaction so
 * concurrent requests on the same bucket serialize correctly.
 */
async function checkDiagnosticsRateLimit(ip: string): Promise<boolean> {
  const bucket = Math.floor(Date.now() / DIAGNOSTICS_RATE_LIMIT_WINDOW_MS);
  const docId = rateLimitDocId(ip, bucket);
  const ref = db.collection('diagnosticsRateLimits').doc(docId);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const rawCount = snap.exists ? snap.data()!.count : 0;
    const count: number = typeof rawCount === 'number' ? rawCount : 0;
    if (count >= DIAGNOSTICS_RATE_LIMIT_MAX) {
      return false;
    }
    if (snap.exists) {
      tx.update(ref, { count: FieldValue.increment(1) });
    } else {
      tx.set(ref, {
        count: 1,
        windowBucket: bucket,
        createdAt: FieldValue.serverTimestamp(),
      });
    }
    return true;
  });
}

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface SubmitDiagnosticsReportResponse {
  reportId: string;
  fingerprint: string;
}

export const submitReport = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SubmitDiagnosticsReportResponse> => {
    const ip = extractClientIp(request.rawRequest);
    const allowed = await checkDiagnosticsRateLimit(ip);
    if (!allowed) {
      throw new HttpsError(
        'resource-exhausted',
        'Too many diagnostics reports. Please wait 60 seconds before submitting again.',
      );
    }

    const parsed = parseSubmitDiagnosticsReportInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }

    const document = buildDiagnosticsReportDocument(
      parsed.input,
      request.auth?.uid ?? null,
      () => FieldValue.serverTimestamp(),
    );
    const ref = await db.collection('diagnosticsReports').add(document);

    return { reportId: ref.id, fingerprint: document.fingerprint as string };
  },
);
