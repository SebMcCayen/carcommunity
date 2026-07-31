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

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { CALLABLE_COST_CLASS } from './inventory';

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
