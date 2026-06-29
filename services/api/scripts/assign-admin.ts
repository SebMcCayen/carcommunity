#!/usr/bin/env node
/**
 * Admin bootstrap script — assigns the `admin: true` Firebase custom claim.
 *
 * Use this script to grant the first administrator account. Subsequent admin
 * changes should go through the admin portal or a similar trusted interface.
 *
 * Usage:
 *   npx tsx scripts/assign-admin.ts --uid <firebase-uid>
 *
 * Prerequisites:
 *   - GOOGLE_APPLICATION_CREDENTIALS must be set to a service account key file
 *     that has the "Firebase Authentication Admin" role, OR the script must run
 *     in a GCP environment where the default service account has that role.
 *   - The target Firebase UID must already exist in Firebase Authentication.
 *
 * Security requirements:
 *   - Do NOT commit service account key files to version control.
 *   - Do NOT run this script during deployment pipelines.
 *   - Custom claims can only be set by trusted backend code — never by clients.
 *
 * @remarks
 *   After the claim is set, existing Firebase ID tokens will carry the old
 *   claims until they expire (up to 1 hour). To force immediate propagation,
 *   revoke the user's refresh tokens with:
 *     firebase auth:revoke <firebase-uid>
 *   or programmatically via auth.revokeRefreshTokens(uid).
 */

import { createInterface } from 'node:readline';
import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';

// ---------------------------------------------------------------------------
// Parse arguments
// ---------------------------------------------------------------------------

const args = process.argv.slice(2);
const uidIndex = args.indexOf('--uid');
const firebaseUid = uidIndex >= 0 ? args[uidIndex + 1] : null;

if (!firebaseUid || !firebaseUid.trim()) {
  console.error('Error: --uid <firebase-uid> is required.');
  console.error('Usage:  npx tsx scripts/assign-admin.ts --uid <firebase-uid>');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Initialise Firebase Admin with Application Default Credentials.
// Never hard-code credentials here.
// ---------------------------------------------------------------------------

const existingApps = getApps();
const app = existingApps.length > 0 ? existingApps[0]! : initializeApp();
const auth = getAuth(app);

// ---------------------------------------------------------------------------
// Confirm and apply
// ---------------------------------------------------------------------------

async function main(): Promise<void> {
  const uid = firebaseUid!.trim();

  let userRecord;
  try {
    userRecord = await auth.getUser(uid);
  } catch {
    console.error(`Error: no Firebase user found with UID "${uid}".`);
    console.error('Verify the UID in the Firebase console before running this script.');
    process.exit(1);
  }

  const currentClaims: Record<string, unknown> = (userRecord.customClaims as Record<string, unknown>) ?? {};
  const alreadyAdmin = currentClaims['admin'] === true;

  console.log('\n=== Admin Claim Assignment ===');
  console.log(`Firebase UID : ${uid}`);
  console.log(`Email        : ${userRecord.email ?? '(not set)'}`);
  console.log(`Display name : ${userRecord.displayName ?? '(not set)'}`);
  console.log(`Current claims: ${JSON.stringify(currentClaims)}`);
  console.log(`Intended change: set  admin: true`);

  if (alreadyAdmin) {
    console.log('\nThis user already has admin: true. No change is needed.');
    process.exit(0);
  }

  console.log('\n⚠️  WARNING: This grants full admin access to the account above.');
  console.log('Ensure this is the correct UID before confirming.\n');

  const confirmed = await promptConfirmation('Confirm? [y/N] ');
  if (!confirmed) {
    console.log('Aborted. No changes were made.');
    process.exit(0);
  }

  const newClaims = { ...currentClaims, admin: true };
  await auth.setCustomUserClaims(uid, newClaims);

  console.log(`\n✅ admin: true has been set for UID ${uid}.`);
  console.log(
    'Existing tokens will carry the old claims until they expire (≤ 1 hour).',
  );
  console.log(
    "To force an immediate token refresh, revoke the user's refresh tokens:",
  );
  console.log(`  firebase auth:revoke ${uid}`);
}

function promptConfirmation(question: string): Promise<boolean> {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    rl.question(question, (answer) => {
      rl.close();
      resolve(answer.trim().toLowerCase() === 'y');
    });
  });
}

main().catch((error: unknown) => {
  console.error('Unexpected error:', error instanceof Error ? error.message : String(error));
  process.exit(1);
});
