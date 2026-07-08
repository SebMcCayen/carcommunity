/**
 * Shared utility library for the admin portal.
 */

export { signInWithGoogle, signOut, checkAdminClaim, getCurrentIdToken, onAdminAuthStateChanged } from './auth';

// Date/string formatting helpers live in a Firebase-free module so they can be
// imported without initializing the Firebase SDK. Re-exported here for
// backwards compatibility with existing `@/lib` callers.
export { formatDate, formatDateOnly, formatTimeOnly, truncate } from './format';
