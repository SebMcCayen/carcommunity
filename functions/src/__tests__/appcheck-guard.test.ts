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

/**
 * The ONE deliberate exception: diagnostics.submitReport is the unauthenticated
 * PRE-AUTH telemetry path and must keep working when App Check is unavailable on
 * the device (the exact case it exists to surface — see submitReport.ts header).
 * It is intentionally NON-enforcing (`enforceAppCheck: false`) with documented
 * abuse compensation. Exempting it here keeps the relaxation explicit: it is
 * asserted separately below, so it can never silently drift back to a value that
 * neither enforces NOR is the vetted opt-out.
 */
const NON_ENFORCING_EXEMPTIONS = new Set(['diagnostics/submitReport.ts']);
/**
 * Detect the non-enforcing opt-out `enforceAppCheck: false`. A literal
 * substring match is fragile — trivial formatting variations that TypeScript
 * treats identically (`enforceAppCheck:false`, `enforceAppCheck : false`,
 * `enforceAppCheck:  false`) would evade it, letting an un-attested callable
 * slip past the guard or the exemption assertion. A whitespace-tolerant regex
 * keeps the guard robust regardless of spacing around the colon or the value.
 */
const NON_ENFORCING_REGEX = /enforceAppCheck\s*:\s*false\b/;

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      return name === '__tests__' ? [] : walk(full);
    }
    return full.endsWith('.ts') ? [full] : [];
  });
}

/** Repo-relative, forward-slash path for exemption matching. */
function relPath(file: string): string {
  return file.slice(SRC.length + 1).split('\\').join('/');
}

describe('App Check enforcement (Phase 15d)', () => {
  it('every onCall invocation uses enforcing options (per-call, not per-file)', () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      // The vetted pre-auth exemption is checked by its own assertion below.
      if (NON_ENFORCING_EXEMPTIONS.has(relPath(file))) continue;
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
        const usesSharedOpts =
          (firstArg.startsWith('CALLABLE_OPTS') || firstArg.includes('...CALLABLE_OPTS')) &&
          fileHasEnforcingOpts;
        const usesInlinePattern =
          window.includes('enforceAppCheck') && !NON_ENFORCING_REGEX.test(window);
        if (!usesSharedOpts && !usesInlinePattern) {
          offenders.push(`${file} @ ${firstArg.slice(0, 40)}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('diagnostics.submitReport is the ONLY vetted non-enforcing callable (pre-auth telemetry)', () => {
    // Pin the deliberate opt-out so it stays intentional: the file must exist,
    // must explicitly set `enforceAppCheck: false`, and must NOT carry the
    // enforcing pattern. If someone re-enables enforcement here (breaking the
    // pre-auth path again) or another file starts opting out, this fails.
    const submit = readFileSync(join(SRC, 'diagnostics/submitReport.ts'), 'utf8');
    expect(NON_ENFORCING_REGEX.test(submit)).toBe(true);
    expect(submit).not.toContain(ENFORCE_PATTERN);

    // No file OTHER than the exemption may use the non-enforcing pattern.
    const stragglers = walk(SRC)
      .filter((file) => !NON_ENFORCING_EXEMPTIONS.has(relPath(file)))
      .filter((file) => NON_ENFORCING_REGEX.test(readFileSync(file, 'utf8')))
      .map(relPath);
    expect(stragglers).toEqual([]);
  });

  it('non-enforcing regex is whitespace-tolerant (guard cannot be evaded by formatting)', () => {
    // All of these are what TypeScript treats as the same opt-out; the guard
    // must catch every spacing variant, otherwise a reformat could sneak an
    // un-attested callable past the checks above.
    expect(NON_ENFORCING_REGEX.test('enforceAppCheck: false')).toBe(true);
    expect(NON_ENFORCING_REGEX.test('enforceAppCheck:false')).toBe(true);
    expect(NON_ENFORCING_REGEX.test('enforceAppCheck : false')).toBe(true);
    expect(NON_ENFORCING_REGEX.test('enforceAppCheck:  false')).toBe(true);
    expect(NON_ENFORCING_REGEX.test('enforceAppCheck:\tfalse')).toBe(true);
    // Enforcing values must NOT match the non-enforcing pattern.
    expect(NON_ENFORCING_REGEX.test('enforceAppCheck: true')).toBe(false);
    expect(NON_ENFORCING_REGEX.test(ENFORCE_PATTERN)).toBe(false);
  });
});
