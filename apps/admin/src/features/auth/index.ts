/**
 * Admin authentication — placeholder.
 *
 * TODO: Implement admin login using Microsoft Entra ID (Azure AD) via the
 *   MSAL browser SDK or a server-side OAuth code flow. Do NOT use simple
 *   username/password authentication for the admin portal.
 *
 * Security requirements (MUST be enforced before any admin feature is live):
 *
 * - TODO: All admin access decisions MUST be verified by backend role checks.
 *   The backend returns the authoritative role for each user (Role enum:
 *   'user' | 'admin' | 'owner'). Never trust a role or admin flag that
 *   originates from the client side.
 *
 * - TODO: Gate every admin route/page behind a server-side role assertion that
 *   calls the backend /v1/auth/me endpoint and checks the returned roles array.
 *
 * - TODO: Do NOT cache or persist role information in the browser beyond the
 *   active authenticated session.
 */

export {};
