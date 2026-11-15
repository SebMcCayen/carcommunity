import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp, FieldValue, type Firestore } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { digest } from './core';
import { visible, eraseReactionRecord } from './engine';

/** Queue only lifecycle transitions, never ordinary count updates. Cleanup is independent of the feature flag. */
export function parentReactionCleanup(database: Firestore, document: string) {
  return onDocumentWritten(
    { document, region: 'europe-west1', retry: true, maxInstances: 2 },
    async (event) => {
      if (
        !event.data?.before.exists ||
        event.data.before.get('reactionCatalogVersion') !== '18.0' ||
        (event.data.after.exists && visible(event.data.after))
      )
        return;
      const before = event.data.before,
        time = before.get('createdAt');
      if (!(time instanceof Timestamp)) return;
      const parentPath = before.ref.path,
        parentEpoch = `${time.seconds}:${time.nanoseconds}`;
      await database
        .doc(`chatReactionCleanup/${digest(parentPath + '\0' + parentEpoch)}`)
        .set({ parentPath, parentEpoch, queuedAt: FieldValue.serverTimestamp() });
    },
  );
}
export async function cleanReactionParents(database: Firestore): Promise<void> {
  const jobs = await database.collection('chatReactionCleanup').limit(5).get();
  for (const job of jobs.docs) {
    const club = job.get('kind') === 'club';
    if (club) {
      const root = await database.doc(`driverClubs/${job.get('roomId')}`).get();
      if (root.exists && root.get('status') !== 'archived') {
        await job.ref.delete();
        continue;
      }
    }
    const query = club
      ? database
          .collection('chatMessageReactions')
          .where('roomKey', '==', `club:${job.get('roomId')}`)
      : database
          .collection('chatMessageReactions')
          .where('parentPath', '==', job.get('parentPath'))
          .where('parentEpoch', '==', job.get('parentEpoch'));
    const rows = await query.limit(50).get();
    for (const row of rows.docs)
      await eraseReactionRecord(database, row.ref.path, club ? undefined : job.get('parentEpoch'));
    if (rows.size < 50) await job.ref.delete();
  }
}
export const communityRemoved = parentReactionCleanup(
  db,
  'communityChat/{roomId}/messages/{messageId}',
);
export const convoyRemoved = parentReactionCleanup(db, 'convoyChats/{roomId}/messages/{messageId}');
export const dmRemoved = parentReactionCleanup(db, 'conversations/{roomId}/messages/{messageId}');
export const clubRemoved = parentReactionCleanup(db, 'driverClubs/{roomId}/messages/{messageId}');
export const eventRemoved = parentReactionCleanup(db, 'events/{roomId}/messages/{messageId}');
export const cleanup = onSchedule(
  {
    schedule: 'every 60 minutes',
    region: 'europe-west1',
    timeoutSeconds: 540,
    maxInstances: 1,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    memory: '256MiB',
  },
  async () => cleanReactionParents(db),
);

export const clubArchived = onDocumentWritten(
  { document: 'driverClubs/{roomId}', region: 'europe-west1', retry: true, maxInstances: 2 },
  async (event) => {
    if (
      !event.data?.before.exists ||
      event.data.before.get('schemaVersion') !== 2 ||
      event.data.before.get('status') !== 'active'
    )
      return;
    if (event.data.after.exists && event.data.after.get('status') !== 'archived') return;
    const roomId = event.params.roomId;
    await db
      .doc(`chatReactionCleanup/club_${digest(roomId)}`)
      .set({ kind: 'club', roomId, queuedAt: FieldValue.serverTimestamp() });
  },
);
