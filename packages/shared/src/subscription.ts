/**
 * Shared subscription entitlement contract used across API, mobile, and admin.
 *
 * Backend is the source of truth for all subscription and entitlement decisions.
 * Client-side checks are for user experience only.
 *
 * MVP entitlements: none | member_monthly
 * No annual plan, no supporter tier, no multiple membership levels in MVP.
 */

import type { SubscriptionEntitlement } from './users.js';

// ---------------------------------------------------------------------------
// Platform
// ---------------------------------------------------------------------------

export const SUBSCRIPTION_PLATFORMS = ['apple', 'google', 'manual'] as const;
export type SubscriptionPlatform = (typeof SUBSCRIPTION_PLATFORMS)[number];

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

export const SUBSCRIPTION_STATUSES = [
  'inactive',
  'active',
  'grace_period',
  'expired',
  'revoked',
  'cancelled',
] as const;
export type SubscriptionStatus = (typeof SUBSCRIPTION_STATUSES)[number];

// ---------------------------------------------------------------------------
// Contracts
// ---------------------------------------------------------------------------

/**
 * Safe, provider-agnostic subscription summary.
 * Never exposes raw provider tokens or sensitive receipt data.
 */
export interface SubscriptionSourceSummary {
  platform: SubscriptionPlatform;
  status: SubscriptionStatus;
  entitlement: SubscriptionEntitlement;
  startsAt: string | null;
  expiresAt: string | null;
}

/**
 * Admin-facing subscription summary for a specific user.
 * Never exposes raw provider tokens or full external identifiers.
 */
export interface AdminUserSubscriptionSummary {
  userId: string;
  entitlement: SubscriptionEntitlement;
  subscription: SubscriptionSourceSummary | null;
  /** True if the user is suspended while still holding an active subscription. */
  isSuspendedWithActiveSubscription: boolean;
}

// ---------------------------------------------------------------------------
// Helper functions
// ---------------------------------------------------------------------------

/**
 * Returns true if the subscription status grants or continues access.
 * grace_period is included to allow brief continuation after expiry.
 */
export function isSubscriptionActiveStatus(status: SubscriptionStatus): boolean {
  return status === 'active' || status === 'grace_period';
}

