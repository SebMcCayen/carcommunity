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
 * Safety guards mirroring the Cloud Functions (functions/src/admin/claims-core.ts,
 * functions/src/shared/access.ts) are applied to the fetched profile BEFORE any
 * write:
 *   - refuse if `role === 'owner'` — owner accounts are managed out-of-band and
 *     must never be clobbered to 'admin' (mirrors guardSetAdminRole).
 *   - refuse if the profile is `suspended` or `deleted` — a restricted account
 *     is denied admin access, so we never issue it an admin claim (mirrors
 *     guardActorIsActiveAdmin / canAccessAdminFeatures).
 *
 * One-time-bootstrap guard: this script is strictly for the FIRST admin. Before
 * any write it refuses to run if any OTHER `users/*` (id != target) already has a
 * role of 'admin' or 'owner'. Re-running for the same target is idempotent ONLY
 * while no such other privileged user exists; once any additional admin/owner is
 * present the script refuses even for the original target, and all further
 * promotions must go through the audited `admin-setAdminRole` callable.
 *
 * Project: the target project id is read from the environment (never
 * hardcoded, so this is safe against wrong-project runs and works for forks),
 * checking FIREBASE_PROJECT_ID, then GCLOUD_PROJECT, then GOOGLE_CLOUD_PROJECT.
 * The script fails fast if none is set and prints the resolved project before
 * acting.
 *
 * WHY THIS LIVES UNDER functions/: it imports `firebase-admin`, which is a
 * dependency of functions/ only (functions/ is deliberately NOT a root npm
 * workspace, so the repo root has no node_modules providing it). Node resolves
 * bare specifiers by walking up from the SCRIPT'S OWN directory, not the shell's
 * cwd — so a copy of this script under the repo-root scripts/ cannot resolve
 * firebase-admin no matter which directory you invoke it from.
 *
 * Auth: uses Application Default Credentials. Run it where ADC is present as
 * a project owner — the simplest being Google Cloud Shell:
 *
 *   cd functions
 *   npm ci                                  # provides firebase-admin
 *   FIREBASE_PROJECT_ID=<project-id> node scripts/set-first-admin.mjs <email> --yes
 *
 * (This script needs no `npx tsc` build — it imports nothing from lib/.)
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
  console.error('Usage (from functions/): node scripts/set-first-admin.mjs <email> --yes');
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

// getUserByEmail throws if the email has a typo or the person has never signed
// in — surface a clear, actionable message instead of a raw stack trace.
let user;
try {
  user = await auth.getUserByEmail(email);
} catch (err) {
  if (err?.code === 'auth/user-not-found') {
    console.error(
      `No Firebase Auth user exists for ${email}. Double-check the address ` +
        'and make sure the person has signed in to the admin web at least once ' +
        'so their account is provisioned.',
    );
  } else {
    console.error(`Failed to look up ${email}: ${err?.message ?? err}`);
  }
  process.exit(1);
}

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

// One-time-bootstrap guard: this script exists only to set the FIRST admin.
// Once any admin/owner exists, all further promotions must go through the
// audited `admin-setAdminRole` callable (see the header). A single-field
// where-in on `role` needs no composite index; limit(2) is enough to find
// someone other than the target.
const existingPrivileged = await db
  .collection('users')
  .where('role', 'in', ['admin', 'owner'])
  .limit(2)
  .get();
const otherPrivileged = existingPrivileged.docs.find((doc) => doc.id !== user.uid);
if (otherPrivileged) {
  console.error(
    `users/${otherPrivileged.id} already has role '${otherPrivileged.data().role}'. ` +
      'An admin/owner already exists, so this one-time bootstrap is disabled. ' +
      'Use the audited admin-setAdminRole callable in the admin UI for further ' +
      'promotions.',
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

// Read the backend-managed access fields the same way toUserAccessState does
// (functions/src/shared/access.ts): a missing/invalid role is a plain 'user',
// and suspended/deleted are only true when strictly === true.
const userData = userSnap.data() ?? {};
const role = ['user', 'admin', 'owner'].includes(userData.role) ? userData.role : 'user';
const suspended = userData.suspended === true;
const deleted = userData.deleted === true;

// Mirror guardSetAdminRole (functions/src/admin/claims-core.ts): the owner role
// is managed out-of-band and must never be overwritten — refuse before we can
// clobber an owner's role down to 'admin'.
if (role === 'owner') {
  console.error(
    `users/${user.uid}.role is 'owner'. Refusing to modify owner accounts ` +
      '(mirrors guardSetAdminRole); owner status is managed out-of-band.',
  );
  process.exit(1);
}

// Mirror guardActorIsActiveAdmin / canAccessAdminFeatures (isRestricted):
// suspended or deleted accounts are denied admin access, so never grant the
// admin claim to one — a missing `suspended` custom claim could otherwise let
// Firestore rules accept the token.
if (suspended || deleted) {
  console.error(
    `users/${user.uid} is ${deleted ? 'deleted' : 'suspended'}. Refusing to grant ` +
      'admin to a suspended/deleted account (mirrors guardActorIsActiveAdmin).',
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
