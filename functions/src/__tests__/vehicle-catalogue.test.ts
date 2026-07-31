/**
 * Unit tests for the server-side vehicle catalogue (garage/vehicle-catalogue.ts)
 * and the structured make/model write path in garage-core.
 *
 * The first suite is a DRIFT GUARD, and it is the load-bearing one: the backend
 * validates against a generated mirror of
 * contracts/vehicles/vehicle-catalogue.json, and `firebase deploy` uploads only
 * functions/, so nothing at run time can consult the contract itself. If the
 * mirror silently diverged, the client would offer ids the backend rejects (or
 * worse, accept ids the client never offers) and the whole point of the feature
 * — a trustworthy per-manufacturer count — would quietly rot. So the contract is
 * read from disk here and compared against the parsed mirror, entry by entry.
 *
 * No emulators required.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CATALOGUE_RELEASE,
  OFFERED_MAX_MODEL_YEAR_OFFSET,
  OFFERED_MIN_MODEL_YEAR,
  OTHER_ID,
  catalogueManufacturers,
  isKnownMakeId,
  isKnownModelId,
  makeDisplayName,
  modelDisplayName,
  offeredModelYearRange,
} from '../garage/vehicle-catalogue';
import {
  OTHER_MAKE_DISPLAY,
  OTHER_MODEL_DISPLAY,
  buildVehicleDocument,
  buildVehicleUpdate,
  parseAddVehicleInput,
  parseUpdateVehicleInput,
  resolveCatalogueDisplayNames,
} from '../garage/garage-core';

interface ContractModel {
  id: string;
  name: string;
}
interface ContractManufacturer {
  id: string;
  name: string;
  common?: boolean;
  models: ContractModel[];
}
interface Contract {
  version: string;
  otherId: string;
  minModelYear: number;
  maxModelYearOffset: number;
  manufacturers: ContractManufacturer[];
}

const contract: Contract = JSON.parse(
  readFileSync(resolve(__dirname, '../../../contracts/vehicles/vehicle-catalogue.json'), 'utf8'),
);

const NOW = new Date('2026-07-04T12:00:00Z');
const serverTimestamp = () => 'SERVER_TS';

describe('vehicle catalogue mirrors the contract', () => {
  it('carries the same version, other-id and year bounds', () => {
    expect(CATALOGUE_RELEASE).toBe(contract.version);
    expect(OTHER_ID).toBe(contract.otherId);
    expect(OFFERED_MIN_MODEL_YEAR).toBe(contract.minModelYear);
    expect(OFFERED_MAX_MODEL_YEAR_OFFSET).toBe(contract.maxModelYearOffset);
  });

  it('holds every manufacturer and model, in contract order', () => {
    // Order is part of the contract: clients render the list as-is (common
    // Swedish brands first, then alphabetical), so a reordered mirror would
    // change the UI without anyone editing the UI.
    expect(catalogueManufacturers().map((m) => ({ id: m.id, name: m.name }))).toEqual(
      contract.manufacturers.map((m) => ({ id: m.id, name: m.name })),
    );
    expect(catalogueManufacturers().map((m) => m.common)).toEqual(
      contract.manufacturers.map((m) => m.common === true),
    );
    for (const [i, make] of contract.manufacturers.entries()) {
      expect(catalogueManufacturers()[i]?.models).toEqual(make.models);
    }
  });

  it('is a real catalogue, not a stub', () => {
    // A truncated mirror (a bad merge, a half-run generator) would still satisfy
    // the structural checks above if the contract were truncated the same way.
    expect(catalogueManufacturers().length).toBeGreaterThan(50);
    const models = catalogueManufacturers().reduce((sum, m) => sum + m.models.length, 0);
    expect(models).toBeGreaterThan(500);
    // The brands this community is actually built around must be present.
    for (const id of ['volvo', 'saab', 'volkswagen', 'bmw', 'toyota']) {
      expect(isKnownMakeId(id)).toBe(true);
    }
    expect(isKnownModelId('volvo', '240')).toBe(true);
    expect(isKnownModelId('saab', '9-3')).toBe(true);
  });

  it('offers the Toyota GT86 and the NIO ET5 Touring as selectable models', () => {
    // Regression guard for the two brand/model pairs added in catalogue v1.1.0.
    // GT86 (first-gen, 2012–2020) is a distinct entry from the existing GR86.
    expect(isKnownModelId('toyota', 'gt86')).toBe(true);
    expect(isKnownModelId('toyota', 'gr86')).toBe(true);
    // NIO already existed as a manufacturer; ET5 Touring is a new model on it.
    expect(isKnownMakeId('nio')).toBe(true);
    expect(isKnownModelId('nio', 'et5-touring')).toBe(true);
  });

  it('never lists the other-bucket id as a real entry', () => {
    for (const make of catalogueManufacturers()) {
      expect(make.id).not.toBe(OTHER_ID);
      expect(make.models.some((m) => m.id === OTHER_ID)).toBe(false);
    }
  });
});

describe('vehicle catalogue lookups', () => {
  it('rejects an unknown manufacturer and an unknown model', () => {
    expect(isKnownMakeId('definitely-not-a-brand')).toBe(false);
    expect(isKnownModelId('volvo', 'not-a-volvo')).toBe(false);
  });

  it('validates the model against its OWN manufacturer', () => {
    // Model ids are unique only within a manufacturer — `3` exists under both
    // Mazda and MG — so validating the halves independently would wave through
    // pairs that do not exist.
    expect(isKnownModelId('mazda', 'mgb')).toBe(false);
    expect(isKnownModelId('mg', 'mgb')).toBe(true);
  });

  it('accepts the other bucket at both levels', () => {
    expect(isKnownMakeId(OTHER_ID)).toBe(true);
    // A real brand whose model is not listed — the actionable growth signal.
    expect(isKnownModelId('volvo', OTHER_ID)).toBe(true);
    expect(isKnownModelId(OTHER_ID, OTHER_ID)).toBe(true);
    // An unknown brand cannot claim a known model: that pair means nothing.
    expect(isKnownModelId(OTHER_ID, '240')).toBe(false);
  });

  it('has no display name for the other bucket', () => {
    expect(makeDisplayName('volvo')).toBe('Volvo');
    expect(modelDisplayName('volvo', '240')).toBe('240');
    expect(makeDisplayName(OTHER_ID)).toBeNull();
    expect(modelDisplayName('volvo', OTHER_ID)).toBeNull();
  });

  it('offers minModelYear … currentYear + offset', () => {
    expect(offeredModelYearRange(NOW)).toEqual({
      min: contract.minModelYear,
      max: 2026 + contract.maxModelYearOffset,
    });
  });
});

describe('structured display-name resolution', () => {
  it('derives both names from the catalogue', () => {
    expect(resolveCatalogueDisplayNames('volvo', '240')).toEqual({ make: 'Volvo', model: '240' });
  });

  it('falls back to the placeholder for a brand-new other-bucket car', () => {
    expect(resolveCatalogueDisplayNames(OTHER_ID, OTHER_ID)).toEqual({
      make: OTHER_MAKE_DISPLAY,
      model: OTHER_MODEL_DISPLAY,
    });
  });

  it('KEEPS owner-authored text rather than flattening it to the placeholder', () => {
    // The migration promise: a pre-catalogue car edited by an owner who picks
    // "Other" must not lose the only description of it that ever existed. These
    // stored docs carry no ids, so their text is the owner's own.
    expect(
      resolveCatalogueDisplayNames('volvo', OTHER_ID, { make: 'Volvo', model: 'Duett' }),
    ).toEqual({ make: 'Volvo', model: 'Duett' });
    expect(
      resolveCatalogueDisplayNames(OTHER_ID, OTHER_ID, { make: 'Kaipan', model: '57' }),
    ).toEqual({ make: 'Kaipan', model: '57' });
  });

  it('does NOT keep text a previous write DERIVED from a catalogue id', () => {
    // A member who picked volvo/240 by mistake and corrects it to Other would
    // otherwise keep a car labelled "Volvo 240" while being counted in the
    // `other` bucket — that label is a lie, not a rescued description.
    expect(
      resolveCatalogueDisplayNames(OTHER_ID, OTHER_ID, {
        make: 'Volvo',
        model: '240',
        makeId: 'volvo',
        modelId: '240',
      }),
    ).toEqual({ make: OTHER_MAKE_DISPLAY, model: OTHER_MODEL_DISPLAY });
  });

  it('applies the rule per level, not per document', () => {
    // Half-migrated: the make was selected from the catalogue on an earlier edit,
    // the model never was. Only the model text is still the owner's.
    expect(
      resolveCatalogueDisplayNames('volvo', OTHER_ID, {
        make: 'Volvo',
        model: 'Duett',
        makeId: 'volvo',
        modelId: null,
      }),
    ).toEqual({ make: 'Volvo', model: 'Duett' });
  });

  it('ignores blank existing labels', () => {
    expect(resolveCatalogueDisplayNames(OTHER_ID, OTHER_ID, { make: '   ', model: 42 })).toEqual({
      make: OTHER_MAKE_DISPLAY,
      model: OTHER_MODEL_DISPLAY,
    });
  });
});

const structuredAdd = {
  makeId: 'volvo',
  modelId: '240',
  modelYear: 1988,
  powertrain: 'petrol',
};

describe('garage-core structured add path', () => {
  it('accepts a catalogue selection and derives the display text', () => {
    const parsed = parseAddVehicleInput(structuredAdd, NOW);
    if (!parsed.ok) throw new Error(parsed.message);
    expect(parsed.input).toMatchObject({
      makeId: 'volvo',
      modelId: '240',
      make: 'Volvo',
      model: '240',
    });
  });

  it('stamps the ids and the catalogue release onto the document', () => {
    const parsed = parseAddVehicleInput(structuredAdd, NOW);
    if (!parsed.ok) throw new Error(parsed.message);
    const doc = buildVehicleDocument(parsed.input, 'u1', serverTimestamp);
    expect(doc.makeId).toBe('volvo');
    expect(doc.modelId).toBe('240');
    expect(doc.catalogueVersion).toBe(CATALOGUE_RELEASE);
    expect(doc.make).toBe('Volvo');
  });

  it('rejects ids the catalogue does not know', () => {
    expect(parseAddVehicleInput({ ...structuredAdd, makeId: 'ferrarri' }, NOW).ok).toBe(false);
    expect(parseAddVehicleInput({ ...structuredAdd, modelId: 'v70-wagon' }, NOW).ok).toBe(false);
    // A real model under the wrong brand.
    expect(parseAddVehicleInput({ makeId: 'mazda', modelId: 'mgb', modelYear: 1975, powertrain: 'petrol' }, NOW).ok).toBe(
      false,
    );
  });

  it('names the rejected id in the error so a client author can fix it', () => {
    const parsed = parseAddVehicleInput({ ...structuredAdd, makeId: 'ferrarri' }, NOW);
    expect(parsed.ok).toBe(false);
    if (parsed.ok) throw new Error('expected failure');
    expect(parsed.message).toContain('ferrarri');
  });

  it('refuses to mix a structured selection with legacy free text', () => {
    // Always a client bug, and silently preferring one would store a document
    // whose text and ids disagree.
    expect(
      parseAddVehicleInput({ ...structuredAdd, make: 'Volvo', model: '240' }, NOW).ok,
    ).toBe(false);
  });

  it('requires both ids together', () => {
    expect(parseAddVehicleInput({ ...structuredAdd, modelId: undefined }, NOW).ok).toBe(false);
    expect(parseAddVehicleInput({ ...structuredAdd, makeId: undefined }, NOW).ok).toBe(false);
  });

  it('rejects an id that is not slug-shaped', () => {
    expect(parseAddVehicleInput({ ...structuredAdd, makeId: 'Volvo' }, NOW).ok).toBe(false);
    expect(parseAddVehicleInput({ ...structuredAdd, makeId: 'volvo!' }, NOW).ok).toBe(false);
  });

  it('holds a structured write to the OFFERED year window', () => {
    const { min, max } = offeredModelYearRange(NOW);
    expect(parseAddVehicleInput({ ...structuredAdd, modelYear: min }, NOW).ok).toBe(true);
    expect(parseAddVehicleInput({ ...structuredAdd, modelYear: max }, NOW).ok).toBe(true);
    expect(parseAddVehicleInput({ ...structuredAdd, modelYear: min - 1 }, NOW).ok).toBe(false);
    // Seb's rule: never future-dated beyond next model year, even though the
    // legacy path still tolerates currentYear + 2 for shipped clients.
    expect(parseAddVehicleInput({ ...structuredAdd, modelYear: max + 1 }, NOW).ok).toBe(false);
    expect(
      parseAddVehicleInput(
        { make: 'Volvo', model: '240', modelYear: max + 1, powertrain: 'petrol' },
        NOW,
      ).ok,
    ).toBe(true);
  });

  it('still accepts the legacy free-text path with no ids', () => {
    const parsed = parseAddVehicleInput(
      { make: 'Kaipan', model: '57', modelYear: 1998, powertrain: 'petrol' },
      NOW,
    );
    if (!parsed.ok) throw new Error(parsed.message);
    expect(parsed.input.makeId).toBeNull();
    expect(parsed.input.modelId).toBeNull();
    const doc = buildVehicleDocument(parsed.input, 'u1', serverTimestamp);
    // Explicitly null, not omitted, so `makeId == null` finds exactly the
    // vehicles that are outside the aggregate.
    expect(doc.makeId).toBeNull();
    expect(doc.catalogueVersion).toBeNull();
    expect(doc.make).toBe('Kaipan');
  });

  it('requires an identity on add', () => {
    expect(parseAddVehicleInput({ modelYear: 2020, powertrain: 'petrol' }, NOW).ok).toBe(false);
  });
});

describe('garage-core structured update path', () => {
  it('re-derives the display text and re-stamps the release', () => {
    const parsed = parseUpdateVehicleInput({ vehicleId: 'v1', makeId: 'saab', modelId: '900' }, NOW);
    if (!parsed.ok) throw new Error(parsed.message);
    const { update, changedFields } = buildVehicleUpdate(parsed.input, serverTimestamp, {
      make: 'saab',
      model: 'ninehundred',
    });
    expect(update.make).toBe('Saab');
    expect(update.model).toBe('900');
    expect(update.makeId).toBe('saab');
    expect(update.catalogueVersion).toBe(CATALOGUE_RELEASE);
    expect(changedFields).toContain('makeId');
  });

  it('keeps the legacy label when the owner selects Other on an existing car', () => {
    const parsed = parseUpdateVehicleInput(
      { vehicleId: 'v1', makeId: 'volvo', modelId: OTHER_ID },
      NOW,
    );
    if (!parsed.ok) throw new Error(parsed.message);
    const { update } = buildVehicleUpdate(parsed.input, serverTimestamp, {
      make: 'volvo',
      model: 'Duett',
    });
    expect(update.make).toBe('Volvo');
    // Not flattened to OTHER_MODEL_DISPLAY — the owner's original text survives.
    expect(update.model).toBe('Duett');
    expect(update.modelId).toBe(OTHER_ID);
  });

  it('leaves a photo-only edit alone', () => {
    const parsed = parseUpdateVehicleInput({ vehicleId: 'v1', imagePath: null }, NOW);
    if (!parsed.ok) throw new Error(parsed.message);
    const { update, changedFields } = buildVehicleUpdate(parsed.input, serverTimestamp, {
      make: 'Volvo',
      model: '240',
    });
    // No make/model/id churn at all: editing a photo must never touch identity.
    expect(changedFields).toEqual(['imagePath']);
    expect(update.makeId).toBeUndefined();
    expect(update.make).toBeUndefined();
  });

  it('rejects an unknown id on update too', () => {
    expect(
      parseUpdateVehicleInput({ vehicleId: 'v1', makeId: 'volvo', modelId: 'xc99' }, NOW).ok,
    ).toBe(false);
  });
});
