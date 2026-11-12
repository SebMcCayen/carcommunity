/**
 * Finance inventory — the CI half of "a new function surfaces, never hides".
 *
 * Cross-checks CALLABLE_COST_CLASS against the canonical callable registry
 * (contracts/functions/functions.json). If a callable is added to the contract
 * without being classified here, this test FAILS — forcing whoever adds it to
 * decide how it costs (a real driver, or an explicit 'uncosted' that the board
 * then flags). That is what stops a newly-added function from silently costing
 * zero on the finance board.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CALLABLE_COST_CLASS,
  SCHEDULED_JOBS,
  REPOSITORY_SCHEDULED_JOBS,
  DRIVER_SCHEDULED_JOBS,
} from './inventory';

interface RegistryFn {
  name: string;
  status: string;
}

function registryCallableNames(): string[] {
  const registry = JSON.parse(
    readFileSync(join(__dirname, '../../../contracts/functions/functions.json'), 'utf8'),
  ) as { functions: RegistryFn[] };
  return registry.functions.map((f) => f.name);
}

describe('CALLABLE_COST_CLASS mirrors the callable registry', () => {
  it('every registered callable is classified (a new function cannot hide its cost)', () => {
    const registered = registryCallableNames();
    const unclassified = registered.filter((name) => !(name in CALLABLE_COST_CLASS));
    expect(unclassified).toEqual([]);
  });

  it('does not classify a callable the registry no longer has (no stale entries)', () => {
    const registered = new Set(registryCallableNames());
    const stale = Object.keys(CALLABLE_COST_CLASS).filter((name) => !registered.has(name));
    expect(stale).toEqual([]);
  });

  it('every classification is a known cost class', () => {
    const valid = new Set(['variable-member', 'admin-rare', 'free', 'uncosted']);
    for (const cls of Object.values(CALLABLE_COST_CLASS)) {
      expect(valid.has(cls)).toBe(true);
    }
  });
});

/**
 * SCHEDULED_JOBS is the OTHER half of "a new function surfaces, never hides" —
 * and the one that had NO CI guard until now. Unlike callables (guarded above
 * against contracts/functions/functions.json), `onSchedule` functions are not in
 * the callable registry, so a newly-added scheduled function used to silently
 * fall off the Finance board: its cron invocations + Firestore work became
 * invisible cost. This block closes that vector by discovering every
 * `onSchedule` export from the source tree at test time (the same grep the
 * inventory doc-comment describes) and asserting SCHEDULED_JOBS covers it.
 */
describe('SCHEDULED_JOBS mirrors every onSchedule export in functions/src', () => {
  const SRC_ROOT = join(__dirname, '..');

  /** `export const <name> = onSchedule(` — the only way a scheduled fn is defined. */
  const SCHEDULED_EXPORT = /export\s+const\s+(\w+)\s*=\s*onSchedule\s*\(/g;

  /**
   * Strips block (`/* … *\/`) and line (`// …`) comments so a commented-out
   * EXAMPLE of an `onSchedule` export (e.g. the JSDoc in errors/serverErrors.ts)
   * is not mistaken for a real deployed function. Good enough for this guard —
   * we only need to not match code that is commented out.
   */
  function stripComments(source: string): string {
    return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  }

  /** Every `<path>:<export>` scheduled function found under functions/src. */
  function discoverScheduledSources(): string[] {
    const found: string[] = [];
    const walk = (dir: string): void => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
          if (entry.name === 'node_modules' || entry.name === 'lib') continue;
          walk(full);
          continue;
        }
        // Only real source files — skip tests/type decls so grepped strings or
        // comments in a *.test.ts can never masquerade as a deployed function.
        if (!entry.name.endsWith('.ts')) continue;
        if (entry.name.endsWith('.test.ts') || entry.name.endsWith('.d.ts')) continue;

        const rel = relative(SRC_ROOT, full).split(sep).join('/');
        const text = stripComments(readFileSync(full, 'utf8'));
        for (const match of text.matchAll(SCHEDULED_EXPORT)) {
          found.push(`${rel}:${match[1]}`);
        }
      }
    };
    walk(SRC_ROOT);
    return found;
  }

  it('every onSchedule export is costed (a new scheduled function cannot hide its cost)', () => {
    const discovered = discoverScheduledSources();
    const inventoried = new Set(REPOSITORY_SCHEDULED_JOBS.map((j) => j.source));
    const missing = discovered.filter((src) => !inventoried.has(src)).sort();

    // If this fails, add the named function to SCHEDULED_JOBS in
    // finance/inventory.ts with a per-run read/write/delete estimate.
    expect(
      missing,
      `Scheduled function(s) missing from SCHEDULED_JOBS in finance/inventory.ts` +
        ` — add an entry (with a per-run cost estimate) for each:\n  ${missing.join('\n  ')}`,
    ).toEqual([]);

    // Sanity: discovery actually found the tree (guards against a broken walk
    // silently passing the assertion above).
    expect(discovered.length).toBeGreaterThan(20);
  });

  it('does not list a scheduled job the source no longer has (no stale/renamed entries)', () => {
    const discovered = new Set(discoverScheduledSources());
    const stale = REPOSITORY_SCHEDULED_JOBS.map((j) => j.source)
      .filter((src) => !discovered.has(src))
      .sort();
    expect(
      stale,
      `SCHEDULED_JOBS entr(y/ies) point at a source that no longer defines an` +
        ` onSchedule export (removed or renamed) — fix the \`source\` or drop the` +
        ` entry:\n  ${stale.join('\n  ')}`,
    ).toEqual([]);
  });

  it('every entry has a unique id and a unique source', () => {
    const ids = SCHEDULED_JOBS.map((j) => j.id);
    const sources = SCHEDULED_JOBS.map((j) => j.source);
    expect(new Set(ids).size).toBe(ids.length);
    expect(new Set(sources).size).toBe(sources.length);
  });

  it('includes the six separately deployed Driver schedules without exempting repository jobs', () => {
    expect(DRIVER_SCHEDULED_JOBS.map((job) => job.id).sort()).toEqual([
      'driver-cleanupExpiredDataExports',
      'driver-clubsMaintenance',
      'driver-maintainClubEvents',
      'driver-processAccountDeletions',
      'driver-processDataExports',
      'driver-processExpiredSuspensions',
    ]);
    expect(SCHEDULED_JOBS).toEqual([...REPOSITORY_SCHEDULED_JOBS, ...DRIVER_SCHEDULED_JOBS]);
    expect(DRIVER_SCHEDULED_JOBS.every((job) => job.source.startsWith('deployed:driver:'))).toBe(
      true,
    );
    expect(REPOSITORY_SCHEDULED_JOBS.every((job) => !job.source.startsWith('deployed:'))).toBe(
      true,
    );
    expect(
      DRIVER_SCHEDULED_JOBS.find((job) => job.id === 'driver-clubsMaintenance')?.runsPerDay,
    ).toBe(1440);
  });

  it('matches repository job CPU and memory to the actual Firebase deploy manifest', async () => {
    process.env.GCLOUD_PROJECT ??= 'demo-test';
    process.env.FIREBASE_CONFIG ??= JSON.stringify({
      projectId: 'demo-test',
      databaseURL: 'https://demo-test.firebaseio.com',
      storageBucket: 'demo-test.appspot.com',
    });
    const entry = (await import('../index')) as Record<string, unknown>;
    for (const job of REPOSITORY_SCHEDULED_JOBS) {
      const [group, name] = job.id.split('-');
      if (!group || !name) throw new Error(`Malformed scheduled job id: ${job.id}`);
      const functions = entry[group] as Record<
        string,
        {
          __endpoint: { cpu?: number; availableMemoryMb?: number };
        }
      >;
      const deployed = functions[name];
      if (!deployed) throw new Error(`Scheduled job missing from deploy manifest: ${job.id}`);
      const endpoint = deployed.__endpoint;
      expect(job.cpu, job.id).toBe(endpoint.cpu ?? 1);
      expect(job.memoryGiB, job.id).toBe((endpoint.availableMemoryMb ?? 256) / 1024);
    }
  }, 30_000);
});
