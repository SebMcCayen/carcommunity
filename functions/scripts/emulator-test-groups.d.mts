/**
 * Types for the emulator-suite group definitions (emulator-test-groups.mjs).
 * The module is the single source of truth for how the emulator integration
 * suite is split into named, feature-based CI groups, plus the coverage guard
 * that keeps the split a clean partition.
 */

export interface TestGroup {
  /** Human-readable group name; used verbatim as the CI check name. */
  name: string;
  /** Basename patterns (`admin*` prefix, or exact) matched against test files. */
  patterns: string[];
}

/** The named feature groups, in matrix order. */
export declare const GROUPS: TestGroup[];

/** Basename key of a test file path: `src/__tests__/foo.emulator.test.ts` → `foo`. */
export declare function basenameKey(file: string): string;

/** Match a pattern against a basename key (prefix if it ends with `*`, else exact). */
export declare function matchPattern(pattern: string, key: string): boolean;

/** Recursively discover every emulator test file under `src`, functions-relative, sorted. */
export declare function discoverTestFiles(root?: string): string[];

/**
 * Assign every file to a group and validate the partition. `errors` is empty
 * iff every file is matched by exactly one group and every pattern matches at
 * least one file.
 */
export declare function assignGroups(files: string[]): {
  groups: { name: string; files: string[] }[];
  errors: string[];
};
