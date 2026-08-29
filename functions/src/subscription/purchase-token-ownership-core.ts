import { GOOGLE_PLAY_PRODUCT_IDS, type GooglePlayProductId } from './google-play-core';

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
 * The first Play slice intentionally supports one effective store purchase at
 * a time. A second token must not revoke, downgrade, or double-charge an
 * already-paid subscription before multi-token tier recomputation and Play's
 * upgrade/downgrade flow exist.
 */
export function assertNoDifferentActiveToken(
  current: CurrentEffectiveSubscription | null,
  nextPurchaseTokenHash: string,
  now: Date,
): void {
  if (current === null || !current.grantsAccess) return;
  if (current.expiresAt !== null && current.expiresAt.getTime() <= now.getTime()) return;
  if (current.purchaseTokenHash === nextPurchaseTokenHash) return;
  throw new PurchaseTokenOwnershipError('different_active_token');
}

export interface PurchaseTokenOwnershipRecord {
  uid: string;
  productId: GooglePlayProductId;
}

/** Pure decision used by transaction code and focused replay tests. */
export function validateTokenOwnership(
  current: unknown,
  expected: PurchaseTokenOwnershipRecord,
): 'create' | 'idempotent' {
  if (current == null) return 'create';
  if (typeof current !== 'object' || Array.isArray(current)) {
    throw new PurchaseTokenOwnershipError('malformed_record');
  }
  const record = current as Record<string, unknown>;
  if (
    typeof record.uid !== 'string' ||
    record.uid.length === 0 ||
    typeof record.productId !== 'string' ||
    !(GOOGLE_PLAY_PRODUCT_IDS as readonly string[]).includes(record.productId)
  ) {
    throw new PurchaseTokenOwnershipError('malformed_record');
  }
  if (record.uid !== expected.uid) {
    throw new PurchaseTokenOwnershipError('different_user');
  }
  if (record.productId !== expected.productId) {
    throw new PurchaseTokenOwnershipError('different_product');
  }
  return 'idempotent';
}
