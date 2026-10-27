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
    `exports.auth={onUserCreate:require(${load('auth/onUserCreate.js')}).onUserCreate};\nconst warnings=require(${load('admin/warnings.js')});\nconst members=require(${load('admin/memberTools.js')});\nexports.admin={warnUser:warnings.warnUser,revokeWarning:warnings.revokeWarning,getMember:members.getMember,updateDisplayName:members.updateDisplayName};\nexports.account={listWarnings:warnings.listOwnWarnings};\nexports.points={adminAdjust:require(${load('points/adminPoints.js')}).adminAdjust};\n`,
  );
  writeFileSync(
    resolve(destination, 'firebase.json'),
    JSON.stringify(
      {
        functions: { source },
        firestore: {
          rules: resolve(root, 'firebase/firestore.rules'),
          indexes: resolve(root, 'firebase/firestore.indexes.json'),
        },
        emulators: {
          auth: { host: '127.0.0.1', port: 19098 },
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
    process.env.FIREBASE_AUTH_EMULATOR_HOST !== '127.0.0.1:19098'
  )
    throw Error('Exact disposable native emulators required');
  const require = createRequire(resolve(root, 'functions/package.json'));
  const { initializeApp } = require('firebase-admin/app');
  const { getAuth } = require('firebase-admin/auth');
  const { getFirestore } = require('firebase-admin/firestore');
  const app = initializeApp({ projectId: project });
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
  console.log('Disposable native members seeded.');
  process.exit(0);
} else throw Error('Expected prepare <new-directory> or seed');
