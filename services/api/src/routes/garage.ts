/**
 * Garage API routes — "Mitt garage" (My Garage).
 *
 * Routes:
 *  GET    /v1/garage/vehicles
 *  GET    /v1/garage/vehicles/:vehicleId
 *  POST   /v1/garage/vehicles
 *  PATCH  /v1/garage/vehicles/:vehicleId
 *  DELETE /v1/garage/vehicles/:vehicleId
 *
 * Access control:
 *  - All routes require authentication and active member_monthly entitlement.
 *  - Ownership is always verified by the service layer, never the client.
 *  - Suspended and deleted users are rejected before reaching service logic.
 *  - userId cannot be set or changed by the client.
 *
 * Privacy:
 *  - No registration numbers, VIN, insurance data, or vehicle location.
 *  - Vehicle profiles are private — only the owning user can access them.
 *  - Admin does not receive individual vehicle details.
 *  - Not-found responses are returned for ownership failures to avoid
 *    leaking whether another user's vehicle exists.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { VEHICLE_POWERTRAINS } from '@carcommunity/shared/garage';
import {
  GARAGE_ROUTE_PATHS,
  DEFAULT_GARAGE_PAGE_SIZE,
  MAX_GARAGE_PAGE_SIZE,
  type CreateVehicleResponse,
  type DeleteVehicleResponse,
  type PaginatedGarageResponse,
  type UpdateVehicleResponse,
  type VehicleDetail,
  type VehicleDetailResponse,
} from '@carcommunity/shared/garage';
import type { VehiclePowertrain } from '@prisma/client';

import { requireMemberHook } from '../lib/auth-context.js';
import { GarageService } from '../lib/garage-service.js';
import type { BadgeService } from '../lib/badge-service.js';

// ---------------------------------------------------------------------------
// Validation schemas
// ---------------------------------------------------------------------------

const vehicleParamsSchema = z.object({ vehicleId: z.string().uuid() }).strict();

const listQuerySchema = z
  .object({
    page: z.coerce.number().int().min(1).default(1),
    pageSize: z
      .coerce.number()
      .int()
      .min(1)
      .max(MAX_GARAGE_PAGE_SIZE)
      .default(DEFAULT_GARAGE_PAGE_SIZE),
  })
  .strict();

const currentYear = new Date().getFullYear();
const MIN_MODEL_YEAR = 1886; // first automobile
const MAX_MODEL_YEAR = currentYear + 2; // small future margin

const powertrainSchema = z.enum(VEHICLE_POWERTRAINS);

const createVehicleSchema = z
  .object({
    make: z.string().min(1).max(80),
    model: z.string().min(1).max(80),
    modelYear: z.number().int().min(MIN_MODEL_YEAR).max(MAX_MODEL_YEAR),
    powertrain: powertrainSchema,
    engineDescription: z.string().max(120).optional(),
    description: z.string().max(500).optional(),
  })
  .strict();

const updateVehicleSchema = z
  .object({
    make: z.string().min(1).max(80).optional(),
    model: z.string().min(1).max(80).optional(),
    modelYear: z.number().int().min(MIN_MODEL_YEAR).max(MAX_MODEL_YEAR).optional(),
    powertrain: powertrainSchema.optional(),
    engineDescription: z.string().max(120).nullable().optional(),
    description: z.string().max(500).nullable().optional(),
  })
  .strict();

// ---------------------------------------------------------------------------
// Response shape guard
// ---------------------------------------------------------------------------

/**
 * Picks only the fields defined in VehicleDetail to ensure no extra fields
 * (e.g. internal DB fields) can leak from the service layer into the HTTP response.
 */
function toSafeVehicleDetail(v: VehicleDetail): VehicleDetail {
  return {
    id: v.id,
    make: v.make,
    model: v.model,
    modelYear: v.modelYear,
    powertrain: v.powertrain,
    engineDescription: v.engineDescription,
    description: v.description,
    createdAt: v.createdAt,
    updatedAt: v.updatedAt,
  };
}

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export interface RegisterGarageRoutesDependencies {
  garageService?: GarageService;
  badgeService?: BadgeService;
}

export async function registerGarageRoutes(
  app: FastifyInstance,
  dependencies: RegisterGarageRoutesDependencies = {},
): Promise<void> {
  const garageService = dependencies.garageService ?? new GarageService(app.prisma);
  const badgeService = dependencies.badgeService;

  // GET /v1/garage/vehicles
  app.get(
    GARAGE_ROUTE_PATHS.list,
    { preHandler: requireMemberHook },
    async (request): Promise<PaginatedGarageResponse> => {
      const auth = request.auth!;
      const query = listQuerySchema.parse(request.query);

      const result = await garageService.listVehicles({
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: { vehicles: result.vehicles },
        meta: {
          page: query.page,
          pageSize: query.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // GET /v1/garage/vehicles/:vehicleId
  app.get(
    GARAGE_ROUTE_PATHS.detail(':vehicleId'),
    { preHandler: requireMemberHook },
    async (request): Promise<VehicleDetailResponse> => {
      const auth = request.auth!;
      const params = vehicleParamsSchema.parse(request.params);

      const vehicle = await garageService.getVehicle({
        vehicleId: params.vehicleId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return { ok: true, data: { vehicle: toSafeVehicleDetail(vehicle) } };
    },
  );

  // POST /v1/garage/vehicles
  app.post(
    GARAGE_ROUTE_PATHS.list,
    { preHandler: requireMemberHook },
    async (request, reply): Promise<CreateVehicleResponse> => {
      const auth = request.auth!;
      const body = createVehicleSchema.parse(request.body);

      const vehicle = await garageService.createVehicle({
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        make: body.make,
        model: body.model,
        modelYear: body.modelYear,
        powertrain: body.powertrain as VehiclePowertrain,
        engineDescription: body.engineDescription,
        description: body.description,
      });

      // Trigger garage_created badge evaluation. Fire-and-forget — badge awards
      // must not block the vehicle creation response.
      if (badgeService) {
        void badgeService.evaluateGarageCreated(auth.userId).catch(() => undefined);
      }

      void reply.status(201);
      return { ok: true, data: { vehicle: toSafeVehicleDetail(vehicle) } };
    },
  );

  // PATCH /v1/garage/vehicles/:vehicleId
  app.patch(
    GARAGE_ROUTE_PATHS.detail(':vehicleId'),
    { preHandler: requireMemberHook },
    async (request): Promise<UpdateVehicleResponse> => {
      const auth = request.auth!;
      const params = vehicleParamsSchema.parse(request.params);
      const body = updateVehicleSchema.parse(request.body);

      const vehicle = await garageService.updateVehicle({
        vehicleId: params.vehicleId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        make: body.make,
        model: body.model,
        modelYear: body.modelYear,
        powertrain: body.powertrain as VehiclePowertrain | undefined,
        engineDescription: body.engineDescription,
        description: body.description,
      });

      return { ok: true, data: { vehicle: toSafeVehicleDetail(vehicle) } };
    },
  );

  // DELETE /v1/garage/vehicles/:vehicleId
  app.delete(
    GARAGE_ROUTE_PATHS.detail(':vehicleId'),
    { preHandler: requireMemberHook },
    async (request): Promise<DeleteVehicleResponse> => {
      const auth = request.auth!;
      const params = vehicleParamsSchema.parse(request.params);

      await garageService.deleteVehicle({
        vehicleId: params.vehicleId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return { ok: true, data: { deleted: true, vehicleId: params.vehicleId } };
    },
  );
}
