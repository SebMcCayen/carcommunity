/** Real Firestore transactions; fake Auth only. No Functions/Auth emulator required. */
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { getApps, initializeApp, deleteApp, type App } from 'firebase-admin/app';
import {
  getFirestore,
  Timestamp,
  FieldPath,
  type Firestore,
  type DocumentData,
} from 'firebase-admin/firestore';
import { beforeAll, beforeEach, afterAll, describe, expect, it } from 'vitest';
import {
  ClubInvitationNotifications,
  invitationGeneration,
  invitationNotificationId,
  invitationReceiptPath,
} from '../clubInvitations/service';

const project = process.env.GCLOUD_PROJECT ?? '';
if (
  !/^demo-[a-z0-9-]+$/.test(project) ||
  !/^127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(process.env.FIRESTORE_EMULATOR_HOST ?? '') ||
  Number(process.env.FIRESTORE_EMULATOR_HOST?.split(':')[1]) > 65535 ||
  (globalThis as { [key: symbol]: unknown })[Symbol.for('kcc.test.noCloud')] !== true ||
  (process.env.GOOGLE_CLOUD_PROJECT && process.env.GOOGLE_CLOUD_PROJECT !== project) ||
  process.env.GOOGLE_APPLICATION_CREDENTIALS ||
  process.env.FIREBASE_TOKEN
)
  throw new Error(
    'Invitation integration tests require the no-cloud runner and loopback demo Firestore.',
  );

const now = Date.now(),
  joined = Timestamp.fromMillis(now - 120000);
const prefix = `invite_emulator_${randomUUID().replaceAll('-', '')}`;
const fakeIdentity = async () => ({ disabled: false, providerData: [] });
let ownApp: App | undefined;
let db: Firestore;
let write: typeof import('../notifications/deliver').writeInAppNotification;
let service: ClubInvitationNotifications;
let originalConfig: DocumentData | undefined, originalPolicy: DocumentData | undefined;
let initialized = false,
  sequence = 0;
const cleanup = new Set<string>();
let clubId: string, uid: string, sender: string, candidate: DocumentData;
const invitationPath = () => `driverClubs/${clubId}/invitations/${uid}`;
const receiptPath = () => invitationReceiptPath(clubId, uid);
const inboxPath = (data = candidate) =>
  `notifications/${uid}/items/${invitationNotificationId(invitationGeneration(clubId, uid, data)!)}`;
const deliverCandidate = (data = candidate) => service.process(clubId, uid, data);
const items = () => db.collection(`notifications/${uid}/items`).limit(5).get();

beforeAll(async () => {
  let app = getApps().find((app) => app.name === '[DEFAULT]');
  if (app && app.options.projectId !== project) throw new Error('Existing Admin project mismatch.');
  if (!app)
    app = ownApp = initializeApp({
      projectId: project,
      databaseURL: `https://${project}-default-rtdb.firebaseio.com`,
    });
  db = getFirestore(app);
  // Import the real writer only after transport/project validation and explicit bootstrap.
  ({ writeInAppNotification: write } = await import('../notifications/deliver'));
  service = new ClubInvitationNotifications(db, fakeIdentity, write, () => now);
  [originalConfig, originalPolicy] = await Promise.all([
    db
      .doc('config/sharedClubs')
      .get()
      .then((snap) => snap.data()),
    db
      .doc('carCommunityPolicy/access')
      .get()
      .then((snap) => snap.data()),
  ]);
  initialized = true;
  const batch = db.batch();
  batch.set(db.doc('config/sharedClubs'), {
    ...originalConfig,
    enabled: true,
    activatedAt: joined,
    invitationNotificationsReady: true,
  });
  batch.set(db.doc('carCommunityPolicy/access'), { ...originalPolicy, enforceInitialized: true });
  await batch.commit();
});

beforeEach(async () => {
  const suffix = `${prefix}_${++sequence}`;
  clubId = `${suffix}_club`;
  uid = `${suffix}_recipient`;
  sender = `${suffix}_sender`;
  candidate = {
    clubId,
    recipientUid: uid,
    inviterUid: sender,
    status: 'pending',
    createdAt: Timestamp.fromMillis(now - 60000),
    expiresAt: Timestamp.fromMillis(now + 86400000),
  };
  const user = {
    onboardingCompletedAt: joined,
    suspended: false,
    deleted: false,
    status: 'active',
  };
  const seed: [string, DocumentData][] = [
    [
      `driverClubs/${clubId}`,
      { schemaVersion: 2, status: 'active', visibility: 'closed', ownerUid: sender },
    ],
    [invitationPath(), candidate],
    [`users/${uid}`, user],
    [`users/${sender}`, user],
    [`userPrivate/${uid}`, {}],
    [`carCommunityAccess/${uid}`, { initialized: true }],
    [`driverClubAccounts/${sender}`, { activeClubId: clubId, role: 'owner', joinedAt: joined }],
    [`driverClubs/${clubId}/members/${sender}`, { role: 'owner', joinedAt: joined }],
  ];
  const batch = db.batch();
  for (const [path, data] of seed) {
    cleanup.add(path);
    batch.set(db.doc(path), data);
  }
  cleanup.add(receiptPath());
  cleanup.add(inboxPath());
  await batch.commit();
});

afterAll(async () => {
  if (initialized) {
    // Exact test-owned paths only; never flush the shared emulator database.
    const batch = db.batch();
    for (const path of cleanup) batch.delete(db.doc(path));
    if (originalConfig) batch.set(db.doc('config/sharedClubs'), originalConfig);
    else batch.delete(db.doc('config/sharedClubs'));
    if (originalPolicy) batch.set(db.doc('carCommunityPolicy/access'), originalPolicy);
    else batch.delete(db.doc('carCommunityPolicy/access'));
    await batch.commit();
  }
  if (ownApp) {
    await db.terminate();
    await deleteApp(ownApp);
  }
});

describe('canonical invitations -> real single-writer Firestore transaction', () => {
  it('commits inbox and receipt together, and concurrent calls/replay after owner deletion stay idempotent', async () => {
    expect((await Promise.all([deliverCandidate(), deliverCandidate()])).sort()).toEqual([
      'delivered',
      'duplicate',
    ]);
    const [inbox, receipt] = await db.getAll(db.doc(inboxPath()), db.doc(receiptPath()));
    expect(inbox!.data()).toMatchObject({
      category: 'club_invite',
      actionType: 'open_notifications',
      relatedEntityId: clubId,
      read: false,
    });
    expect(receipt!.data()).toMatchObject({ outcome: 'delivered', notificationId: inbox!.id });
    expect(inbox!.createTime!.isEqual(receipt!.createTime!)).toBe(true);
    expect(
      (inbox!.get('createdAt') as Timestamp).isEqual(receipt!.get('processedAt') as Timestamp),
    ).toBe(true);
    await db.doc(inboxPath()).delete();
    expect(await deliverCandidate()).toBe('duplicate');
    expect((await items()).empty).toBe(true);
    expect((await db.doc(receiptPath()).get()).exists).toBe(true);
  });

  it('aborts the real queued inbox write if the producer fails before its receipt', async () => {
    const failAfterWriter: typeof write = async (...args) => {
      await write(...args);
      throw new Error('injected-before-receipt');
    };
    const failing = new ClubInvitationNotifications(db, fakeIdentity, failAfterWriter, () => now);
    await expect(failing.process(clubId, uid, candidate)).rejects.toThrow(
      'injected-before-receipt',
    );
    const snapshots = await db.getAll(db.doc(inboxPath()), db.doc(receiptPath()));
    expect(snapshots.map((snap) => snap.exists)).toEqual([false, false]);
    expect(await deliverCandidate()).toBe('delivered');
  });

  it('persists an opt-out receipt and cannot revive it when preferences are enabled later', async () => {
    await db
      .doc(`userPrivate/${uid}`)
      .set({ notificationPreferences: { club_invite: { inApp: false } } });
    expect(await deliverCandidate()).toBe('opted_out');
    expect((await items()).empty).toBe(true);
    expect((await db.doc(receiptPath()).get()).get('outcome')).toBe('opted_out');
    await db
      .doc(`userPrivate/${uid}`)
      .set({ notificationPreferences: { club_invite: { inApp: true } } });
    expect(await deliverCandidate()).toBe('duplicate');
    expect((await items()).empty).toBe(true);
  });

  it('honors a committed preference change between canonical checks and writer eligibility reads', async () => {
    let entered!: () => void, release!: () => void;
    const atWriter = new Promise<void>((resolve) => {
      entered = resolve;
    });
    const resume = new Promise<void>((resolve) => {
      release = resolve;
    });
    const pausedWriter: typeof write = async (...args) => {
      entered();
      await resume;
      return write(...args);
    };
    const paused = new ClubInvitationNotifications(db, fakeIdentity, pausedWriter, () => now);
    const work = paused.process(clubId, uid, candidate);
    try {
      await Promise.race([
        atWriter,
        work.then(() => {
          throw new Error('Writer was not reached.');
        }),
      ]);
      // userPrivate has not been read/locked yet; this does not deadlock the transaction.
      await db
        .doc(`userPrivate/${uid}`)
        .set({ notificationPreferences: { club_invite: { inApp: false } } });
    } finally {
      release();
    }
    expect(await work).toBe('opted_out');
    expect((await items()).empty).toBe(true);
    expect((await db.doc(receiptPath()).get()).get('outcome')).toBe('opted_out');
  });

  it('does not deliver obsolete/declined generations and delivers only the current reinvite', async () => {
    await db.doc(invitationPath()).update({ status: 'declined' });
    expect(await deliverCandidate()).toBe('ineligible');
    const next = { ...candidate, createdAt: Timestamp.fromMillis(now - 30000) };
    cleanup.add(inboxPath(next));
    await db.doc(invitationPath()).set(next);
    expect(await deliverCandidate()).toBe('ineligible');
    expect((await items()).empty).toBe(true);
    expect(await deliverCandidate(next)).toBe('delivered');
    expect(await deliverCandidate()).toBe('duplicate');
    expect((await items()).docs.map((doc) => doc.id)).toEqual([db.doc(inboxPath(next)).id]);
  });

  it('gates producer and queued push on readiness independently of shared-club activation', async () => {
    const config = db.doc('config/sharedClubs');
    try {
      await config.update({ invitationNotificationsReady: false });
      expect(await deliverCandidate()).toBe('ineligible');
      expect((await db.doc(receiptPath()).get()).exists).toBe(false);
      expect((await config.get()).get('enabled')).toBe(true);
      await config.update({ invitationNotificationsReady: true });
      expect(await deliverCandidate()).toBe('delivered');
      await config.update({ invitationNotificationsReady: false });
      expect(await service.canPush(clubId, uid, db.doc(inboxPath()).id)).toBe(false);
    } finally {
      await config.update({ invitationNotificationsReady: true });
    }
  });

  it('backfills a preexisting native snapshot through the exact service and receipts', async () => {
    const { runBackfill } = await import(
      pathToFileURL(resolve(__dirname, '../../scripts/backfill-club-invitations.mjs')).href
    );
    // Bound the operator scan to the fixture's deterministic ID range; skip unrelated clubs.
    const cursor = { clubAfter: null, clubId, invitationAfter: null };
    const isolatedDb = {
      doc: (path: string) => db.doc(path),
      collection: (path: string) => {
        expect(path).toBe('driverClubs');
        return db.collection(path).where(FieldPath.documentId(), '==', clubId);
      },
    };
    const scan = { db: isolatedDb, service, invitationGeneration, cursor, now: () => now };
    const dry = await runBackfill(scan);
    expect(dry.summary.dryRun).toBe(true);
    expect((await items()).empty).toBe(true);
    expect((await db.doc(receiptPath()).get()).exists).toBe(false);
    await runBackfill({ ...scan, apply: true });
    expect((await items()).size).toBe(1);
    await db.doc(inboxPath()).delete();
    await runBackfill({ ...scan, apply: true });
    expect((await items()).empty).toBe(true);
    expect((await db.doc(receiptPath()).get()).get('outcome')).toBe('delivered');
  });
});
