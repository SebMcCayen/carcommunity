/**
 * Bootstrap the FIRST admin by granting the `admin: true` custom claim.
 *
 * Why this exists: the normal `admin-setAdminRole` callable requires an
 * EXISTING admin (it never lets you promote yourself), so the very first
 * admin must be set out-of-band with the Admin SDK. After this, promote
 * everyone else through the callable in the admin UI.
 *
 * The admin web grants access from the `admin: true` custom claim
 * (checkAdminClaim reads the ID token); firestore.rules isAdmin() and the
 * callable admin guard read the same token claim. So this one claim is the
 * whole gate. Existing claims are preserved (merged).
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

const PROJECT_ID = 'kungsbacka-car-community';
const email = process.argv[2];

if (!email) {
  console.error('Usage: node scripts/set-first-admin.mjs <email>');
  process.exit(1);
}

initializeApp({ credential: applicationDefault(), projectId: PROJECT_ID });
const auth = getAuth();

const user = await auth.getUserByEmail(email);
await auth.setCustomUserClaims(user.uid, { ...(user.customClaims ?? {}), admin: true });
console.log(`✔ admin:true set for ${email} (uid ${user.uid}).`);
console.log('Now sign out and back in to the admin web to refresh the token.');
