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
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
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

    const document = buildDiagnosticsReportDocument(
      parsed.input,
      request.auth?.uid ?? null,
      () => FieldValue.serverTimestamp(),
    );
    const ref = await db.collection('diagnosticsReports').add(document);

    return { reportId: ref.id, fingerprint: document.fingerprint as string };
  },
);
