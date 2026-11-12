import {
  AppleVerificationError,
  parseAppleTransaction,
  type AppleEntitlementOutcome,
} from './apple-store-core';

export interface AppleEventVersion {
  purchaseDateMillis: number;
  signedDateMillis: number;
  source: 'transaction' | 'notification';
}

export interface AppleSubscriptionState {
  outcome: AppleEntitlementOutcome;
  version: AppleEventVersion;
}

function invalid(): never {
  throw new AppleVerificationError('malformed_response', 'Invalid Apple subscription state.');
}

function millis(value: unknown): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value <= 0 ||
    Number.isNaN(new Date(value).getTime())
  )
    invalid();
  return value;
}

/** Called only after Apple's library has verified every supplied JWS. */
export function appleTransactionState(
  transaction: Record<string, unknown>,
  accountToken: string,
  now: Date,
): AppleSubscriptionState {
  return {
    outcome: parseAppleTransaction(transaction, accountToken, now),
    version: {
      purchaseDateMillis: millis(transaction.purchaseDate),
      signedDateMillis: millis(transaction.signedDate),
      source: 'transaction',
    },
  };
}

/** Renewal status is signed separately; an expired transaction can still be in grace. */
export function appleNotificationState(
  transaction: Record<string, unknown>,
  renewal: Record<string, unknown> | null,
  status: unknown,
  signedDate: unknown,
  accountToken: string,
  now: Date,
): AppleSubscriptionState {
  const state = appleTransactionState(transaction, accountToken, now);
  state.version = {
    ...state.version,
    source: 'notification',
    signedDateMillis: millis(signedDate),
  };
  if (![1, 2, 3, 4, 5].includes(status as number)) invalid();
  if (
    renewal &&
    (renewal.originalTransactionId !== transaction.originalTransactionId ||
      renewal.productId !== transaction.productId)
  )
    invalid();
  const outcome = state.outcome;
  if (status === 5 || outcome.status === 'revoked') {
    outcome.status = 'revoked';
    outcome.entitlement = 'none';
  } else if (transaction.isUpgraded === true || status === 2 || status === 3) {
    outcome.status = status === 3 ? 'inactive' : 'expired';
    outcome.entitlement = 'none';
  } else if (status === 4) {
    const graceUntil = new Date(millis(renewal?.gracePeriodExpiresDate));
    outcome.expiresAt = graceUntil;
    outcome.status = graceUntil > now ? 'grace_period' : 'expired';
    outcome.entitlement = graceUntil > now ? 'member_monthly' : 'none';
  } else if (outcome.entitlement === 'member_monthly' && renewal?.autoRenewStatus === 0) {
    // Disabling renewal keeps access until the already-paid period ends.
    outcome.status = 'cancelled';
  }
  return state;
}

export function readAppleEventVersion(value: unknown): AppleEventVersion | null {
  if (!value || typeof value !== 'object') return null;
  const v = value as Record<string, unknown>;
  if (
    (v.source !== 'transaction' && v.source !== 'notification') ||
    typeof v.purchaseDateMillis !== 'number' ||
    !Number.isSafeInteger(v.purchaseDateMillis) ||
    typeof v.signedDateMillis !== 'number' ||
    !Number.isSafeInteger(v.signedDateMillis)
  )
    return null;
  return v as unknown as AppleEventVersion;
}

/** Old periods cannot undo upgrades; a device JWS cannot undo signed renewal state. */
export function isNewerAppleState(next: AppleEventVersion, previous: AppleEventVersion): boolean {
  if (next.purchaseDateMillis !== previous.purchaseDateMillis) {
    return next.purchaseDateMillis > previous.purchaseDateMillis;
  }
  if (next.source !== previous.source) return next.source === 'notification';
  return next.signedDateMillis > previous.signedDateMillis;
}
