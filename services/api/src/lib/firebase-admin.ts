/**
 * Firebase Admin SDK initialization.
 *
 * Uses Application Default Credentials (ADC). In production, ADC resolves
 * through Workload Identity Federation or an attached service account.
 * In local development, set GOOGLE_APPLICATION_CREDENTIALS to a service
 * account key file path (never commit the file to version control).
 *
 * @see https://cloud.google.com/docs/authentication/application-default-credentials
 */

import { getApps, initializeApp, type App } from 'firebase-admin/app';
import { getAuth, type Auth } from 'firebase-admin/auth';

const APP_NAME = 'carcommunity-api';

/**
 * Returns the initialized Firebase Admin App, creating it on first call.
 * If FIREBASE_PROJECT_ID is provided it is passed explicitly; otherwise ADC
 * picks up the project from the environment automatically.
 */
export function getFirebaseAdminApp(projectId?: string): App {
  const existing = getApps().find((a) => a.name === APP_NAME);
  if (existing) {
    return existing;
  }

  return initializeApp(
    projectId ? { projectId } : undefined,
    APP_NAME,
  );
}

/**
 * Returns the Firebase Admin Auth instance, initializing the app if needed.
 */
export function getFirebaseAdminAuth(projectId?: string): Auth {
  return getAuth(getFirebaseAdminApp(projectId));
}
