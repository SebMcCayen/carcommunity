/**
 * Shared subscription entitlement contract used across API, mobile, and admin.
 *
 * Backend is the source of truth for all subscription and entitlement decisions.
 * Client-side checks are for user experience only.
 *
 * Legacy entitlements remain `none | member_monthly`; new code should use the
 * brand-neutral Community / Plus / Supporter tier contract below. The legacy
 * paid entitlement maps explicitly to Plus.
 */

import type { SubscriptionEntitlement } from './users.js';

// ---------------------------------------------------------------------------
// Plans and capabilities
// ---------------------------------------------------------------------------

export const SUBSCRIPTION_TIERS = ['community', 'plus', 'supporter'] as const;
export type SubscriptionTier = (typeof SUBSCRIPTION_TIERS)[number];

/** Immutable store identifiers. Never derive these from display names. */
export const PLUS_MONTHLY_PRODUCT_ID = 'plus_monthly' as const;
export const SUPPORTER_MONTHLY_PRODUCT_ID = 'supporter_monthly' as const;
export const SUBSCRIPTION_PRODUCT_IDS = [
  PLUS_MONTHLY_PRODUCT_ID,
  SUPPORTER_MONTHLY_PRODUCT_ID,
] as const;
export type SubscriptionProductId = (typeof SUBSCRIPTION_PRODUCT_IDS)[number];

export const SUBSCRIPTION_ACCESS_CAPABILITIES = [
  'exactOtherUserLivePositions',
  'fullEventDetails',
  'eventCheckIn',
  'attendeeNames',
  'partnerOffers',
] as const;
export type SubscriptionAccessCapability = (typeof SUBSCRIPTION_ACCESS_CAPABILITIES)[number];

export type DriveHistoryLimit =
  { kind: 'latest'; count: 5 } | { kind: 'rolling_days'; days: 90 } | { kind: 'unlimited' };

export interface SubscriptionCapabilityProfile {
  tier: SubscriptionTier;
  /**
   * Approved Swedish list price for planning and admin reporting only.
   * Checkout must display the localized price supplied by the app store.
   */
  monthlyPriceSek: 39 | 119 | null;
  billingPeriod: 'monthly' | null;
  productId: SubscriptionProductId | null;
  hasIntroDiscount: false;
  garageVehicleLimit: 2 | 5 | 10;
  driveHistory: DriveHistoryLimit;
  /** Legacy/future-only capability; does not gate current map visibility. */
  exactOtherUserLivePositions: boolean;
  fullEventDetails: boolean;
  eventCheckIn: boolean;
  attendeeNames: boolean;
  partnerOffers: boolean;
  supporterBadge: {
    available: boolean;
    /** Supporters may override this presentation default in their profile settings. */
    defaultVisible: boolean;
  };
}

/** User-controlled presentation setting; meaningful only for Supporter. */
export interface SupporterBadgePreference {
  visible: boolean;
}

/** Central product-policy source of truth for clients and shared tooling. */
export const SUBSCRIPTION_CAPABILITY_PROFILES = {
  community: {
    tier: 'community',
    monthlyPriceSek: null,
    billingPeriod: null,
    productId: null,
    hasIntroDiscount: false,
    garageVehicleLimit: 2,
    driveHistory: { kind: 'latest', count: 5 },
    exactOtherUserLivePositions: false,
    fullEventDetails: true,
    eventCheckIn: false,
    attendeeNames: false,
    partnerOffers: false,
    supporterBadge: { available: false, defaultVisible: false },
  },
  plus: {
    tier: 'plus',
    monthlyPriceSek: 39,
    billingPeriod: 'monthly',
    productId: PLUS_MONTHLY_PRODUCT_ID,
    hasIntroDiscount: false,
    garageVehicleLimit: 5,
    driveHistory: { kind: 'rolling_days', days: 90 },
    exactOtherUserLivePositions: true,
    fullEventDetails: true,
    eventCheckIn: true,
    attendeeNames: true,
    partnerOffers: true,
    supporterBadge: { available: false, defaultVisible: false },
  },
  supporter: {
    tier: 'supporter',
    monthlyPriceSek: 119,
    billingPeriod: 'monthly',
    productId: SUPPORTER_MONTHLY_PRODUCT_ID,
    hasIntroDiscount: false,
    garageVehicleLimit: 10,
    driveHistory: { kind: 'unlimited' },
    exactOtherUserLivePositions: true,
    fullEventDetails: true,
    eventCheckIn: true,
    attendeeNames: true,
    partnerOffers: true,
    supporterBadge: { available: true, defaultVisible: true },
  },
} as const satisfies Record<SubscriptionTier, SubscriptionCapabilityProfile>;

const TIER_RANK: Record<SubscriptionTier, number> = {
  community: 0,
  plus: 1,
  supporter: 2,
};

/** Compatibility boundary for data written before tier fields existed. */
export function subscriptionTierForLegacyEntitlement(
  entitlement: SubscriptionEntitlement,
): SubscriptionTier {
  return entitlement === 'member_monthly' ? 'plus' : 'community';
}

/** Resolves effective access for old records while preserving explicit paid tiers. */
export function resolveSubscriptionTier(input: {
  entitlement: SubscriptionEntitlement;
  tier?: SubscriptionTier | null;
}): SubscriptionTier {
  // A stored tier may be retained after expiry/revocation for lifecycle
  // reporting, but `none` must always resolve to free Community access.
  if (input.entitlement === 'none') return 'community';
  return input.tier ?? 'plus';
}

export function isPaidSubscriptionTier(tier: SubscriptionTier): boolean {
  return tier === 'plus' || tier === 'supporter';
}

/** Compatibility projection for existing activeMember readers. */
export function grantsLegacyActiveMember(input: {
  entitlement: SubscriptionEntitlement;
  status: SubscriptionStatus;
  tier?: SubscriptionTier | null;
}): boolean {
  return (
    input.entitlement === 'member_monthly' &&
    isPaidSubscriptionTier(resolveSubscriptionTier(input)) &&
    isSubscriptionActiveStatus(input.status)
  );
}

export function isTierAtLeast(tier: SubscriptionTier, required: SubscriptionTier): boolean {
  return TIER_RANK[tier] >= TIER_RANK[required];
}

export function hasSubscriptionCapability(
  tier: SubscriptionTier,
  capability: SubscriptionAccessCapability,
): boolean {
  return SUBSCRIPTION_CAPABILITY_PROFILES[tier][capability];
}

function driveHistoryRank(limit: DriveHistoryLimit): number {
  switch (limit.kind) {
    case 'latest':
      return limit.count;
    case 'rolling_days':
      return 10_000 + limit.days;
    case 'unlimited':
      return Number.POSITIVE_INFINITY;
  }
}

/**
 * Proves capability monotonicity, independent of tier names and prices.
 * A superset must retain every boolean capability and improve at least one
 * limit or optional capability.
 */
export function isProvableCapabilitySuperset(
  candidate: SubscriptionCapabilityProfile,
  base: SubscriptionCapabilityProfile,
): boolean {
  const retainsCapabilities =
    SUBSCRIPTION_ACCESS_CAPABILITIES.every(
      (capability) => !base[capability] || candidate[capability],
    ) &&
    (!base.supporterBadge.available || candidate.supporterBadge.available);
  const retainsLimits =
    candidate.garageVehicleLimit >= base.garageVehicleLimit &&
    driveHistoryRank(candidate.driveHistory) >= driveHistoryRank(base.driveHistory);
  const strictlyImproves =
    candidate.garageVehicleLimit > base.garageVehicleLimit ||
    driveHistoryRank(candidate.driveHistory) > driveHistoryRank(base.driveHistory) ||
    (!base.supporterBadge.available && candidate.supporterBadge.available);
  return retainsCapabilities && retainsLimits && strictlyImproves;
}

export function isTierCapabilitySuperset(
  candidate: SubscriptionTier,
  base: SubscriptionTier,
): boolean {
  return isProvableCapabilitySuperset(
    SUBSCRIPTION_CAPABILITY_PROFILES[candidate],
    SUBSCRIPTION_CAPABILITY_PROFILES[base],
  );
}

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
  /** Optional while legacy records are backfilled; resolve missing values from entitlement. */
  tier?: SubscriptionTier;
  /** Optional on rolling legacy records; null means the historical start is unknown. */
  startsAt?: string | null;
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
 * grace_period continues access while payment retries; cancelled continues
 * access only until the already-paid expiresAt boundary enforced by backend.
 */
export function isSubscriptionActiveStatus(status: SubscriptionStatus): boolean {
  return status === 'active' || status === 'grace_period' || status === 'cancelled';
}
