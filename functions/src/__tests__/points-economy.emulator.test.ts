/**
 * Points-economy emulator integration tests (Phase 20).
 *
 * These need the full Emulator Suite (auth + functions + firestore + database)
 * and therefore a JDK, so they are excluded from the default `vitest run` and
 * live behind vitest.emulator.config.ts. Run them with
 *   pnpm --filter @carcommunity/functions emulators:test
 * which is exactly what the test-firebase-rules workflow does.
 *
 * What they prove end-to-end (the pure maths is covered exhaustively by
 * points-economy-core.test.ts):
 *  - points.recordDailyOpen credits once per Europe/Stockholm day, is
 *    idempotent on a repeat call, and refuses client-supplied arguments;
 *  - the drives.save -> points-onDriveSaved trigger awards drive_5km once for
 *    a >= 5 km drive and never for a shorter one;
 *  - the garage.addVehicle -> points-onVehicleCreated trigger awards
 *    garage_first_car exactly once, ever;
 *  - the incidents.confirm -> points-onIncidentConfirmed trigger pays the
 *    REPORTER, not the confirmer;
 *  - events.checkIn refuses a single ping, refuses a sample outside the
 *    150 m fence, and verifies attendance on two samples ten minutes apart —
 *    which then awards event_attend_verified through
 *    points-onAttendanceVerified;
 *  - the per-rule daily limits and the DAILY_POINTS_CAP hold.
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, FirebaseError, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signInWithEmailAndPassword,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import {
  Timestamp,
  getFirestore as getAdminFirestore,
} from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  DAILY_POINTS_CAP,
  attendanceDocId,
  economyIdempotencyKey,
  stockholmDayKey,
} from '../points/points-economy-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'points-economy-emulator');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(
  read: () => Promise<T | undefined>,
  timeoutMs = 30_000,
  intervalMs = 250,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

async function callableErrorCode(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    return 'no-error';
  } catch (error) {
    if (error instanceof FirebaseError) return error.code;
    throw error;
  }
}

async function createProvisionedUser(prefix: string): Promise<TestUser> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  await adminDb.collection('users').doc(uid).set({ activeMember: true }, { merge: true });
  return { uid, email, password };
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

/** The ledger entry for a deterministic economy key, or undefined. */
async function ledgerEntry(
  uid: string,
  key: string,
): Promise<FirebaseFirestore.DocumentData | undefined> {
  const snap = await adminDb.collection('pointsLedger').doc(uid).collection('entries').doc(key).get();
  return snap.exists ? snap.data() : undefined;
}

async function awaitLedgerEntry(uid: string, key: string): Promise<FirebaseFirestore.DocumentData> {
  return pollUntil(() => ledgerEntry(uid, key));
}

async function entryCount(uid: string): Promise<number> {
  const snap = await adminDb.collection('pointsLedger').doc(uid).collection('entries').count().get();
  return snap.data().count;
}

/**
 * Entries for ONE source. Since the §7 badge ladders landed, a single domain
 * event can legitimately credit twice from different systems (adding a first
 * car pays garage_first_car AND unlocks Samlare Brons, whose threshold is 1),
 * so a "once and never again" assertion has to name the source it guards
 * rather than counting the whole ledger.
 */
async function entryCountBySource(uid: string, source: string): Promise<number> {
  const snap = await adminDb
    .collection('pointsLedger')
    .doc(uid)
    .collection('entries')
    .where('source', '==', source)
    .count()
    .get();
  return snap.data().count;
}

/**
 * A valid `garage.addVehicle` payload — the callable's schema is strict, so
 * the powertrain is required and the year field is `modelYear`
 * (contracts/schemas/garage.schema.json).
 */
const VEHICLE = (overrides: { make: string; model: string; modelYear: number }) => ({
  powertrain: 'petrol',
  ...overrides,
});

/** Coordinates for the fixture event / drives. */
const LAT = 57.4879;
const LON = 12.0763;
const north = (metres: number) => ({ latitude: LAT + metres / 111_320, longitude: LON });

let member: TestUser;
let organiser: TestUser;
let reporter: TestUser;
let confirmer: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'points-economy-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  member = await createProvisionedUser('pe-member');
  organiser = await createProvisionedUser('pe-organiser');
  await adminAuth.setCustomUserClaims(organiser.uid, { admin: true });
  await adminDb.collection('users').doc(organiser.uid).set({ role: 'admin' }, { merge: true });
  reporter = await createProvisionedUser('pe-reporter');
  confirmer = await createProvisionedUser('pe-confirmer');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('points.recordDailyOpen', () => {
  it('credits once per local day and is idempotent on a repeat call', async () => {
    const user = await createProvisionedUser('pe-open');
    await signInAs(user);

    const first = (await call('points-recordDailyOpen', {})).data as {
      pointsAwarded: number;
      streak: number;
      multiplier: number;
      alreadyCountedToday: boolean;
      day: string;
    };
    expect(first.pointsAwarded).toBe(5);
    expect(first.streak).toBe(1);
    expect(first.multiplier).toBe(1);
    expect(first.alreadyCountedToday).toBe(false);
    expect(first.day).toBe(stockholmDayKey(new Date()));

    const second = (await call('points-recordDailyOpen', {})).data as {
      pointsAwarded: number;
      alreadyCountedToday: boolean;
    };
    expect(second.pointsAwarded).toBe(0);
    expect(second.alreadyCountedToday).toBe(true);

    // Exactly ONE ledger entry, on the deterministic per-day key.
    const key = economyIdempotencyKey('daily_open', user.uid, first.day)!;
    const entry = await awaitLedgerEntry(user.uid, key);
    expect(entry.amount).toBe(5);
    expect(entry.transactionType).toBe('earn');
    expect(await entryCount(user.uid)).toBe(1);
  });

  it('refuses a client-supplied point value, streak or day', async () => {
    const user = await createProvisionedUser('pe-forge');
    await signInAs(user);
    expect(await callableErrorCode(call('points-recordDailyOpen', { points: 9999 }))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(call('points-recordDailyOpen', { streak: 7 }))).toBe(
      'functions/invalid-argument',
    );
    expect(
      await callableErrorCode(call('points-recordDailyOpen', { day: '2020-01-01' })),
    ).toBe('functions/invalid-argument');
    // Nothing was written by any of the rejected calls.
    expect(await entryCount(user.uid)).toBe(0);
  });

  it('requires authentication', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('points-recordDailyOpen', {}))).toBe(
      'functions/unauthenticated',
    );
  });
});

describe('drive_5km via the rides trigger', () => {
  const saveDrive = async (distanceMetres: number, sessionId: string) => {
    const startedAt = new Date(Date.now() - 30 * 60_000);
    // ~1 point per 100 m so drives.save computes the distance server-side.
    const steps = Math.max(2, Math.round(distanceMetres / 100));
    const routePoints = Array.from({ length: steps + 1 }, (_, i) => ({
      ...north(i * (distanceMetres / steps)),
      timestampMs: startedAt.getTime() + i * 10_000,
    }));
    return (
      await call('drives-save', {
        startedAt: startedAt.toISOString(),
        endedAt: new Date(startedAt.getTime() + steps * 10_000).toISOString(),
        routePoints,
        sourceSessionId: sessionId,
      })
    ).data as { rideId: string; distanceMeters: number | null };
  };

  it('awards 15 KP once for a >= 5 km drive and nothing for a short one', async () => {
    const user = await createProvisionedUser('pe-drive');
    await signInAs(user);

    const short = await saveDrive(1_500, `short-${Date.now()}`);
    expect(short.distanceMeters ?? 0).toBeLessThan(5_000);

    const long = await saveDrive(6_000, `long-${Date.now()}`);
    expect(long.distanceMeters ?? 0).toBeGreaterThanOrEqual(5_000);

    const key = economyIdempotencyKey('drive_5km', long.rideId)!;
    const entry = await awaitLedgerEntry(user.uid, key);
    expect(entry.amount).toBe(15);
    expect(entry.source).toBe('system');
    // The short drive earned nothing: exactly one entry exists.
    expect(await entryCount(user.uid)).toBe(1);

    // A repeat save with the same sourceSessionId is idempotent at the drives
    // layer AND cannot produce a second award.
    await saveDrive(6_000, long.rideId.split('_').slice(1).join('_'));
    expect(await entryCount(user.uid)).toBe(1);
  });

  it('credits the weekly driving total but never the daily total (#861)', async () => {
    const user = await createProvisionedUser('pe-drive-nodaily');
    await signInAs(user);
    const day = stockholmDayKey(new Date());

    const long = await saveDrive(6_000, `nodaily-${Date.now()}`);
    const entry = await awaitLedgerEntry(user.uid, economyIdempotencyKey('drive_5km', long.rideId)!);
    expect(entry.amount).toBe(15);

    // The drive credited the WEEKLY driving total...
    const weekly = await adminDb
      .collection('pointsWeeklyDriving')
      .where('userId', '==', user.uid)
      .get();
    expect(weekly.empty).toBe(false);
    expect(weekly.docs[0]!.data().total).toBe(15);

    // ...but left the DAILY total untouched: driving is decoupled from the
    // daily cap in BOTH directions, so it consumes none of the headroom the
    // daily cap leaves for non-driving rules (issue #861).
    const daily = await adminDb
      .collection('pointsDailyTotals')
      .doc(`${user.uid}__${day}`)
      .get();
    expect(daily.exists).toBe(false);
  });

  it('caps drive_5km at 2 per local day', async () => {
    const user = await createProvisionedUser('pe-drivecap');
    await signInAs(user);
    const rides: string[] = [];
    for (let i = 0; i < 3; i += 1) {
      const ride = await saveDrive(6_000, `cap-${Date.now()}-${i}`);
      rides.push(ride.rideId);
    }
    await awaitLedgerEntry(user.uid, economyIdempotencyKey('drive_5km', rides[1]!)!);
    // Give the third trigger time to run and be refused.
    await new Promise((resolve) => setTimeout(resolve, 3_000));
    expect(await ledgerEntry(user.uid, economyIdempotencyKey('drive_5km', rides[2]!)!)).toBeUndefined();

    const counter = await adminDb
      .collection('pointsRuleCounters')
      .doc(`${user.uid}__drive_5km__${stockholmDayKey(new Date())}`)
      .get();
    expect(counter.data()?.count).toBe(2);
  });
});

describe('garage_first_car via the vehicles trigger', () => {
  it('awards 25 KP for the first car and never again', async () => {
    const user = await createProvisionedUser('pe-garage');
    await signInAs(user);

    await call('garage-addVehicle', VEHICLE({ make: 'Volvo', model: '240', modelYear: 1989 }));
    const key = economyIdempotencyKey('garage_first_car', user.uid)!;
    const entry = await awaitLedgerEntry(user.uid, key);
    expect(entry.amount).toBe(25);
    expect(entry.source).toBe('garage');

    await call('garage-addVehicle', VEHICLE({ make: 'Saab', model: '900', modelYear: 1993 }));
    await new Promise((resolve) => setTimeout(resolve, 3_000));
    // Only the garage award is guarded here. The ledger also carries a
    // separate `badge` entry for Samlare Brons (threshold 1 vehicle), which is
    // a different award from a different system, not a double-credit.
    expect(await entryCountBySource(user.uid, 'garage')).toBe(1);
  });
});

describe('incident_report_confirmed via the confirmations trigger', () => {
  it('pays the REPORTER when another member confirms', async () => {
    await signInAs(reporter);
    // incidents.report answers with the incident RECORD — the id field is
    // `id`, not `incidentId` (functions/src/incidents/report.ts).
    const reported = (
      await call('incidents-report', {
        type: 'hazard',
        latitude: LAT,
        longitude: LON,
      })
    ).data as { id: string };
    expect(typeof reported.id).toBe('string');

    await signInAs(confirmer);
    await call('incidents-confirm', { incidentId: reported.id });

    const key = economyIdempotencyKey(
      'incident_report_confirmed',
      reported.id,
      confirmer.uid,
    )!;
    const entry = await awaitLedgerEntry(reporter.uid, key);
    expect(entry.amount).toBe(15);
    // The confirmer earns nothing for confirming.
    expect(await ledgerEntry(confirmer.uid, key)).toBeUndefined();

    // A repeat confirmation writes nothing new (incidents.confirm is
    // idempotent, so the trigger never fires a second time).
    await call('incidents-confirm', { incidentId: reported.id });
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    expect(await entryCount(reporter.uid)).toBe(1);
  });
});

describe('events.checkIn — geofence + dwell', () => {
  /**
   * Publishes an event whose window is [startsAt, endsAt].
   *
   * events.publish refuses a draft whose start time has already passed
   * (guardPublishable in events-core.ts), so an already-running meet cannot be
   * created directly. The draft is therefore created and published with a
   * FUTURE start, and the real window is written afterwards with the Admin SDK
   * — the emulator equivalent of publishing on Friday and checking in on
   * Sunday. startsAt/endsAt live on the teaser document (buildEventDocuments),
   * which is exactly where events.checkIn reads them from.
   */
  async function publishEvent(startsAt: Date, endsAt: Date): Promise<string> {
    await signInAs(organiser);
    const publishSafeStart = new Date(Date.now() + 60 * 60_000);
    const created = (
      await call('events-create', {
        title: 'Söndagsträff',
        approximateArea: 'Kungsbacka',
        startsAt: publishSafeStart.toISOString(),
        endsAt: new Date(publishSafeStart.getTime() + 60 * 60_000).toISOString(),
        latitude: LAT,
        longitude: LON,
      })
    ).data as { eventId: string };
    await call('events-publish', { eventId: created.eventId });
    await adminDb.collection('events').doc(created.eventId).update({
      startsAt: Timestamp.fromDate(startsAt),
      endsAt: Timestamp.fromDate(endsAt),
    });
    return created.eventId;
  }

  it('needs two samples ten minutes apart inside the fence, then awards 50 KP', async () => {
    const startsAt = new Date(Date.now() - 20 * 60_000);
    const endsAt = new Date(Date.now() + 2 * 60 * 60_000);
    const eventId = await publishEvent(startsAt, endsAt);

    const user = await createProvisionedUser('pe-attend');
    await signInAs(user);

    // A single ping proves nothing.
    const first = (
      await call('events-checkIn', {
        eventId,
        latitude: LAT,
        longitude: LON,
        accuracyMeters: 8,
        capturedAt: new Date().toISOString(),
      })
    ).data as { result: string; verified: boolean };
    expect(first.result).toBe('recorded');
    expect(first.verified).toBe(false);

    // A sample 2 km away does not qualify.
    const away = (
      await call('events-checkIn', {
        eventId,
        ...north(2_000),
        accuracyMeters: 8,
        capturedAt: new Date().toISOString(),
      })
    ).data as { result: string };
    expect(away.result).toBe('outside_geofence');

    // Back-date the stored first sample by 11 minutes so the next live sample
    // clears the 10-minute spacing + dwell requirement. (The samples are
    // server-validated; only their stored timestamps are adjusted here, which
    // is the emulator equivalent of waiting eleven minutes.)
    const attendanceRef = adminDb
      .collection('eventAttendance')
      .doc(attendanceDocId(eventId, user.uid));
    const stored = (await attendanceRef.get()).data()!;
    await attendanceRef.update({
      samples: (stored.samples as Array<Record<string, unknown>>).map((sample) => ({
        ...sample,
        capturedAtMs: (sample.capturedAtMs as number) - 11 * 60_000,
      })),
    });

    const second = (
      await call('events-checkIn', {
        eventId,
        latitude: LAT,
        longitude: LON,
        accuracyMeters: 8,
        capturedAt: new Date().toISOString(),
      })
    ).data as { result: string; verified: boolean; dwellSeconds: number };
    expect(second.result).toBe('verified');
    expect(second.verified).toBe(true);
    expect(second.dwellSeconds).toBeGreaterThanOrEqual(600);

    const key = economyIdempotencyKey('event_attend_verified', eventId, user.uid)!;
    const entry = await awaitLedgerEntry(user.uid, key);
    expect(entry.amount).toBe(50);
    expect(entry.source).toBe('event');

    // Verification ALSO credits the attendance badge (first_event), which the
    // badges-onBadgeProgressWritten trigger may pay points for — so the total
    // ledger count after verification is not necessarily 1. Let those settle,
    // snapshot the count, then prove the RE-CHECK-IN adds nothing on top.
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    const beforeRecheck = await entryCount(user.uid);
    await call('events-checkIn', {
      eventId,
      latitude: LAT,
      longitude: LON,
      accuracyMeters: 8,
      capturedAt: new Date().toISOString(),
    });
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    // already_verified: no further event_attend_verified, and the badge is
    // already held, so the ledger is unchanged.
    expect(await entryCount(user.uid)).toBe(beforeRecheck);
  });

  /**
   * The verified-attendee tally is the gate on `event_host_success`, and it is
   * a THRESHOLD, so a corrupt NEGATIVE holds it under the bar rather than
   * granting headroom. Clamping the READ alone is not enough: with
   * `FieldValue.increment(1)` the server applies the increment to whatever is
   * really stored, so `-3` would persist as `-2` while the function returned
   * 1 — and since `counted/{uid}` is a one-shot claim, every attendee spent
   * climbing back to 0 is spent for good. This pins the STORED value.
   */
  it('repairs a corrupt attendee tally rather than incrementing it', async () => {
    const startsAt = new Date(Date.now() - 20 * 60_000);
    const eventId = await publishEvent(startsAt, new Date(Date.now() + 2 * 60 * 60_000));

    const countsRef = adminDb.collection('eventAttendanceCounts').doc(eventId);
    await countsRef.set({ eventId, verifiedCount: -3 });

    const user = await createProvisionedUser('pe-tally');
    await signInAs(user);

    const sample = { eventId, latitude: LAT, longitude: LON, accuracyMeters: 8 };
    await call('events-checkIn', { ...sample, capturedAt: new Date().toISOString() });

    // Back-date the stored sample so the next one clears the spacing rule.
    const attendanceRef = adminDb
      .collection('eventAttendance')
      .doc(attendanceDocId(eventId, user.uid));
    const storedDoc = (await attendanceRef.get()).data()!;
    await attendanceRef.update({
      samples: (storedDoc.samples as Array<Record<string, unknown>>).map((entry) => ({
        ...entry,
        capturedAtMs: (entry.capturedAtMs as number) - 11 * 60_000,
      })),
    });

    const verified = (
      await call('events-checkIn', { ...sample, capturedAt: new Date().toISOString() })
    ).data as { result: string };
    expect(verified.result).toBe('verified');

    // One verified attendee against a stored -3 must leave 1, not -2.
    const tally = await pollUntil(async () => {
      const value = (await countsRef.get()).data()?.verifiedCount;
      return typeof value === 'number' && value !== -3 ? value : undefined;
    });
    expect(tally).toBe(1);
  });

  it('refuses a sample outside the [start-30, end+30] window', async () => {
    const startsAt = new Date(Date.now() + 4 * 60 * 60_000);
    const eventId = await publishEvent(startsAt, new Date(startsAt.getTime() + 60 * 60_000));
    await signInAs(member);
    const response = (
      await call('events-checkIn', {
        eventId,
        latitude: LAT,
        longitude: LON,
        accuracyMeters: 8,
        capturedAt: new Date().toISOString(),
      })
    ).data as { result: string };
    expect(response.result).toBe('outside_window');
  });

  it('rejects a self-reported mock location as risk_review, crediting no dwell', async () => {
    const startsAt = new Date(Date.now() - 20 * 60_000);
    const eventId = await publishEvent(startsAt, new Date(Date.now() + 2 * 60 * 60_000));
    const user = await createProvisionedUser('pe-mock');
    await signInAs(user);

    // A fix reported as mock scores at the review threshold on its own, exactly
    // as a Kronjakt claim does — inside the fence and window is not enough.
    const response = (
      await call('events-checkIn', {
        eventId,
        latitude: LAT,
        longitude: LON,
        accuracyMeters: 8,
        isMockLocation: true,
        capturedAt: new Date().toISOString(),
      })
    ).data as { result: string; verified: boolean };
    expect(response.result).toBe('risk_review');
    expect(response.verified).toBe(false);

    // The rejected sample never joins `samples`, so it can contribute no dwell,
    // and the risk score/reasons stay off the owner-readable record.
    const stored = (
      await adminDb.collection('eventAttendance').doc(attendanceDocId(eventId, user.uid)).get()
    ).data();
    expect(stored?.samples ?? []).toEqual([]);
    expect(stored?.verified ?? false).toBe(false);
    expect(stored).not.toHaveProperty('lastRiskReasons');
  });

  it('refuses a forged dwell, distance or point value', async () => {
    const startsAt = new Date(Date.now() - 10 * 60_000);
    const eventId = await publishEvent(startsAt, new Date(startsAt.getTime() + 2 * 60 * 60_000));
    await signInAs(member);
    for (const forged of [{ dwellMs: 999_999 }, { points: 50 }, { verified: true }]) {
      expect(
        await callableErrorCode(
          call('events-checkIn', {
            eventId,
            latitude: LAT,
            longitude: LON,
            capturedAt: new Date().toISOString(),
            ...forged,
          }),
        ),
      ).toBe('functions/invalid-argument');
    }
  });
});

describe('the global daily cap', () => {
  it('pays the partial remainder and then nothing', async () => {
    const user = await createProvisionedUser('pe-cap');
    // Pre-load the day's counter to 10 points below the cap.
    const day = stockholmDayKey(new Date());
    await adminDb
      .collection('pointsDailyTotals')
      .doc(`${user.uid}__${day}`)
      .set({ userId: user.uid, day, total: DAILY_POINTS_CAP - 10 });

    await signInAs(user);
    await call('garage-addVehicle', VEHICLE({ make: 'Volvo', model: 'V70', modelYear: 2004 }));

    const key = economyIdempotencyKey('garage_first_car', user.uid)!;
    const entry = await awaitLedgerEntry(user.uid, key);
    // garage_first_car is worth 25; only 10 were left.
    expect(entry.amount).toBe(10);
    expect(String(entry.description)).toContain('25 p');
    expect(String(entry.description)).toContain('10 p');

    // With the cap now exhausted, the daily open pays nothing but the streak
    // still advances.
    const open = (await call('points-recordDailyOpen', {})).data as {
      pointsAwarded: number;
      streak: number;
      dailyCapReached: boolean;
    };
    expect(open.pointsAwarded).toBe(0);
    expect(open.dailyCapReached).toBe(true);
    expect(open.streak).toBe(1);
    const streakDoc = await adminDb.collection('pointsStreaks').doc(user.uid).get();
    expect(streakDoc.data()?.streak).toBe(1);
    expect(streakDoc.data()?.lastOpenDay).toBe(day);

    // COUNTERS MOVE WITH THE ENTRY OR NOT AT ALL — the award engine's central
    // atomicity claim, asserted against the real Firestore rather than the
    // cap arithmetic alone. The capped-out open wrote no ledger entry, so it
    // must also have left the daily_open rule counter untouched (otherwise the
    // member would have burned today's single open on an award they were never
    // paid) and must not have moved the daily total past the ceiling.
    expect(
      await ledgerEntry(user.uid, economyIdempotencyKey('daily_open', user.uid, day)!),
    ).toBeUndefined();
    const openCounter = await adminDb
      .collection('pointsRuleCounters')
      .doc(`${user.uid}__daily_open__${day}`)
      .get();
    expect(openCounter.exists).toBe(false);
    const totals = await adminDb.collection('pointsDailyTotals').doc(`${user.uid}__${day}`).get();
    expect(totals.data()?.total).toBe(DAILY_POINTS_CAP);
  });
});

describe('Kronjakt crowns fold into the daily cap', () => {
  it('counts a crown award against DAILY_POINTS_CAP but not the driving cap', async () => {
    const user = await createProvisionedUser('pe-crown');
    const day = stockholmDayKey(new Date());
    // Write a crown-sourced ledger entry the way crownHunt.submitClaim does,
    // and let points-onLedgerEntryCreated fold it in.
    await adminDb
      .collection('pointsLedger')
      .doc(user.uid)
      .collection('entries')
      .doc(`crown-test-${Date.now()}`)
      .set({
        transactionType: 'earn',
        source: 'crown_hunt',
        amount: 120,
        balanceAfter: 120,
        description: 'Kronjakt: test',
        idempotencyKey: null,
        relatedEntityType: null,
        relatedEntityId: null,
        createdByUserId: null,
        createdAt: Timestamp.now(),
      });

    const total = await pollUntil(async () => {
      const snap = await adminDb.collection('pointsDailyTotals').doc(`${user.uid}__${day}`).get();
      const value = snap.data()?.total as number | undefined;
      return value === 120 ? value : undefined;
    });
    expect(total).toBe(120);

    // The weekly DRIVING counter is untouched by the crown.
    const weekly = await adminDb
      .collection('pointsWeeklyDriving')
      .where('userId', '==', user.uid)
      .get();
    expect(weekly.empty).toBe(true);
  });

  it('still pays a saved drive after crowns filled the daily cap (#861)', async () => {
    const user = await createProvisionedUser('pe-crown-drive');
    await signInAs(user);
    const day = stockholmDayKey(new Date());

    // Crowns collected during the drive have already pushed the day's total
    // PAST DAILY_POINTS_CAP (a single legendary is worth 500, and crowns are
    // never clipped). Preload the daily counter exactly as the crown fold
    // (points-onLedgerEntryCreated) would have left it after such a session.
    await adminDb
      .collection('pointsDailyTotals')
      .doc(`${user.uid}__${day}`)
      .set({ userId: user.uid, day, total: DAILY_POINTS_CAP + 200 });

    // Save a >= 5 km drive (server computes the distance from the route).
    const startedAt = new Date(Date.now() - 30 * 60_000);
    const distanceMetres = 6_000;
    const steps = Math.max(2, Math.round(distanceMetres / 100));
    const routePoints = Array.from({ length: steps + 1 }, (_, i) => ({
      ...north(i * (distanceMetres / steps)),
      timestampMs: startedAt.getTime() + i * 10_000,
    }));
    const ride = (
      await call('drives-save', {
        startedAt: startedAt.toISOString(),
        endedAt: new Date(startedAt.getTime() + steps * 10_000).toISOString(),
        routePoints,
        sourceSessionId: `crown-drive-${Date.now()}`,
      })
    ).data as { rideId: string };

    // The drive is paid IN FULL despite the blown daily cap: driving rules
    // answer to the weekly driving cap alone, so collecting crowns no longer
    // zeroes out a genuine saved drive (issue #861). Before the fix this
    // returned `cap_reached` and wrote no ledger entry.
    const entry = await awaitLedgerEntry(user.uid, economyIdempotencyKey('drive_5km', ride.rideId)!);
    expect(entry.amount).toBe(15);
    // Paid in full → the description is the bare label, with no "15 p → x p" clip.
    expect(String(entry.description)).not.toContain('→');
  });
});
