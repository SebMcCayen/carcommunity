import { PLUS_MONTHLY_PRODUCT_ID, SUPPORTER_MONTHLY_PRODUCT_ID } from './subscription-core';

const STORE_PRODUCT_IDS = [PLUS_MONTHLY_PRODUCT_ID, SUPPORTER_MONTHLY_PRODUCT_ID] as const;
type StoreProductId = (typeof STORE_PRODUCT_IDS)[number];

export class PurchaseTokenOwnershipError extends Error {
  constructor(
    readonly reason:
      | 'different_user'
      | 'different_product'
      | 'different_active_token'
      | 'verification_in_progress'
      | 'malformed_record',
  ) {
    super('Purchase token ownership conflict.');
    this.name = 'PurchaseTokenOwnershipError';
  }
}

export interface CurrentEffectiveSubscription {
  grantsAccess: boolean;
  purchaseTokenHash: string | null;
  expiresAt: Date | null;
}

/**
 * One effective store purchase is retained at a time. A different token may
 * replace active access only when Play's verified response links it to the
 * currently stored token.
 */
export function assertNoDifferentActiveToken(
  current: CurrentEffectiveSubscription | null,
  nextPurchaseTokenHash: string,
  linkedPurchaseTokenHash: string | null,
  now: Date,
): void {
  if (current === null || !current.grantsAccess) return;
  if (current.expiresAt !== null && current.expiresAt.getTime() <= now.getTime()) return;
  if (current.purchaseTokenHash === nextPurchaseTokenHash) return;
  // Play authenticates linkedPurchaseToken as the purchase being replaced.
  // Its hash must match the currently effective token before a new token can
  // replace active access for this UID.
  if (
    current.purchaseTokenHash !== null &&
    linkedPurchaseTokenHash !== null &&
    current.purchaseTokenHash === linkedPurchaseTokenHash
  ) {
    return;
  }
  throw new PurchaseTokenOwnershipError('different_active_token');
}

export interface PurchaseTokenOwnershipRecord {
  uid: string;
  productId: StoreProductId;
}

/** Pure decision used by transaction code and focused replay tests. */
export function validateTokenOwnership(
  current: unknown,
  expected: PurchaseTokenOwnershipRecord,
  allowVerifiedProductTransition = false,
): 'create' | 'idempotent' | 'update_product' {
  if (current == null) return 'create';
  if (typeof current !== 'object' || Array.isArray(current)) {
    throw new PurchaseTokenOwnershipError('malformed_record');
  }
  const record = current as Record<string, unknown>;
  if (
    typeof record.uid !== 'string' ||
    record.uid.length === 0 ||
    typeof record.productId !== 'string' ||
    !(STORE_PRODUCT_IDS as readonly string[]).includes(record.productId)
  ) {
    throw new PurchaseTokenOwnershipError('malformed_record');
  }
  if (record.uid !== expected.uid) {
    throw new PurchaseTokenOwnershipError('different_user');
  }
  if (record.productId !== expected.productId) {
    // A deferred replacement keeps the new purchase token while its effective
    // line-item product changes at renewal. This path is used only after the
    // Play API authenticated the token and account binding.
    if (allowVerifiedProductTransition) return 'update_product';
    throw new PurchaseTokenOwnershipError('different_product');
  }
  return 'idempotent';
}
