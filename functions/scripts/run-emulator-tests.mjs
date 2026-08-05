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
 *  a genuine test failure (non-zero sentinel) OR a missing sentinel (emulators
 *  failed to start / vitest never ran) still fails the job. Real failures are
 *  never masked.
 *
 * Nothing here patches firebase-tools or changes firebase.json; the emulator
 * topology and the vitest config are untouched.
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const functionsDir = join(__dirname, '..');

// Shared sentinel path — computed identically by both modes so the OUTER
// process can read what the INNER process wrote. Kept out of the repo (tmpdir)
// so there is nothing to .gitignore and no stale file committed.
const SENTINEL = join(tmpdir(), 'carcommunity-emulator-test-result.json');

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

async function inner() {
  // Run the vitest emulator suite. `pnpm test:emulator` is the single source of
  // truth for how the suite is invoked (vitest run --config
  // vitest.emulator.config.ts).
  const vitestCode = await run('pnpm', ['test:emulator']);

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

  // Record the REAL vitest result for the OUTER process to trust.
  try {
    mkdirSync(dirname(SENTINEL), { recursive: true });
    writeFileSync(SENTINEL, JSON.stringify({ vitestCode, at: new Date().toISOString() }));
  } catch (err) {
    console.error(`[run-emulator-tests] could not write sentinel: ${err.message}`);
    // Fall through: exiting non-zero below on a failure still fails correctly;
    // on success a missing sentinel makes OUTER fail conservatively.
  }

  // Exit with vitest's real code so emulators:exec's own success/failure log
  // stays accurate too.
  return vitestCode;
}

async function outer() {
  // Clear any stale sentinel so a crash that never writes one is caught as a
  // missing result rather than a stale pass.
  try {
    if (existsSync(SENTINEL)) rmSync(SENTINEL);
  } catch {
    /* best effort */
  }

  const selfPath = fileURLToPath(import.meta.url);
  // emulators:exec runs its <script> argument through a shell; keep it a single
  // token-safe command.
  const innerCmd = `node ${JSON.stringify(selfPath)} --inner`;

  const execCode = await run('firebase', [
    'emulators:exec',
    '--only',
    'auth,functions,firestore,database,storage',
    '--project',
    'demo-test',
    '--config',
    '../firebase.json',
    innerCmd,
  ]);

  // Derive the job result from the sentinel (vitest's real outcome), NOT from
  // execCode — teardown noise lives in execCode, the truth lives in the file.
  let sentinel;
  try {
    sentinel = JSON.parse(readFileSync(SENTINEL, 'utf8'));
  } catch {
    console.error(
      '[run-emulator-tests] no test-result sentinel found — the emulators or vitest did not run to completion. Failing the job.',
    );
    return execCode === 0 ? 1 : execCode;
  }

  if (sentinel.vitestCode === 0) {
    if (execCode !== 0) {
      console.warn(
        `[run-emulator-tests] vitest passed (exit 0) but emulators:exec exited ${execCode} — treating as emulator-shutdown noise and passing the job. (See the teardown-crash note in this script.)`,
      );
    }
    return 0;
  }

  console.error(
    `[run-emulator-tests] vitest failed (exit ${sentinel.vitestCode}). Failing the job.`,
  );
  return sentinel.vitestCode;
}

const mode = process.argv.includes('--inner') ? inner : outer;
process.exit(await mode());
