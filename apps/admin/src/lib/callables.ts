/**
 * Callable Cloud Functions client for the admin portal (Phase 13a).
 *
 * The migration target for every admin mutation: callables carry the
 * Firebase ID token automatically, the backend independently verifies
 * the `admin` custom claim on each call, and sensitive actions produce
 * adminAuditEvents records server-side. This replaces the legacy
 * `apiRequest` REST client feature by feature (see the Phase 13
 * checklist in docs/migration/native-firebase-migration-plan.md).
 *
 * Deployed callable names are the grouped-export form `domain-action`
 * (contracts/functions/functions.json).
 */

import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { FirebaseError } from 'firebase/app';
import { getFirebaseApp } from './firebase';
import { ApiError } from './api';

/** All Cloud Functions deploy to europe-west1 (docs/api-guidelines.md). */
const FUNCTIONS_REGION = 'europe-west1';

let functionsInstance: Functions | null = null;
let emulatorConnected = false;

export function getAdminFunctions(): Functions {
  if (!functionsInstance) {
    functionsInstance = getFunctions(getFirebaseApp(), FUNCTIONS_REGION);
  }
  const emulatorHost = import.meta.env.VITE_FUNCTIONS_EMULATOR_HOST as string | undefined;
  if (emulatorHost && !emulatorConnected && !import.meta.env.PROD) {
    const [host, port] = emulatorHost.split(':');
    connectFunctionsEmulator(functionsInstance, host ?? '127.0.0.1', Number(port ?? 5001));
    emulatorConnected = true;
  }
  return functionsInstance;
}

/** functions/* error codes → HTTP-ish status codes for ApiError parity. */
const CODE_TO_STATUS: Record<string, number> = {
  'invalid-argument': 400,
  unauthenticated: 401,
  'permission-denied': 403,
  'not-found': 404,
  'already-exists': 409,
  'failed-precondition': 412,
  'resource-exhausted': 429,
  unimplemented: 501,
};

/**
 * Invokes a callable by its deployed name and normalizes errors into the
 * ApiError shape existing pages already handle.
 */
export async function callAdmin<TResponse>(name: string, data: unknown): Promise<TResponse> {
  try {
    const result = await httpsCallable(getAdminFunctions(), name)(data);
    return result.data as TResponse;
  } catch (error) {
    if (error instanceof FirebaseError) {
      const code = error.code.replace(/^functions\//, '');
      throw new ApiError(CODE_TO_STATUS[code] ?? 500, code, error.message);
    }
    throw error;
  }
}
