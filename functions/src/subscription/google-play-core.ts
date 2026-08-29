/**
 * Pure Google Play subscriptionsv2 response validation and lifecycle mapping.
 *
 * This module deliberately accepts `unknown`: Android Publisher responses are
 * an external trust boundary. No entitlement decision is made until product,
 * Firebase-account binding, state, and timestamps have all been validated
 * here. The package name is pinned separately in the Android Publisher URL.
 */

import { createHash } from 'node:crypto';
import {
  PLUS_MONTHLY_PRODUCT_ID,
  SUPPORTER_MONTHLY_PRODUCT_ID,
  type SubscriptionEntitlement,
  type SubscriptionStatus,
  type SubscriptionTier,
} from './subscription-core';

export const GOOGLE_PLAY_PACKAGE_NAME = 'com.kungsbackacarcommunity.app' as const;
export const GOOGLE_PLAY_RUNTIME_SERVICE_ACCOUNT =
  'play-subscription-verifier@kungsbacka-car-community.iam.gserviceaccount.com' as const;

export const GOOGLE_PLAY_PRODUCT_IDS = [
  PLUS_MONTHLY_PRODUCT_ID,
  SUPPORTER_MONTHLY_PRODUCT_ID,
] as const;

export type GooglePlayProductId = (typeof GOOGLE_PLAY_PRODUCT_IDS)[number];

export type GooglePlayVerificationErrorCode =
  'malformed_response' | 'account_mismatch' | 'unsupported_product' | 'unknown_state';

export class GooglePlayVerificationError extends Error {
  constructor(
    readonly code: GooglePlayVerificationErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'GooglePlayVerificationError';
  }
}

export interface GooglePlayEntitlementOutcome {
  productId: GooglePlayProductId;
  tier: SubscriptionTier;
  status: SubscriptionStatus;
  entitlement: SubscriptionEntitlement;
  startsAt: Date | null;
  expiresAt: Date | null;
  acknowledgementRequired: boolean;
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function malformed(message: string): never {
  throw new GooglePlayVerificationError('malformed_response', message);
}

function parseTimestamp(value: unknown, field: string): Date | null {
  if (value == null) return null;
  if (typeof value !== 'string') malformed(`${field} must be an RFC 3339 string.`);
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) malformed(`${field} is not a valid timestamp.`);
  return parsed;
}

function parseProductId(value: unknown): GooglePlayProductId {
  if (value === PLUS_MONTHLY_PRODUCT_ID || value === SUPPORTER_MONTHLY_PRODUCT_ID) {
    return value;
  }
  throw new GooglePlayVerificationError(
    'unsupported_product',
    'The Play purchase does not contain an allowed subscription product.',
  );
}

function tierForProduct(productId: GooglePlayProductId): SubscriptionTier {
  return productId === SUPPORTER_MONTHLY_PRODUCT_ID ? 'supporter' : 'plus';
}

/**
 * One-way, deterministic Firebase UID binding sent to Play as the obfuscated
 * account id. SHA-256 hex is exactly 64 ASCII characters and contains no raw
 * UID or other PII.
 */
export function obfuscatedAccountIdForUid(uid: string): string {
  return createHash('sha256').update(uid, 'utf8').digest('hex');
}

/**
 * Validates and maps a purchases.subscriptionsv2.get response.
 *
 * ACTIVE, IN_GRACE_PERIOD, and CANCELED-before-expiry retain entitlement.
 * PENDING, PAUSED, ON_HOLD, PENDING_PURCHASE_CANCELED, and EXPIRED do not.
 * Unknown or internally inconsistent responses throw and therefore fail closed.
 */
export function parseGooglePlaySubscription(
  value: unknown,
  expectedObfuscatedAccountId: string,
  now: Date = new Date(),
): GooglePlayEntitlementOutcome {
  if (!isRecord(value)) malformed('Play response must be an object.');
  if (value.kind !== 'androidpublisher#subscriptionPurchaseV2') {
    malformed('Unexpected Play response kind.');
  }

  const external = value.externalAccountIdentifiers;
  if (!isRecord(external)) {
    throw new GooglePlayVerificationError(
      'account_mismatch',
      'Play response has no external account binding.',
    );
  }
  if (external.obfuscatedExternalAccountId !== expectedObfuscatedAccountId) {
    throw new GooglePlayVerificationError(
      'account_mismatch',
      'Play purchase is bound to a different Firebase account.',
    );
  }

  if (!Array.isArray(value.lineItems) || value.lineItems.length === 0) {
    malformed('Play response contains no subscription line items.');
  }

  const lineItems = value.lineItems.map((lineItem, index) => {
    if (!isRecord(lineItem)) malformed(`lineItems[${index}] must be an object.`);
    return {
      productId: parseProductId(lineItem.productId),
      expiryTime: parseTimestamp(lineItem.expiryTime, `lineItems[${index}].expiryTime`),
    };
  });
  const productIds = new Set(lineItems.map((item) => item.productId));
  if (productIds.size !== 1) {
    malformed('Play response contains ambiguous subscription products.');
  }
  const productId = lineItems[0]?.productId;
  if (productId === undefined) malformed('Play response contains no product.');
  const expiries = lineItems
    .map((item) => item.expiryTime)
    .filter((expiry): expiry is Date => expiry !== null);
  const expiresAt =
    expiries.length === 0
      ? null
      : new Date(Math.max(...expiries.map((expiry) => expiry.getTime())));
  const startsAt = parseTimestamp(value.startTime, 'startTime');

  const acknowledgementState = value.acknowledgementState;
  if (
    acknowledgementState !== 'ACKNOWLEDGEMENT_STATE_PENDING' &&
    acknowledgementState !== 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED'
  ) {
    malformed('Unknown or missing acknowledgement state.');
  }
  const acknowledgementRequired = acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING';

  const state = value.subscriptionState;
  const nowMs = now.getTime();
  const expiryMs = expiresAt?.getTime() ?? null;
  const futureExpiry = expiryMs !== null && expiryMs > nowMs;

  switch (state) {
    case 'SUBSCRIPTION_STATE_ACTIVE':
      if (!futureExpiry) malformed('Active Play subscription has no future expiry.');
      return {
        productId,
        tier: tierForProduct(productId),
        status: 'active',
        entitlement: 'member_monthly',
        startsAt,
        expiresAt,
        acknowledgementRequired,
      };
    case 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD':
      if (!futureExpiry) malformed('Grace-period Play subscription has no future expiry.');
      return {
        productId,
        tier: tierForProduct(productId),
        status: 'grace_period',
        entitlement: 'member_monthly',
        startsAt,
        expiresAt,
        acknowledgementRequired,
      };
    case 'SUBSCRIPTION_STATE_CANCELED':
      if (!futureExpiry) malformed('Canceled Play subscription is already expired.');
      return {
        productId,
        tier: tierForProduct(productId),
        status: 'cancelled',
        entitlement: 'member_monthly',
        startsAt,
        expiresAt,
        acknowledgementRequired,
      };
    case 'SUBSCRIPTION_STATE_EXPIRED':
      if (expiryMs === null || expiryMs > nowMs) {
        malformed('Expired Play subscription has an inconsistent expiry.');
      }
      return {
        productId,
        tier: tierForProduct(productId),
        status: 'expired',
        entitlement: 'none',
        startsAt,
        expiresAt,
        acknowledgementRequired: false,
      };
    case 'SUBSCRIPTION_STATE_PENDING':
    case 'SUBSCRIPTION_STATE_PAUSED':
    case 'SUBSCRIPTION_STATE_ON_HOLD':
    case 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED':
      return {
        productId,
        tier: tierForProduct(productId),
        status: 'inactive',
        entitlement: 'none',
        startsAt,
        expiresAt,
        acknowledgementRequired: false,
      };
    case 'SUBSCRIPTION_STATE_UNSPECIFIED':
    default:
      throw new GooglePlayVerificationError(
        'unknown_state',
        'Unknown Google Play subscription state.',
      );
  }
}
