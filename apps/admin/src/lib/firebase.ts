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
import { getAuth, type Auth } from 'firebase/auth';

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY as string | undefined,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN as string | undefined,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID as string | undefined,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET as string | undefined,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID as string | undefined,
  appId: import.meta.env.VITE_FIREBASE_APP_ID as string | undefined,
};

function getFirebaseApp(): FirebaseApp {
  const existing = getApps();
  if (existing.length > 0) {
    return existing[0]!;
  }
  return initializeApp(firebaseConfig);
}

export function getFirebaseAuth(): Auth {
  return getAuth(getFirebaseApp());
}
