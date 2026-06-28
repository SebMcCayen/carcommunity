/**
 * Digital Billboards API client for the mobile app.
 *
 * Privacy rules:
 * - Do NOT send userId, coordinates, or arbitrary metadata.
 * - Analytics failures must NOT block the user's main action.
 * - Tokens are never logged.
 */
import {
  DIGITAL_BILLBOARD_ROUTE_PATHS,
  buildBillboardInteractionPath,
  buildBillboardPath,
  type BillboardInteractionType,
  type PublicBillboardDetailResponse,
  type PublicBillboardMapMarkersResponse,
  type RecordBillboardInteractionRequest,
  type RecordBillboardInteractionResponse,
} from '@carcommunity/shared/digital-billboards';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');
const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { Authorization: 'Bearer ' + token } : {};

class BillboardApiError extends Error {
  constructor(public readonly statusCode: number, message: string) {
    super(message);
    this.name = 'BillboardApiError';
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
    throw new BillboardApiError(response.status, message);
  }

  return (await response.json()) as TResponse;
}

export { BillboardApiError };

export async function fetchBillboardMapMarkers(token?: string): Promise<PublicBillboardMapMarkersResponse> {
  return requestJson<PublicBillboardMapMarkersResponse>(DIGITAL_BILLBOARD_ROUTE_PATHS.mapMarkers, {
    headers: { 'Content-Type': 'application/json', ...buildAuthHeader(token) },
  });
}

export async function fetchBillboardDetail(
  billboardId: string,
  token?: string,
): Promise<PublicBillboardDetailResponse> {
  return requestJson<PublicBillboardDetailResponse>(buildBillboardPath(billboardId), {
    headers: { 'Content-Type': 'application/json', ...buildAuthHeader(token) },
  });
}

async function recordBillboardInteraction(
  billboardId: string,
  interactionType: BillboardInteractionType,
  token?: string,
): Promise<void> {
  const body: RecordBillboardInteractionRequest = { interactionType };
  await requestJson<RecordBillboardInteractionResponse>(buildBillboardInteractionPath(billboardId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...buildAuthHeader(token) },
    body: JSON.stringify(body),
  });
}

export function fireAndForgetBillboardInteraction(
  billboardId: string,
  interactionType: BillboardInteractionType,
  token?: string,
): void {
  void recordBillboardInteraction(billboardId, interactionType, token).catch(() => undefined);
}
