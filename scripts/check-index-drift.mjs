/**
 * Detect drift between the Firestore composite indexes DECLARED in
 * firebase/firestore.indexes.json and the ones actually DEPLOYED to a project.
 *
 * Why this exists: on 2026-07-19 every `friend-list` call in production failed
 * with `9 FAILED_PRECONDITION: The query requires an index` because
 * `firebase deploy --only firestore:indexes` had apparently never been run —
 * NINE declared indexes were missing from prod, silently, for weeks. Nothing in
 * the repo could catch it:
 *   - A missing composite index ERRORS at query time (FAILED_PRECONDITION); it
 *     does not return an empty list, so the failure only ever shows up as a
 *     runtime 500 for real users.
 *   - The Firestore emulator AUTO-CREATES any index a query asks for, so the
 *     rules/functions test suites pass whether or not the index file was ever
 *     deployed. No local test can detect this class of bug.
 * The only source of truth is the live project, so this script asks it.
 *
 * Reported in BOTH directions, with deliberately different severities:
 *   - declared-but-not-deployed -> FATAL (exit 1). Every query needing that
 *     index is failing in production right now.
 *   - deployed-but-not-declared -> WARN only (exit 0). Stale leftovers (e.g. the
 *     pre-rename `friendRequests [receiverId, ...]` indexes) cost storage and
 *     write latency but break nothing, and hand-created indexes are a legitimate
 *     if discouraged stopgap. Failing on these would make the check fire during
 *     the window between deploying an index and landing its declaration.
 *
 * Usage:
 *   node scripts/check-index-drift.mjs --project <projectId>
 *   node scripts/check-index-drift.mjs --deployed <file.json>   # offline/tests
 *
 * Flags:
 *   --project <id>    Project to query via the Firebase CLI. Defaults to
 *                     $GOOGLE_CLOUD_PROJECT / $FIREBASE_PROJECT.
 *   --deployed <path> Read the deployed index list from a JSON file instead of
 *                     shelling out. Used by the unit tests and for debugging a
 *                     captured `firebase firestore:indexes` dump.
 *   --declared <path> Override the repo index file (default firebase/firestore.indexes.json).
 *   --json            Emit a machine-readable report on stdout instead of text.
 *
 * This script is READ-ONLY. It never deploys, and it never edits the index file.
 */

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_DECLARED = resolve(REPO_ROOT, 'firebase/firestore.indexes.json');
// firebase-tools is a dependency of the functions workspace (pnpm), not the
// root npm workspaces — same path the deploy workflows invoke.
const FIREBASE_BIN = resolve(REPO_ROOT, 'functions/node_modules/.bin/firebase');

/* ------------------------------------------------------------------ *
 * Normalisation
 * ------------------------------------------------------------------ */

/**
 * Render one index field as `path:direction`.
 *
 * Firestore fields carry EITHER an `order` (ASCENDING/DESCENDING) or an
 * `arrayConfig` (CONTAINS); the two are mutually exclusive, so one slot holds
 * whichever is present. `CONTAINS` is kept distinct from an order because an
 * array-contains index is not interchangeable with an equality index.
 */
function fieldKey(field) {
  const direction = field.order ?? field.arrayConfig ?? 'UNSPECIFIED';
  return `${field.fieldPath}:${direction}`;
}

/**
 * Drop the implicit trailing `__name__` field that the Firestore Admin API
 * returns but firestore.indexes.json omits.
 *
 * Every composite index implicitly ends with the document name, and its
 * direction follows the LAST explicitly-declared field. So a trailing
 * `__name__` that matches the preceding field's order is exactly the implicit
 * one and must be stripped, or every deployed index would look like drift.
 *
 * A trailing `__name__` whose order DIFFERS from the preceding field is NOT
 * implicit — it is a genuinely distinct index that a user can only get by
 * declaring `__name__` explicitly, and it satisfies different queries. Those we
 * keep, so the index compares as its own thing rather than being silently
 * folded into its implicit-ordering sibling.
 *
 * A single-field index consisting only of `__name__` has no preceding field to
 * inherit from, so it is left alone.
 */
function stripImplicitName(fields) {
  if (fields.length < 2) return fields;
  const last = fields[fields.length - 1];
  if (last.fieldPath !== '__name__') return fields;
  const previous = fields[fields.length - 2];
  // `previous` may be an arrayConfig field (CONTAINS), which has no order;
  // an implicit __name__ after CONTAINS is ASCENDING.
  const implicitOrder = previous.order ?? 'ASCENDING';
  return last.order === implicitOrder ? fields.slice(0, -1) : fields;
}

/**
 * Canonical identity of an index: collection group + query scope + the ordered
 * field list. Field ORDER is significant (a,b is a different index from b,a),
 * so the list is never sorted.
 *
 * `queryScope` defaults to COLLECTION because that is the Firestore default and
 * the repo file may omit it; without the default a declared entry lacking the
 * key would never match its deployed COLLECTION counterpart.
 */
export function indexKey(index) {
  const scope = index.queryScope || 'COLLECTION';
  const fields = stripImplicitName(index.fields ?? [])
    .map(fieldKey)
    .join(', ');
  return `${index.collectionGroup} [${scope}] (${fields})`;
}

/* ------------------------------------------------------------------ *
 * Parsing
 * ------------------------------------------------------------------ */

/**
 * Pull the index array out of whatever shape the input has.
 *
 * Accepted, because the CLI's output shape depends on how it was invoked and we
 * do not want the check to break on a firebase-tools upgrade:
 *   - `{ indexes: [...] }`            — the repo file and the CLI's default output
 *   - `{ result: { indexes: [...] } }`— the CLI's global `--json` envelope
 *   - `[ ... ]`                       — a bare array
 */
export function extractIndexes(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.indexes)) return payload.indexes;
  if (Array.isArray(payload?.result?.indexes)) return payload.result.indexes;
  if (Array.isArray(payload?.result)) return payload.result;
  throw new Error(
    'Could not find an index list in the input — expected { indexes: [...] }, ' +
      'a { result: { indexes: [...] } } CLI envelope, or a bare array.',
  );
}

/* ------------------------------------------------------------------ *
 * Diff
 * ------------------------------------------------------------------ */

/**
 * Compare the two index sets by canonical key.
 *
 * Duplicates are collapsed by key (a Map), which is intentional: two entries
 * with the same key ARE the same index, and Firestore would reject the second.
 */
export function diffIndexes(declared, deployed) {
  const declaredByKey = new Map(declared.map((i) => [indexKey(i), i]));
  const deployedByKey = new Map(deployed.map((i) => [indexKey(i), i]));

  const missing = [...declaredByKey.keys()].filter((k) => !deployedByKey.has(k));
  const stale = [...deployedByKey.keys()].filter((k) => !declaredByKey.has(k));

  return {
    missing, // declared in the repo, absent from the project -> queries FAIL
    stale, // present in the project, absent from the repo -> leftover
    declaredCount: declaredByKey.size,
    deployedCount: deployedByKey.size,
  };
}

/* ------------------------------------------------------------------ *
 * CLI
 * ------------------------------------------------------------------ */

function parseArgs(argv) {
  const args = { json: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--json') {
      args.json = true;
    } else if (arg === '--project' || arg === '--deployed' || arg === '--declared') {
      const value = argv[i + 1];
      if (value === undefined || value.startsWith('--')) {
        throw new Error(`${arg} requires a value`);
      }
      args[arg.slice(2)] = value;
      i += 1;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function fetchDeployedIndexes(projectId) {
  // `firestore:indexes` is a read-only listing; the caller needs
  // datastore.indexes.list on the project (the Firebase deploy service account
  // already has it via roles/datastore.owner).
  const stdout = execFileSync(
    FIREBASE_BIN,
    ['firestore:indexes', '--project', projectId, '--json'],
    // stderr passes through so an auth failure is readable in the job log
    // rather than being swallowed into an opaque non-zero exit.
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], cwd: REPO_ROOT },
  );
  return JSON.parse(stdout);
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const declaredPath = args.declared ? resolve(args.declared) : DEFAULT_DECLARED;
  const declared = extractIndexes(JSON.parse(readFileSync(declaredPath, 'utf8')));

  let deployed;
  if (args.deployed) {
    deployed = extractIndexes(JSON.parse(readFileSync(resolve(args.deployed), 'utf8')));
  } else {
    const projectId =
      args.project || process.env.GOOGLE_CLOUD_PROJECT || process.env.FIREBASE_PROJECT;
    if (!projectId) {
      throw new Error(
        'No project id — pass --project <id>, set GOOGLE_CLOUD_PROJECT, or pass ' +
          '--deployed <file.json> to diff against a captured index dump.',
      );
    }
    deployed = extractIndexes(fetchDeployedIndexes(projectId));
  }

  const result = diffIndexes(declared, deployed);

  if (args.json) {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  } else {
    console.log(
      `Compared ${result.declaredCount} declared index(es) against ${result.deployedCount} deployed.`,
    );
    if (result.stale.length > 0) {
      console.log(
        `\nWARN: ${result.stale.length} deployed index(es) are not declared in the repo.`,
      );
      console.log('These are leftovers (e.g. renamed fields). They break nothing, but cost');
      console.log('storage and write latency — delete them in the Firebase console, or add');
      console.log('them to firebase/firestore.indexes.json if they are still needed.');
      for (const key of result.stale) console.log(`  - ${key}`);
    }
    if (result.missing.length > 0) {
      console.log(`\nERROR: ${result.missing.length} declared index(es) are NOT deployed.`);
      console.log('Every query that needs one of these is currently failing in production');
      console.log('with `9 FAILED_PRECONDITION: The query requires an index`. Fix with:');
      console.log('  firebase deploy --only firestore:indexes --project <projectId>');
      for (const key of result.missing) console.log(`  - ${key}`);
    }
    if (result.missing.length === 0 && result.stale.length === 0) {
      console.log('No drift: deployed indexes match firebase/firestore.indexes.json.');
    }
  }

  // Only missing indexes are fatal — stale ones are informational (see header).
  process.exitCode = result.missing.length > 0 ? 1 : 0;
}

// Only run when invoked directly, so the unit tests can import the helpers.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(`check-index-drift: ${error.message}`);
    process.exitCode = 1;
  }
}
