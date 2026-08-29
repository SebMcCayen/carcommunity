/**
 * Google Play Android Publisher transport.
 *
 * Authentication uses Application Default Credentials from the function's
 * dedicated runtime service account. No JSON key, client secret, or long-lived
 * credential exists in source or environment configuration.
 */

import { GoogleAuth } from 'google-auth-library';
import {
  GOOGLE_PLAY_PACKAGE_NAME,
  type GooglePlayEntitlementOutcome,
  parseGooglePlaySubscription,
} from './google-play-core';

const ANDROID_PUBLISHER_SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const API_ROOT = 'https://androidpublisher.googleapis.com/androidpublisher/v3';

export class GooglePlayApiError extends Error {
  constructor(
    readonly operation: 'get' | 'acknowledge',
    readonly reason: 'invalid_purchase' | 'unavailable' = 'unavailable',
  ) {
    super(`Google Play ${operation} request failed.`);
    this.name = 'GooglePlayApiError';
  }
}

/**
 * Distinguishes definitive token rejection from retryable provider failures
 * without preserving or logging the provider error, whose URL can contain the
 * raw purchase token.
 */
export function classifyGooglePlayGetError(
  error: unknown,
): 'invalid_purchase' | 'unavailable' {
  if (error === null || typeof error !== 'object') return 'unavailable';
  const record = error as Record<string, unknown>;
  const response = record.response;
  const responseStatus =
    response !== null && typeof response === 'object'
      ? (response as Record<string, unknown>).status
      : undefined;
  const status = typeof responseStatus === 'number' ? responseStatus : record.status;
  return status === 400 || status === 404 || status === 410
    ? 'invalid_purchase'
    : 'unavailable';
}

export interface GooglePlaySubscriptionClient {
  getSubscription(purchaseToken: string): Promise<unknown>;
  acknowledgeSubscription(productId: string, purchaseToken: string): Promise<void>;
}

export class AdcGooglePlaySubscriptionClient implements GooglePlaySubscriptionClient {
  private readonly auth = new GoogleAuth({ scopes: [ANDROID_PUBLISHER_SCOPE] });

  async getSubscription(purchaseToken: string): Promise<unknown> {
    const url = `${API_ROOT}/applications/${encodeURIComponent(
      GOOGLE_PLAY_PACKAGE_NAME,
    )}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
    try {
      const client = await this.auth.getClient();
      const response = await client.request<unknown>({ method: 'GET', url });
      return response.data;
    } catch (error) {
      // Never include the request URL or provider error: both can contain the
      // raw purchase token. Callers log only this sanitized operation name.
      throw new GooglePlayApiError('get', classifyGooglePlayGetError(error));
    }
  }

  async acknowledgeSubscription(productId: string, purchaseToken: string): Promise<void> {
    const url = `${API_ROOT}/applications/${encodeURIComponent(
      GOOGLE_PLAY_PACKAGE_NAME,
    )}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(
      purchaseToken,
    )}:acknowledge`;
    try {
      const client = await this.auth.getClient();
      await client.request({ method: 'POST', url, data: {} });
    } catch {
      throw new GooglePlayApiError('acknowledge');
    }
  }
}

export async function verifyGooglePlaySubscription(
  client: GooglePlaySubscriptionClient,
  input: {
    purchaseToken: string;
    expectedObfuscatedAccountId: string;
    now?: Date;
  },
): Promise<GooglePlayEntitlementOutcome> {
  const response = await client.getSubscription(input.purchaseToken);
  return parseGooglePlaySubscription(
    response,
    input.expectedObfuscatedAccountId,
    input.now ?? new Date(),
  );
}
