/**
 * Phase 16 coverage guard: every IMPLEMENTED callable in the registry
 * must be invoked by at least one emulator test (by its deployed
 * `domain-action` name). A new callable without a test fails the unit
 * suite — the coverage audit can no longer regress silently.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('callable test coverage (Phase 16)', () => {
  it('every implemented callable is exercised by an emulator test', () => {
    const registry = JSON.parse(
      readFileSync(join(__dirname, '../../../contracts/functions/functions.json'), 'utf8'),
    ) as { functions: Array<{ name: string; status: string }> };

    const testsDir = __dirname;
    const testSource = readdirSync(testsDir)
      .filter((f) => f.endsWith('.emulator.test.ts'))
      .map((f) => readFileSync(join(testsDir, f), 'utf8'))
      .join('\n');

    const uncovered = registry.functions
      .filter((f) => f.status === 'implemented')
      .map((f) => f.name.replaceAll('.', '-'))
      .filter(
        (deployedName) =>
          // Count only actual invocations (call helper / httpsCallable /
          // a CALLABLE_NAME constant), never describe() titles or comments.
          !testSource.includes(`call('${deployedName}'`) &&
          !testSource.includes(`httpsCallable(functions, '${deployedName}')`) &&
          !testSource.includes(`CALLABLE_NAME = '${deployedName}'`),
      );

    expect(uncovered).toEqual([]);
  });
});
