/**
 * Partner Insights interaction recording API client for the mobile app.
 *
 * Privacy rules:
 *  - Do NOT send userId, coordinates, consent state, or offer codes.
 *  - Only send: interactionType and optionally relatedOfferId.
 *  - Analytics failures must NOT block the user's intended action.
 *  - No indefinite retries.
 *  - Tokens are never logged.
 */

import {
  buildRecordInteractionPath,
  type PartnerInteractionType,
  type RecordPartnerInteractionRequest,
  type RecordPartnerInteractionResponse,
} from '@carcommunity/shared/partner-insights';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { Authorization: 'Bearer ' + token } : {};

class PartnerInsightsApiError extends Error {
  constructor(public readonly statusCode: number, message: string) {
    super(message);
    this.name = 'PartnerInsightsApiError';
  }
}

async function requestJson<TResponse>(path: string, init: RequestInit = {}): Promise<TResponse> {
  const response = await fetch(buildUrl(path), init);
  if (!response.ok) {
    interface ErrorBody {
      error?: { message?: string };
    }
    let message = `HTTP ${response.status}`;
    try {
      const body = (await response.json()) as ErrorBody;
      if (body?.error?.message) {
        message = body.error.message;
      }
    } catch {
      // Ignore non-JSON error bodies.
    }
    throw new PartnerInsightsApiError(response.status, message);
  }
  return (await response.json()) as TResponse;
}

export async function recordPartnerInteraction(
  partnerId: string,
  interactionType: PartnerInteractionType,
  token?: string,
  relatedOfferId?: string,
): Promise<void> {
  const body: RecordPartnerInteractionRequest = {
    interactionType,
    ...(relatedOfferId ? { relatedOfferId } : {}),
  };

  await requestJson<RecordPartnerInteractionResponse>(buildRecordInteractionPath(partnerId), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify(body),
  });
}

export function fireAndForgetInteraction(
  partnerId: string,
  interactionType: PartnerInteractionType,
  token?: string,
  relatedOfferId?: string,
): void {
  void recordPartnerInteraction(partnerId, interactionType, token, relatedOfferId).catch(() => undefined);
}
