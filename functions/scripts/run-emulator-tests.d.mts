/**
 * Types for the JS emulator-suite runner (run-emulator-tests.mjs). Only the
 * pure decision helper is consumed by tests; the runner's main path drives the
 * emulators and has no exported surface worth typing.
 */

/**
 * Decide the OUTER exit code from the sentinel the INNER process left behind.
 * Returns 0 only for a sentinel with a validated integer `vitestCode === 0`;
 * a missing/unreadable/malformed sentinel or a non-zero code is a failure.
 */
export declare function resolveOuterExitCode(sentinelPath: string, execCode: number): number;

/**
 * Turn the optional KCC_TEST_FILES env var into the positional vitest file
 * args. Returns `{ args: [] }` when unset (whole suite), `{ args: [file, …] }`
 * for a non-empty group, and `{ error }` when it is set but names no files —
 * which the runner fails closed on rather than silently running the whole suite
 * or testing nothing.
 */
export declare function resolveTestFileArgs(
  env?: NodeJS.ProcessEnv,
): { args: string[] } | { error: string };
