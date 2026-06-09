/**
 * Admin auth placeholder.
 *
 * TODO: Implement Microsoft Entra ID authentication flow for admin users.
 * TODO: Enforce admin access in backend before production rollout.
 * TODO: Never trust client-side admin flags for authorization.
 * TODO: Gate every admin route/page behind a server-side role assertion that
 *   calls the backend /v1/auth/me endpoint and checks the returned roles array.
 * TODO: Do NOT cache or persist role information in the browser beyond the
 *   active authenticated session.
 */

import { canAccessAdminFeatures } from '@carcommunity/shared/users';
import type { UserRole, UserStatus } from '@carcommunity/shared/users';

export const ADMIN_AUTH_PLACEHOLDER_NOTE =
  'Admin authentication is a backend-enforced future integration with Microsoft Entra ID.';

/**
 * UI-only helper for admin area access decisions.
 *
 * This function is for user experience gating only (e.g. hiding/showing admin
 * navigation). It does NOT constitute a security boundary.
 *
 * Security requirements:
 * - The backend MUST verify admin role independently for every admin request.
 * - Client-side checks are NOT security boundaries.
 * - Never trust a role or admin flag that originates purely from the client side.
 *
 * TODO: Replace with actual backend role check via /v1/auth/me once
 *   Microsoft Entra ID integration is implemented.
 */
export function uiCanAccessAdminArea(user: { role: UserRole; status: UserStatus } | null): boolean {
  if (!user) return false;
  // UI hint only — backend must validate admin role independently.
  return canAccessAdminFeatures(user);
}

