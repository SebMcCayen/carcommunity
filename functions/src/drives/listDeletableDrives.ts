/**
 * drives.listDeletable — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-listDeletable`.
 *
 * Owner-only deletion inventory. After a downgrade, drives beyond the caller's
 * tier window (Community past the newest 5, Plus older than 90 days) are
 * omitted by drives.listHistory, so the user has no rideId with which to delete
 * them. This returns the ids of ALL owned drives regardless of tier window —
 * MINIMAL fields only (rideId, createdAtMillis, title, startedAtMillis) so the
 * client can offer "delete this drive" for retained-but-hidden drives. It is a
 * deletion index, deliberately NOT a stats or history bypass: it exposes no
 * distances, speeds, durations, route paths/thumbnails, images or session ids.
 *
 * No tier or membership gate (parity with drives.delete: a lapsed member may
 * still clean up drives saved during a previous membership). Same region/App
 * Check/actor gate as listHistory (requireActiveActor). Rules are intentionally
 * unchanged in this slice.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import {
  FieldPath,
  Timestamp,
  type DocumentData,
  type DocumentSnapshot,
  type Query,
} from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import {
  deletableDrivesPageSize,
  parseListDeletableDrivesInput,
  type DeletableDriveItem,
  type ListDeletableDrivesResponse,
} from './listDeletableDrives-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

function timestampMillis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

function boundedText(value: unknown, maxLength: number): string | null {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength ? value : null;
}

function toDeletableDriveItem(doc: DocumentSnapshot<DocumentData>): DeletableDriveItem | null {
  const data = doc.data();
  if (!data) return null;
  const createdAtMillis = timestampMillis(data.createdAt);
  if (createdAtMillis == null) return null;
  return {
    rideId: doc.id,
    createdAtMillis,
    title: boundedText(data.title, 200),
    startedAtMillis: timestampMillis(data.startedAt),
  };
}

export const listDeletableDrives = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ListDeletableDrivesResponse> => {
    const actor = await requireActiveActor(request);
    const parsed = parseListDeletableDrivesInput(request.data);
    if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);

    let query: Query<DocumentData> = db
      .collection('rides')
      .where('userId', '==', actor.uid)
      .orderBy('createdAt', 'desc')
      .orderBy(FieldPath.documentId(), 'desc');

    if (parsed.input.cursorRideId) {
      const cursor = await db.collection('rides').doc(parsed.input.cursorRideId).get();
      if (
        !cursor.exists ||
        cursor.data()?.userId !== actor.uid ||
        timestampMillis(cursor.data()?.createdAt) == null
      ) {
        // Do not reveal whether a cursor names another user's private drive.
        throw new HttpsError('not-found', 'Drive cursor not found.');
      }
      query = query.startAfter(cursor);
    }

    const pageSize = deletableDrivesPageSize(parsed.input.pageSize);
    // One look-ahead document decides hasMore without a second query. Project
    // only the fields toDeletableDriveItem reads (plus the ordering field
    // createdAt) so this never fetches the heavy route/stats fields — the
    // rideId comes from doc.id, which a projection always exposes.
    const snapshot = await query
      .select('createdAt', 'title', 'startedAt')
      .limit(pageSize + 1)
      .get();
    const hasMore = snapshot.size > pageSize;
    const visibleDocs = snapshot.docs.slice(0, pageSize);
    const drives = visibleDocs
      .map(toDeletableDriveItem)
      .filter((drive): drive is DeletableDriveItem => drive != null);

    return {
      drives,
      hasMore,
      nextCursorRideId:
        hasMore && visibleDocs.length > 0 ? visibleDocs[visibleDocs.length - 1]!.id : null,
    };
  },
);
