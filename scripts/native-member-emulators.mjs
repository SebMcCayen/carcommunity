#!/usr/bin/env node
/** Disposable localhost-only native acceptance; exports real compiled handlers. */
import { createRequire } from 'node:module';
import { mkdirSync, writeFileSync, symlinkSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const project = 'demo-kcc-ios-ci';
if (process.argv[2] === 'prepare') {
  const destination = resolve(process.argv[3]);
  const source = resolve(destination, 'functions');
  mkdirSync(source, { recursive: true });
  const load = (file) => JSON.stringify(resolve(root, 'functions/lib', file));
  writeFileSync(
    resolve(source, 'package.json'),
    JSON.stringify({
      name: 'native-member-acceptance',
      private: true,
      main: 'index.cjs',
      engines: { node: '22' },
      dependencies: { 'firebase-admin': '^13.0.0', 'firebase-functions': '^6.0.0' },
    }),
  );
  symlinkSync(resolve(root, 'functions/node_modules'), resolve(source, 'node_modules'), 'dir');
  writeFileSync(
    resolve(source, 'index.cjs'),
    `require("firebase-admin/app").initializeApp({projectId:"demo-kcc-ios-ci",databaseURL:"https://demo-kcc-ios-ci-default-rtdb.firebaseio.com"});\nexports.auth={onUserCreate:require(${load('auth/onUserCreate.js')}).onUserCreate};\nconst warnings=require(${load('admin/warnings.js')});\nconst members=require(${load('admin/memberTools.js')});\nexports.admin={warnUser:warnings.warnUser,revokeWarning:warnings.revokeWarning,getMember:members.getMember,updateDisplayName:members.updateDisplayName};\nObject.assign(exports.admin,require(${load('admin/publicModeration.js')}));\nconst access=require(${load('access/carCommunity.js')});\nconst decisions=require(${load('access/adminAccess.js')});\nexports.carCommunityAccess={onAccessWrite:access.onAccessWrite,onIdentityWrite:access.onIdentityWrite};\nexports.admin.getAccessTarget=decisions.getAccessTarget;exports.admin.updateAccess=decisions.updateAccess;\nexports.account={listWarnings:warnings.listOwnWarnings,getAccess:access.getAccess};\nexports.points={adminAdjust:require(${load('points/adminPoints.js')}).adminAdjust};\nexports.crownHunt={pausePoint:require(${load('crownHunt/managePoints.js')}).pausePoint,updateSpawnArea:require(${load('crownHunt/spawnAreas.js')}).updateSpawnArea};\n`,
  );
  writeFileSync(
    resolve(destination, 'firebase.json'),
    JSON.stringify(
      {
        functions: { source: 'functions' },
        database: { rules: resolve(root, 'firebase/database.rules.json') },
        firestore: {
          rules: resolve(root, 'firebase/firestore.rules'),
          indexes: resolve(root, 'firebase/firestore.indexes.json'),
        },
        emulators: {
          auth: { host: '127.0.0.1', port: 19098 },
          database: { host: '127.0.0.1', port: 19000 },
          firestore: { host: '127.0.0.1', port: 18088 },
          functions: { host: '127.0.0.1', port: 15001 },
          hub: { host: '127.0.0.1', port: 14441 },
          logging: { host: '127.0.0.1', port: 14501 },
          ui: { enabled: false },
          singleProjectMode: true,
        },
      },
      null,
      2,
    ),
  );
} else if (process.argv[2] === 'seed') {
  if (
    process.env.GCLOUD_PROJECT !== project ||
    process.env.FIRESTORE_EMULATOR_HOST !== '127.0.0.1:18088' ||
    process.env.FIREBASE_AUTH_EMULATOR_HOST !== '127.0.0.1:19098' ||
    process.env.FIREBASE_DATABASE_EMULATOR_HOST !== '127.0.0.1:19000'
  )
    throw Error('Exact disposable native emulators required');
  const require = createRequire(resolve(root, 'functions/package.json'));
  const { initializeApp } = require('firebase-admin/app');
  const { getAuth } = require('firebase-admin/auth');
  const { getFirestore } = require('firebase-admin/firestore');
  const app = initializeApp({
    projectId: project,
    databaseURL: `https://${project}-default-rtdb.firebaseio.com`,
  });
  const auth = getAuth(app),
    db = getFirestore(app);
  for (const [uid, role] of [
    ['native-admin', 'admin'],
    ['native-member', 'user'],
  ]) {
    await auth.createUser({
      uid,
      email: `${uid}@example.com`,
      password: 'Native-local-only-12345',
    });
    await auth.setCustomUserClaims(uid, role === 'admin' ? { admin: true } : {});
    await db
      .collection('users')
      .doc(uid)
      .set({ role, displayName: uid, activeMember: true, suspended: false, deleted: false });
    await db.collection('pointsLedger').doc(uid).set({ balance: 100 });
  }
  await db
    .collection('carCommunityPolicy')
    .doc('access')
    .set({ enforceInitialized: true, rolloutState: 'ready' });
  await require('firebase-admin/database')
    .getDatabase(app)
    .ref('carCommunityPolicy/enforceInitialized')
    .set(true);
  const { Timestamp } = require('firebase-admin/firestore');
  const createdAt = Timestamp.fromMillis(Date.now() - 10000);
  await db.doc('events/native-moderation').set({
    status: 'published',
    title: 'Native public moderation',
    createdByUserId: 'native-admin',
  });
  await db
    .doc('events/native-moderation/participants/native-member')
    .set({ status: 'going', userId: 'native-member' });
  await db
    .doc('events/native-moderation/rsvps/native-member')
    .set({ status: 'going', userId: 'native-member' });
  await db.doc('events/native-moderation/messages/native-event-message').set({
    authorUserId: 'native-member',
    authorDisplayName: 'Member',
    message: 'Event original',
    moderationState: 'visible',
    createdAt,
  });
  await db.doc('communityChat/global/messages/native-public-message').set({
    senderUid: 'native-member',
    senderDisplayName: 'Member',
    text: 'Public original',
    createdAt,
  });
  await db.doc('communityChat/global/messages/native-public-reply').set({
    senderUid: 'native-member',
    text: 'Reply preserved',
    createdAt,
    replyTo: {
      messageId: 'native-public-message',
      senderUid: 'native-member',
      textPreview: 'Public original',
    },
  });
  await db.doc('crownHuntPoints/native-handplaced').set({
    status: 'active',
    title: 'Native hand-placed',
    repeatRule: 'once',
    latitude: 57.4874,
    longitude: 12.0761,
    rewardPoints: 10,
    geofenceRadiusMeters: 75,
    createdByUserId: 'native-admin',
    createdAt,
  });
  await db.doc('crownSpawnAreas/native-area').set({
    name: 'Native area',
    active: true,
    safeAreaConfirmed: true,
    shape: { type: 'rectangle', bounds: { south: 57.48, north: 57.5, west: 12.06, east: 12.09 } },
    createdByUserId: 'native-admin',
    createdAt,
  });
  await db.doc('crownSpawns/native-automatic').set({
    status: 'live',
    areaId: 'native-area',
    cellKey: require(resolve(root, 'functions/lib/crownHunt/crown-spawn-core.js')).crownCellKey(
      57.4874,
      12.0761,
    ),
    latitude: 57.4874,
    longitude: 12.0761,
    rewardPoints: 25,
    rarity: 'uncommon',
    collectMode: 'shared',
    collectRadiusMeters: 75,
    expiresAt: Timestamp.fromMillis(Date.now() + 3600000),
    createdAt,
  });
  console.log('Disposable native members seeded.');
  process.exit(0);
} else throw Error('Expected prepare <new-directory> or seed');
