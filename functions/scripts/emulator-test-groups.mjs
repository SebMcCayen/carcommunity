#!/usr/bin/env node
/**
 * Single source of truth for how the Firebase emulator integration suite is
 * split into named, feature-based CI groups.
 *
 * ## Why groups instead of numeric shards
 *
 * The ~900-test emulator suite across 8 emulators outgrew a single free
 * (~7 GB) standard runner and got the Firestore emulator SIGKILL'd mid-run. We
 * split it across parallel free `ubuntu-latest` jobs — but instead of opaque
 * `shard 1/4` numbers, each job runs a named feature SET of the test files, so
 * the CI check name says WHAT it tests ("Events, Convoy, Live & Garage"). Each
 * group spins up its own fresh emulator set and runs ONLY its files, so its
 * peak memory + wall-time sit well under the ceiling.
 *
 * ## Contract (enforced by the coverage guard below)
 *
 * Every `functions/src/** /*.emulator.test.ts` file MUST belong to EXACTLY one
 * group. `assignGroups` fails (non-empty `errors`) if any file is matched by
 * zero groups, by more than one, or if a pattern matches no file at all. This
 * makes it impossible to add a new emulator test that silently never runs, or
 * to double-run one. The guard runs both as a fast unit test
 * (run-emulator-tests-runner.test.ts) and in the CI `plan` job that emits the
 * matrix, so a mis-assignment fails long before any emulator starts.
 *
 * ## Patterns
 *
 * Each group lists patterns matched against a file's BASENAME with the
 * `.emulator.test.ts` suffix stripped (e.g. `admin-delete-user`). A trailing
 * `*` is an anchored prefix match (`admin*` → `admin`, `admin-delete-user`);
 * otherwise it is an exact match. Patterns are ALWAYS anchored at the start, so
 * `notifications*` never captures `events-created-notification` — unlike
 * vitest's own substring filter, this can't cross-match by coincidence.
 */

import { appendFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const functionsDir = join(__dirname, '..');
const SUFFIX = '.emulator.test.ts';

/**
 * The groups. Balanced by test-case weight (not just file count) so each job's
 * peak memory stays well under the free-runner ceiling; see the counts in the
 * PR description. Keep them roughly even when adding tests.
 * @type {{ name: string, patterns: string[] }[]}
 */
export const GROUPS = [
  {
    name: 'Security Rules, Admin & Platform Core',
    patterns: [
      'security-rules',
      'auth',
      'account',
      'featureflags',
      'admin*',
      'purge-never-onboarded',
      'inactivity',
    ],
  },
  {
    name: 'Social, Chat & Notifications',
    patterns: [
      'rules-social-graph',
      'friends',
      'dm',
      'user-search',
      'chatchannels',
      'communityDigest',
      'notifications*',
      'block*',
      'moderation*',
    ],
  },
  {
    name: 'Events, Convoy, Live & Garage',
    patterns: ['events*', 'convoy*', 'live*', 'drives', 'garage', 'subscription-expiry'],
  },
  {
    name: 'Crown Hunt, Points, Badges, Incidents & Partners',
    patterns: [
      'crownhunt*',
      'leaderboard',
      'points*',
      'badges',
      'incidents',
      'phase11',
      'partner*',
      'insights',
      'billboards',
      'diagnostics',
      'errors',
      'serverErrors',
      'feedback',
      'metrics',
      'finance',
    ],
  },
];

/** Basename key of a test file path: `src/__tests__/foo.emulator.test.ts` → `foo`. */
export function basenameKey(file) {
  const base = file.slice(file.lastIndexOf('/') + 1);
  return base.endsWith(SUFFIX) ? base.slice(0, -SUFFIX.length) : base;
}

/** Match a single pattern against a basename key (prefix if it ends with `*`, else exact). */
export function matchPattern(pattern, key) {
  return pattern.endsWith('*') ? key.startsWith(pattern.slice(0, -1)) : key === pattern;
}

/** Recursively discover every emulator test file under `src`, as functions-relative paths, sorted. */
export function discoverTestFiles(root = join(functionsDir, 'src')) {
  const out = [];
  const walk = (dir, rel) => {
    for (const ent of readdirSync(dir, { withFileTypes: true })) {
      const r = rel ? `${rel}/${ent.name}` : ent.name;
      if (ent.isDirectory()) walk(join(dir, ent.name), r);
      else if (ent.name.endsWith(SUFFIX)) out.push(`src/${r}`);
    }
  };
  walk(root, '');
  return out.sort();
}

/**
 * Assign every file to a group and validate the partition.
 *
 * @param {string[]} files functions-relative test file paths
 * @returns {{ groups: {name:string, files:string[]}[], errors: string[] }}
 *   `groups` holds each group's uniquely-assigned files; `errors` is empty iff
 *   the partition is valid (every file matched by exactly one group, and every
 *   pattern matches at least one file).
 */
export function assignGroups(files) {
  const assignments = files.map((file) => {
    const key = basenameKey(file);
    const groups = GROUPS.filter((g) => g.patterns.some((p) => matchPattern(p, key))).map(
      (g) => g.name,
    );
    return { file, key, groups };
  });

  const groups = GROUPS.map((g) => ({
    name: g.name,
    files: assignments.filter((a) => a.groups.length === 1 && a.groups[0] === g.name).map((a) => a.file),
  }));

  const errors = [];
  for (const a of assignments) {
    if (a.groups.length === 0) {
      errors.push(
        `Unassigned emulator test (matched by NO group): ${a.file}. Add its basename to a group in scripts/emulator-test-groups.mjs so it actually runs in CI.`,
      );
    } else if (a.groups.length > 1) {
      errors.push(
        `Ambiguous emulator test (matched by ${a.groups.length} groups: ${a.groups.join(
          ', ',
        )}): ${a.file}. Fix the overlapping patterns so it belongs to exactly one group.`,
      );
    }
  }
  for (const g of GROUPS) {
    for (const p of g.patterns) {
      if (!files.some((f) => matchPattern(p, basenameKey(f)))) {
        errors.push(
          `Stale pattern "${p}" in group "${g.name}" matches no emulator test file — remove or fix it.`,
        );
      }
    }
  }

  return { groups, errors };
}

/** True when this file is the process entrypoint (not merely imported by a test). */
function isEntrypoint() {
  const argv1 = process.argv[1];
  if (!argv1) return false;
  try {
    return fileURLToPath(import.meta.url) === argv1 || argv1.endsWith('emulator-test-groups.mjs');
  } catch {
    return false;
  }
}

function main() {
  const files = discoverTestFiles();
  const { groups, errors } = assignGroups(files);

  for (const g of groups) {
    console.error(`[groups] ${g.name}: ${g.files.length} file(s)`);
  }

  if (errors.length > 0) {
    console.error('\n[groups] COVERAGE GUARD FAILED — the emulator suite is not a clean partition:');
    for (const e of errors) console.error(`  - ${e}`);
    process.exit(1);
  }
  console.error(
    `[groups] OK: all ${files.length} emulator test files map to exactly one of ${groups.length} groups.`,
  );

  if (process.argv.includes('--emit-matrix')) {
    // The matrix each shard job runs. `files` is a single space-separated string
    // so it can travel through the job env var untouched.
    const matrix = { include: groups.map((g) => ({ name: g.name, files: g.files.join(' ') })) };
    const line = `matrix=${JSON.stringify(matrix)}`;
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${line}\n`);
    else console.log(JSON.stringify(matrix));
  }
}

if (isEntrypoint()) main();
