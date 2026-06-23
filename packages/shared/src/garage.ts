/**
 * Shared contracts for the "My Garage" (Mitt garage) feature.
 *
 * Privacy rules encoded here:
 *  - No registration numbers, VIN, or insurance data.
 *  - No vehicle location data.
 *  - No owner email, provider identity, or subscription information.
 *  - No internal moderation metadata.
 *  - Vehicle profiles remain private in this step.
 *
 * Access rules:
 *  - Only authenticated users with active member_monthly entitlement may use garage features.
 *  - Suspended and deleted users must not access garage features.
 *  - Each vehicle belongs to exactly one user and is only accessible by that user.
 *
 * Future preparation:
 *  - TODO: Add garage-created badge once badge system is implemented.
 *  - TODO: Add optional public profile visibility with appropriate moderation.
 *  - TODO: Add vehicle image upload with content moderation once storage is ready.
 *  - TODO: Add vehicle selection for event participation.
 *  - TODO: Add social sharing card (must exclude owner identity, location, and exact metadata).
 */

export const VEHICLE_POWERTRAINS = [
  'petrol',
  'diesel',
  'hybrid',
  'plug_in_hybrid',
  'electric',
  'other',
] as const;
export type VehiclePowertrain = (typeof VEHICLE_POWERTRAINS)[number];

export const GARAGE_ROUTE_PATHS = {
  list: '/v1/garage/vehicles',
  detail: (vehicleId: string) => `/v1/garage/vehicles/${vehicleId}`,
} as const;

export const DEFAULT_GARAGE_PAGE_SIZE = 20;
export const MAX_GARAGE_PAGE_SIZE = 20;

/**
 * MVP vehicle limit per user.
 * Enforced on the backend; used by the service layer.
 */
export const MAX_VEHICLES_PER_USER = 5;

/**
 * Summary item returned in the garage vehicle list.
 * Never includes description to keep list responses small.
 *
 * Excluded fields (must never appear here):
 *  - registration number / VIN
 *  - owner email or identity
 *  - subscription information
 *  - location data
 *  - insurance data
 *  - internal moderation metadata
 */
export interface VehicleSummary {
  id: string;
  make: string;
  model: string;
  modelYear: number;
  powertrain: VehiclePowertrain;
  engineDescription: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Full vehicle detail. Includes optional description.
 * Only accessible by the owning user.
 */
export interface VehicleDetail extends VehicleSummary {
  description: string | null;
}

/**
 * Request body for creating a new vehicle.
 * userId must never be included — ownership is derived from the auth context.
 */
export interface CreateVehicleRequest {
  make: string;
  model: string;
  modelYear: number;
  powertrain: VehiclePowertrain;
  engineDescription?: string;
  description?: string;
}

/**
 * Request body for updating an existing vehicle.
 * Only the listed fields may be changed.
 * userId, id, and timestamps are not editable.
 */
export interface UpdateVehicleRequest {
  make?: string;
  model?: string;
  modelYear?: number;
  powertrain?: VehiclePowertrain;
  engineDescription?: string | null;
  description?: string | null;
}

export interface PaginatedGarageResponse {
  ok: true;
  data: {
    vehicles: VehicleSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface VehicleDetailResponse {
  ok: true;
  data: {
    vehicle: VehicleDetail;
  };
}

export interface CreateVehicleResponse {
  ok: true;
  data: {
    vehicle: VehicleDetail;
  };
}

export interface UpdateVehicleResponse {
  ok: true;
  data: {
    vehicle: VehicleDetail;
  };
}

export interface DeleteVehicleResponse {
  ok: true;
  data: {
    deleted: true;
    vehicleId: string;
  };
}
