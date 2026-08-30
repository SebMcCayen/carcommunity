#!/usr/bin/env node
/**
 * Generates the Android (Kotlin), Cloud Functions (TypeScript) and iOS
 * (Swift) mirrors of the canonical vehicle catalogue
 * (contracts/vehicles/vehicle-catalogue.json).
 *
 * WHY MIRRORS AND NOT A DIRECT IMPORT
 * -----------------------------------
 * The garage's make/model selectors and the backend's validation of the ids
 * they produce must agree exactly, or the aggregation the whole feature exists
 * for ("how many Volvos does the community own?") is worthless. Neither
 * consumer can read the contract file at run time, though:
 *
 *  - `firebase deploy` uploads ONLY the `functions/` directory, and
 *    functions/tsconfig.json sets `rootDir: src`, so nothing above
 *    functions/src exists in production.
 *  - The Android APK has no repo checkout; a raw asset would mean shipping a
 *    JSON parser and a file read on a screen open.
 *  - The iOS app bundle likewise has no checkout, and its selectors parse the
 *    same packed lines lazily (apps/ios/KCC/Garage/VehicleCatalogue.swift).
 *
 * So the contract stays the single source of truth and this script emits three
 * mechanical mirrors of it, exactly as `generate-tokens.mjs` /
 * `generate-strings.mjs` already do for design tokens and strings. Drift is
 * caught in CI: the contracts workflow re-runs this script and diffs all three
 * mirrors, and functions/src/__tests__/vehicle-catalogue.test.ts asserts the
 * TypeScript mirror is byte-identical to a fresh render of the contract.
 *
 * ENCODING
 * --------
 * All mirrors hold ONE line per manufacturer:
 *
 *   makeId|Make name|C|modelId=Model name;modelId=Model name
 *
 * where C is 1 for a `common` (Swedish-roads) manufacturer and 0 otherwise.
 * Two reasons for a packed string rather than emitted object literals:
 *
 *  1. Size and cost. ~1300 models as Kotlin `Model("v70", "V70")` calls would
 *     be tens of thousands of bytecode instructions in a single initializer —
 *     a real risk of the 64 KB method limit and a guaranteed cost at class
 *     load. String literals live in the constant pool, and both wrappers parse
 *     them LAZILY on first use (the add/edit-vehicle screen), so an app start
 *     that never opens the garage pays nothing.
 *  2. One encoding, one place to get it wrong — the Kotlin, TypeScript and Swift
 *     parsers are checked against the same contract by their own unit tests.
 *
 * `|`, `;` and `=` are therefore reserved; the script FAILS if any id or
 * display name contains one, rather than emitting a mirror that silently
 * mis-parses.
 *
 * Usage: node scripts/generate-vehicle-catalogue.mjs   (from the repo root)
 * Run this after editing contracts/vehicles/vehicle-catalogue.json.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const CONTRACT = 'contracts/vehicles/vehicle-catalogue.json';
const KOTLIN_OUT =
  'apps/android/app/src/main/java/com/kungsbackacarcommunity/app/garage/VehicleCatalogueData.kt';
const TS_OUT = 'functions/src/garage/vehicle-catalogue.generated.ts';
const SWIFT_OUT = 'apps/ios/KCC/Garage/VehicleCatalogueData.swift';

const RESERVED = ['|', ';', '='];

/** Reserved-character guard — a mis-encoded mirror must fail loudly, not silently. */
function assertEncodable(what, value) {
  for (const ch of RESERVED) {
    if (value.includes(ch)) {
      throw new Error(
        `${what} ${JSON.stringify(value)} contains the reserved encoding character ` +
          `"${ch}". Rename it in ${CONTRACT}, or change the encoding in this script ` +
          'AND in every parser (VehicleCatalogue.kt / vehicle-catalogue.ts / VehicleCatalogue.swift).',
      );
    }
  }
  if (/[\r\n]/.test(value)) {
    throw new Error(`${what} ${JSON.stringify(value)} contains a line break.`);
  }
}

export function readCatalogue(root = repoRoot) {
  return JSON.parse(readFileSync(resolve(root, CONTRACT), 'utf8'));
}

/** The packed lines both mirrors embed (see the ENCODING note above). */
export function encodeCatalogue(catalogue) {
  const seenMakeIds = new Set();
  return catalogue.manufacturers.map((make) => {
    assertEncodable('manufacturer id', make.id);
    assertEncodable('manufacturer name', make.name);
    if (make.id === catalogue.otherId) {
      throw new Error(
        `manufacturer id "${make.id}" collides with otherId — the "Other / not listed" ` +
          'bucket is synthesised by the clients and must not be a real catalogue entry.',
      );
    }
    if (seenMakeIds.has(make.id)) {
      throw new Error(`duplicate manufacturer id "${make.id}"`);
    }
    seenMakeIds.add(make.id);

    const seenModelIds = new Set();
    const models = make.models.map((model) => {
      assertEncodable('model id', model.id);
      assertEncodable('model name', model.name);
      if (model.id === catalogue.otherId) {
        throw new Error(
          `model id "${model.id}" under "${make.id}" collides with otherId — the ` +
            '"Other / not listed" bucket is synthesised by the clients.',
        );
      }
      if (seenModelIds.has(model.id)) {
        throw new Error(`duplicate model id "${model.id}" under manufacturer "${make.id}"`);
      }
      seenModelIds.add(model.id);
      return `${model.id}=${model.name}`;
    });

    return `${make.id}|${make.name}|${make.common ? 1 : 0}|${models.join(';')}`;
  });
}

const HEADER_LINES = [
  '// GENERATED FILE — do not edit by hand.',
  `// Source: ${CONTRACT}`,
  '// Regenerate: node scripts/generate-vehicle-catalogue.mjs',
];

/** Kotlin/TypeScript string literal: only quotes, backslashes and `$` need care. */
function kotlinLiteral(value) {
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('$', '\\$')}"`;
}

function tsLiteral(value) {
  return `'${value.replaceAll('\\', '\\\\').replaceAll("'", "\\'")}'`;
}

/** Swift string literal: backslashes and quotes need escaping (`\(` would interpolate). */
function swiftLiteral(value) {
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
}

export function renderKotlin(catalogue) {
  const encoded = encodeCatalogue(catalogue);
  return [
    ...HEADER_LINES,
    'package com.kungsbackacarcommunity.app.garage',
    '',
    '/**',
     ' * Packed vehicle catalogue data. Parsed LAZILY by [VehicleCatalogue] — never at',
    ' * class load — so an app start that never opens the add/edit-vehicle form pays',
    ' * nothing for it. See scripts/generate-vehicle-catalogue.mjs for the encoding.',
    ' *',
    ' * The "Other / not listed" bucket is NOT in this data: it is synthesised by',
    ' * [VehicleCatalogue] at both levels so an unlisted brand or model is still a',
    ' * SELECTION rather than free text.',
    ' */',
    'internal object VehicleCatalogueData {',
    `    const val VERSION = ${kotlinLiteral(catalogue.version)}`,
    `    const val OTHER_ID = ${kotlinLiteral(catalogue.otherId)}`,
    `    const val MIN_MODEL_YEAR = ${catalogue.minModelYear}`,
    `    const val MAX_MODEL_YEAR_OFFSET = ${catalogue.maxModelYearOffset}`,
    '',
    '    /** One line per manufacturer: `makeId|Make name|common(0/1)|modelId=Model name;…`. */',
    '    val ENCODED: List<String> =',
    '        listOf(',
    ...encoded.map((line) => `            ${kotlinLiteral(line)},`),
    '        )',
    '}',
    '',
  ].join('\n');
}

export function renderTypeScript(catalogue) {
  const encoded = encodeCatalogue(catalogue);
  return [
    ...HEADER_LINES,
    '',
    '/**',
    ' * Packed vehicle catalogue data. Parsed LAZILY by ./vehicle-catalogue.ts on first',
    ' * lookup, so a cold start of an unrelated callable never pays for it. See',
    ' * scripts/generate-vehicle-catalogue.mjs for the encoding.',
    ' *',
    ' * The "Other / not listed" bucket is NOT in this data: it is synthesised by',
    ' * ./vehicle-catalogue.ts at both levels.',
    ' */',
    '',
    `export const CATALOGUE_VERSION = ${tsLiteral(catalogue.version)};`,
    `export const CATALOGUE_OTHER_ID = ${tsLiteral(catalogue.otherId)};`,
    `export const CATALOGUE_MIN_MODEL_YEAR = ${catalogue.minModelYear};`,
    `export const CATALOGUE_MAX_MODEL_YEAR_OFFSET = ${catalogue.maxModelYearOffset};`,
    '',
    '/** One line per manufacturer: `makeId|Make name|common(0/1)|modelId=Model name;…`. */',
    'export const CATALOGUE_ENCODED: readonly string[] = [',
    ...encoded.map((line) => `  ${tsLiteral(line)},`),
    '];',
    '',
  ].join('\n');
}

export function renderSwift(catalogue) {
  const encoded = encodeCatalogue(catalogue);
  return [
    ...HEADER_LINES,
    '',
    '/// Packed vehicle catalogue data. Parsed LAZILY by ``VehicleCatalogue`` — never',
    '/// at app start — so a launch that never opens the add/edit-vehicle form pays',
    '/// nothing for it. See scripts/generate-vehicle-catalogue.mjs for the encoding.',
    '///',
    '/// The "Other / not listed" bucket is NOT in this data: it is synthesised by',
    '/// ``VehicleCatalogue`` at both levels so an unlisted brand or model is still a',
    '/// SELECTION rather than free text.',
    'enum VehicleCatalogueData {',
    `    static let version = ${swiftLiteral(catalogue.version)}`,
    `    static let otherId = ${swiftLiteral(catalogue.otherId)}`,
    `    static let minModelYear = ${catalogue.minModelYear}`,
    `    static let maxModelYearOffset = ${catalogue.maxModelYearOffset}`,
    '',
    '    /// One line per manufacturer: `makeId|Make name|common(0/1)|modelId=Model name;…`.',
    '    static let encoded: [String] = [',
    ...encoded.map((line) => `        ${swiftLiteral(line)},`),
    '    ]',
    '}',
    '',
  ].join('\n');
}

function main() {
  const catalogue = readCatalogue();
  const outputs = [
    [KOTLIN_OUT, renderKotlin(catalogue)],
    [TS_OUT, renderTypeScript(catalogue)],
    [SWIFT_OUT, renderSwift(catalogue)],
  ];
  for (const [relative, contents] of outputs) {
    writeFileSync(resolve(repoRoot, relative), contents, 'utf8');
    console.log(`Wrote ${relative} (${contents.length} bytes)`);
  }
  const models = catalogue.manufacturers.reduce((sum, m) => sum + m.models.length, 0);
  console.log(
    `Catalogue v${catalogue.version}: ${catalogue.manufacturers.length} manufacturers, ${models} models.`,
  );
}

// Importable for tests (the drift guard renders without writing).
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
