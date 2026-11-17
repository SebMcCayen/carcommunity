import { HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { FieldValue, Timestamp, type Firestore } from 'firebase-admin/firestore';
import { z } from 'zod';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { readFeatureFlagsSnapshot, flagFromSnapshot } from '../shared/featureFlags';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { geoCellKey } from '../incidents/incidents-core';
import { isRestricted, toUserAccessState } from '../shared/access';

export const EPIC_LOCATION_MAX_AGE_MS = 24 * 60 * 60 * 1000;
export const EPIC_LOCATION_MIN_UPDATE_MS = 5 * 60 * 1000;
const schema = z
  .object({
    location: z
      .object({
        latitude: z.number().min(-90).max(90),
        longitude: z.number().min(-180).max(180),
        recordedAtMillis: z.number().finite().nonnegative(),
      })
      .strict()
      .nullable(),
  })
  .strict();
type LocationInput = z.infer<typeof schema>['location'];

/** One overwritten private position, never a trail and never a discovery marker. */
export async function saveEpicLocation(
  store: Firestore,
  uid: string,
  location: LocationInput,
  nowMs = Date.now(),
) {
  if (
    location &&
    (nowMs - location.recordedAtMillis > EPIC_LOCATION_MIN_UPDATE_MS ||
      location.recordedAtMillis > nowMs + 60000)
  ) {
    throw new HttpsError('invalid-argument', 'A fresh location is required.');
  }
  const ref = store.doc(`userPrivate/${uid}`);
  await store.runTransaction(async (tx) => {
    const [user, snapshot] = await tx.getAll(store.doc(`users/${uid}`), ref);
    if (!user!.exists || isRestricted(toUserAccessState(user!.data()))) return;
    const current = snapshot!.get('epicNotificationLocation');
    if (!location || snapshot!.get('notificationPreferences.epic_crown.inApp') === false) {
      if (current) tx.update(ref, { epicNotificationLocation: FieldValue.delete() });
      return;
    }
    const previousAt =
      current?.recordedAt instanceof Timestamp ? current.recordedAt.toMillis() : -Infinity;
    // Reconnects, UI recomposition and stale devices cannot overwrite a newer fix.
    if (location.recordedAtMillis <= previousAt || nowMs - previousAt < EPIC_LOCATION_MIN_UPDATE_MS)
      return;
    tx.set(
      ref,
      {
        epicNotificationLocation: {
          latitude: location.latitude,
          longitude: location.longitude,
          geoCell: geoCellKey(location.latitude, location.longitude),
          recordedAt: Timestamp.fromMillis(location.recordedAtMillis),
          expiresAt: Timestamp.fromMillis(location.recordedAtMillis + EPIC_LOCATION_MAX_AGE_MS),
        },
      },
      { merge: true },
    );
  });
}

export const updateEpicLocation = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request) => {
    const actor = await requireActiveActor(request);
    const parsed = schema.safeParse(request.data);
    if (!parsed.success)
      throw new HttpsError(
        'invalid-argument',
        'Expected { location: { latitude, longitude, recordedAtMillis } | null }.',
      );
    if (parsed.data.location !== null) {
      const flags = await readFeatureFlagsSnapshot();
      if (!flagFromSnapshot(flags, 'crownHunt') || !flagFromSnapshot(flags, 'crownHuntSpawn'))
        return { updated: false };
    }
    await saveEpicLocation(db, actor.uid, parsed.data.location);
    return { updated: true };
  },
);

/** Clears only the expired nested field, never the user's private document. */
export async function clearExpiredEpicLocations(store: Firestore, nowMs: number): Promise<number> {
  let cleared = 0;
  for (;;) {
    const page = await store
      .collection('userPrivate')
      .where('epicNotificationLocation.expiresAt', '<=', Timestamp.fromMillis(nowMs))
      .limit(100)
      .get();
    if (page.empty) return cleared;
    for (const snapshot of page.docs) {
      const removed = await store.runTransaction(async (tx) => {
        const current = await tx.get(snapshot.ref);
        const expiry = current.get('epicNotificationLocation.expiresAt');
        if (!(expiry instanceof Timestamp) || expiry.toMillis() > nowMs) return false;
        tx.update(snapshot.ref, { epicNotificationLocation: FieldValue.delete() });
        return true;
      });
      if (removed) cleared++;
    }
  }
}
