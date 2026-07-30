/**
 * Cost guardrail: every deployed function must declare a `maxInstances`.
 *
 * Cloud Functions v2 defaults `maxInstances` to 1000, so a function that omits
 * it has no upper bound on spend. This guard makes that omission a failing unit
 * test instead of a line on an invoice: a new callable, trigger or schedule has
 * to pick a tier from `shared/instanceLimits.ts` (or set a documented literal)
 * before it can ship.
 *
 * ## Why this is STRUCTURAL, not a source scan
 *
 * The first version of this guard regexed `functions/src/**` for `= onCall(`
 * and friends. That works only for as long as every definition happens to be
 * formatted the way the regex expects: `=onCall(` (no space), a line break
 * between `=` and the call, a rename of the import, a wrapper helper, or a new
 * trigger type nobody added to the pattern would all make the guard *silently
 * skip* the definition. A guard that skips does not fail — it just stops
 * guarding, and nobody finds out until a runaway function bills.
 *
 * So the guard now reads the same thing the deploy reads. `firebase-functions`
 * attaches a `__endpoint` deploy manifest to every definition it produces, and
 * `firebase deploy` discovers functions by walking the entry point's exports
 * looking for exactly that property. This test imports `../index` and walks it
 * the same way, then asserts on the resolved `maxInstances` of every endpoint
 * it finds. Formatting, import aliases, wrapper factories and future trigger
 * types are all irrelevant: if the deploy would create the function, the walk
 * sees it, because it is the same object.
 *
 * Importing the backend entry point needs no credentials — `firebase-admin`
 * reads project/database/bucket from `FIREBASE_CONFIG` (set below) and resolves
 * credentials lazily, and nothing in module scope performs I/O.
 *
 * ## What this guard still cannot see
 *
 * Stated explicitly so the guard is not trusted for more than it checks:
 *
 * 1. **Config changed outside the repo.** `maxInstances` edited in the Cloud
 *    console or via `gcloud run services update` is invisible here; this test
 *    asserts source intent, not live configuration. Detecting that drift needs
 *    GCP credentials and belongs in a deploy-time check, not the unit suite.
 * 2. **A second functions codebase.** `firebase.json` declares exactly one
 *    (`default`, source `functions`, entry `lib/index.js`). A new codebase, or
 *    a second entry point, would deploy functions this walk never imports —
 *    whoever adds one must extend `ENTRY_POINTS` below.
 * 3. **A function that exists in `src/` but is not exported from `index.ts`.**
 *    It is also not deployed, so it cannot cost anything; it starts being
 *    checked the moment it is wired up, which is strictly before it can bill.
 * 4. **Other cost axes.** `minInstances` (idle containers), `concurrency`,
 *    `timeoutSeconds` and memory size all move the bill too. Only the instance
 *    ceiling is guarded here.
 *
 * A `maxInstances` supplied as a params `Expression` rather than a literal is
 * *not* a blind spot but it is a deliberate design choice: the guard requires a
 * plain integer and so would FAIL on an expression rather than wave it through,
 * because the value behind an expression is not knowable at test time.
 */

import { beforeAll, describe, expect, it } from 'vitest';

/**
 * Deploy manifest `firebase-functions` attaches to every definition
 * (`onCall`, `onSchedule`, `onDocument*`, `onRequest`, the 1st-gen
 * `functions.runWith(...).auth.user().onCreate(...)`, and anything added
 * later). Typed loosely on purpose — the guard's whole point is to accept
 * whatever shape shows up and judge the value, not to trust a declared type.
 */
type Endpoint = { maxInstances?: unknown; platform?: unknown };
type Deployable = { __endpoint?: Endpoint };

/** The exported modules `firebase deploy` discovers functions from. */
const ENTRY_POINTS = ['../index'] as const;

/**
 * Deployed function ids (`group-name`, exactly as they appear in the Cloud
 * console) deliberately left uncapped for now because a concurrent branch is
 * editing the same lines (PR #629 `auth/onUserCreate.ts`, PR #631
 * `auth/completeOnboarding.ts`). Both are once-per-account paths, so the
 * uncapped default is the lowest-risk gap in the codebase — but it IS a gap,
 * and listing it here keeps it visible rather than forgotten. Remove an entry
 * (and add the cap) once the owning PR has merged; the pinning test below
 * fails if an entry is left behind after the cap lands.
 */
const UNCAPPED_EXEMPTIONS = new Set(['auth-completeOnboarding', 'auth-onUserCreate']);

/**
 * Cloud Functions' own default ceiling. A function that "declares" this is not
 * capped in any meaningful sense, so the guard rejects it as loudly as a
 * missing value.
 */
const PLATFORM_DEFAULT_MAX_INSTANCES = 1000;

/**
 * Lower bound on how many functions the walk must find. Not a target — it
 * exists so that a change to the export shape that makes discovery quietly
 * return (almost) nothing fails here, instead of turning the whole guard into
 * a vacuous `expect([]).toEqual([])`. The codebase deploys ~148 functions.
 */
const MIN_EXPECTED_FUNCTIONS = 100;

type Discovered = { id: string; endpoint: Endpoint };

type Discovery = {
  /** One entry per function `firebase deploy` would create. */
  functions: Discovered[];
  /**
   * Exports that are object-like (so they could have been a function or a
   * group of functions) yet yielded no endpoint at all. Anything landing here
   * is a shape the walk does not understand, which is precisely the situation
   * where a silent skip would hide an uncapped function — so it is a failure,
   * not a warning.
   */
  unclassified: string[];
};

/**
 * Walk an entry point's exports the way the Firebase CLI does: an export
 * carrying `__endpoint` is a function whose deployed id is its dotted path
 * joined with `-`; a plain object is a group to recurse into.
 *
 * Primitive exports are skipped without complaint — a string or a number
 * provably cannot be a Cloud Function. Every other unproductive shape (an
 * object or array that yields no endpoint, a bare function with no
 * `__endpoint`) is reported in `unclassified`, deepest node first, so nothing
 * object-shaped can pass through unexamined.
 */
export function discoverFunctions(moduleExports: Record<string, unknown>): Discovery {
  const functions: Discovered[] = [];
  const unclassified: string[] = [];

  const visit = (value: unknown, path: string[]): boolean => {
    const id = path.join('-');
    const objectLike = typeof value === 'function' || (typeof value === 'object' && value !== null);
    if (!objectLike) return false;

    const endpoint = (value as Deployable).__endpoint;
    if (endpoint !== null && typeof endpoint === 'object') {
      functions.push({ id, endpoint });
      return true;
    }

    const flaggedBefore = unclassified.length;
    let produced = false;
    if (typeof value === 'object' && !Array.isArray(value)) {
      for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
        if (visit(child, [...path, key])) produced = true;
      }
    }
    // Report only the deepest unproductive node, so one stray leaf does not
    // also indict every group above it.
    if (!produced && unclassified.length === flaggedBefore) unclassified.push(id);
    return produced;
  };

  for (const [key, value] of Object.entries(moduleExports)) visit(value, [key]);
  return { functions, unclassified };
}

/**
 * `firebase-functions` does not leave an omitted option `undefined` in the
 * manifest — it fills in a `ResetValue` sentinel meaning "deploy at the
 * platform default". That is what a function with no `maxInstances` looks
 * like here, so it is worth naming in the failure message rather than
 * reporting as an anonymous object. Brand-checked with the library's own
 * symbol tag (`ResetValue.is()`), which survives duplicate module instances.
 */
const RESET_VALUE_TAG = Symbol.for('firebase-functions:ResetValue:Tag');

function isResetValue(value: unknown): boolean {
  return (
    typeof value === 'object' &&
    value !== null &&
    (value as Record<symbol, unknown>)[RESET_VALUE_TAG] === true
  );
}

/** Readable rendering of whatever turned up where an integer was expected. */
function describeValue(value: unknown): string {
  if (isResetValue(value)) {
    return `not set (would deploy at the platform default of ${PLATFORM_DEFAULT_MAX_INSTANCES})`;
  }
  if (value === undefined) return 'missing (undefined)';
  if (value === null) return 'null';
  if (typeof value === 'object')
    return `a ${Array.isArray(value) ? 'array' : 'non-integer object'}`;
  return `${JSON.stringify(value)} (${typeof value})`;
}

/**
 * The cap rule itself, pure and independently testable: returns a human
 * message when `maxInstances` fails to bound the function, `null` when it
 * does. Exemptions are applied by the caller, not here.
 */
export function capViolation(id: string, endpoint: Endpoint): string | null {
  const max = endpoint.maxInstances;
  if (typeof max !== 'number' || !Number.isInteger(max)) {
    return `${id}: maxInstances is ${describeValue(max)} — pick a tier from shared/instanceLimits.ts`;
  }
  if (max <= 0) return `${id}: maxInstances is ${max} — must be at least 1`;
  if (max >= PLATFORM_DEFAULT_MAX_INSTANCES) {
    return `${id}: maxInstances is ${max}, which is the platform default (${PLATFORM_DEFAULT_MAX_INSTANCES}) or higher — that is not a cap`;
  }
  return null;
}

let discovery: Discovery;

beforeAll(async () => {
  // Deterministic Admin SDK bootstrap: `src/firebase.ts` calls initializeApp()
  // at module scope and getDatabase() needs a databaseURL. Neither performs
  // I/O, so no emulator and no credentials are required — only this config.
  process.env.GCLOUD_PROJECT ??= 'demo-test';
  process.env.FIREBASE_CONFIG ??= JSON.stringify({
    projectId: 'demo-test',
    databaseURL: 'https://demo-test.firebaseio.com',
    storageBucket: 'demo-test.appspot.com',
  });

  const modules = await Promise.all(
    ENTRY_POINTS.map((entry) => import(entry) as Promise<Record<string, unknown>>),
  );
  discovery = modules
    .map((mod) => discoverFunctions(mod))
    .reduce<Discovery>(
      (acc, next) => ({
        functions: [...acc.functions, ...next.functions],
        unclassified: [...acc.unclassified, ...next.unclassified],
      }),
      { functions: [], unclassified: [] },
    );
});

describe('instance ceilings (cost guardrail)', () => {
  it('every deployed function declares a bounding maxInstances', () => {
    const offenders = discovery.functions
      .filter(({ id }) => !UNCAPPED_EXEMPTIONS.has(id))
      .map(({ id, endpoint }) => capViolation(id, endpoint))
      .filter((message): message is string => message !== null);

    expect(offenders).toEqual([]);
  });

  it('discovery actually walked the backend (the guard is not vacuous)', () => {
    expect(discovery.functions.length).toBeGreaterThanOrEqual(MIN_EXPECTED_FUNCTIONS);
    // Ids are the deployed names; duplicates would mean the walk double-counted
    // and could equally mean it mis-attributed a cap.
    expect(new Set(discovery.functions.map((f) => f.id)).size).toBe(discovery.functions.length);
  });

  it('no export escapes classification', () => {
    // If this fails, index.ts grew an object-shaped export the walk does not
    // understand. Do NOT relax the walk — work out whether that export deploys
    // a function, because a shape the guard cannot read is a shape that could
    // be hiding one.
    expect(discovery.unclassified).toEqual([]);
  });

  it('the uncapped exemptions still exist and are still uncapped', () => {
    // Pin the exemption list so it cannot rot: if one of these gains a cap (or
    // disappears), the entry must be removed rather than silently excusing a
    // function that no longer needs excusing.
    const byId = new Map(discovery.functions.map((f) => [f.id, f.endpoint]));
    for (const id of UNCAPPED_EXEMPTIONS) {
      const endpoint = byId.get(id);
      expect(
        endpoint,
        `${id} is no longer deployed — drop it from UNCAPPED_EXEMPTIONS`,
      ).toBeDefined();
      expect(
        capViolation(id, endpoint as Endpoint),
        `${id} is capped now — drop it from UNCAPPED_EXEMPTIONS`,
      ).not.toBeNull();
    }
  });
});

describe('the guard itself has teeth', () => {
  // A structural walk cannot be evaded by formatting *by construction*, but
  // "by construction" is exactly the kind of claim this repo has been burned
  // by asserting without checking. These cases exercise the walk and the rule
  // directly against shapes the old source-scanning regex would have missed.

  const endpointOf = (maxInstances: unknown) =>
    Object.assign(() => undefined, { __endpoint: { platform: 'gcfv2', maxInstances } });

  it('finds functions regardless of how the source was written', () => {
    // These four exports stand in for `= onCall(`, `=onCall(` (no space), a
    // definition split across a line break, and one built by a wrapper
    // factory. Post-evaluation they are indistinguishable — which is the whole
    // argument for walking objects instead of text.
    const { functions, unclassified } = discoverFunctions({
      spaced: endpointOf(20),
      unspaced: endpointOf(20),
      lineBroken: endpointOf(20),
      viaFactory: endpointOf(20),
    });
    expect(functions.map((f) => f.id).sort()).toEqual([
      'lineBroken',
      'spaced',
      'unspaced',
      'viaFactory',
    ]);
    expect(unclassified).toEqual([]);
  });

  it('catches a REAL onCall defined without maxInstances', async () => {
    // The end-to-end proof, using the actual library rather than a stand-in:
    // an `onCall` with no ceiling, discovered and rejected exactly as a real
    // one in index.ts would be. This also pins the sentinel the library uses
    // for an omitted option, so a future firebase-functions release that
    // changes it breaks the test instead of quietly disarming the guard.
    const { onCall } = await import('firebase-functions/v2/https');
    const uncapped = onCall({ region: 'europe-west1' }, () => undefined);
    const capped = onCall({ region: 'europe-west1', maxInstances: 20 }, () => undefined);

    const { functions, unclassified } = discoverFunctions({ demo: { capped, uncapped } });
    expect(unclassified).toEqual([]);
    expect(functions.map((f) => f.id)).toEqual(['demo-capped', 'demo-uncapped']);

    const offenders = functions
      .map(({ id, endpoint }) => capViolation(id, endpoint))
      .filter((m) => m !== null);
    expect(offenders).toHaveLength(1);
    expect(offenders[0]).toContain('demo-uncapped');
    expect(offenders[0]).toContain(`platform default of ${PLATFORM_DEFAULT_MAX_INSTANCES}`);
  });

  it('catches an uncapped function inside a grouped export', () => {
    const { functions } = discoverFunctions({
      live: { updatePosition: endpointOf(50), listNearby: endpointOf(undefined) },
    });
    const offenders = functions
      .map(({ id, endpoint }) => capViolation(id, endpoint))
      .filter((m) => m !== null);
    expect(offenders).toHaveLength(1);
    expect(offenders[0]).toContain('live-listNearby');
  });

  it('rejects caps that do not actually bound anything', () => {
    expect(capViolation('a', { maxInstances: 1000 })).toContain('not a cap');
    expect(capViolation('a', { maxInstances: 0 })).toContain('at least 1');
    expect(capViolation('a', { maxInstances: null })).not.toBeNull();
    expect(capViolation('a', { maxInstances: '20' })).not.toBeNull();
    expect(capViolation('a', { maxInstances: 20.5 })).not.toBeNull();
    expect(capViolation('a', {})).not.toBeNull();
    // A params Expression is an object, not an integer: fail-safe, not skipped.
    expect(capViolation('a', { maxInstances: { toCEL: () => '{{ params.MAX }}' } })).not.toBeNull();
    expect(capViolation('a', { maxInstances: 20 })).toBeNull();
  });

  it('flags an object-shaped export it cannot resolve to a function', () => {
    const { functions, unclassified } = discoverFunctions({
      real: endpointOf(20),
      notAFunctionGroup: { version: '1.0.0' },
      emptyGroup: {},
      bareHelper: () => undefined,
      // Primitives provably cannot be functions, so they are skipped silently.
      someConstant: 'europe-west1',
    });
    expect(functions.map((f) => f.id)).toEqual(['real']);
    expect(unclassified.sort()).toEqual(['bareHelper', 'emptyGroup', 'notAFunctionGroup']);
  });
});
