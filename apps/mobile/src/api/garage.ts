import {
  GARAGE_ROUTE_PATHS,
  DEFAULT_GARAGE_PAGE_SIZE,
  type CreateVehicleRequest,
  type CreateVehicleResponse,
  type DeleteVehicleResponse,
  type PaginatedGarageResponse,
  type UpdateVehicleRequest,
  type UpdateVehicleResponse,
  type VehicleDetailResponse,
} from '@carcommunity/shared/garage';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { authorization: 'Bearer ' + token } : {};

/**
 * Typed error thrown when a garage API request returns a non-2xx status.
 */
export class GarageApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'GarageApiError';
  }
}

async function requestJson<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  if (!base) {
    throw new Error(
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const response = await fetch(buildUrl(path), init);

  if (!response.ok) {
    throw new GarageApiError(
      response.status,
      `Garage request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

/**
 * List the current user's vehicles, newest first.
 */
export async function listVehicles(
  page = 1,
  pageSize = DEFAULT_GARAGE_PAGE_SIZE,
  token?: string,
): Promise<PaginatedGarageResponse> {
  const searchParams = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  });
  return requestJson<PaginatedGarageResponse>(
    `${GARAGE_ROUTE_PATHS.list}?${searchParams.toString()}`,
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}

/**
 * Get a single vehicle detail.
 */
export async function getVehicle(
  vehicleId: string,
  token?: string,
): Promise<VehicleDetailResponse> {
  return requestJson<VehicleDetailResponse>(GARAGE_ROUTE_PATHS.detail(vehicleId), {
    method: 'GET',
    headers: buildAuthHeader(token),
  });
}

/**
 * Create a new vehicle. Requires active member_monthly subscription.
 * userId must never be included in the request body — ownership is backend-enforced.
 */
export async function createVehicle(
  body: CreateVehicleRequest,
  token?: string,
): Promise<CreateVehicleResponse> {
  return requestJson<CreateVehicleResponse>(GARAGE_ROUTE_PATHS.list, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify(body),
  });
}

/**
 * Update an existing vehicle. Only editable fields are sent.
 */
export async function updateVehicle(
  vehicleId: string,
  body: UpdateVehicleRequest,
  token?: string,
): Promise<UpdateVehicleResponse> {
  return requestJson<UpdateVehicleResponse>(GARAGE_ROUTE_PATHS.detail(vehicleId), {
    method: 'PATCH',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify(body),
  });
}

/**
 * Delete a vehicle by ID.
 */
export async function deleteVehicle(
  vehicleId: string,
  token?: string,
): Promise<DeleteVehicleResponse> {
  return requestJson<DeleteVehicleResponse>(GARAGE_ROUTE_PATHS.detail(vehicleId), {
    method: 'DELETE',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
  });
}
