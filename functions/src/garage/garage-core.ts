/**
 * Garage domain — pure input validation and document builders (Phase 9e).
 *
 * Ports the legacy semantics of services/api/src/lib/garage-service.ts and
 * packages/shared/src/garage.ts to the Firestore model
 * (docs/firebase-data-model.md `vehicles`):
 *
 * - Garage features are NOT member-gated (the legacy canAccessGarage gate is
 *   retired): adding, editing, and deleting vehicles only require
 *   requireActiveActor — any authenticated, non-suspended, non-deleted user.
 * - New additions follow the effective subscription tier: Community 2, Plus 5,
 *   Supporter 10. MAX_VEHICLES_PER_USER remains the absolute system ceiling.
 * - The vehicles document is authenticated-readable by design
 *   (docs/firebase-data-model.md). `registrationPlate` is a DELIBERATELY
 *   PUBLIC, user-entered field (Seb product decision 2026-07): the owner opts in
 *   by filling the field, and it is then stored here on the
 *   authenticated-readable doc on purpose. Be precise about the audience — the
 *   `vehicles` read rule is `isAuthenticated()`, so ANY signed-in user can read
 *   the plate; it is gated on neither an active membership nor a suspension
 *   check, and "shown to other users" understates it. It is the one exception —
 *   VIN, insurance data, and vehicle location remain unrepresentable and must
 *   never be added,
 *   as those were never intended to be public. The plate is a free-form,
 *   normalised string (trim/collapse-whitespace/uppercase, no country regex) so
 *   imports and personalised plates are accepted.
 *   FUTURE (not built): an automatic vehicle-info lookup keyed on this plate
 *   would slot in as a garage.lookupVehicleByPlate callable — normalise here,
 *   look up there. This module only holds the manual field.
 * - Ownership failures surface as not-found, never permission-denied, to
 *   avoid leaking whether another user's vehicle exists (legacy parity).
 *
 * STRUCTURED MAKE/MODEL (2026-07)
 * -------------------------------
 * Make and model are now SELECTED from the static catalogue
 * (contracts/vehicles/vehicle-catalogue.json, see ./vehicle-catalogue.ts) so the
 * community can count cars per manufacturer. Two write paths exist, and both
 * stay accepted — the same "accept a superset of what we offer" rule the
 * powertrain vocabulary already follows:
 *
 *  - STRUCTURED (`makeId` + `modelId`): the only path a current client uses.
 *    Both ids are validated against the catalogue, the display strings
 *    `make`/`model` are DERIVED here from the catalogue (never taken from the
 *    request, so a client cannot label a `volvo` id "Ferrari"), and
 *    `catalogueVersion` records which release the selection came from.
 *  - LEGACY (`make` + `model` free text): what shipped clients send. Still
 *    accepted verbatim so those builds keep working, but it leaves
 *    `makeId`/`modelId` null, i.e. the vehicle is simply not part of the
 *    aggregate.
 *
 * Mixing the two in one request is REJECTED: it is always a client bug, and
 * silently preferring one would persist a document whose text and ids disagree.
 *
 * Vehicles created before this change keep their free-text `make`/`model`
 * untouched and hold no ids. Nothing is backfilled or fuzzy-matched — a wrong
 * mapping would be worse than an unmapped car (docs/firebase-data-model.md).
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';
import type { SubscriptionTier } from '../subscription/subscription-core';
import {
  CATALOGUE_RELEASE,
  isKnownMakeId,
  isKnownModelId,
  makeDisplayName,
  modelDisplayName,
  offeredModelYearRange,
} from './vehicle-catalogue';

/**
 * Maximum vehicles a single user may keep in their garage. Raised 5 → 10
 * (2026-08) so multi-car owners are not squeezed; the Samlare badge ladder was
 * re-tuned to match (Brons 1 / Silver 3 / Guld 6 / Platina 10). This is the ONE
 * source of truth for the cap: the addVehicle callable enforces it inside a
 * transaction and the Android client mirrors it (GarageScreen.kt).
 */
export const MAX_VEHICLES_PER_USER = 10;

/** Tier allowances. The 10-car constant above remains the absolute system cap. */
export const GARAGE_VEHICLE_LIMITS = {
  community: 2,
  plus: 5,
  supporter: MAX_VEHICLES_PER_USER,
} as const satisfies Record<SubscriptionTier, number>;

export function garageVehicleLimitForTier(tier: SubscriptionTier): number {
  return GARAGE_VEHICLE_LIMITS[tier];
}
/**
 * Cap on photos per vehicle. Ten is generous for a car profile (exterior +
 * interior + engine + detail shots) while bounding worst-case storage at
 * 10 × 10 MB = 100 MB per vehicle and keeping the detail-page pager/thumbnail
 * strip a manageable size. Matches the cap #506 proposed.
 */
export const MAX_VEHICLE_PHOTOS = 10;
export const VEHICLE_IMAGE_PATH_MAX_LENGTH = 500;
export const VEHICLE_MAKE_MODEL_MAX_LENGTH = 80;
export const ENGINE_DESCRIPTION_MAX_LENGTH = 120;
export const VEHICLE_DESCRIPTION_MAX_LENGTH = 500;
/**
 * Max length of a normalised registration plate. 12 comfortably fits Swedish
 * plates (ABC12D / ABC123) plus spacing and the longer European / personalised
 * / import formats members may run; the field is intentionally format-agnostic.
 */
export const REGISTRATION_PLATE_MAX_LENGTH = 12;
export const MIN_MODEL_YEAR = 1886; // first automobile
/** Small future margin for next-model-year vehicles. */
export function maxModelYear(now: Date): number {
  return now.getFullYear() + 2;
}

/**
 * Bound on a catalogue id on the wire. Real ids are short slugs (`volvo`,
 * `range-rover-evoque`); this only stops a pathological string reaching the
 * catalogue lookup, which then rejects anything not in the catalogue anyway.
 */
export const CATALOGUE_ID_MAX_LENGTH = 64;

/**
 * `make` / `model` display text stored for a vehicle whose brand (or model) is
 * the "Other / not listed" bucket, when there is no pre-existing label to keep.
 *
 * Deliberately plain English rather than a localized string: `make`/`model` are
 * stored ONCE on a document read by every client, the RTDB main-car mirror
 * (functions/src/live) and the admin web, so it cannot follow the reader's
 * locale. Clients that know about the catalogue render a localized label from
 * `makeId === 'other'` instead of this text and never show it; it exists so the
 * document is never blank and older readers show something sensible.
 */
export const OTHER_MAKE_DISPLAY = 'Other make';
export const OTHER_MODEL_DISPLAY = 'Other model';

/**
 * Display text for a structured (`makeId`/`modelId`) selection.
 *
 * `make`/`model` remain the human-readable fields every existing reader already
 * uses (the garage list, the public car profile, the live main-car mirror, the
 * map marker subtitle, the admin web), so deriving them here means the new ids
 * are additive: NOTHING downstream had to change, and a legacy vehicle with no
 * ids keeps rendering exactly as before.
 *
 * The "Other" bucket never destroys OWNER-AUTHORED text: when a member edits a
 * pre-catalogue car and picks Other — because their brand or model genuinely is
 * not listed — the free text they originally typed is KEPT as the display value.
 * Replacing "Duett" with "Other model" would destroy the only description of
 * their car that ever existed, which is exactly what this migration must not do.
 *
 * That only applies to text the OWNER wrote, which is why the check is per level
 * and looks at the stored id, not just the stored text. Text on a document that
 * already had a catalogue id was DERIVED by this function on a previous write,
 * so keeping it would be a lie: a member who picked `volvo`/`240` by mistake and
 * corrects it to Other would otherwise keep a car labelled "Volvo 240" while
 * being counted in the `other` bucket.
 *
 * [existing] is the currently-stored document (absent when adding).
 */
export function resolveCatalogueDisplayNames(
  makeId: string,
  modelId: string,
  existing?: { make?: unknown; model?: unknown; makeId?: unknown; modelId?: unknown },
): { make: string; model: string } {
  /** The stored text, but only when the OWNER wrote it (no id at that level). */
  const ownerAuthored = (text: unknown, storedId: unknown): string | null => {
    if (storedId != null) return null;
    return typeof text === 'string' && text.trim().length > 0 ? text : null;
  };
  return {
    make:
      makeDisplayName(makeId) ??
      ownerAuthored(existing?.make, existing?.makeId) ??
      OTHER_MAKE_DISPLAY,
    model:
      modelDisplayName(makeId, modelId) ??
      ownerAuthored(existing?.model, existing?.modelId) ??
      OTHER_MODEL_DISPLAY,
  };
}

/**
 * The powertrain values a client OFFERS when creating a vehicle: exactly
 * Petrol / Diesel / Hybrid / Electric. This is the canonical, product-facing
 * set — the Android form lists these four and nothing else.
 *
 * Declaration order is the order the form renders them in, so this array is
 * also the UI contract, not just a vocabulary.
 */
export const SELECTABLE_VEHICLE_POWERTRAINS = ['petrol', 'diesel', 'hybrid', 'electric'] as const;
export type SelectableVehiclePowertrain = (typeof SELECTABLE_VEHICLE_POWERTRAINS)[number];

/**
 * RETIRED powertrain values. No client offers these any more, but vehicles
 * created before the four-option change still hold them in Firestore, and
 * shipped clients (<= v0.8.0) still send them.
 *
 * They are therefore deliberately still ACCEPTED on the wire and still stored
 * verbatim — never silently rewritten. Two reasons:
 *
 *  1. **No data corruption.** Mapping `plug_in_hybrid` -> `hybrid` on read
 *     would be lossy, and `other` has no honest target at all. An existing
 *     vehicle keeps the value its owner chose until the owner changes it.
 *  2. **No hard break for old clients.** Rejecting these on add would fail the
 *     add outright for anyone on a shipped build who taps "Laddhybrid"/"Annat",
 *     since app updates roll out gradually. Accepting-but-not-offering
 *     converges the data as users update, with no error path.
 *
 * The Android form renders a retired value only when the vehicle being edited
 * already has it (so the selection is honest); picking any of the four
 * migrates that vehicle forward permanently.
 *
 * Do NOT add to this list — it only ever shrinks, once telemetry shows no
 * vehicle still holds a given value.
 */
export const LEGACY_VEHICLE_POWERTRAINS = ['plug_in_hybrid', 'other'] as const;
export type LegacyVehiclePowertrain = (typeof LEGACY_VEHICLE_POWERTRAINS)[number];

/**
 * Every powertrain value the callables ACCEPT and Firestore may hold — the
 * offered four plus the retired two. This is intentionally wider than
 * [SELECTABLE_VEHICLE_POWERTRAINS]: what we accept is a superset of what we
 * offer, so old documents load and old clients keep working.
 */
export const VEHICLE_POWERTRAINS = [
  ...SELECTABLE_VEHICLE_POWERTRAINS,
  ...LEGACY_VEHICLE_POWERTRAINS,
] as const;
export type VehiclePowertrain = (typeof VEHICLE_POWERTRAINS)[number];

/**
 * Normalises a user-entered registration plate for storage: trims the ends,
 * collapses internal whitespace runs to a single space, and uppercases. A blank
 * (or whitespace-only) value normalises to null so an empty field CLEARS the
 * plate. Deliberately format-agnostic — NO Swedish-plate regex — because members
 * may run imports or personalised plates; this is a light normalise, not a
 * validator that rejects "foreign-looking" plates.
 *
 * Length is enforced separately (REGISTRATION_PLATE_MAX_LENGTH) against the
 * normalised value, so trailing/duplicate spaces never count toward the cap.
 *
 * FUTURE seam: a garage.lookupVehicleByPlate callable would call this to
 * canonicalise the plate before an automatic vehicle-info lookup. Not built yet.
 */
export function normaliseRegistrationPlate(value: string | null | undefined): string | null {
  if (value == null) return null;
  const collapsed = value.trim().replace(/\s+/g, ' ').toUpperCase();
  return collapsed.length === 0 ? null : collapsed;
}

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

/**
 * A catalogue id on the wire. Shape-checked here only; membership is checked
 * against the catalogue in [refineVehicleIdentity], because a valid-looking
 * slug that is not in the catalogue must be rejected just as hard.
 */
const catalogueIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(CATALOGUE_ID_MAX_LENGTH)
  .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/);

/** The make/model/year fields as they arrive, before the identity refinement. */
type VehicleIdentityShape = {
  makeId?: string;
  modelId?: string;
  make?: string;
  model?: string;
  modelYear?: number;
};

/**
 * Enforces the two-write-path rule (see the module KDoc) plus the catalogue
 * membership and the tighter structured year window.
 *
 * This is the ENFORCEMENT point for the whole feature: the dropdowns in the app
 * are UX, and a request assembled by hand can carry any string at all. If this
 * lets an unknown id through, "how many Volvos are there" stops being a fact.
 *
 * @param identityRequired true on the add path (a new vehicle must name itself),
 *   false on update (a partial edit may touch neither make nor model).
 */
function refineVehicleIdentity(identityRequired: boolean, now: Date) {
  return (value: VehicleIdentityShape, ctx: z.RefinementCtx): void => {
    const structured = value.makeId !== undefined || value.modelId !== undefined;
    const legacy = value.make !== undefined || value.model !== undefined;

    if (structured && legacy) {
      ctx.addIssue({
        code: 'custom',
        message:
          'Send either makeId + modelId (catalogue selection) or make + model (legacy free text), never both.',
      });
      return;
    }

    if (structured) {
      const { makeId, modelId } = value;
      if (makeId === undefined || modelId === undefined) {
        ctx.addIssue({
          code: 'custom',
          message: 'makeId and modelId must be sent together.',
        });
        return;
      }
      if (!isKnownMakeId(makeId)) {
        ctx.addIssue({
          code: 'custom',
          message: `Unknown makeId "${makeId}" (contracts/vehicles/vehicle-catalogue.json).`,
        });
        return;
      }
      // Model ids are unique only within a manufacturer, so the pair is checked
      // together — "mazda"/"mgb" must not pass just because both halves exist.
      if (!isKnownModelId(makeId, modelId)) {
        ctx.addIssue({
          code: 'custom',
          message: `Unknown modelId "${modelId}" for makeId "${makeId}".`,
        });
        return;
      }
      // A structured write is held to the window the selector actually offers,
      // which is narrower than the legacy 1886…+2 range kept for old clients.
      if (value.modelYear !== undefined) {
        const { min, max } = offeredModelYearRange(now);
        if (value.modelYear < min || value.modelYear > max) {
          ctx.addIssue({
            code: 'custom',
            message: `modelYear must be between ${min} and ${max}.`,
          });
        }
      }
      return;
    }

    // A new vehicle must name itself fully; an EDIT may still change just one of
    // the two free-text fields, which shipped clients are entitled to do and
    // which worked before the catalogue existed. Either way the ids are cleared
    // (see buildVehicleUpdate), so a half-edit cannot leave text and ids
    // disagreeing.
    if (legacy && identityRequired && (value.make === undefined || value.model === undefined)) {
      ctx.addIssue({
        code: 'custom',
        message: 'make and model must be sent together.',
      });
      return;
    }

    if (identityRequired && !legacy) {
      ctx.addIssue({
        code: 'custom',
        message: 'A vehicle needs makeId + modelId (or legacy make + model).',
      });
    }
  };
}

function vehicleFieldSchemas(now: Date) {
  return {
    makeId: catalogueIdSchema,
    modelId: catalogueIdSchema,
    make: z.string().trim().min(1).max(VEHICLE_MAKE_MODEL_MAX_LENGTH),
    model: z.string().trim().min(1).max(VEHICLE_MAKE_MODEL_MAX_LENGTH),
    modelYear: z.number().int().min(MIN_MODEL_YEAR).max(maxModelYear(now)),
    powertrain: z.enum(VEHICLE_POWERTRAINS),
    engineDescription: z.string().max(ENGINE_DESCRIPTION_MAX_LENGTH).nullable(),
    description: z.string().max(VEHICLE_DESCRIPTION_MAX_LENGTH).nullable(),
    color: z.string().trim().min(1).max(80).nullable(),
    // Registration plate: accept any string (or null to clear), normalise it
    // (trim/collapse-whitespace/uppercase), then enforce the cap against the
    // NORMALISED value. A generous raw bound guards against pathological input
    // before normalisation; the real limit is checked after.
    registrationPlate: z
      .string()
      .max(64)
      .nullable()
      .transform((v) => normaliseRegistrationPlate(v))
      .refine((v) => v === null || v.length <= REGISTRATION_PLATE_MAX_LENGTH, {
        message: `registrationPlate must be at most ${REGISTRATION_PLATE_MAX_LENGTH} characters after normalisation.`,
      }),
  };
}

export type AddVehicleInput = {
  /**
   * Catalogue manufacturer id (`volvo`, or `other` for the not-listed bucket),
   * or null when the request came in on the legacy free-text path. This — not
   * [make] — is what aggregation counts.
   */
  makeId: string | null;
  /** Catalogue model id within [makeId], or null on the legacy path. */
  modelId: string | null;
  /**
   * Display text. DERIVED from the catalogue for a structured request (never
   * taken from the wire), or the caller's free text on the legacy path. Kept as
   * the field every existing reader renders, so the ids are purely additive.
   */
  make: string;
  model: string;
  modelYear: number;
  powertrain: VehiclePowertrain;
  engineDescription?: string | null;
  description?: string | null;
  color?: string | null;
  /**
   * Registration plate, already normalised (trim/collapse/uppercase) by the
   * parse schema — or null when the user left it blank / cleared it. Stored on
   * the authenticated-readable vehicle doc: an intentionally PUBLIC field per product
   * decision (see the module KDoc), unlike VIN / insurance / location.
   */
  registrationPlate?: string | null;
};

export type UpdateVehicleInput = {
  vehicleId: string;
  /**
   * Structured selection. Present together with [modelId] or not at all;
   * validated against the catalogue. When present, `make`/`model` are re-derived
   * server-side, so these two never arrive alongside the legacy text fields.
   */
  makeId?: string;
  modelId?: string;
  /**
   * Legacy free-text edit from a shipped client. Still accepted, but it CLEARS
   * `makeId`/`modelId` (see [buildVehicleUpdate]): free text that no longer
   * matches the stored ids would silently corrupt the aggregate, so the vehicle
   * drops out of it instead.
   */
  make?: string;
  model?: string;
  modelYear?: number;
  powertrain?: VehiclePowertrain;
  engineDescription?: string | null;
  description?: string | null;
  color?: string | null;
  /** Normalised plate, or null to clear (see [AddVehicleInput.registrationPlate]). */
  registrationPlate?: string | null;
  /**
   * Cloud Storage image path — set after upload, or null to clear. Must lie
   * under the caller's own vehicleImages/{uid}/{vehicleId}/ prefix; the
   * callable validates the prefix against the authenticated uid.
   */
  imagePath?: string | null;
};

export type DeleteVehicleInput = { vehicleId: string };

/**
 * Sets or clears the caller's "main car" flag on one owned vehicle. The
 * callable enforces the max-1 constraint transactionally (setting one main
 * clears any other), so this input only carries the target + the desired flag.
 */
export type SetMainVehicleInput = { vehicleId: string; isMain: boolean };

/** Appends one uploaded photo path to a vehicle's gallery (garage.addVehiclePhoto). */
export type AddVehiclePhotoInput = { vehicleId: string; photoPath: string };

/** Removes one photo path from a vehicle's gallery (garage.removeVehiclePhoto). */
export type RemoveVehiclePhotoInput = { vehicleId: string; photoPath: string };

/**
 * Sets the full display order of a vehicle's photos (garage.reorderVehiclePhotos).
 * orderedPaths must be a permutation of the vehicle's current photo set; its
 * first entry becomes the cover.
 */
export type ReorderVehiclePhotosInput = { vehicleId: string; orderedPaths: string[] };

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

/**
 * The identity refinement's messages name the exact rejected id, which is the
 * difference between a client author fixing a typo in minutes and guessing for
 * an hour. Surface the first one alongside the generic shape hint.
 */
function withFirstIssue(expected: string, error: z.ZodError): string {
  const first = error.issues[0];
  return first?.message ? `${expected} ${first.message}` : expected;
}

export function parseAddVehicleInput(data: unknown, now: Date): ParseResult<AddVehicleInput> {
  const fields = vehicleFieldSchemas(now);
  const schema = z
    .object({
      makeId: fields.makeId.optional(),
      modelId: fields.modelId.optional(),
      make: fields.make.optional(),
      model: fields.model.optional(),
      modelYear: fields.modelYear,
      powertrain: fields.powertrain,
      engineDescription: fields.engineDescription.optional(),
      description: fields.description.optional(),
      color: fields.color.optional(),
      registrationPlate: fields.registrationPlate.optional(),
    })
    .strict()
    .superRefine(refineVehicleIdentity(true, now));
  const expected =
    'Expected addVehicleRequest (contracts/schemas/garage.schema.json): { makeId, modelId, modelYear, powertrain, … } (or legacy { make, model, … }).';
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: withFirstIssue(expected, result.error) };
  }
  const raw = result.data;
  // Structured requests carry ids only; the display text is resolved HERE from
  // the catalogue so the stored `make`/`model` can never contradict the ids.
  // There is no existing document on add, so an `other` selection falls back to
  // the neutral placeholder.
  const identity =
    raw.makeId !== undefined && raw.modelId !== undefined
      ? {
          makeId: raw.makeId,
          modelId: raw.modelId,
          ...resolveCatalogueDisplayNames(raw.makeId, raw.modelId),
        }
      : {
          makeId: null,
          modelId: null,
          // The refinement guarantees both are present on the legacy path.
          make: raw.make as string,
          model: raw.model as string,
        };
  return { ok: true, input: { ...raw, ...identity } };
}

export function parseUpdateVehicleInput(data: unknown, now: Date): ParseResult<UpdateVehicleInput> {
  const fields = vehicleFieldSchemas(now);
  const schema = z
    .object({
      vehicleId: vehicleIdSchema,
      makeId: fields.makeId.optional(),
      modelId: fields.modelId.optional(),
      make: fields.make.optional(),
      model: fields.model.optional(),
      modelYear: fields.modelYear.optional(),
      powertrain: fields.powertrain.optional(),
      engineDescription: fields.engineDescription.optional(),
      description: fields.description.optional(),
      color: fields.color.optional(),
      registrationPlate: fields.registrationPlate.optional(),
      imagePath: z.string().min(1).max(500).nullable().optional(),
    })
    .strict()
    // identityRequired = false: a partial edit (a new photo, a plate) touches
    // neither make nor model, and must stay legal.
    .superRefine(refineVehicleIdentity(false, now));
  const expected =
    'Expected { vehicleId } plus updateVehicleRequest fields (contracts/schemas/garage.schema.json).';
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: withFirstIssue(expected, result.error) };
  }
  return { ok: true, input: result.data };
}

export function parseDeleteVehicleInput(data: unknown): ParseResult<DeleteVehicleInput> {
  return parse(z.object({ vehicleId: vehicleIdSchema }).strict(), data, 'Expected { vehicleId }.');
}

export function parseSetMainVehicleInput(data: unknown): ParseResult<SetMainVehicleInput> {
  return parse(
    z.object({ vehicleId: vehicleIdSchema, isMain: z.boolean() }).strict(),
    data,
    'Expected { vehicleId, isMain }.',
  );
}

// A single photo path on the wire: non-empty, bounded, but NOT yet validated
// against the caller's own vehicleImages prefix — that check needs the uid and
// vehicleId and lives in isValidVehicleImagePath at the callable.
const photoPathSchema = z.string().min(1).max(VEHICLE_IMAGE_PATH_MAX_LENGTH);

export function parseAddVehiclePhotoInput(data: unknown): ParseResult<AddVehiclePhotoInput> {
  return parse(
    z.object({ vehicleId: vehicleIdSchema, photoPath: photoPathSchema }).strict(),
    data,
    'Expected { vehicleId, photoPath } (contracts/schemas/garage.schema.json addVehiclePhotoRequest).',
  );
}

export function parseRemoveVehiclePhotoInput(data: unknown): ParseResult<RemoveVehiclePhotoInput> {
  return parse(
    z.object({ vehicleId: vehicleIdSchema, photoPath: photoPathSchema }).strict(),
    data,
    'Expected { vehicleId, photoPath } (contracts/schemas/garage.schema.json removeVehiclePhotoRequest).',
  );
}

export function parseReorderVehiclePhotosInput(
  data: unknown,
): ParseResult<ReorderVehiclePhotosInput> {
  return parse(
    z
      .object({
        vehicleId: vehicleIdSchema,
        // At most the cap; the permutation check at the callable rejects any
        // set that doesn't match the stored photos exactly (including length).
        orderedPaths: z.array(photoPathSchema).min(1).max(MAX_VEHICLE_PHOTOS),
      })
      .strict(),
    data,
    'Expected { vehicleId, orderedPaths } (contracts/schemas/garage.schema.json reorderVehiclePhotosRequest).',
  );
}

// ---------------------------------------------------------------------------
// Canonical Cloud Storage paths (backend-domain-mapping.md Storage table)
// ---------------------------------------------------------------------------

export function vehicleImagePrefix(uid: string, vehicleId: string): string {
  return `vehicleImages/${uid}/${vehicleId}/`;
}

/**
 * imagePath must point into the caller's own prefix for this vehicle AND be
 * exactly one path segment below it — storage rules match precisely
 * vehicleImages/{userId}/{vehicleId}/{imageId}, so a nested path could be
 * persisted here but never written or read.
 */
export function isValidVehicleImagePath(
  imagePath: string,
  uid: string,
  vehicleId: string,
): boolean {
  const prefix = vehicleImagePrefix(uid, vehicleId);
  if (!imagePath.startsWith(prefix)) {
    return false;
  }
  const imageId = imagePath.slice(prefix.length);
  return imageId.trim().length > 0 && !imageId.includes('/');
}

// ---------------------------------------------------------------------------
// Multi-photo gallery — pure list logic (garage.addVehiclePhoto /
// removeVehiclePhoto / reorderVehiclePhotos + updateVehicle cover reconcile)
// ---------------------------------------------------------------------------
//
// Cover-photo model: `photoPaths` is the ordered source of truth; the cover is
// always photoPaths[0], and `vehicles/{id}.imagePath` is kept as a denormalised
// mirror of that cover for backward compatibility (shipped clients and the
// profile card still read imagePath). Every photo-mutating callable maintains
// both fields together so `imagePath === photoPaths[0]` (or both null/empty).

/**
 * The vehicle's current photo set, migrating legacy single-photo documents on
 * read: a doc that predates `photoPaths` (only `imagePath` set) is treated as a
 * one-element gallery `[imagePath]`; a doc with a valid `photoPaths` array uses
 * it verbatim (blanks/non-strings dropped defensively).
 */
export function readExistingPhotoPaths(data: {
  photoPaths?: unknown;
  imagePath?: unknown;
}): string[] {
  const raw = data.photoPaths;
  if (Array.isArray(raw)) {
    return raw.filter((p): p is string => typeof p === 'string' && p.trim().length > 0);
  }
  const imagePath = data.imagePath;
  return typeof imagePath === 'string' && imagePath.trim().length > 0 ? [imagePath] : [];
}

/** The cover path for a gallery: its first entry, or null when empty. */
export function coverPhotoPath(paths: string[]): string | null {
  return paths[0] ?? null;
}

/** Why an append was rejected. */
export type AppendPhotoError = 'duplicate' | 'cap';

/**
 * Appends [photoPath] to [existing], enforcing the cap and rejecting a
 * duplicate (image ids are fresh UUIDs, so a duplicate means a double-record).
 */
export function appendPhotoPath(
  existing: string[],
  photoPath: string,
): { ok: true; paths: string[] } | { ok: false; error: AppendPhotoError } {
  if (existing.includes(photoPath)) {
    return { ok: false, error: 'duplicate' };
  }
  if (existing.length >= MAX_VEHICLE_PHOTOS) {
    return { ok: false, error: 'cap' };
  }
  return { ok: true, paths: [...existing, photoPath] };
}

/**
 * Removes [photoPath] from [existing], preserving the order of the rest. The
 * cover promotes automatically: when the removed path was photoPaths[0], the
 * next remaining photo becomes the new first (cover). `found` is false when the
 * path was not part of the gallery.
 */
export function removePhotoPath(
  existing: string[],
  photoPath: string,
): { found: boolean; paths: string[] } {
  const paths = existing.filter((p) => p !== photoPath);
  return { found: paths.length !== existing.length, paths };
}

/** True when [candidate] is a reordering of [existing] (same multiset). */
export function isPhotoPermutation(existing: string[], candidate: string[]): boolean {
  if (existing.length !== candidate.length) return false;
  const remaining = [...existing];
  for (const path of candidate) {
    const index = remaining.indexOf(path);
    if (index === -1) return false;
    remaining.splice(index, 1);
  }
  return remaining.length === 0;
}

/**
 * Reconciles [existing] to reflect [newCover] set through updateVehicle's
 * single `imagePath` field (the edit-form "change photo" flow and shipped
 * clients), keeping the photoPaths array coherent with the cover mirror:
 *  - null cover clears the whole gallery;
 *  - a cover already in the gallery is promoted to the front (reorder);
 *  - a brand-new cover replaces the current cover (photoPaths[0]) while
 *    preserving any additional photos — matching "changed the one photo" for
 *    single-photo clients, and never growing past the existing length.
 */
export function reconcileCoverPhotoPaths(existing: string[], newCover: string | null): string[] {
  if (newCover === null) return [];
  if (existing.includes(newCover)) {
    return [newCover, ...existing.filter((p) => p !== newCover)];
  }
  return [newCover, ...existing.slice(1)];
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
    // Catalogue ids — the aggregation keys. Explicitly null (not omitted) on the
    // legacy free-text path so every document has the same shape and an
    // aggregate can query `makeId == null` to see exactly what is unmapped.
    makeId: input.makeId,
    modelId: input.modelId,
    // Which catalogue release the ids were picked from; null when there was no
    // structured selection. Taken from the SERVER's catalogue, never the client's
    // claim, so the provenance cannot be forged by an old or patched build.
    catalogueVersion: input.makeId === null ? null : CATALOGUE_RELEASE,
    modelYear: input.modelYear,
    powertrain: input.powertrain,
    engineDescription: input.engineDescription ?? null,
    description: input.description ?? null,
    color: input.color ?? null,
    // Intentionally-public, user-entered plate (already normalised by the parse
    // schema). null when the user left it blank. See the module KDoc.
    registrationPlate: input.registrationPlate ?? null,
    imagePath: null,
    // Ordered photo gallery (source of truth); imagePath mirrors photoPaths[0]
    // as the cover. New vehicles start with no photos.
    photoPaths: [],
    // Main-car flag (max 1 per user, enforced by garage.setMainVehicle). New
    // vehicles are never the main car until the owner marks them.
    isMainCar: false,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

/**
 * Partial update; returns changed field names for validation/emptiness.
 *
 * @param existing the vehicle's CURRENT stored data (text AND ids), used only so
 *   an "Other / not listed" selection keeps a member's original free text
 *   instead of flattening it to a placeholder. The ids matter: text that a
 *   previous write DERIVED from a catalogue id must not be kept when the owner
 *   moves that level to Other. Omit when unavailable; the placeholder is used.
 */
export function buildVehicleUpdate(
  input: UpdateVehicleInput,
  serverTimestamp: () => unknown,
  existing?: { make?: unknown; model?: unknown },
): { update: Record<string, unknown>; changedFields: string[] } {
  const update: Record<string, unknown> = {};
  const changedFields: string[] = [];
  const assign = (key: string, value: unknown) => {
    update[key] = value;
    changedFields.push(key);
  };

  if (input.makeId !== undefined && input.modelId !== undefined) {
    // Structured selection: write the ids, re-derive the display text from the
    // catalogue, and re-stamp the release. The parse refinement has already
    // rejected any id the catalogue does not know.
    const names = resolveCatalogueDisplayNames(input.makeId, input.modelId, existing);
    assign('makeId', input.makeId);
    assign('modelId', input.modelId);
    assign('catalogueVersion', CATALOGUE_RELEASE);
    assign('make', names.make);
    assign('model', names.model);
  } else {
    // Legacy free-text edit (a shipped client). Accepted, but the stored ids can
    // no longer be trusted to describe this text, so they are CLEARED rather
    // than left to poison the aggregate. The text itself is never touched
    // otherwise — a vehicle that predates the catalogue and is edited by an old
    // client keeps exactly the make/model its owner typed.
    if (input.make !== undefined) assign('make', input.make);
    if (input.model !== undefined) assign('model', input.model);
    if (input.make !== undefined || input.model !== undefined) {
      assign('makeId', null);
      assign('modelId', null);
      assign('catalogueVersion', null);
    }
  }
  if (input.modelYear !== undefined) assign('modelYear', input.modelYear);
  if (input.powertrain !== undefined) assign('powertrain', input.powertrain);
  if (input.engineDescription !== undefined) assign('engineDescription', input.engineDescription);
  if (input.description !== undefined) assign('description', input.description);
  if (input.color !== undefined) assign('color', input.color);
  // registrationPlate arrives already normalised (null clears it).
  if (input.registrationPlate !== undefined) assign('registrationPlate', input.registrationPlate);
  if (input.imagePath !== undefined) assign('imagePath', input.imagePath);

  if (changedFields.length > 0) {
    update.updatedAt = serverTimestamp();
  }
  return { update, changedFields };
}
