/**
 * incidents.report — member-gated create of a crowd-sourced incident
 * (contracts/functions/functions.json: incidents.report).
 *
 * Deployed via the `incidents` export group as `incidents-report`
 * (europe-west1). Requires an active member (requireMemberActor). The incident
 * is written to `incidents/{id}` with a computed `geoCell` (nearby-query index)
 * and a per-type `expiresAt` TTL; `createdAt` is a server timestamp. All writes
 * flow through this callable — clients cannot write the collection directly.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import {
  buildIncidentFields,
  expiryFor,
  isReportable,
  parseReportInput,
  type IncidentView,
} from './incidents-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const report = onCall(CALLABLE_OPTS, async (request): Promise<IncidentView> => {
  const actor = await requireMemberActor(request);

  const parsed = parseReportInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  if (!isReportable(input.latitude, input.longitude)) {
    throw new HttpsError('invalid-argument', 'Invalid coordinate.');
  }

  const now = new Date();
  const expiresAt = expiryFor(input.type, now);
  const fields = buildIncidentFields({
    type: input.type,
    latitude: input.latitude,
    longitude: input.longitude,
    source: 'user',
    reporterUid: actor.uid,
    note: input.note,
  });

  const ref = db.collection('incidents').doc();
  await ref.set({
    ...fields,
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: Timestamp.fromDate(expiresAt),
  });

  return {
    id: ref.id,
    type: fields.type,
    latitude: fields.latitude,
    longitude: fields.longitude,
    source: fields.source,
    reporterUid: fields.reporterUid,
    note: fields.note,
    // The stored createdAt is a server timestamp resolved by Firestore, which
    // may not equal `now`; returning `now` here would be misleading. Clients
    // read the authoritative value via listNearby / Firestore reads.
    createdAt: null,
    expiresAt: expiresAt.toISOString(),
    // A brand-new report has no confirmations yet; the field is not written to
    // the document until the first incidents.confirm bumps it.
    confirmationCount: 0,
    // Nor any "it's gone" votes: neither field is written until the first
    // incidents.reportCleared, so a fresh report is never faded.
    clearedCount: 0,
    reportedCleared: false,
  };
});
