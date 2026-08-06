#!/usr/bin/env node
/**
 * Robust runner for the Firebase emulator integration suite (the
 * `test-firebase-rules` CI job, ~900 tests across 8 emulators).
 *
 * ## Why this exists
 *
 * The suite is functionally green but the *job* used to be flaky, failing in
 * two ways that had nothing to do with a test actually failing:
 *
 *  1. Teardown crash. After every test passed and vitest exited 0, the
 *     Firestore/RTDB *background triggers* the suite fired (crownHunt-*,
 *     points-onLedgerEntryCreated, userSearch-onUserProfileWrite, badges-*,
 *     …) are dispatched asynchronously and their execution backlog lags
 *     behind the synchronous vitest completion. `firebase emulators:exec`
 *     begins tearing the emulators down the instant the script returns, so an
 *     in-flight trigger races the shutdown: it tries to spin up a functions
 *     runtime worker that never becomes ready (the emulator is stopping),
 *     and after the 30s discovery timeout the worker rejects with
 *     "Failed to load function" (firebase-tools functionsRuntimeWorker.js).
 *     That stray rejection during shutdown flips emulators:exec's exit code to
 *     1 — surfacing as `⬢ functions: Failed to handle request …` →
 *     `Failed to start functions: Failed to load function` →
 *     `ELIFECYCLE Command failed` — even though the suite passed. The crown
 *     work added more background triggers, which made the race more likely.
 *
 *  2. Mid-suite freeze / hang, guarded at the job level by `timeout-minutes`
 *     in the workflow (this script does not attempt to unstick a hung
 *     emulator; it makes the *result* trustworthy once the run completes).
 *
 * ## The fix (two layers, belt-and-suspenders)
 *
 *  INNER mode (runs *inside* emulators:exec): run vitest, then DRAIN — hold
 *  the process open for a short quiet window while the emulators are still
 *  fully up so the async trigger backlog finishes on its normal ~ms path
 *  instead of racing the shutdown. Record vitest's real exit code to a
 *  sentinel file. The drain makes the common case shut down cleanly.
 *
 *  OUTER mode: run `firebase emulators:exec <inner>` and derive the job's
 *  success from the SENTINEL (vitest's real result), NOT from emulators:exec's
 *  exit code. So stray shutdown noise can no longer fail a green suite — while
 *  a genuine test failure, OR anything that leaves the sentinel
 *  missing/unreadable/malformed (emulators failed to start, vitest never ran,
 *  a crash mid-write), still fails the job. The ONLY path that exits 0 is a
 *  sentinel whose `vitestCode` is a validated integer 0 — real failures are
 *  never masked and a corrupt sentinel never false-passes.
 *
 * The sentinel lives inside a securely-created unique temp directory
 * (`fs.mkdtempSync`, mode 0700, random suffix) whose path is handed to the
 * INNER process via an env var — never a predictable/fixed /tmp path (which
 * would be a symlink/race risk and could collide across concurrent local
 * runs). The directory is removed when the run finishes.
 *
 * Nothing here patches firebase-tools or changes firebase.json; the emulator
 * topology and the vitest config are untouched.
 */

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const functionsDir = join(__dirname, '..');

// Env var the OUTER process uses to tell the INNER process where to record the
// vitest result. The value is a path inside a per-run mkdtemp'd directory.
const ENV_SENTINEL = 'KCC_EMULATOR_TEST_SENTINEL';

// Optional explicit test-file list, set by the CI matrix so each runner spins
// up its own full emulator set but runs only ONE named feature group's files
// (see scripts/emulator-test-groups.mjs). This is what keeps each job's peak
// memory + wall-time under the free-runner ceiling — the single-job suite
// outgrew ~7 GB and got the Firestore emulator SIGKILL'd. The value is a
// whitespace-separated list of functions-relative paths. Absent → run the
// whole suite (local `pnpm test:emulator`, the runner unit test, and any
// non-grouped caller keep working unchanged).
const ENV_TEST_FILES = 'KCC_TEST_FILES';

// Quiet window (ms) to let the background-trigger backlog drain before the
// emulators shut down. A run is ~10 min; the default here is negligible
// overhead and comfortably covers the observed ~ms-per-trigger drain. Tunable
// via env for slow runners.
const DRAIN_MS = Number(process.env.EMULATOR_DRAIN_MS ?? 12_000);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Spawn a command, inheriting stdio, and resolve with its exit code. */
function run(command, args, opts = {}) {
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      stdio: 'inherit',
      cwd: functionsDir,
      shell: false,
      ...opts,
    });
    child.on('error', (err) => {
      console.error(`[run-emulator-tests] failed to spawn ${command}: ${err.message}`);
      resolve(1);
    });
    child.on('exit', (code, signal) => {
      if (signal) {
        console.error(`[run-emulator-tests] ${command} killed by signal ${signal}`);
        resolve(1);
        return;
      }
      resolve(code ?? 0);
    });
  });
}

/**
 * Decide the OUTER exit code from the sentinel the INNER process left behind.
 *
 * Contract: the job succeeds (returns 0) ONLY when the sentinel exists, parses,
 * and carries a validated integer `vitestCode === 0`. Every other case —
 * missing file, unreadable, non-JSON, missing/non-integer `vitestCode`, or a
 * non-zero `vitestCode` — is a FAILURE and returns a non-zero code. This is
 * what prevents both masked test failures and corrupt-sentinel false-greens.
 *
 * Exported (and side-effect-free) so it can be unit-tested directly.
 *
 * @param {string} sentinelPath path the INNER process wrote its result to
 * @param {number} execCode exit code `firebase emulators:exec` returned
 * @returns {number} exit code for the job
 */
export function resolveOuterExitCode(sentinelPath, execCode) {
  const failCode = execCode === 0 ? 1 : execCode;

  let raw;
  try {
    raw = readFileSync(sentinelPath, 'utf8');
  } catch {
    console.error(
      '[run-emulator-tests] no test-result sentinel found — the emulators or vitest did not run to completion. Failing the job.',
    );
    return failCode;
  }

  let sentinel;
  try {
    sentinel = JSON.parse(raw);
  } catch {
    console.error(
      '[run-emulator-tests] test-result sentinel is not valid JSON — refusing to trust it. Failing the job.',
    );
    return failCode;
  }

  const code = sentinel?.vitestCode;
  if (!Number.isInteger(code)) {
    console.error(
      `[run-emulator-tests] test-result sentinel has no valid integer vitestCode (got ${JSON.stringify(
        code,
      )}) — refusing to trust it. Failing the job.`,
    );
    return failCode;
  }

  if (code === 0) {
    if (execCode !== 0) {
      console.warn(
        `[run-emulator-tests] vitest passed (exit 0) but emulators:exec exited ${execCode} — treating as emulator-shutdown noise and passing the job. (See the teardown-crash note in this script.)`,
      );
    }
    return 0;
  }

  console.error(`[run-emulator-tests] vitest failed (exit ${code}). Failing the job.`);
  return code;
}

/**
 * Turn the optional test-file-group env var into the positional vitest args.
 *
 * Fail-closed contract for CI integrity:
 *  - Unset             → `{ args: [] }` (run the whole suite; unchanged local
 *    and unit-test behaviour).
 *  - Set & non-empty   → `{ args: [file, …] }` — the group's files, forwarded to
 *    vitest as positional file filters so it runs ONLY those.
 *  - Set but empty/whitespace → `{ error }`. An empty group must NEVER fall back
 *    to running the whole suite (that would re-create the memory ceiling this
 *    change avoids) NOR silently test nothing. The caller turns `error` into a
 *    failed run.
 *
 * Exported (side-effect-free) so it can be unit-tested directly.
 *
 * @param {NodeJS.ProcessEnv} [env=process.env]
 * @returns {{ args: string[] } | { error: string }}
 */
export function resolveTestFileArgs(env = process.env) {
  const raw = env[ENV_TEST_FILES];
  if (raw == null) return { args: [] };

  const files = raw.split(/\s+/).filter(Boolean);
  if (files.length === 0) {
    return {
      error: `${ENV_TEST_FILES} is set but names no files — a CI group must run at least one test file. Refusing to run (an empty group would silently test nothing).`,
    };
  }
  return { args: files };
}

async function inner() {
  const sentinelPath = process.env[ENV_SENTINEL];

  // Run the vitest emulator suite. `pnpm test:emulator` is the single source of
  // truth for how the suite is invoked (vitest run --config
  // vitest.emulator.config.ts). Under the CI matrix we append this group's test
  // FILES as positional filters so each runner executes only its named subset —
  // far lower peak memory than the whole run.
  //
  // The files are passed WITHOUT a `--` separator. `pnpm run <script> -- X`
  // forwards the `--` verbatim into the final command; while a positional file
  // path survives that, keeping the invocation `pnpm test:emulator <files>` →
  // `vitest run --config … <files>` is the clean, unambiguous form (and mirrors
  // how you'd filter files by hand).
  const group = resolveTestFileArgs();
  let vitestCode;
  if ('error' in group) {
    console.error(
      `[run-emulator-tests] ${group.error} Refusing to run an ill-defined group; failing this job.`,
    );
    vitestCode = 1;
  } else {
    const pnpmArgs = ['test:emulator', ...group.args];
    if (group.args.length) {
      console.log(
        `[run-emulator-tests] running ${group.args.length} test file(s) for this group:\n  ${group.args.join('\n  ')}`,
      );
    }
    vitestCode = await run('pnpm', pnpmArgs);
  }

  // DRAIN: with the emulators still fully up, give the async Firestore/RTDB
  // trigger backlog a quiet window to finish on its fast path, so nothing is
  // in-flight when emulators:exec tears everything down next. Skip the wait on
  // a real failure (fail fast — no point draining a red run).
  if (vitestCode === 0 && DRAIN_MS > 0) {
    console.log(
      `[run-emulator-tests] tests passed; draining background-trigger backlog for ${DRAIN_MS}ms before shutdown…`,
    );
    await sleep(DRAIN_MS);
  }

  if (!sentinelPath) {
    // Should never happen (OUTER always sets it); never silently pass without
    // a place to record a trustworthy result.
    console.error(
      `[run-emulator-tests] ${ENV_SENTINEL} is not set — cannot record a trustworthy result.`,
    );
    return vitestCode === 0 ? 1 : vitestCode;
  }

  // Record the REAL vitest result for the OUTER process to trust.
  try {
    writeFileSync(sentinelPath, JSON.stringify({ vitestCode, at: new Date().toISOString() }));
  } catch (err) {
    // A missing/corrupt sentinel makes OUTER fail conservatively, so surface it
    // and fail here too rather than let a passing vitest look green.
    console.error(`[run-emulator-tests] could not write sentinel: ${err.message}`);
    return vitestCode === 0 ? 1 : vitestCode;
  }

  // Exit with vitest's real code so emulators:exec's own success/failure log
  // stays accurate too.
  return vitestCode;
}

async function outer() {
  // Securely-created, unique, private (mode 0700) temp dir with a random
  // suffix — no predictable /tmp path, no cross-run collisions, no symlink
  // race. Removed in `finally`.
  const sentinelDir = mkdtempSync(join(tmpdir(), 'kcc-emu-'));
  const sentinelPath = join(sentinelDir, 'result.json');

  try {
    const selfPath = fileURLToPath(import.meta.url);
    // emulators:exec runs its <script> argument through a shell; keep it a
    // single token-safe command. The sentinel path travels to the inner node
    // process via the environment (emulators:exec forwards process.env).
    const innerCmd = `node ${JSON.stringify(selfPath)} --inner`;

    const execCode = await run(
      'firebase',
      [
        'emulators:exec',
        '--only',
        'auth,functions,firestore,database,storage',
        '--project',
        'demo-test',
        '--config',
        '../firebase.json',
        innerCmd,
      ],
      { env: { ...process.env, [ENV_SENTINEL]: sentinelPath } },
    );

    return resolveOuterExitCode(sentinelPath, execCode);
  } finally {
    try {
      rmSync(sentinelDir, { recursive: true, force: true });
    } catch {
      /* best effort */
    }
  }
}

/**
 * True when this file is the process entrypoint (node scripts/run-emulator-tests.mjs),
 * false when it is merely imported (e.g. by the unit test, which only wants the
 * exported helper). Robust to how the path was passed: `process.argv[1]` is
 * whatever was on the command line — often RELATIVE (pnpm runs
 * `node scripts/run-emulator-tests.mjs`) — and either side may traverse a
 * symlink. Both sides are normalized to a real absolute filesystem path before
 * comparing, so a relative argv, a differing cwd, or a symlinked worktree/tmp
 * path can't make the entrypoint check silently return false — which for a
 * CI-integrity runner would be the worst case: a no-op that never runs the
 * tests yet lets the job pass.
 */
function isEntrypoint() {
  const argv1 = process.argv[1];
  if (!argv1) return false;
  try {
    const modulePath = realpathSync(fileURLToPath(import.meta.url));
    const invokedPath = realpathSync(resolve(argv1));
    return modulePath === invokedPath;
  } catch {
    return false;
  }
}

if (isEntrypoint()) {
  const mode = process.argv.includes('--inner') ? inner : outer;
  process.exit(await mode());
}
