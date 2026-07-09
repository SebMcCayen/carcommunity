/**
 * diagnostics.submitReport — PUBLIC callable
 * (contracts/functions/functions.json).
 *
 * The only unauthenticated callable in the codebase, deliberately: crash
 * and sign-in-failure reports must be submittable before authentication
 * works (legacy optionalAuthHook parity). Authenticated callers get their
 * UID attached; anonymous reports store userId: null.
 *
 * APP CHECK — INTENTIONALLY NON-ENFORCING (unlike EVERY other callable):
 * this is the pre-auth telemetry path, and the exact failure it exists to
 * surface — a broken sign-in on a device that cannot yet mint a Play
 * Integrity App Check token — is ALSO the case where App Check is
 * unavailable (observed 2026-07-09: submitReport logged `app: MISSING`
 * while enforceAppCheck rejected the request BEFORE the handler ran, so no
 * diagnosticsReports doc was written, the onSignInFailure trigger never
 * fired, and no GitHub issue was filed). Hard-enforcing App Check here
 * makes the telemetry dead exactly when it is needed, so we set
 * `enforceAppCheck: false`: a missing/invalid token no longer blocks the
 * report. A VALID token is still verified best-effort by the runtime and
 * surfaces via `request.app`; we record only whether one was present
 * (`appCheckPresent`) so admins can tell attested from unattested reports.
 *
 * TRADE-OFF / abuse compensation (documented deliberately): dropping
 * enforcement widens the anonymous write surface, but the blast radius is
 * bounded — (1) writes land ONLY in `diagnosticsReports`, an admin-read-only
 * private collection (no client can read it back) with a 90-day retention
 * sweep; (2) all payloads are server-sanitized in diagnostics-core (tokens/
 * credentials/coordinates/stack-trace keys stripped, bounded scalars only);
 * and (3) the sole outbound/public effect — the auto-filed GitHub issue via
 * the onSignInFailure trigger — is independently bounded by signInIssues-core
 * (strict allowlist + single `Unknown` bucket + per-fingerprint dedup), so an
 * unauthenticated flood cannot mint unbounded public issues regardless of how
 * many reports it submits. This relaxation is scoped to THIS callable ONLY;
 * every other callable keeps App Check enforced (see appcheck-guard.test.ts).
 *
 * All privacy guarantees run server-side in diagnostics-core:
 * metadata sanitization (tokens/credentials/coordinates/stack traces
 * stripped, bounded scalars only), no raw headers, dedup fingerprint.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  buildDiagnosticsReportDocument,
  parseSubmitDiagnosticsReportInput,
} from './diagnostics-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  // Deliberately NON-enforcing — see the file header. This is the ONLY
  // callable that opts out of App Check enforcement; the guard test
  // (appcheck-guard.test.ts) exempts it explicitly so the exception stays
  // intentional and visible rather than an accident.
  enforceAppCheck: false,
};

export interface SubmitDiagnosticsReportResponse {
  reportId: string;
  fingerprint: string;
}

export const submitReport = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SubmitDiagnosticsReportResponse> => {
    const parsed = parseSubmitDiagnosticsReportInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }

    // Best-effort App Check: a valid token is still verified by the runtime
    // and populates request.app; a missing/invalid one is NOT rejected (see
    // header). Record only presence — never the token — so admins can weigh
    // attested vs unattested reports.
    const appCheckPresent = request.app != null;

    const document = buildDiagnosticsReportDocument(
      parsed.input,
      request.auth?.uid ?? null,
      () => FieldValue.serverTimestamp(),
      { appCheckPresent },
    );
    const ref = await db.collection('diagnosticsReports').add(document);

    return { reportId: ref.id, fingerprint: document.fingerprint as string };
  },
);
