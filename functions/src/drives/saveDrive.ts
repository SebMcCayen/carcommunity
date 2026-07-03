/**
 * drives.save — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-save`.
 *
 * Saves a completed drive: validates the submitted recording, computes
 * distance/duration/average speed server-side (drive-calculations.ts — the
 * client never writes stats), and creates `rides/{rideId}`. The client then
 * uploads the compressed route file and map preview to the canonical
 * Cloud Storage paths returned in the response (`rideRoutes/{uid}/{rideId}/`,
 * owner+member-gated by storage rules).
 *
 * Member-only (legacy parity: free users cannot save drives; drives saved
 * during a previous membership remain listable/deletable). Idempotent per
 * sourceSessionId: a retry returns the existing drive instead of duplicating
 * it (legacy sourceLiveLocationSessionId dedupe).
 *
 * No top-speed field is ever stored or returned.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import {
  buildRideDocument,
  computeDriveStats,
  guardDriveTimes,
  guardRoutePoints,
  parseSaveDriveInput,
  ridePreviewPath,
  rideRoutePath,
  type DriveStats,
} from './drives-core';

export interface SaveDriveResponse extends DriveStats {
  rideId: string;
  routePath: string;
  previewImagePath: string;
  /** True when this call returned an already-saved drive (idempotent retry). */
  alreadySaved: boolean;
}

export const saveDrive = onCall(
  {
    region: 'europe-west1',
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<SaveDriveResponse> => {
    const actor = await requireMemberActor(request);

    const parsed = parseSaveDriveInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;

    const timesGuard = guardDriveTimes(input.startedAt, input.endedAt);
    if (!timesGuard.ok) {
      throw new HttpsError(timesGuard.code, timesGuard.message);
    }
    const pointsGuard = guardRoutePoints(input.routePoints);
    if (!pointsGuard.ok) {
      throw new HttpsError(pointsGuard.code, pointsGuard.message);
    }

    const ridesRef = db.collection('rides');

    // Idempotency: a retry with the same sourceSessionId returns the drive
    // that was already saved for this recording.
    if (input.sourceSessionId) {
      const existing = await ridesRef
        .where('userId', '==', actor.uid)
        .where('sourceSessionId', '==', input.sourceSessionId)
        .limit(1)
        .get();
      const docSnap = existing.docs[0];
      if (docSnap) {
        const data = docSnap.data();
        return {
          rideId: docSnap.id,
          durationSeconds: data.durationSeconds as number,
          distanceMeters: (data.distanceMeters as number | null) ?? null,
          averageSpeedMetersPerSecond:
            (data.averageSpeedMetersPerSecond as number | null) ?? null,
          routePath: data.routePath as string,
          previewImagePath: data.previewImagePath as string,
          alreadySaved: true,
        };
      }
    }

    const stats = computeDriveStats(input);
    const rideRef = ridesRef.doc();
    await rideRef.set(
      buildRideDocument(input, { userId: actor.uid, rideId: rideRef.id, stats }, () =>
        FieldValue.serverTimestamp(),
      ),
    );

    return {
      rideId: rideRef.id,
      ...stats,
      routePath: rideRoutePath(actor.uid, rideRef.id),
      previewImagePath: ridePreviewPath(actor.uid, rideRef.id),
      alreadySaved: false,
    };
  },
);
