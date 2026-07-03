/**
 * Garage domain — pure input validation and document builders (Phase 9e).
 *
 * Ports the legacy semantics of services/api/src/lib/garage-service.ts and
 * packages/shared/src/garage.ts to the Firestore model
 * (docs/firebase-data-model.md `vehicles`):
 *
 * - Garage features are member-only (legacy canAccessGarage): adding,
 *   editing, and deleting vehicles all require an active member.
 * - Each user is limited to MAX_VEHICLES_PER_USER vehicles.
 * - The vehicles document is authenticated-readable by design
 *   (docs/firebase-data-model.md), so NO registration numbers, VIN,
 *   insurance data, or vehicle location can ever be stored — the strict
 *   schemas make such fields unrepresentable.
 * - Ownership failures surface as not-found, never permission-denied, to
 *   avoid leaking whether another user's vehicle exists (legacy parity).
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

/** Legacy limits (packages/shared/src/garage.ts). */
export const MAX_VEHICLES_PER_USER = 5;
export const VEHICLE_MAKE_MODEL_MAX_LENGTH = 80;
export const ENGINE_DESCRIPTION_MAX_LENGTH = 120;
export const VEHICLE_DESCRIPTION_MAX_LENGTH = 500;
export const MIN_MODEL_YEAR = 1886; // first automobile
/** Small future margin for next-model-year vehicles. */
export function maxModelYear(now: Date): number {
  return now.getFullYear() + 2;
}

export const VEHICLE_POWERTRAINS = [
  'petrol',
  'diesel',
  'hybrid',
  'plug_in_hybrid',
  'electric',
  'other',
] as const;
export type VehiclePowertrain = (typeof VEHICLE_POWERTRAINS)[number];

// ---------------------------------------------------------------------------
// Input schemas
// ---------------------------------------------------------------------------

// Firestore-safe document ID (same rationale as drives-core rideIdSchema).
const vehicleIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

function vehicleFieldSchemas(now: Date) {
  return {
    make: z.string().trim().min(1).max(VEHICLE_MAKE_MODEL_MAX_LENGTH),
    model: z.string().trim().min(1).max(VEHICLE_MAKE_MODEL_MAX_LENGTH),
    modelYear: z.number().int().min(MIN_MODEL_YEAR).max(maxModelYear(now)),
    powertrain: z.enum(VEHICLE_POWERTRAINS),
    engineDescription: z.string().max(ENGINE_DESCRIPTION_MAX_LENGTH).nullable(),
    description: z.string().max(VEHICLE_DESCRIPTION_MAX_LENGTH).nullable(),
    color: z.string().trim().min(1).max(80).nullable(),
  };
}

export type AddVehicleInput = {
  make: string;
  model: string;
  modelYear: number;
  powertrain: VehiclePowertrain;
  engineDescription?: string | null;
  description?: string | null;
  color?: string | null;
};

export type UpdateVehicleInput = Partial<AddVehicleInput> & {
  vehicleId: string;
  /**
   * Cloud Storage image path — set after upload, or null to clear. Must lie
   * under the caller's own vehicleImages/{uid}/{vehicleId}/ prefix; the
   * callable validates the prefix against the authenticated uid.
   */
  imagePath?: string | null;
};

export type DeleteVehicleInput = { vehicleId: string };

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export function parseAddVehicleInput(data: unknown, now: Date): ParseResult<AddVehicleInput> {
  const fields = vehicleFieldSchemas(now);
  const schema = z
    .object({
      make: fields.make,
      model: fields.model,
      modelYear: fields.modelYear,
      powertrain: fields.powertrain,
      engineDescription: fields.engineDescription.optional(),
      description: fields.description.optional(),
      color: fields.color.optional(),
    })
    .strict();
  return parse(
    schema,
    data,
    'Expected addVehicleRequest (contracts/schemas/garage.schema.json): { make, model, modelYear, powertrain, engineDescription?, description?, color? }.',
  );
}

export function parseUpdateVehicleInput(
  data: unknown,
  now: Date,
): ParseResult<UpdateVehicleInput> {
  const fields = vehicleFieldSchemas(now);
  const schema = z
    .object({
      vehicleId: vehicleIdSchema,
      make: fields.make.optional(),
      model: fields.model.optional(),
      modelYear: fields.modelYear.optional(),
      powertrain: fields.powertrain.optional(),
      engineDescription: fields.engineDescription.optional(),
      description: fields.description.optional(),
      color: fields.color.optional(),
      imagePath: z.string().min(1).max(500).nullable().optional(),
    })
    .strict();
  return parse(
    schema,
    data,
    'Expected { vehicleId } plus updateVehicleRequest fields (contracts/schemas/garage.schema.json).',
  );
}

export function parseDeleteVehicleInput(data: unknown): ParseResult<DeleteVehicleInput> {
  return parse(
    z.object({ vehicleId: vehicleIdSchema }).strict(),
    data,
    'Expected { vehicleId }.',
  );
}

// ---------------------------------------------------------------------------
// Canonical Cloud Storage paths (backend-domain-mapping.md Storage table)
// ---------------------------------------------------------------------------

export function vehicleImagePrefix(uid: string, vehicleId: string): string {
  return `vehicleImages/${uid}/${vehicleId}/`;
}

/** imagePath must point into the caller's own prefix for this vehicle. */
export function isValidVehicleImagePath(
  imagePath: string,
  uid: string,
  vehicleId: string,
): boolean {
  const prefix = vehicleImagePrefix(uid, vehicleId);
  return imagePath.startsWith(prefix) && imagePath.length > prefix.length;
}

// ---------------------------------------------------------------------------
// Document builders
// ---------------------------------------------------------------------------

/** vehicles/{vehicleId} document (docs/firebase-data-model.md). */
export function buildVehicleDocument(
  input: AddVehicleInput,
  userId: string,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    userId,
    make: input.make,
    model: input.model,
    modelYear: input.modelYear,
    powertrain: input.powertrain,
    engineDescription: input.engineDescription ?? null,
    description: input.description ?? null,
    color: input.color ?? null,
    imagePath: null,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

/** Partial update; returns changed field names for validation/emptiness. */
export function buildVehicleUpdate(
  input: UpdateVehicleInput,
  serverTimestamp: () => unknown,
): { update: Record<string, unknown>; changedFields: string[] } {
  const update: Record<string, unknown> = {};
  const changedFields: string[] = [];
  const assign = (key: string, value: unknown) => {
    update[key] = value;
    changedFields.push(key);
  };

  if (input.make !== undefined) assign('make', input.make);
  if (input.model !== undefined) assign('model', input.model);
  if (input.modelYear !== undefined) assign('modelYear', input.modelYear);
  if (input.powertrain !== undefined) assign('powertrain', input.powertrain);
  if (input.engineDescription !== undefined)
    assign('engineDescription', input.engineDescription);
  if (input.description !== undefined) assign('description', input.description);
  if (input.color !== undefined) assign('color', input.color);
  if (input.imagePath !== undefined) assign('imagePath', input.imagePath);

  if (changedFields.length > 0) {
    update.updatedAt = serverTimestamp();
  }
  return { update, changedFields };
}
