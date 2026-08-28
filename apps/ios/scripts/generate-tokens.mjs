#!/usr/bin/env node
/**
 * Generates Swift design-token constants from the canonical contract
 * (contracts/design-tokens/tokens.json) into Tokens.swift — the sibling of
 * apps/android/scripts/generate-tokens.mjs.
 *
 * Generated enums (namespaces):
 * - KccPalette      — brand/status colors (SwiftUI Color)
 * - KccSpacing      — spacing scale (CGFloat, points)
 * - KccRadius       — corner radius scale (CGFloat, points)
 * - KccTypeScale    — font sizes (CGFloat, points) and weights (Font.Weight)
 * - KccLightColors / KccDarkColors — semantic theme colors (SwiftUI Color)
 *
 * Usage: node apps/ios/scripts/generate-tokens.mjs   (from repo root)
 * CI verifies the generated file is up to date; run this after editing
 * contracts/design-tokens/tokens.json.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const tokens = JSON.parse(
  readFileSync(resolve(repoRoot, 'contracts/design-tokens/tokens.json'), 'utf8'),
);
const OUT = 'apps/ios/KCC/Design/Tokens.swift';

function hexToSwiftColor(hex) {
  if (!/^#[0-9A-F]{6}$/.test(hex)) throw new Error(`Unexpected color format: ${hex}`);
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `Color(red: ${r} / 255, green: ${g} / 255, blue: ${b} / 255)`;
}

/** spacing keys are numeric ("0", "1", ...); prefix for valid Swift identifiers. */
function spacingName(key) {
  return `s${key}`;
}

const weightMap = { 400: '.regular', 500: '.medium', 600: '.semibold', 700: '.bold' };

const lines = [];
lines.push(
  '// GENERATED FILE — do not edit by hand.',
  '// Source: contracts/design-tokens/tokens.json',
  '// Regenerate: node apps/ios/scripts/generate-tokens.mjs',
  '',
  'import SwiftUI',
  '',
  '/// KCC Crown UI brand palette.',
  'enum KccPalette {',
);
for (const [name, hex] of Object.entries(tokens.palette)) {
  lines.push(`    static let ${name} = ${hexToSwiftColor(hex)}`);
}
lines.push('}', '', '/// Spacing scale (4pt base).', 'enum KccSpacing {');
for (const [key, value] of Object.entries(tokens.spacing)) {
  lines.push(`    static let ${spacingName(key)}: CGFloat = ${value}`);
}
lines.push('}', '', '/// Corner radius scale.', 'enum KccRadius {');
for (const [key, value] of Object.entries(tokens.radius)) {
  lines.push(`    static let ${key}: CGFloat = ${value}`);
}
lines.push(
  '}',
  '',
  '/// Type scale: sizes in points (user-scalable via Dynamic Type when used',
  '/// with `Font.system(size:relativeTo:)`) and font weights.',
  'enum KccTypeScale {',
);
for (const [key, value] of Object.entries(tokens.typography.size)) {
  lines.push(`    static let ${key}: CGFloat = ${value}`);
}
for (const [key, value] of Object.entries(tokens.typography.weight)) {
  const mapped = weightMap[value];
  if (!mapped) throw new Error(`Unmapped font weight: ${value}`);
  lines.push(`    static let ${key} = Font.Weight${mapped}`);
}
lines.push('}');

for (const theme of ['light', 'dark']) {
  const enumName = theme === 'light' ? 'KccLightColors' : 'KccDarkColors';
  lines.push('', `/// Semantic ${theme}-theme colors.`, `enum ${enumName} {`);
  for (const [name, hex] of Object.entries(tokens.themes[theme].colors)) {
    lines.push(`    static let ${name} = ${hexToSwiftColor(hex)}`);
  }
  lines.push('}');
}
lines.push('');

writeFileSync(resolve(repoRoot, OUT), lines.join('\n'));
console.log(`${OUT}: palette ${Object.keys(tokens.palette).length}, themes light+dark`);
