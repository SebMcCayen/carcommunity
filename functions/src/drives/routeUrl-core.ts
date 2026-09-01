/**
 * Pure logic for drives.routeUrl — a short-lived signed download URL for a
 * saved drive's full GPS route file (rideRoutes/{uid}/{rideId}/route.bin).
 *
 * This module is Firebase-Admin-free so the authorization decision, the
 * subscription-tier visibility re-check and the signing fail-safe can be unit
 * tested without the Storage emulator (whose ADC-less runtime cannot produce a
 * real V4 signature — see routeUrl.ts).
 *
 * WHY THE TIER RE-CHECK LIVES HERE
 * --------------------------------
 * drives.listHistory already hides drives beyond a member's tier window
 * (Community: newest 5; Plus: rolling 90 days; Supporter: all — see
 * driveHistory-core.ts). But listHistory only decides what the app is SHOWN;
 * it does not gate the route file. Without an independent re-check, a member
 * who downgraded (or simply guesses a rideId) could ask for the route of a
 * drive that is hidden beyond their window and replay it. So this callable
 * re-derives the exact same visibility from server state and denies anything
 * outside it, using the SAME policy source (driveHistoryPolicyForTier) so the
 * two paths cannot drift.
 */

import { z } from 'zod';
import { driveHistoryPolicyForTier } from './driveHistory-core';
import type { SubscriptionTier } from '../subscription/subscription-core';

/**
 * Signed URL lifetime. Deliberately short: the URL is handed straight to the
 * client for an immediate download, so five minutes covers a slow device or a
 * retry without leaving a long-lived, forwardable link to private GPS data.
 */
export const ROUTE_URL_EXPIRY_MS = 5 * 60 * 1000;

/**
 * Shown when a drive exists and is owned by the caller but falls outside their
 * subscription tier's visible window (the downgrade-replay guard above).
 */
export const ROUTE_UPGRADE_MESSAGE =
  'This drive is outside your plan’s history window. Upgrade to access older routes.';

/**
 * Shown when the route file cannot be signed. This is the operator-IAM
 * fail-safe (see routeUrl.ts) — never a leak of the underlying signing error.
 */
export const ROUTE_UNAVAILABLE_MESSAGE = 'Route replay is temporarily unavailable.';

/** Shown when the drive is visible and owned, but has no stored route file. */
export const ROUTE_MISSING_MESSAGE = 'This drive has no stored route to replay.';

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

// Firestore-safe document ID, mirroring drives-core.ts rideIdSchema (kept local
// so this additive module does not widen drives-core's export surface — keeps a
// merge with the sibling drives.stats/listDeletable PR trivial).
const routeUrlInputSchema = z
  .object({
    rideId: z
      .string()
      .trim()
      .min(1)
      .max(300)
      .regex(/^[A-Za-z0-9._-]+$/)
      .refine((id) => id !== '.' && id !== '..'),
  })
  .strict();

export type RouteUrlInput = z.infer<typeof routeUrlInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseRouteUrlInput(data: unknown): ParseResult<RouteUrlInput> {
  const result = routeUrlInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: 'Expected { rideId: string (1..300, Firestore-safe id) }.' };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Tier visibility re-check
// ---------------------------------------------------------------------------

export type RouteVisibility = { visible: true } | { visible: false; message: string };

/**
 * Decides whether the caller's tier may access this ride's route right now.
 *
 * - Supporter: always visible (unlimited history).
 * - Plus: visible only if the ride was created within the rolling 90-day
 *   window measured from SERVER time (a null/unknown createdAt fails closed).
 * - Community: visible only if the ride is among the caller's newest five,
 *   which the caller determines with the SAME ordered query listHistory uses
 *   and passes in as `isAmongNewestForCommunity` (createdAt ties included).
 */
export function decideRouteVisibility(params: {
  tier: SubscriptionTier;
  rideCreatedAtMillis: number | null;
  serverNowMillis: number;
  isAmongNewestForCommunity: boolean;
}): RouteVisibility {
  const { tier, rideCreatedAtMillis, serverNowMillis, isAmongNewestForCommunity } = params;

  if (tier === 'supporter') return { visible: true };

  if (tier === 'plus') {
    const policy = driveHistoryPolicyForTier('plus', serverNowMillis);
    if (
      policy.kind === 'rolling_days' &&
      rideCreatedAtMillis != null &&
      rideCreatedAtMillis >= policy.cutoffMillis
    ) {
      return { visible: true };
    }
    return { visible: false, message: ROUTE_UPGRADE_MESSAGE };
  }

  // Community.
  return isAmongNewestForCommunity
    ? { visible: true }
    : { visible: false, message: ROUTE_UPGRADE_MESSAGE };
}

// ---------------------------------------------------------------------------
// Signing fail-safe
// ---------------------------------------------------------------------------

export interface SignedRouteUrl {
  url: string;
  expiresAtMillis: number;
}

/** Produces a V4 signed read URL that expires at the given epoch-millis. */
export type RouteSigner = (expiresAtMillis: number) => Promise<string>;

export type SignRouteUrlResult = { ok: true; value: SignedRouteUrl } | { ok: false };

/**
 * Signs the route URL, converting ANY signer failure (or an empty/invalid URL)
 * into a typed `{ ok: false }` the callable maps to failed-precondition. The
 * raw error is never propagated — V4 signing failures embed the runtime
 * service-account email and IAM detail, which must not reach the client. The
 * optional `onError` hook lets the callable log the real cause for triage.
 */
export async function signRouteUrl(
  signer: RouteSigner,
  serverNowMillis: number,
  onError?: (error: unknown) => void,
): Promise<SignRouteUrlResult> {
  const expiresAtMillis = serverNowMillis + ROUTE_URL_EXPIRY_MS;
  try {
    const url = await signer(expiresAtMillis);
    if (typeof url !== 'string' || url.length === 0) {
      onError?.(new Error('signer returned an empty URL'));
      return { ok: false };
    }
    return { ok: true, value: { url, expiresAtMillis } };
  } catch (error) {
    onError?.(error);
    return { ok: false };
  }
}
