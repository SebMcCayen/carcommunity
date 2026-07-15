// Seeds the test user "Sven Svensson" into the LOCAL Firebase emulators
// (Auth + Firestore). Never touches production: the *_EMULATOR_HOST env vars
// force firebase-admin to talk only to the local emulators.
//
// Usage (emulators must be running — see README.md):
//   node scripts/local-android/seed-sven.js
// firebase-admin is resolved from functions/node_modules explicitly, so this
// works from any cwd without setting NODE_PATH.
process.env.FIREBASE_AUTH_EMULATOR_HOST =
  process.env.FIREBASE_AUTH_EMULATOR_HOST || '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST =
  process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';

const path = require('path');
// Resolve firebase-admin from functions/node_modules (installed by the
// functions workspace) so this works from any cwd. Fall back to a bare require
// (NODE_PATH / hoisted install) if that path isn't present.
let admin;
try {
  admin = require(path.join(__dirname, '..', '..', 'functions', 'node_modules', 'firebase-admin'));
} catch (_) {
  admin = require('firebase-admin');
}
admin.initializeApp({ projectId: 'kungsbacka-car-community' });

const auth = admin.auth();
const db = admin.firestore();

const UID = 'sven-svensson-test';
const EMAIL = 'sven.svensson@example.com';
const PASSWORD = 'Test1234!';
const DISPLAY_NAME = 'Sven Svensson';

async function main() {
  // 1) Auth user (idempotent). The existence check (getUser) is wrapped on its
  // own so its error hint stays accurate: only 'auth/user-not-found' means
  // "create it"; any other error (e.g. the Auth emulator isn't running ->
  // ECONNREFUSED) is a real read failure and is rethrown with a hint. The
  // update/create write happens OUTSIDE that try, so a write failure surfaces
  // its own accurate error rather than the "Failed to read" message.
  let userExists = true;
  try {
    await auth.getUser(UID);
  } catch (e) {
    if (!e || e.code !== 'auth/user-not-found') {
      throw new Error(
        `Failed to read auth user ${UID} — is the Auth emulator running at ` +
        `${process.env.FIREBASE_AUTH_EMULATOR_HOST}? Underlying error: ` +
        `${e && e.message ? e.message : e}`,
      );
    }
    userExists = false;
  }
  if (userExists) {
    await auth.updateUser(UID, {
      email: EMAIL, password: PASSWORD, displayName: DISPLAY_NAME, emailVerified: true,
    });
    console.log('Updated existing auth user', UID);
  } else {
    await auth.createUser({
      uid: UID, email: EMAIL, password: PASSWORD, displayName: DISPLAY_NAME, emailVerified: true,
    });
    console.log('Created auth user', UID);
  }

  // 2) Custom claims the Firestore rules read (activeMember; NOT suspended).
  await auth.setCustomUserClaims(UID, { activeMember: true });
  console.log('Set custom claims { activeMember: true }');

  // 3) users/{uid} — a normal, active, onboarded member.
  const now = admin.firestore.FieldValue.serverTimestamp();
  await db.collection('users').doc(UID).set({
    displayName: DISPLAY_NAME,
    role: 'user',
    activeMember: true,
    suspended: false,
    deleted: false,
    bio: 'Test member seeded for local evaluation.',
    avatarPath: null,
    onboardingCompletedAt: now,
    createdAt: now,
  }, { merge: true });
  console.log('Wrote users/' + UID);

  // 4) userPrivate/{uid} — consent timestamps so onboarding is fully complete.
  await db.collection('userPrivate').doc(UID).set({
    ageConfirmedAt: now,
    termsAcceptedAt: now,
    privacyPolicyAcceptedAt: now,
  }, { merge: true });
  console.log('Wrote userPrivate/' + UID);

  console.log('\nSEED OK');
  console.log('  uid:      ' + UID);
  console.log('  email:    ' + EMAIL);
  console.log('  password: ' + PASSWORD);
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
