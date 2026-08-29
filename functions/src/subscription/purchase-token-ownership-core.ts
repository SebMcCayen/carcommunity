import type { GooglePlayProductId } from './google-play-core';

export class PurchaseTokenOwnershipError extends Error {
  constructor(readonly reason: 'different_user' | 'different_product' | 'malformed_record') {
    super('Purchase token ownership conflict.');
    this.name = 'PurchaseTokenOwnershipError';
  }
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
  if (record.uid !== expected.uid) {
    throw new PurchaseTokenOwnershipError('different_user');
  }
  if (record.productId !== expected.productId) {
    throw new PurchaseTokenOwnershipError('different_product');
  }
  return 'idempotent';
}
