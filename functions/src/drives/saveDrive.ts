/**
 * drives.save — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-save`.
 *
 * Saves a completed drive: validates the submitted recording, computes
 * distance/duration/average speed/maximum speed server-side
 * (drive-calculations.ts — the client never writes stats) plus a ~64-point
 * route thumbnail (route-thumbnail.ts), and creates `rides/{rideId}`. The client then
 * uploads the compressed route file and map preview to the canonical
 * Cloud Storage paths returned in the response (`rideRoutes/{uid}/{rideId}/`,
 * owner+member-gated by storage rules).
 *
 * Member-only (legacy parity: free users cannot save drives; drives saved
 * during a previous membership remain listable/deletable). Idempotent per
 * sourceSessionId: a retry returns the existing drive instead of duplicating
 * it (legacy sourceLiveLocationSessionId dedupe).
 *
 * Maximum speed IS stored and returned (`maxSpeedMetersPerSecond`) — this file
 * previously declared "No top-speed field is ever stored or returned", reversed
 * by an explicit product decision (2026-07); see drives-core.ts. Storing it does
 * not make it a game: no record, no ranking, no comparison between drives.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import {
  buildRideDocument,
  computeDriveStats,
  computeRouteThumbnail,
  guardDriveTimes,
  guardRoutePoints,
  parseSaveDriveInput,
  ridePreviewPath,
  rideRoutePath,
  type DriveStats,
} from './drives-core';

/**
 * The saved drive's server-computed stats and storage paths.
 *
 * `routeThumbnail` is deliberately NOT returned: it is derived from points the
 * caller just sent, so echoing a few hundred bytes of polyline back tells the
 * client nothing it could not recompute, and the History list reads it off the
 * ride document anyway.
 */
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
    const stats = computeDriveStats(input);
    // Derived once, here, from points the client already sent: the History list
    // then draws the drive's shape straight off the document it already reads.
    const routeThumbnail = computeRouteThumbnail(input);

    // Idempotency: a sourceSessionId maps to a DETERMINISTIC document ID, and
    // the create runs in a transaction — concurrent retries serialize on the
    // same document instead of racing a query-then-create into duplicates.
    const rideRef = input.sourceSessionId
      ? ridesRef.doc(`${actor.uid}_${input.sourceSessionId}`)
      : ridesRef.doc();

    const alreadySaved = await db.runTransaction(async (tx) => {
      const existing = await tx.get(rideRef);
      if (existing.exists) {
        return true;
      }
      tx.set(
        rideRef,
        buildRideDocument(
          input,
          { userId: actor.uid, rideId: rideRef.id, stats, routeThumbnail },
          () => FieldValue.serverTimestamp(),
        ),
      );
      return false;
    });

    if (alreadySaved) {
      const data = (await rideRef.get()).data()!;
      return {
        rideId: rideRef.id,
        durationSeconds: data.durationSeconds as number,
        distanceMeters: (data.distanceMeters as number | null) ?? null,
        averageSpeedMetersPerSecond: (data.averageSpeedMetersPerSecond as number | null) ?? null,
        // Absent on drives saved before maxSpeedMetersPerSecond existed (there
        // is no backfill), which reads as null exactly like a summary-only save.
        maxSpeedMetersPerSecond: (data.maxSpeedMetersPerSecond as number | null) ?? null,
        routePath: data.routePath as string,
        previewImagePath: data.previewImagePath as string,
        alreadySaved: true,
      };
    }

    return {
      rideId: rideRef.id,
      ...stats,
      routePath: rideRoutePath(actor.uid, rideRef.id),
      previewImagePath: ridePreviewPath(actor.uid, rideRef.id),
      alreadySaved: false,
    };
  },
);
