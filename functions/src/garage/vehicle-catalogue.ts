/**
 * Vehicle catalogue — server-side lookup over the static manufacturer/model
 * data (contracts/vehicles/vehicle-catalogue.json, mirrored into
 * ./vehicle-catalogue.generated.ts).
 *
 * WHY THIS EXISTS ON THE SERVER
 * -----------------------------
 * Members pick make/model/year from dropdowns instead of typing them so the
 * community can COUNT cars per manufacturer (Seb product decision 2026-07). A
 * dropdown is UX, not enforcement: a hand-rolled callable request can send any
 * string it likes. Every id that reaches `vehicles/{id}.makeId` / `.modelId` is
 * therefore validated here, against the same catalogue release the clients were
 * generated from — otherwise the aggregate is a count of whatever clients felt
 * like sending.
 *
 * THE "OTHER / NOT LISTED" BUCKET
 * ------------------------------
 * [OTHER_ID] is accepted at both levels and is NOT in the contract data — it is
 * synthesised here. A member with a rare import, a kit car or a brand we simply
 * have not listed must still be able to add their car; in an enthusiast
 * community those are the most engaged members, and a strict enum with no
 * alternative would lock them out entirely. `other` keeps that a SELECTION
 * (no free-text field, so Seb's instruction holds) and keeps the data honest:
 * an explicit bucket that aggregation can subtract, rather than prose nobody
 * can group.
 *
 * `makeId: <real>` + `modelId: 'other'` is the actionable growth signal — it
 * says "someone owns a Volvo whose model we do not list", which is directly
 * fixable by adding that model. `makeId: 'other'` is a count-only signal.
 *
 * Parsing is LAZY (first lookup) so a cold start of an unrelated callable never
 * pays for ~1300 models.
 */

import {
  CATALOGUE_ENCODED,
  CATALOGUE_MAX_MODEL_YEAR_OFFSET,
  CATALOGUE_MIN_MODEL_YEAR,
  CATALOGUE_OTHER_ID,
  CATALOGUE_VERSION,
} from './vehicle-catalogue.generated';

/** The catalogue release stamped onto structured vehicle writes. */
export const CATALOGUE_RELEASE = CATALOGUE_VERSION;

/** The reserved "Other / not listed" id, valid as BOTH a makeId and a modelId. */
export const OTHER_ID = CATALOGUE_OTHER_ID;

/**
 * First model year OFFERED and accepted for a STRUCTURED write. Deliberately
 * later than garage-core's absolute MIN_MODEL_YEAR (1886), which still applies
 * to the legacy free-text path so no existing vehicle becomes unsaveable.
 */
export const OFFERED_MIN_MODEL_YEAR = CATALOGUE_MIN_MODEL_YEAR;

/** Years past the current year that a structured write may claim (1 = next model year). */
export const OFFERED_MAX_MODEL_YEAR_OFFSET = CATALOGUE_MAX_MODEL_YEAR_OFFSET;

export interface CatalogueModel {
  readonly id: string;
  readonly name: string;
}

export interface CatalogueManufacturer {
  readonly id: string;
  readonly name: string;
  /** True for brands commonly seen on Swedish roads (clients surface these first). */
  readonly common: boolean;
  readonly models: readonly CatalogueModel[];
}

interface CatalogueIndex {
  readonly manufacturers: readonly CatalogueManufacturer[];
  readonly byMakeId: ReadonlyMap<string, CatalogueManufacturer>;
  readonly modelsByMakeId: ReadonlyMap<string, ReadonlyMap<string, string>>;
}

let index: CatalogueIndex | null = null;

function buildIndex(): CatalogueIndex {
  const manufacturers: CatalogueManufacturer[] = [];
  const byMakeId = new Map<string, CatalogueManufacturer>();
  const modelsByMakeId = new Map<string, Map<string, string>>();

  for (const line of CATALOGUE_ENCODED) {
    // `makeId|Make name|common(0/1)|modelId=Model name;…` — split with a limit
    // of 4 so a display name is never truncated by a stray delimiter (the
    // generator rejects those, but parsing defensively costs nothing).
    const firstBar = line.indexOf('|');
    const secondBar = line.indexOf('|', firstBar + 1);
    const thirdBar = line.indexOf('|', secondBar + 1);
    if (firstBar < 0 || secondBar < 0 || thirdBar < 0) {
      throw new Error(`Malformed vehicle-catalogue line: ${line.slice(0, 40)}…`);
    }
    const id = line.slice(0, firstBar);
    const name = line.slice(firstBar + 1, secondBar);
    const common = line.slice(secondBar + 1, thirdBar) === '1';
    const models: CatalogueModel[] = [];
    const modelIds = new Map<string, string>();
    for (const chunk of line.slice(thirdBar + 1).split(';')) {
      const eq = chunk.indexOf('=');
      if (eq <= 0) {
        throw new Error(`Malformed vehicle-catalogue model chunk "${chunk}" under "${id}".`);
      }
      const modelId = chunk.slice(0, eq);
      const modelName = chunk.slice(eq + 1);
      models.push({ id: modelId, name: modelName });
      modelIds.set(modelId, modelName);
    }
    const manufacturer: CatalogueManufacturer = { id, name, common, models };
    manufacturers.push(manufacturer);
    byMakeId.set(id, manufacturer);
    modelsByMakeId.set(id, modelIds);
  }

  return { manufacturers, byMakeId, modelsByMakeId };
}

function catalogue(): CatalogueIndex {
  index ??= buildIndex();
  return index;
}

/** Every manufacturer, in contract order (common brands first, then alphabetical). */
export function catalogueManufacturers(): readonly CatalogueManufacturer[] {
  return catalogue().manufacturers;
}

/** True for a real catalogue manufacturer id or [OTHER_ID]. */
export function isKnownMakeId(makeId: string): boolean {
  return makeId === OTHER_ID || catalogue().byMakeId.has(makeId);
}

/**
 * True when [modelId] is offered by [makeId], or is [OTHER_ID].
 *
 * Model ids are unique only WITHIN a manufacturer (`3` exists under both Mazda
 * and MG), so a model is only ever valid together with its make — validating
 * the two independently would accept a "Mazda MGB".
 *
 * `makeId: 'other'` accepts only `modelId: 'other'`: once the brand is unknown
 * there is no model list to pick from, and allowing a real model id under an
 * unknown brand would produce nonsense pairs in the aggregate.
 */
export function isKnownModelId(makeId: string, modelId: string): boolean {
  if (makeId === OTHER_ID) return modelId === OTHER_ID;
  if (modelId === OTHER_ID) return catalogue().byMakeId.has(makeId);
  return catalogue().modelsByMakeId.get(makeId)?.has(modelId) === true;
}

/** Display name for a manufacturer id, or null for [OTHER_ID] / an unknown id. */
export function makeDisplayName(makeId: string): string | null {
  return catalogue().byMakeId.get(makeId)?.name ?? null;
}

/** Display name for a model within a manufacturer, or null for [OTHER_ID] / unknown. */
export function modelDisplayName(makeId: string, modelId: string): string | null {
  return catalogue().modelsByMakeId.get(makeId)?.get(modelId) ?? null;
}

/**
 * The model-year window a STRUCTURED write may claim, evaluated against the
 * server clock: `minModelYear … currentYear + maxModelYearOffset`. The clients
 * offer exactly this window (from the device clock), so a device whose clock
 * runs ahead can offer a year the server rejects — the safe direction, since
 * the alternative is storing a year nobody can have owned yet.
 */
export function offeredModelYearRange(now: Date): { min: number; max: number } {
  return {
    min: OFFERED_MIN_MODEL_YEAR,
    max: now.getFullYear() + OFFERED_MAX_MODEL_YEAR_OFFSET,
  };
}
