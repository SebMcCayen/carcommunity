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
