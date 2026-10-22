import { createHash } from 'node:crypto';
import {
  PLUS_MONTHLY_PRODUCT_ID,
  SUPPORTER_MONTHLY_PRODUCT_ID,
  type SubscriptionEntitlement,
  type SubscriptionStatus,
  type SubscriptionTier,
} from './subscription-core';

export const APPLE_BUNDLE_ID = 'com.kungsbackacarcommunity.app' as const;
export const APPLE_PRODUCT_IDS = [PLUS_MONTHLY_PRODUCT_ID, SUPPORTER_MONTHLY_PRODUCT_ID] as const;

export type AppleProductId = (typeof APPLE_PRODUCT_IDS)[number];

export type AppleVerificationErrorCode =
  'malformed_response' | 'account_mismatch' | 'unsupported_product' | 'unsupported_transaction';

export class AppleVerificationError extends Error {
  constructor(
    readonly code: AppleVerificationErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'AppleVerificationError';
  }
}

export interface AppleEntitlementOutcome {
  productId: AppleProductId;
  tier: SubscriptionTier;
  status: SubscriptionStatus;
  entitlement: SubscriptionEntitlement;
  startsAt: Date | null;
  expiresAt: Date;
  /** Stable subscription-chain identifier. Only its SHA-256 hash is persisted. */
  originalTransactionId: string;
}

type UnknownRecord = Record<string, unknown>;

function malformed(message: string): never {
  throw new AppleVerificationError('malformed_response', message);
}

function timestamp(value: unknown, field: string, required = false): Date | null {
  if (value == null) {
    if (required) malformed(`${field} is required.`);
    return null;
  }
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) {
    malformed(`${field} must be a positive millisecond timestamp.`);
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) malformed(`${field} is invalid.`);
  return date;
}

function product(value: unknown): AppleProductId {
  if (value === PLUS_MONTHLY_PRODUCT_ID || value === SUPPORTER_MONTHLY_PRODUCT_ID) {
    return value;
  }
  throw new AppleVerificationError(
    'unsupported_product',
    'The Apple transaction does not contain an allowed subscription product.',
  );
}

/**
 * The same one-way account binding supplied to StoreKit as appAccountToken.
 * It is UUID-shaped because StoreKit requires a UUID, but contains no raw UID.
 */
export function appleAppAccountTokenForUid(uid: string): string {
  const bytes = createHash('sha256').update(uid, 'utf8').digest().subarray(0, 16);
  bytes[6] = (bytes[6]! & 0x0f) | 0x50;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Maps an already cryptographically verified Apple JWS payload. */
export function parseAppleTransaction(
  value: unknown,
  expectedAppAccountToken: string,
  now: Date = new Date(),
): AppleEntitlementOutcome {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) {
    malformed('Apple transaction must be an object.');
  }
  const transaction = value as UnknownRecord;
  if (transaction.type !== 'Auto-Renewable Subscription') {
    throw new AppleVerificationError(
      'unsupported_transaction',
      'Apple transaction is not an auto-renewable subscription.',
    );
  }
  if (transaction.inAppOwnershipType !== 'PURCHASED') {
    throw new AppleVerificationError(
      'unsupported_transaction',
      'Family-shared subscriptions are not account-bound purchases.',
    );
  }
  if (
    typeof transaction.appAccountToken !== 'string' ||
    transaction.appAccountToken.toLowerCase() !== expectedAppAccountToken.toLowerCase()
  ) {
    throw new AppleVerificationError(
      'account_mismatch',
      'Apple transaction is bound to a different account.',
    );
  }
  if (
    typeof transaction.originalTransactionId !== 'string' ||
    transaction.originalTransactionId.length === 0 ||
    transaction.originalTransactionId.length > 128
  ) {
    malformed('originalTransactionId is missing or malformed.');
  }

  const productId = product(transaction.productId);
  const startsAt = timestamp(transaction.purchaseDate, 'purchaseDate');
  const expiresAt = timestamp(transaction.expiresDate, 'expiresDate', true)!;
  const revokedAt = timestamp(transaction.revocationDate, 'revocationDate');
  const inactive = revokedAt !== null || transaction.isUpgraded === true || expiresAt <= now;

  return {
    productId,
    tier: productId === SUPPORTER_MONTHLY_PRODUCT_ID ? 'supporter' : 'plus',
    status: revokedAt !== null ? 'revoked' : inactive ? 'expired' : 'active',
    entitlement: inactive ? 'none' : 'member_monthly',
    startsAt,
    expiresAt,
    originalTransactionId: transaction.originalTransactionId,
  };
}
