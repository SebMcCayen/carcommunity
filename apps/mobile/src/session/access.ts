/**
 * Mobile access helpers for UI gating.
 *
 * @remarks These helpers are for user experience only. They use the shared
 *   access helpers backed by data returned from the backend.
 *
 *   TODO: UI gating is only for user experience (hiding/showing features).
 *     The backend MUST enforce all access decisions independently.
 *     Never unlock features purely client-side. The backend is the source of truth.
 *
 *   TODO: Enforce blocking once the blocking graph is available — blocking checks
 *     must be performed server-side and reflected in backend responses.
 */

import {
  canAccessAdminFeatures,
  canAccessMemberFeatures,
  canShareOwnLiveLocation,
} from '@carcommunity/shared/users';
import { canViewOtherUsersLiveLocation } from '@carcommunity/shared/live-location';

import type { MobileSessionUser } from './types';

/**
 * Returns true if the current user's session indicates member features are available.
 * Backend must enforce this check before returning any member-only data.
 */
export function currentUserCanAccessMemberFeatures(user: MobileSessionUser | null): boolean {
  if (!user) return false;
  return canAccessMemberFeatures(user);
}

/**
 * Returns true if the current user's session indicates admin features are available in the UI.
 * Backend must enforce admin role independently before serving any admin data.
 */
export function currentUserCanAccessAdminFeatures(user: MobileSessionUser | null): boolean {
  if (!user) return false;
  return canAccessAdminFeatures(user);
}

/**
 * Returns true if the current user's session indicates they can view other users' live locations.
 * Backend must enforce entitlement and visibility rules before returning any location data.
 */
export function currentUserCanViewOtherLiveLocations(user: MobileSessionUser | null): boolean {
  if (!user) return false;
  return canViewOtherUsersLiveLocation(user);
}

/**
 * Returns true if the current user's session indicates they can start a live location session.
 * Backend must enforce suspension and feature-flag checks before allowing a session to start.
 */
export function currentUserCanShareOwnLiveLocation(user: MobileSessionUser | null): boolean {
  if (!user) return false;
  return canShareOwnLiveLocation(user);
}
