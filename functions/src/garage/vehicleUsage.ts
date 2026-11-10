import { Timestamp, type Transaction } from 'firebase-admin/firestore';
import { z } from 'zod';
import { HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { db } from '../firebase';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { requireActiveActor } from '../shared/memberActor';
import { rankVehicleUsage, VEHICLE_USAGE_WINDOW_MS, type VehicleUse } from './vehicleUsage-core';

const usageEvents = (uid: string) => db.doc(`userPrivate/${uid}`).collection('vehicleUsageEvents');
const listInput = z.object({}).strict();

export async function writeVehicleUse(
  tx: Transaction,
  uid: string,
  kind: 'solo' | 'convoy',
  sourceId: string,
  vehicleId: string,
  startedAt: Timestamp,
  requestId?: string,
): Promise<void> {
  const events = usageEvents(uid);
  const tracking = events.doc('tracking');
  const event = events.doc(`${kind}_${requestId ?? sourceId}`);
  // Keep the original use if the same start request is replayed. The marker
  // stays after the events age out so recent saved convoy rides do not become
  // historical seeds again in a quiet period.
  const first = await tx.get(tracking);
  if (requestId && (await tx.get(event)).exists) return;
  const firstTrackedAt = first.get('firstTrackedAt');
  if (!(firstTrackedAt instanceof Timestamp) || startedAt.toMillis() < firstTrackedAt.toMillis()) {
    tx.set(tracking, { firstTrackedAt: startedAt });
  }
  tx.set(event, {
    vehicleId,
    startedAt,
    expiresAt: Timestamp.fromMillis(startedAt.toMillis() + VEHICLE_USAGE_WINDOW_MS),
  });
}

function readUse(data: FirebaseFirestore.DocumentData): VehicleUse | null {
  if (
    typeof data.vehicleId !== 'string' ||
    !data.vehicleId ||
    !(data.startedAt instanceof Timestamp)
  ) {
    return null;
  }
  return { vehicleId: data.vehicleId, startedAtMs: data.startedAt.toMillis() };
}

export const rankedDrivingVehicleIds = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 15,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<{ vehicleIds: string[] }> => {
    const actor = await requireActiveActor(request);
    if (!listInput.safeParse(request.data ?? {}).success) {
      throw new HttpsError('invalid-argument', 'Expected an empty request.');
    }

    const nowMs = Date.now();
    const cutoff = Timestamp.fromMillis(nowMs - VEHICLE_USAGE_WINDOW_MS);
    const events = usageEvents(actor.uid);
    return db.runTransaction(
      async (tx) => {
        // Read the cutoff and events from one snapshot so the very first
        // recorded start cannot overlap with its historical ride on this read.
        const [tracking, confirmed] = await Promise.all([
          tx.get(events.doc('tracking')),
          tx.get(
            events
              .where('startedAt', '>=', cutoff)
              .where('startedAt', '<=', Timestamp.fromMillis(nowMs))
              .select('startedAt', 'vehicleId'),
          ),
        ]);

        const uses = confirmed.docs.map((doc) => readUse(doc.data())).filter((use) => use !== null);
        // The time boundary also separates convoy starts from their saved rides,
        // which refer to a separate live-session ID.
        const firstTrackedAt = tracking.get('firstTrackedAt');
        const firstTrackedAtMs =
          firstTrackedAt instanceof Timestamp ? firstTrackedAt.toMillis() : nowMs + 1;
        if (firstTrackedAtMs > cutoff.toMillis()) {
          const history = await tx.get(
            db
              .collection('rides')
              .where('userId', '==', actor.uid)
              .where('createdAt', '>=', cutoff)
              .orderBy('createdAt', 'desc')
              .select('startedAt', 'vehicleId'),
          );
          for (const doc of history.docs) {
            const use = readUse(doc.data());
            if (use && use.startedAtMs < firstTrackedAtMs) uses.push(use);
          }
        }
        return { vehicleIds: rankVehicleUsage(uses, nowMs) };
      },
      { readOnly: true },
    );
  },
);
