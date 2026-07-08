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
 * authoritative datastore before granting the token claim). Existing claims
 * and other user-doc fields are preserved (merged).
 *
 * Auth: uses Application Default Credentials. Run it where ADC is present as
 * a project owner — the simplest being Google Cloud Shell:
 *
 *   node scripts/set-first-admin.mjs <email>
 *
 * (or set GOOGLE_APPLICATION_CREDENTIALS to a service-account key locally).
 * The user must have signed in to the admin web at least once so their
 * account exists. They must sign out and back in afterwards to refresh the
 * ID token so the new claim takes effect.
 */
import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';

const PROJECT_ID = 'kungsbacka-car-community';
const email = process.argv[2];

if (!email) {
  console.error('Usage: node scripts/set-first-admin.mjs <email>');
  process.exit(1);
}

initializeApp({ credential: applicationDefault(), projectId: PROJECT_ID });
const auth = getAuth();
const db = getFirestore();

const user = await auth.getUserByEmail(email);

// Privilege-increasing ordering: write the authoritative Firestore role first
// (merge so other user-doc fields are preserved), then grant the token claim.
await db.collection('users').doc(user.uid).set({ role: 'admin' }, { merge: true });
console.log(`✔ users/${user.uid}.role='admin' set (Firestore backstop).`);

await auth.setCustomUserClaims(user.uid, { ...(user.customClaims ?? {}), admin: true });
console.log(`✔ admin:true claim set for ${email} (uid ${user.uid}).`);
console.log('Now sign out and back in to the admin web to refresh the token.');
