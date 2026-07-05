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
  it('every onCall invocation uses enforcing options (per-call, not per-file)', () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      const source = readFileSync(file, 'utf8');
      const fileHasEnforcingOpts =
        source.includes('const CALLABLE_OPTS') && source.includes(ENFORCE_PATTERN);
      // Examine EACH onCall call site: it must either pass the file's
      // enforcing CALLABLE_OPTS object, or inline options containing the
      // enforcement pattern within the argument window. A second callable
      // added to an already-protected file cannot slip through.
      const siteRegex = /onCall\(\s*([^,)]*)/g;
      for (const match of source.matchAll(siteRegex)) {
        const firstArg = (match[1] ?? '').trim();
        const window = source.slice(match.index ?? 0, (match.index ?? 0) + 600);
        const usesSharedOpts = firstArg.startsWith('CALLABLE_OPTS') && fileHasEnforcingOpts;
        const usesInlinePattern = window.includes('enforceAppCheck');
        if (!usesSharedOpts && !usesInlinePattern) {
          offenders.push(`${file} @ ${firstArg.slice(0, 40)}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
