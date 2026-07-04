/**
 * Phase 15d guard: every callable in functions/src must carry the App
 * Check enforcement pattern (enforced in production, disabled only under
 * the emulator). A new callable that forgets enforceAppCheck fails this
 * test rather than shipping unprotected.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = join(__dirname, '..');
const ENFORCE_PATTERN = "enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true'";

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      return name === '__tests__' ? [] : walk(full);
    }
    return full.endsWith('.ts') ? [full] : [];
  });
}

describe('App Check enforcement (Phase 15d)', () => {
  it('every onCall callable sets the production enforcement pattern', () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      const source = readFileSync(file, 'utf8');
      const onCallCount = (source.match(/onCall\(/g) ?? []).length;
      if (onCallCount === 0) continue;
      if (!source.includes(ENFORCE_PATTERN)) {
        offenders.push(file);
      }
    }
    expect(offenders).toEqual([]);
  });
});
