/**
 * Firebase client SDK initialization for the admin web application.
 *
 * Configuration is read from Vite environment variables (VITE_* prefix).
 * Never commit real credentials to version control — use .env.local
 * (which is git-ignored) for local development values.
 *
 * The Firebase app is initialized once as a module-level singleton.
 */

import { initializeApp, getApps, type FirebaseApp } from 'firebase/app';
import {
  initializeAppCheck,
  ReCaptchaV3Provider,
  type AppCheck,
} from 'firebase/app-check';
import { getAuth, type Auth } from 'firebase/auth';

function requireEnv(name: keyof ImportMetaEnv): string {
  const value = import.meta.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

const firebaseConfig = {
  apiKey: requireEnv('VITE_FIREBASE_API_KEY'),
  authDomain: requireEnv('VITE_FIREBASE_AUTH_DOMAIN'),
  projectId: requireEnv('VITE_FIREBASE_PROJECT_ID'),
  storageBucket: requireEnv('VITE_FIREBASE_STORAGE_BUCKET'),
  messagingSenderId: requireEnv('VITE_FIREBASE_MESSAGING_SENDER_ID'),
  appId: requireEnv('VITE_FIREBASE_APP_ID'),
};

let appCheckInstance: AppCheck | null = null;

/**
 * Registers Firebase App Check (Phase 15c) with the reCAPTCHA v3
 * provider. Registration is a no-op until VITE_APPCHECK_SITE_KEY is
 * configured (console provisioning at cutover) — clients degrade
 * gracefully while enforcement is off server-side per docs/app-check.md.
 * In non-production builds VITE_APPCHECK_DEBUG_TOKEN feeds the debug
 * provider for emulator/CI runs.
 */
function registerAppCheck(app: FirebaseApp): void {
  if (appCheckInstance) return;
  const siteKey = import.meta.env.VITE_APPCHECK_SITE_KEY as string | undefined;
  if (!siteKey) return;
  if (!import.meta.env.PROD) {
    const debugToken = import.meta.env.VITE_APPCHECK_DEBUG_TOKEN as string | undefined;
    if (debugToken) {
      (globalThis as Record<string, unknown>).FIREBASE_APPCHECK_DEBUG_TOKEN = debugToken;
    }
  }
  appCheckInstance = initializeAppCheck(app, {
    provider: new ReCaptchaV3Provider(siteKey),
    isTokenAutoRefreshEnabled: true,
  });
}

export function getFirebaseApp(): FirebaseApp {
  const existing = getApps();
  if (existing.length > 0) {
    // App Check registration is idempotent (guarded by appCheckInstance):
    // register even when someone else initialized the app first.
    registerAppCheck(existing[0]!);
    return existing[0]!;
  }
  const app = initializeApp(firebaseConfig);
  registerAppCheck(app);
  return app;
}

export function getFirebaseAuth(): Auth {
  return getAuth(getFirebaseApp());
}
