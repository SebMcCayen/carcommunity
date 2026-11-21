import { Timestamp } from 'firebase-admin/firestore';
import { onCall } from '../access/callable';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  readBadgeCounters,
  readProfileStreak,
  streakDayKey,
  type BadgeCounters,
} from './badge-tiers';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

/** Read-only owner projection. Award counters keep their historical semantics. */
export type MyBadgeProgressResult = BadgeCounters & {
  currentDayStreak: number;
  currentVehiclesInGarage: number;
};

export const getMyProgress = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<MyBadgeProgressResult> => {
    const { uid } = await requireActiveActor(request);
    const [progress, lifecycle, garage] = await Promise.all([
      db.collection('badgeProgress').doc(uid).get(),
      db.collection('userLifecycle').doc(uid).get(),
      db.collection('vehicles').where('userId', '==', uid).count().get(),
    ]);
    const data = progress.data();
    const lastLogin = lifecycle.get('lastLoginAt');
    const streak = readProfileStreak(
      data,
      lastLogin instanceof Timestamp ? streakDayKey(lastLogin.toDate()) : null,
      streakDayKey(new Date()),
    );
    const counters = readBadgeCounters(data);
    const currentVehiclesInGarage = garage.data().count;
    return {
      ...counters,
      ...streak,
      vehiclesInGarage: Math.max(counters.vehiclesInGarage, currentVehiclesInGarage),
      currentVehiclesInGarage,
    };
  },
);
