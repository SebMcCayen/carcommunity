/**
 * Admin authentication — Firebase Authentication with Google Sign-In.
 *
 * Authentication is implemented in src/lib/auth.ts and src/components/auth/.
 *
 * Security requirements (enforced before any admin feature goes live):
 *
 * - All admin access decisions MUST be verified by backend role checks.
 *   The backend checks the Firebase `admin: true` custom claim on every
 *   privileged request. Never trust a role or admin flag from the client.
 *
 * - Every protected route is guarded by ProtectedRoute which verifies the
 *   admin claim via Firebase ID token inspection.
 *
 * - Admin claim status is not cached or persisted beyond the active
 *   Firebase auth session.
 */

export {};
