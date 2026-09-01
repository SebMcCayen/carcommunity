/**
 * Unit tests for the pure RTDN envelope decoder
 * (functions/src/subscription/rtdn-core.ts).
 *
 * The decoder is the trust boundary for Play's Pub/Sub payload: these pin that
 * it narrows a well-formed envelope to the right kind and returns null (the
 * ack/no-op signal) for everything it cannot safely act on.
 */

import { describe, expect, it } from 'vitest';
import { GOOGLE_PLAY_PACKAGE_NAME } from '../subscription/google-play-core';
import {
  decodeRtdnMessageData,
  describeSubscriptionNotificationType,
  isExpectedRtdnPackage,
} from '../subscription/rtdn-core';

function encode(payload: unknown): string {
  return Buffer.from(JSON.stringify(payload), 'utf8').toString('base64');
}

const SUBSCRIPTION_ENVELOPE = {
  version: '1.0',
  packageName: GOOGLE_PLAY_PACKAGE_NAME,
  eventTimeMillis: '1756000000000',
  subscriptionNotification: {
    version: '1.0',
    notificationType: 2,
    purchaseToken: 'token-abc',
    subscriptionId: 'plus_monthly',
  },
};

describe('decodeRtdnMessageData', () => {
  it('decodes a subscription notification', () => {
    const decoded = decodeRtdnMessageData(encode(SUBSCRIPTION_ENVELOPE));
    expect(decoded).not.toBeNull();
    expect(decoded?.kind).toBe('subscription');
    if (decoded?.kind !== 'subscription') throw new Error('wrong kind');
    expect(decoded.eventTimeMillis).toBe(1756000000000);
    expect(decoded.packageName).toBe(GOOGLE_PLAY_PACKAGE_NAME);
    expect(decoded.notification.purchaseToken).toBe('token-abc');
    expect(decoded.notification.notificationType).toBe(2);
    expect(decoded.notification.subscriptionId).toBe('plus_monthly');
  });

  it('accepts eventTimeMillis as a JSON number too', () => {
    const decoded = decodeRtdnMessageData(
      encode({ ...SUBSCRIPTION_ENVELOPE, eventTimeMillis: 1756000000001 }),
    );
    expect(decoded?.eventTimeMillis).toBe(1756000000001);
  });

  it('decodes a voided purchase notification', () => {
    const decoded = decodeRtdnMessageData(
      encode({
        version: '1.0',
        packageName: GOOGLE_PLAY_PACKAGE_NAME,
        eventTimeMillis: '1756000000002',
        voidedPurchaseNotification: {
          purchaseToken: 'token-void',
          productType: 1,
          refundType: 1,
        },
      }),
    );
    expect(decoded?.kind).toBe('voided');
    if (decoded?.kind !== 'voided') throw new Error('wrong kind');
    expect(decoded.notification.purchaseToken).toBe('token-void');
    expect(decoded.notification.productType).toBe(1);
  });

  it('decodes a test notification (no token required)', () => {
    const decoded = decodeRtdnMessageData(
      encode({
        version: '1.0',
        packageName: GOOGLE_PLAY_PACKAGE_NAME,
        eventTimeMillis: '1756000000003',
        testNotification: { version: '1.0' },
      }),
    );
    expect(decoded?.kind).toBe('test');
  });

  it('prefers a subscription notification over a coexisting voided one', () => {
    const decoded = decodeRtdnMessageData(
      encode({
        ...SUBSCRIPTION_ENVELOPE,
        voidedPurchaseNotification: { purchaseToken: 'token-void' },
      }),
    );
    expect(decoded?.kind).toBe('subscription');
  });

  it('returns null for non-base64 / non-JSON garbage', () => {
    expect(decodeRtdnMessageData('not-valid-base64-#####')).toBeNull();
    expect(decodeRtdnMessageData(Buffer.from('not json', 'utf8').toString('base64'))).toBeNull();
  });

  it('returns null for a missing / empty payload', () => {
    expect(decodeRtdnMessageData(undefined)).toBeNull();
    expect(decodeRtdnMessageData(null)).toBeNull();
    expect(decodeRtdnMessageData('')).toBeNull();
    expect(decodeRtdnMessageData(123 as unknown)).toBeNull();
  });

  it('returns null when packageName is absent', () => {
    expect(
      decodeRtdnMessageData(
        encode({
          version: '1.0',
          eventTimeMillis: '1756000000000',
          subscriptionNotification: SUBSCRIPTION_ENVELOPE.subscriptionNotification,
        }),
      ),
    ).toBeNull();
  });

  it('returns null for a subscription notification with no usable token', () => {
    expect(
      decodeRtdnMessageData(
        encode({
          ...SUBSCRIPTION_ENVELOPE,
          subscriptionNotification: { ...SUBSCRIPTION_ENVELOPE.subscriptionNotification, purchaseToken: '' },
        }),
      ),
    ).toBeNull();
    expect(
      decodeRtdnMessageData(
        encode({
          ...SUBSCRIPTION_ENVELOPE,
          subscriptionNotification: {
            ...SUBSCRIPTION_ENVELOPE.subscriptionNotification,
            purchaseToken: 'x'.repeat(8193),
          },
        }),
      ),
    ).toBeNull();
  });

  it('returns null for a subscription notification missing eventTimeMillis', () => {
    expect(
      decodeRtdnMessageData(
        encode({
          version: '1.0',
          packageName: GOOGLE_PLAY_PACKAGE_NAME,
          subscriptionNotification: SUBSCRIPTION_ENVELOPE.subscriptionNotification,
        }),
      ),
    ).toBeNull();
  });

  it('returns null for a non-integer / negative / unsafe / astronomical eventTimeMillis', () => {
    // The watermark must be a non-negative safe integer within a sane ceiling —
    // fail closed otherwise, so a poisoned value cannot freeze the watermark.
    const rejected: unknown[] = [
      '123.4',
      123.4,
      -1,
      '-5',
      Number.MAX_SAFE_INTEGER + 1,
      'NaN',
      Infinity,
      // Astronomical but still a safe integer (year ~33658) — beyond the cap.
      1_000_000_000_000_000,
      Number.MAX_SAFE_INTEGER,
      String(Number.MAX_SAFE_INTEGER),
    ];
    for (const bad of rejected) {
      expect(
        decodeRtdnMessageData(encode({ ...SUBSCRIPTION_ENVELOPE, eventTimeMillis: bad })),
        `eventTimeMillis ${String(bad)} should be rejected`,
      ).toBeNull();
    }
  });

  it('accepts a realistic present-day eventTimeMillis (below the ceiling)', () => {
    const decoded = decodeRtdnMessageData(
      encode({ ...SUBSCRIPTION_ENVELOPE, eventTimeMillis: Date.UTC(2026, 0, 1) }),
    );
    expect(decoded?.eventTimeMillis).toBe(Date.UTC(2026, 0, 1));
  });

  it('returns null for a non-integer notificationType', () => {
    expect(
      decodeRtdnMessageData(
        encode({
          ...SUBSCRIPTION_ENVELOPE,
          subscriptionNotification: {
            ...SUBSCRIPTION_ENVELOPE.subscriptionNotification,
            notificationType: 'renewed',
          },
        }),
      ),
    ).toBeNull();
  });

  it('returns null for an unknown / one-time-product notification kind', () => {
    expect(
      decodeRtdnMessageData(
        encode({
          version: '1.0',
          packageName: GOOGLE_PLAY_PACKAGE_NAME,
          eventTimeMillis: '1756000000004',
          oneTimeProductNotification: { purchaseToken: 'x', sku: 'y', notificationType: 1 },
        }),
      ),
    ).toBeNull();
  });
});

describe('isExpectedRtdnPackage', () => {
  it('matches only this app package', () => {
    expect(isExpectedRtdnPackage(GOOGLE_PLAY_PACKAGE_NAME)).toBe(true);
    expect(isExpectedRtdnPackage('com.someone.else')).toBe(false);
  });
});

describe('describeSubscriptionNotificationType', () => {
  it('names known types and labels unknown ones', () => {
    expect(describeSubscriptionNotificationType(2)).toBe('SUBSCRIPTION_RENEWED');
    expect(describeSubscriptionNotificationType(12)).toBe('SUBSCRIPTION_REVOKED');
    expect(describeSubscriptionNotificationType(999)).toBe('UNKNOWN(999)');
  });
});
