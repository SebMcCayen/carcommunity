/** Manual operator tool. Defaults to read-only; never changes shared Auth/users. */
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const args = process.argv.slice(2),
  project = args.find((v) => v.startsWith('--project='))?.slice(10);
const databaseURL = args.find((v) => v.startsWith('--database-url='))?.slice(15);
const apply = args.includes('--apply'),
  verify = args.includes('--verify');
if (
  !project ||
  !databaseURL ||
  !/^https:\/\/[a-z0-9.-]+\.(?:firebaseio\.com|firebasedatabase\.app)$/.test(databaseURL) ||
  args.some(
    (v) =>
      !['--apply', '--verify', `--project=${project}`, `--database-url=${databaseURL}`].includes(v),
  )
)
  throw Error(
    'Expected --project=<explicit-id> --database-url=<deployed-database-url> [--apply | --verify]',
  );
if (apply && (verify || process.env.KCC_ALLOW_ACCESS_BACKFILL !== project))
  throw Error('Apply requires explicit KCC_ALLOW_ACCESS_BACKFILL matching the project');
const emulatorKeys = [
  'FIRESTORE_EMULATOR_HOST',
  'FIREBASE_AUTH_EMULATOR_HOST',
  'FIREBASE_DATABASE_EMULATOR_HOST',
];
const local = emulatorKeys.some((k) => process.env[k]);
if (
  local &&
  (!project.startsWith('demo-') ||
    emulatorKeys.some((k) => !/^127\.0\.0\.1:\d+$/.test(process.env[k] ?? '')))
)
  throw Error('Complete loopback demo emulator stack required');
const { initializeApp, deleteApp } = require('firebase-admin/app');
const app = initializeApp({
  projectId: project,
  databaseURL,
});
const { getFirestore, FieldPath } = require('firebase-admin/firestore');
const { getDatabase } = require('firebase-admin/database');
const { getAuth } = require('firebase-admin/auth');
const db = getFirestore(),
  auth = getAuth();
const initialize = apply
  ? require('../lib/access/carCommunity.js').initializeCarCommunityAccess
  : null;
let cursor = null,
  total = 0,
  missing = 0,
  unavailable = 0,
  mismatch = 0;
for (;;) {
  let query = db.collection('users').orderBy(FieldPath.documentId()).limit(100);
  if (cursor) query = query.startAfter(cursor);
  const page = await query.get();
  if (page.empty) break;
  for (const profile of page.docs) {
    if (profile.get('deleted') === true) continue;
    try {
      await auth.getUser(profile.id);
    } catch (e) {
      if (e.code === 'auth/user-not-found') {
        unavailable++;
        continue;
      }
      throw e;
    }
    total++;
    const access = await db.collection('carCommunityAccess').doc(profile.id).get();
    if (access.get('initialized') !== true) missing++;
    if (apply) await initialize(profile.id);
    if (verify) {
      const mirror = (await getDatabase().ref(`carCommunityAccess/${profile.id}`).get()).val();
      if (
        !mirror ||
        mirror.version !== access.get('version') ||
        mirror.initialized !== true ||
        mirror.suspended !== (access.get('suspended') === true) ||
        mirror.inheritedBlocked !== (access.get('inheritedBlocked') === true)
      )
        mismatch++;
    }
  }
  cursor = page.docs.at(-1).id;
}
console.log(
  JSON.stringify({
    mode: apply ? 'apply' : verify ? 'verify' : 'dry-run',
    total,
    missing,
    unavailable,
    mirrorMismatches: mismatch,
  }),
);
if (verify && (missing || mismatch)) process.exitCode = 1;

// Close the RTDB connection so successful verification terminates normally.
await deleteApp(app);
