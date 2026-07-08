/**
 * Bootstrap the FIRST admin by granting the `admin: true` custom claim.
 *
 * Why this exists: the normal `admin-setAdminRole` callable requires an
 * EXISTING admin (it never lets you promote yourself), so the very first
 * admin must be set out-of-band with the Admin SDK. After this, promote
 * everyone else through the callable in the admin UI.
 *
 * Admin access has TWO required layers, so this script sets BOTH:
 *   1. The `admin: true` custom claim on the ID token — gates the admin web
 *      (checkAdminClaim reads the token) and firestore.rules isAdmin().
 *   2. The authoritative Firestore `users/{uid}.role === 'admin'` — the
 *      Cloud Functions admin callables (requireAdminActor) reject with
 *      `permission-denied` unless BOTH the token claim AND this doc role are
 *      present; toUserAccessState defaults a missing role to 'user'.
 * The role doc is written FIRST (privilege-increasing ordering: update the
 * authoritative datastore before granting the token claim). The user doc must
 * already exist (the user has to have signed in / been provisioned) — we never
 * create it here, to avoid leaving a partial profile document behind. Existing
 * claims and other user-doc fields are preserved (merged); `updatedAt` is
 * refreshed with a server timestamp to mirror the callable's behaviour.
 *
 * Project: the target project id is read from the environment (never
 * hardcoded, so this is safe against wrong-project runs and works for forks),
 * checking FIREBASE_PROJECT_ID, then GCLOUD_PROJECT, then GOOGLE_CLOUD_PROJECT.
 * The script fails fast if none is set and prints the resolved project before
 * acting.
 *
 * Auth: uses Application Default Credentials. Run it where ADC is present as
 * a project owner — the simplest being Google Cloud Shell:
 *
 *   FIREBASE_PROJECT_ID=<project-id> node scripts/set-first-admin.mjs <email> --yes
 *
 * (or set GOOGLE_APPLICATION_CREDENTIALS to a service-account key locally).
 * A confirmation flag (`--yes`, or CONFIRM=1) is REQUIRED so an account is
 * never promoted by accident: without it the script prints the project and
 * target it WOULD act on and exits non-zero. The user must have signed in to
 * the admin web at least once so their account exists. They must sign out and
 * back in afterwards to refresh the ID token so the new claim takes effect.
 */
import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';

// Derive the project id from the environment and fail fast if it is missing so
// we can never run against a hardcoded/wrong project.
const projectId =
  process.env.FIREBASE_PROJECT_ID || process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT;

const args = process.argv.slice(2);
const confirmed = args.includes('--yes') || process.env.CONFIRM === '1';
const email = args.find((arg) => !arg.startsWith('--'));

if (!email) {
  console.error('Usage: node scripts/set-first-admin.mjs <email> --yes');
  process.exit(1);
}

if (!projectId) {
  console.error(
    'No Firebase project id in the environment. Set one of ' +
      'FIREBASE_PROJECT_ID, GCLOUD_PROJECT or GOOGLE_CLOUD_PROJECT before running.',
  );
  process.exit(1);
}

initializeApp({ credential: applicationDefault(), projectId });
const auth = getAuth();
const db = getFirestore();

const user = await auth.getUserByEmail(email);

// Show the operator exactly what is about to happen before mutating anything.
console.log(`Project: ${projectId}`);
console.log(`Target:  ${email} (uid ${user.uid})`);

// Confirmation gate: refuse to promote an account without an explicit flag.
if (!confirmed) {
  console.error(
    'Refusing to promote without confirmation. Re-run with --yes ' +
      '(or CONFIRM=1) once the project and target above are correct.',
  );
  process.exit(1);
}

const userRef = db.collection('users').doc(user.uid);
const userSnap = await userRef.get();

// Require the profile doc to exist so we never create a partial one; the user
// must have signed in / been provisioned first.
if (!userSnap.exists) {
  console.error(
    `users/${user.uid} does not exist. The user must sign in / be provisioned ` +
      'first before they can be promoted (we will not create a partial profile).',
  );
  process.exit(1);
}

// Privilege-increasing ordering: write the authoritative Firestore role first
// (merge so other user-doc fields are preserved, refreshing updatedAt to mirror
// the callable), then grant the token claim.
await userRef.set({ role: 'admin', updatedAt: FieldValue.serverTimestamp() }, { merge: true });
console.log(`✔ users/${user.uid}.role='admin' set (Firestore backstop).`);

await auth.setCustomUserClaims(user.uid, { ...(user.customClaims ?? {}), admin: true });
console.log(`✔ admin:true claim set for ${email} (uid ${user.uid}).`);
console.log('Now sign out and back in to the admin web to refresh the token.');
