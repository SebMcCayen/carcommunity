#!/usr/bin/env node
/**
 * Generates the iOS String Catalog (Localizable.xcstrings) from the canonical
 * localization contracts (contracts/localization/sv.json and en.json) — the
 * sibling of apps/android/scripts/generate-strings.mjs.
 *
 * - Keys stay flattened dot-paths exactly as in the contracts
 *   (liveLocation.start), so iOS and Android share semantic key names
 *   (Android only differs by its resource-name restriction: dots become
 *   underscores there).
 * - Android/Java positional format specifiers are converted to their iOS
 *   printf equivalents: %n$s -> %n$@, %n$d -> %n$lld. The contracts only use
 *   positional specifiers (CI-checked below), so SwiftUI's automatic
 *   argument substitution stays deterministic in both locales.
 * - Swedish is the source language; English is a full translation. Key-set
 *   parity between the two contract files is enforced by the contracts CI
 *   job, so this script simply requires both to be present.
 * - Output is sorted by key and stably formatted so the file diffs cleanly
 *   and CI can `git diff --exit-code` it.
 *
 * Usage: node apps/ios/scripts/generate-strings.mjs   (from repo root)
 * CI verifies the generated file is up to date; run this after editing
 * contracts/localization/*.json.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const OUT = 'apps/ios/KCC/Resources/Localizable.xcstrings';

function flatten(obj, prefix = '', acc = new Map()) {
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') {
      acc.set(path, value);
    } else if (value && typeof value === 'object' && !Array.isArray(value)) {
      flatten(value, path, acc);
    } else {
      throw new Error(`Unsupported leaf at ${path}: ${JSON.stringify(value)}`);
    }
  }
  return acc;
}

/**
 * Converts the contracts' Java/Android positional specifiers to iOS printf
 * ones. Non-positional specifiers (%s, %d) are rejected rather than guessed
 * at: positional forms are what the contracts use, and implicit ordering
 * would silently diverge between locales with different word order.
 */
function toIosFormat(value, key) {
  const converted = value
    .replaceAll(/%(\d+)\$s/g, '%$1$$@')
    .replaceAll(/%(\d+)\$d/g, '%$1$$lld');
  const leftover = converted.replace(/%\d+\$(@|lld)/g, '').match(/%[a-zA-Z]/);
  if (leftover) {
    throw new Error(
      `${key}: non-positional or unsupported format specifier ${leftover[0]} — use positional %n$s / %n$d in the contracts`,
    );
  }
  return converted;
}

const sv = flatten(JSON.parse(readFileSync(resolve(repoRoot, 'contracts/localization/sv.json'), 'utf8')));
const en = flatten(JSON.parse(readFileSync(resolve(repoRoot, 'contracts/localization/en.json'), 'utf8')));

const strings = {};
for (const key of [...sv.keys()].sort()) {
  if (!en.has(key)) {
    // Key-set parity is the contracts CI job's to enforce; fail loudly rather
    // than emit a catalog with untranslated English.
    throw new Error(`${key} exists in sv.json but not en.json`);
  }
  strings[key] = {
    extractionState: 'manual',
    localizations: {
      sv: { stringUnit: { state: 'translated', value: toIosFormat(sv.get(key), key) } },
      en: { stringUnit: { state: 'translated', value: toIosFormat(en.get(key), key) } },
    },
  };
}

const catalog = {
  sourceLanguage: 'sv',
  strings,
  version: '1.0',
};

writeFileSync(resolve(repoRoot, OUT), JSON.stringify(catalog, null, 2) + '\n');
console.log(`${OUT}: ${Object.keys(strings).length} strings (sv + en)`);
