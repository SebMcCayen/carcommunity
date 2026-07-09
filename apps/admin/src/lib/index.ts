/**
 * Shared utility library for the admin portal.
 */

export { signInWithGoogle, signOut, checkAdminClaim, getCurrentIdToken, onAdminAuthStateChanged } from './auth';

// Date/string formatting helpers live in a Firebase-free module. Import them
// from `@/lib/format` directly to avoid initializing the Firebase SDK. They are
// re-exported here for backwards compatibility with existing `@/lib` callers,
// but note that this barrel also re-exports Firebase-dependent modules (via
// `./auth`), so importing from `@/lib` still pulls in the Firebase SDK.
export { formatDate, formatDateOnly, formatTimeOnly, truncate } from './format';
