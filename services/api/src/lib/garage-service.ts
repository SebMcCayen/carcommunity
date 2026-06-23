/**
 * GarageService — backend business logic for "Mitt garage" (My Garage).
 *
 * Design rules enforced here:
 *  - Vehicle ownership is always derived from the authenticated user context.
 *    userId is never accepted from the client.
 *  - Only users with active member_monthly entitlement may use garage features.
 *  - Suspended and deleted users are rejected at the earliest opportunity.
 *  - Each user is limited to MAX_VEHICLES_PER_USER vehicles.
 *  - Ownership failures return a safe not_found response to avoid leaking
 *    whether another user's vehicle exists.
 *  - Only necessary fields are selected.
 *  - No registration numbers, VIN, insurance data, or vehicle location is
 *    ever stored or returned.
 *  - Vehicle profiles are private in this step.
 *  - Admin access to individual vehicles is not provided — only aggregate counts.
 *
 * Future preparation:
 *  - TODO: Add garage-created badge trigger once the badge system is implemented.
 *  - TODO: Add optional public visibility toggle when public profiles are introduced.
 */

import { Prisma } from '@prisma/client';
import type { PrismaClient, Vehicle } from '@prisma/client';

import type { VehicleDetail, VehicleSummary } from '@carcommunity/shared/garage';
import {
  MAX_VEHICLES_PER_USER,
  MAX_GARAGE_PAGE_SIZE,
  DEFAULT_GARAGE_PAGE_SIZE,
} from '@carcommunity/shared/garage';
import { canAccessGarage, isSuspendedStatus } from '@carcommunity/shared/users';
import type { SubscriptionEntitlement, UserRole, UserStatus } from '@carcommunity/shared/users';
import type { VehiclePowertrain } from '@prisma/client';

import { AppError } from './errors.js';

export interface GarageActor {
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}

export interface ListVehiclesResult {
  vehicles: VehicleSummary[];
  total: number;
  hasNext: boolean;
}

function assertEligibleForGarage(actor: GarageActor): void {
  if (actor.status === 'deleted') {
    throw new AppError(403, 'forbidden', 'Your account has been deleted.');
  }
  if (isSuspendedStatus(actor.status)) {
    throw new AppError(403, 'suspended', 'Your account has been suspended.');
  }
  if (!canAccessGarage(actor)) {
    throw new AppError(403, 'forbidden', 'Member subscription required to access garage features.');
  }
}

type VehicleSummaryRecord = Pick<
  Vehicle,
  'id' | 'make' | 'model' | 'modelYear' | 'powertrain' | 'engineDescription' | 'createdAt' | 'updatedAt'
>;

function toSummary(v: VehicleSummaryRecord): VehicleSummary {
  return {
    id: v.id,
    make: v.make,
    model: v.model,
    modelYear: v.modelYear,
    powertrain: v.powertrain as VehicleSummary['powertrain'],
    engineDescription: v.engineDescription ?? null,
    createdAt: v.createdAt.toISOString(),
    updatedAt: v.updatedAt.toISOString(),
  };
}

function toDetail(v: Vehicle): VehicleDetail {
  return {
    ...toSummary(v),
    description: v.description ?? null,
  };
}

export class GarageService {
  constructor(private readonly prisma: PrismaClient) {}

  /**
   * List the authenticated user's vehicles, newest first.
   * Returns summary items without the description field.
   */
  public async listVehicles(params: {
    actor: GarageActor;
    page: number;
    pageSize: number;
  }): Promise<ListVehiclesResult> {
    assertEligibleForGarage(params.actor);

    const take = Math.min(params.pageSize, MAX_GARAGE_PAGE_SIZE);
    const skip = (params.page - 1) * take;
    const where = { userId: params.actor.userId };

    const [total, vehicles] = await this.prisma.$transaction([
      this.prisma.vehicle.count({ where }),
      this.prisma.vehicle.findMany({
        where,
        select: {
          id: true,
          make: true,
          model: true,
          modelYear: true,
          powertrain: true,
          engineDescription: true,
          createdAt: true,
          updatedAt: true,
        },
        orderBy: { createdAt: 'desc' },
        skip,
        take,
      }),
    ]);

    return {
      vehicles: vehicles.map(toSummary),
      total,
      hasNext: skip + vehicles.length < total,
    };
  }

  /**
   * Get a single vehicle detail for the authenticated owner.
   * Returns 404 for non-existent or unowned vehicles to avoid leaking
   * whether another user's vehicle exists.
   */
  public async getVehicle(params: {
    vehicleId: string;
    actor: GarageActor;
  }): Promise<VehicleDetail> {
    assertEligibleForGarage(params.actor);

    const vehicle = await this.prisma.vehicle.findFirst({
      where: {
        id: params.vehicleId,
        userId: params.actor.userId,
      },
    });

    if (!vehicle) {
      throw new AppError(404, 'not_found', 'Vehicle not found.');
    }

    return toDetail(vehicle);
  }

  /**
   * Create a new vehicle for the authenticated user.
   * Enforces the per-user vehicle limit.
   * userId is always set from the actor — never from the client.
   */
  public async createVehicle(params: {
    actor: GarageActor;
    make: string;
    model: string;
    modelYear: number;
    powertrain: VehiclePowertrain;
    engineDescription?: string;
    description?: string;
  }): Promise<VehicleDetail> {
    assertEligibleForGarage(params.actor);

    const maxCreateRetries = 3;
    for (let attempt = 0; attempt < maxCreateRetries; attempt += 1) {
      try {
        const vehicle = await this.prisma.$transaction(
          async (tx) => {
            const existingCount = await tx.vehicle.count({
              where: { userId: params.actor.userId },
            });

            if (existingCount >= MAX_VEHICLES_PER_USER) {
              throw new AppError(
                409,
                'conflict',
                `Vehicle limit reached. A maximum of ${MAX_VEHICLES_PER_USER} vehicles is allowed per user.`,
              );
            }

            return tx.vehicle.create({
              data: {
                userId: params.actor.userId,
                make: params.make,
                model: params.model,
                modelYear: params.modelYear,
                powertrain: params.powertrain,
                engineDescription: params.engineDescription ?? null,
                description: params.description ?? null,
              },
            });
          },
          { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
        );

        return toDetail(vehicle);
      } catch (error) {
        const isSerializationFailure =
          error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2034';
        if (!isSerializationFailure || attempt === maxCreateRetries - 1) {
          throw error;
        }
      }
    }

    throw new AppError(500, 'internal_error', 'Internal server error.');
  }

  /**
   * Update a vehicle owned by the authenticated user.
   * Only the listed fields are updatable. userId, id, and timestamps are immutable.
   * Returns 404 for non-existent or unowned vehicles.
   */
  public async updateVehicle(params: {
    vehicleId: string;
    actor: GarageActor;
    make?: string;
    model?: string;
    modelYear?: number;
    powertrain?: VehiclePowertrain;
    engineDescription?: string | null;
    description?: string | null;
  }): Promise<VehicleDetail> {
    assertEligibleForGarage(params.actor);

    const existing = await this.prisma.vehicle.findUnique({
      where: { id: params.vehicleId },
      select: { userId: true },
    });

    if (!existing || existing.userId !== params.actor.userId) {
      throw new AppError(404, 'not_found', 'Vehicle not found.');
    }

    const vehicle = await this.prisma.vehicle.update({
      where: { id: params.vehicleId },
      data: {
        ...(params.make !== undefined ? { make: params.make } : {}),
        ...(params.model !== undefined ? { model: params.model } : {}),
        ...(params.modelYear !== undefined ? { modelYear: params.modelYear } : {}),
        ...(params.powertrain !== undefined ? { powertrain: params.powertrain } : {}),
        ...(params.engineDescription !== undefined ? { engineDescription: params.engineDescription } : {}),
        ...(params.description !== undefined ? { description: params.description } : {}),
      },
    });

    return toDetail(vehicle);
  }

  /**
   * Delete a vehicle owned by the authenticated user.
   * Returns 404 for non-existent or unowned vehicles.
   * Hard deletion — vehicle data is removed immediately and permanently.
   */
  public async deleteVehicle(params: {
    vehicleId: string;
    actor: GarageActor;
  }): Promise<void> {
    assertEligibleForGarage(params.actor);

    const vehicle = await this.prisma.vehicle.findUnique({
      where: { id: params.vehicleId },
      select: { userId: true },
    });

    if (!vehicle || vehicle.userId !== params.actor.userId) {
      throw new AppError(404, 'not_found', 'Vehicle not found.');
    }

    await this.prisma.vehicle.delete({ where: { id: params.vehicleId } });
  }

  /**
   * Return aggregate stats for admin use only.
   * Never includes individual vehicle details, descriptions, or user data.
   * Do not add registration data — it must not be collected.
   *
   * TODO: Add a TODO for moderation aggregate if public vehicle profiles are introduced later.
   */
  public async getAdminStats(): Promise<{
    totalVehicleCount: number;
    usersWithVehicleCount: number;
  }> {
    const [totalVehicleCount, usersWithVehicleCount] = await this.prisma.$transaction([
      this.prisma.vehicle.count(),
      this.prisma.vehicle.count({ distinct: ['userId'] }),
    ]);

    return {
      totalVehicleCount,
      usersWithVehicleCount,
    };
  }

  public static readonly DEFAULT_PAGE_SIZE = DEFAULT_GARAGE_PAGE_SIZE;
}
