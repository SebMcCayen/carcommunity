/**
 * Google Play Real-time Developer Notifications (RTDN) — pure envelope
 * decoding and validation.
 *
 * Play publishes one base64-encoded `DeveloperNotification` JSON per Pub/Sub
 * message (https://developer.android.com/google/play/billing/rtdn-reference).
 * This module is the trust boundary for that payload: it accepts an opaque
 * base64 string and returns a validated, discriminated `DecodedRtdn`, or
 * `null` for ANYTHING it cannot make sense of (bad base64, invalid JSON,
 * missing/oversized fields, an unrecognised notification kind).
 *
 * `null` is the FAIL-SAFE signal: the Pub/Sub handler acks a `null` (logs and
 * returns) rather than throwing, so a single poison message can never wedge
 * the subscription in an infinite Pub/Sub redelivery loop. Only TRANSIENT
 * downstream failures (see rtdn.ts) throw to request a retry.
 *
 * NO clock reads, NO Firebase Admin SDK — like google-play-core.ts, so the
 * decode/validate rules are unit-testable in isolation.
 */

import { GOOGLE_PLAY_PACKAGE_NAME } from './google-play-core';

/** Default Pub/Sub topic the operator wires Play RTDN to (see the PR body). */
export const RTDN_TOPIC = 'play-subscription-rtdn';

/**
 * Subscription notification type codes (RTDN reference). Kept for LOGGING and
 * observability only: the handler never trusts the type to decide entitlement
 * — Google's own guidance is to re-fetch the authoritative state with
 * `purchases.subscriptionsv2.get` after any notification, which is exactly
 * what rtdn.ts does. The type just tells us it is a subscription event and
 * gives the logs a human-readable reason.
 */
export const SUBSCRIPTION_NOTIFICATION_TYPES: Readonly<Record<number, string>> = {
  1: 'SUBSCRIPTION_RECOVERED',
  2: 'SUBSCRIPTION_RENEWED',
  3: 'SUBSCRIPTION_CANCELED',
  4: 'SUBSCRIPTION_PURCHASED',
  5: 'SUBSCRIPTION_ON_HOLD',
  6: 'SUBSCRIPTION_IN_GRACE_PERIOD',
  7: 'SUBSCRIPTION_RESTARTED',
  8: 'SUBSCRIPTION_PRICE_CHANGE_CONFIRMED',
  9: 'SUBSCRIPTION_DEFERRED',
  10: 'SUBSCRIPTION_PAUSED',
  11: 'SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED',
  12: 'SUBSCRIPTION_REVOKED',
  13: 'SUBSCRIPTION_EXPIRED',
};

/** Upper bound on a purchase token, mirroring the verify callable's schema. */
const MAX_PURCHASE_TOKEN_LENGTH = 8192;

/**
 * Upper bound on eventTimeMillis: 2100-01-01T00:00:00Z (Date.UTC(2100,0,1)).
 * The watermark is monotonic, so a single absurd far-future value would freeze
 * a token's watermark and cause every later legitimate event to be skipped as
 * "stale". Real Play epoch-millis are ~1.7e12 (mid-2020s); this ceiling leaves
 * ~74 years of headroom yet sits far below Number.MAX_SAFE_INTEGER, so any
 * astronomical value fails closed to null (poison ack). A static cap avoids
 * threading a clock into this pure decoder; it is deliberately generous rather
 * than "now + skew" because rejecting only impossible values needs no precision.
 */
const MAX_EVENT_TIME_MILLIS = Date.UTC(2100, 0, 1);

export interface RtdnSubscriptionNotification {
  notificationType: number;
  purchaseToken: string;
  subscriptionId: string;
}

export interface RtdnVoidedPurchaseNotification {
  purchaseToken: string;
  /** 1 = subscription, 2 = one-time (RTDN reference); optional in payload. */
  productType?: number;
  /** 1 = full, 2 = partial refund (RTDN reference); optional in payload. */
  refundType?: number;
}

/**
 * A structurally-valid RTDN envelope, narrowed to exactly one actionable kind.
 * The raw `purchaseToken` is carried through UNHASHED because rtdn.ts needs it
 * for the authoritative Play API call; it is hashed the instant ownership is
 * resolved and is never persisted or logged (same rule as verify.ts).
 */
export type DecodedRtdn =
  | {
      kind: 'subscription';
      packageName: string;
      eventTimeMillis: number;
      notification: RtdnSubscriptionNotification;
    }
  | {
      kind: 'voided';
      packageName: string;
      eventTimeMillis: number;
      notification: RtdnVoidedPurchaseNotification;
    }
  | { kind: 'test'; packageName: string; eventTimeMillis: number };

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/**
 * Play may send eventTimeMillis as a JSON number or a stringified long. This
 * is the monotonic idempotency watermark, so it must be a NON-NEGATIVE SAFE
 * INTEGER: any non-integer, negative, NaN/Infinity, or unsafe value fails
 * closed to null (→ the delivery is treated as poison and acked).
 */
function parseEventTimeMillis(value: unknown): number | null {
  let candidate: number;
  if (typeof value === 'number') {
    candidate = value;
  } else if (typeof value === 'string' && value.trim() !== '') {
    candidate = Number(value);
  } else {
    return null;
  }
  if (!Number.isSafeInteger(candidate) || candidate < 0) return null;
  // Sane upper bound: an absurd far-future value would poison the monotonic
  // watermark and skip every later legitimate event.
  if (candidate > MAX_EVENT_TIME_MILLIS) return null;
  return candidate;
}

function isUsableToken(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= MAX_PURCHASE_TOKEN_LENGTH;
}

/**
 * Decodes and validates the base64 `message.data` of a Pub/Sub RTDN delivery.
 *
 * Returns `null` (→ ack, no-op) for every unrecoverable payload. Package-name
 * verification is left to the caller so a mismatch can be logged with context;
 * this function only guarantees the SHAPE.
 */
export function decodeRtdnMessageData(base64Data: unknown): DecodedRtdn | null {
  if (typeof base64Data !== 'string' || base64Data.length === 0) return null;

  let parsed: unknown;
  try {
    const json = Buffer.from(base64Data, 'base64').toString('utf8');
    if (json.length === 0) return null;
    parsed = JSON.parse(json);
  } catch {
    return null;
  }

  if (!isRecord(parsed)) return null;
  if (typeof parsed.packageName !== 'string' || parsed.packageName.length === 0) return null;
  const packageName = parsed.packageName;

  const eventTimeMillis = parseEventTimeMillis(parsed.eventTimeMillis);

  // A subscriptionNotification takes precedence, then voided, then test.
  const sub = parsed.subscriptionNotification;
  if (isRecord(sub)) {
    if (eventTimeMillis === null) return null;
    if (!isUsableToken(sub.purchaseToken)) return null;
    if (typeof sub.notificationType !== 'number' || !Number.isInteger(sub.notificationType)) {
      return null;
    }
    if (typeof sub.subscriptionId !== 'string' || sub.subscriptionId.length === 0) return null;
    return {
      kind: 'subscription',
      packageName,
      eventTimeMillis,
      notification: {
        notificationType: sub.notificationType,
        purchaseToken: sub.purchaseToken,
        subscriptionId: sub.subscriptionId,
      },
    };
  }

  const voided = parsed.voidedPurchaseNotification;
  if (isRecord(voided)) {
    if (eventTimeMillis === null) return null;
    if (!isUsableToken(voided.purchaseToken)) return null;
    return {
      kind: 'voided',
      packageName,
      eventTimeMillis,
      notification: {
        purchaseToken: voided.purchaseToken,
        ...(typeof voided.productType === 'number' ? { productType: voided.productType } : {}),
        ...(typeof voided.refundType === 'number' ? { refundType: voided.refundType } : {}),
      },
    };
  }

  if (isRecord(parsed.testNotification)) {
    // A test ping carries no token; eventTimeMillis is optional here.
    return { kind: 'test', packageName, eventTimeMillis: eventTimeMillis ?? 0 };
  }

  // A oneTimeProductNotification or an unknown future kind: nothing to act on.
  return null;
}

/** True when the envelope was minted for THIS app's Play listing. */
export function isExpectedRtdnPackage(packageName: string): boolean {
  return packageName === GOOGLE_PLAY_PACKAGE_NAME;
}

/** Human-readable subscription notification type for logs. */
export function describeSubscriptionNotificationType(notificationType: number): string {
  return SUBSCRIPTION_NOTIFICATION_TYPES[notificationType] ?? `UNKNOWN(${notificationType})`;
}
