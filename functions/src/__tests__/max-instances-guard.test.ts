/**
 * Cost guardrail: every deployed function must declare a `maxInstances`.
 *
 * Cloud Functions v2 defaults `maxInstances` to 1000, so a function that omits
 * it has no upper bound on spend. This guard makes that omission a failing unit
 * test instead of a line on an invoice: a new callable, trigger or schedule has
 * to pick a tier from `shared/instanceLimits.ts` (or set a documented literal)
 * before it can ship.
 *
 * The check is a source scan, deliberately — importing the compiled backend
 * would need Admin SDK credentials, which the unit suite does not have.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = join(__dirname, '..');

/**
 * Files whose options object is deliberately left alone for now because a
 * concurrent branch is editing the same lines (PR #629 `auth/onUserCreate.ts`,
 * PR #631 `auth/completeOnboarding.ts`). Both are once-per-account paths, so
 * the uncapped default is the lowest-risk gap in the codebase — but it IS a
 * gap, and listing it here keeps it visible rather than forgotten. Remove an
 * entry (and add the cap) once the owning PR has merged.
 */
const UNCAPPED_EXEMPTIONS = new Set(['auth/completeOnboarding.ts', 'auth/onUserCreate.ts']);

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

/**
 * Names of `const …OPTS = { … }` objects in this file that already carry a cap.
 * A definition site passing one of these (directly or spread) is covered.
 */
function cappedOptsConstants(source: string): string[] {
  return [...source.matchAll(/const (\w*OPTS) = \{([\s\S]*?)\n\};/g)]
    .filter((match) => (match[2] ?? '').includes('maxInstances'))
    .map((match) => match[1] ?? '')
    .filter((name) => name.length > 0);
}

describe('instance ceilings (cost guardrail)', () => {
  it('every function definition declares maxInstances', () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      if (UNCAPPED_EXEMPTIONS.has(relPath(file))) continue;
      const source = readFileSync(file, 'utf8');
      const capped = cappedOptsConstants(source);
      // v2 definitions (`onCall`, `onSchedule`, `onDocument*`, `onRequest`) and
      // the 1st-gen `runWith` options object are all covered.
      const siteRegex = /(?:= (on[A-Z]\w*)|\.(runWith))\(\s*([^,)]*)/g;
      for (const match of source.matchAll(siteRegex)) {
        const kind = match[1] ?? match[2];
        const firstArg = (match[3] ?? '').trim();
        const window = source.slice(match.index ?? 0, (match.index ?? 0) + 700);
        const viaConstant = capped.some((name) => firstArg.includes(name));
        if (!viaConstant && !window.includes('maxInstances')) {
          offenders.push(`${relPath(file)} @ ${kind}(${firstArg.slice(0, 30)}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('the uncapped exemptions still exist and are still uncapped', () => {
    // Pin the exemption list so it cannot rot: if one of these files gains a
    // cap (or disappears), the entry must be removed from the set rather than
    // silently excusing a file that no longer needs excusing.
    for (const rel of UNCAPPED_EXEMPTIONS) {
      const source = readFileSync(join(SRC, rel), 'utf8');
      expect(source, `${rel} is capped now — drop it from UNCAPPED_EXEMPTIONS`).not.toContain(
        'maxInstances',
      );
    }
  });
});
