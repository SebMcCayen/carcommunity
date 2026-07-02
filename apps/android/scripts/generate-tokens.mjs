#!/usr/bin/env node
/**
 * Generates Kotlin design-token constants from the canonical contract
 * (contracts/design-tokens/tokens.json) into Tokens.kt.
 *
 * Generated objects:
 * - KccPalette      — brand/status colors (Compose Color)
 * - KccSpacing      — spacing scale (Dp)
 * - KccRadius       — corner radius scale (Dp)
 * - KccTypeScale    — font sizes (TextUnit/sp) and weights (FontWeight)
 * - KccLightColors / KccDarkColors — semantic theme colors (Compose Color)
 *
 * Usage: node apps/android/scripts/generate-tokens.mjs   (from repo root)
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
const OUT = 'apps/android/app/src/main/java/com/kungsbackacarcommunity/app/design/Tokens.kt';

function hexToComposeColor(hex) {
  if (!/^#[0-9A-F]{6}$/.test(hex)) throw new Error(`Unexpected color format: ${hex}`);
  return `Color(0xFF${hex.slice(1)})`;
}

/** spacing keys are numeric ("0", "1", ...); prefix for valid Kotlin identifiers. */
function spacingName(key) {
  return `s${key}`;
}

const weightMap = { 400: 'Normal', 500: 'Medium', 600: 'SemiBold', 700: 'Bold' };

const lines = [];
lines.push(
  '// GENERATED FILE — do not edit by hand.',
  '// Source: contracts/design-tokens/tokens.json',
  '// Regenerate: node apps/android/scripts/generate-tokens.mjs',
  'package com.kungsbackacarcommunity.app.design',
  '',
  'import androidx.compose.ui.graphics.Color',
  'import androidx.compose.ui.text.font.FontWeight',
  'import androidx.compose.ui.unit.dp',
  'import androidx.compose.ui.unit.sp',
  '',
  '/** KCC Crown UI brand palette. */',
  'object KccPalette {',
);
for (const [name, hex] of Object.entries(tokens.palette)) {
  lines.push(`    val ${name} = ${hexToComposeColor(hex)}`);
}
lines.push('}', '', '/** Spacing scale (4pt base). */', 'object KccSpacing {');
for (const [key, value] of Object.entries(tokens.spacing)) {
  lines.push(`    val ${spacingName(key)} = ${value}.dp`);
}
lines.push('}', '', '/** Corner radius scale. */', 'object KccRadius {');
for (const [key, value] of Object.entries(tokens.radius)) {
  lines.push(`    val ${key} = ${value}.dp`);
}
lines.push(
  '}',
  '',
  '/** Type scale: sizes in sp (user-scalable) and font weights. */',
  'object KccTypeScale {',
);
for (const [key, value] of Object.entries(tokens.typography.size)) {
  lines.push(`    val ${key} = ${value}.sp`);
}
for (const [key, value] of Object.entries(tokens.typography.weight)) {
  const mapped = weightMap[value];
  if (!mapped) throw new Error(`Unmapped font weight: ${value}`);
  lines.push(`    val ${key} = FontWeight.${mapped}`);
}
lines.push('}');

for (const theme of ['light', 'dark']) {
  const objName = theme === 'light' ? 'KccLightColors' : 'KccDarkColors';
  lines.push('', `/** Semantic ${theme}-theme colors. */`, `object ${objName} {`);
  for (const [name, hex] of Object.entries(tokens.themes[theme].colors)) {
    lines.push(`    val ${name} = ${hexToComposeColor(hex)}`);
  }
  lines.push('}');
}
lines.push('');

writeFileSync(resolve(repoRoot, OUT), lines.join('\n'));
console.log(`${OUT}: palette ${Object.keys(tokens.palette).length}, themes light+dark`);
