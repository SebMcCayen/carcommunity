#!/usr/bin/env node
/**
 * Generates Android strings.xml resources from the canonical localization
 * contracts (contracts/localization/sv.json and en.json).
 *
 * - Keys are flattened dot-paths converted to snake resource names
 *   (liveLocation.start -> livelocation_start is NOT used; dots become
 *   underscores preserving camelCase: liveLocation.start -> liveLocation_start).
 * - Values are escaped for Android resource XML (&, <, >, ', ", newline).
 * - Swedish is the default locale (res/values/), English is res/values-en/.
 * - app_name is injected as a fixed extra resource.
 *
 * Usage: node apps/android/scripts/generate-strings.mjs   (from repo root)
 * CI verifies the generated files are up to date; run this after editing
 * contracts/localization/*.json.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');

const APP_NAME = 'Kungsbacka Car Community';

const targets = [
  { source: 'contracts/localization/sv.json', out: 'apps/android/app/src/main/res/values/strings.xml' },
  { source: 'contracts/localization/en.json', out: 'apps/android/app/src/main/res/values-en/strings.xml' },
];

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

function toResourceName(dotPath) {
  const name = dotPath.replaceAll('.', '_');
  if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(name)) {
    throw new Error(`Invalid Android resource name derived from ${dotPath}: ${name}`);
  }
  return name;
}

function escapeAndroid(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll("'", "\\'")
    .replaceAll('"', '\\"')
    .replaceAll('\n', '\\n');
}

for (const { source, out } of targets) {
  const dict = JSON.parse(readFileSync(resolve(repoRoot, source), 'utf8'));
  const entries = flatten(dict);

  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- GENERATED FILE — do not edit by hand.',
    `     Source: ${source}`,
    '     Regenerate: node apps/android/scripts/generate-strings.mjs -->',
    '<resources>',
    `    <string name="app_name">${escapeAndroid(APP_NAME)}</string>`,
  ];

  for (const [dotPath, value] of entries) {
    const formatted = value.includes('%') ? ' formatted="false"' : '';
    lines.push(`    <string name="${toResourceName(dotPath)}"${formatted}>${escapeAndroid(value)}</string>`);
  }

  lines.push('</resources>', '');
  writeFileSync(resolve(repoRoot, out), lines.join('\n'));
  console.log(`${out}: ${entries.size + 1} strings`);
}
