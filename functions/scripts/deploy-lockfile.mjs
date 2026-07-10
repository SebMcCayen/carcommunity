/**
 * Deploy lockfile guard/sync for functions/package-lock.json.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * CI installs functions dependencies with pnpm against the ROOT
 * pnpm-lock.yaml (the pnpm workspace lock — see .github/dependabot.yml for
 * the lockfile-incident history). But `firebase deploy` uploads ONLY the
 * functions/ directory, so the production Cloud Build never sees that
 * lockfile: without a lockfile in functions/, it runs an UNPINNED
 * `npm install` and the deployed dependency tree can drift from what CI
 * tested. Committing an npm-format functions/package-lock.json makes Cloud
 * Build run `npm ci` instead, pinning the deployed tree.
 *
 * Two lockfiles covering one manifest can drift apart silently — exactly the
 * incident class documented in .github/dependabot.yml (#192/#198). This
 * script is the guard:
 *
 *   node scripts/deploy-lockfile.mjs check
 *     Fails (exit 1) unless
 *       1. functions/package-lock.json exists and is in sync with
 *          functions/package.json (dependencies + devDependencies), and
 *       2. every PRODUCTION dependency resolves to the SAME version as the
 *          root pnpm-lock.yaml (the CI-tested version), and
 *       3. every root pnpm override (root package.json "pnpm"."overrides")
 *          is mirrored in functions/package.json "overrides", so the npm
 *          lock applies the same transitive security pins as pnpm.
 *     Run by validate-functions CI and by the deploy workflow.
 *
 *   node scripts/deploy-lockfile.mjs sync
 *     Regenerates functions/package-lock.json, pinning the production
 *     dependencies to the versions resolved by the root pnpm-lock.yaml,
 *     then runs the check. Run this after any change to
 *     functions/package.json or to the functions importer in pnpm-lock.yaml
 *     (e.g. after `pnpm install` / a dependency bump).
 *
 * Only production dependencies must match pnpm exactly: devDependencies are
 * never installed by the Cloud Functions build (NODE_ENV=production), so for
 * them the lock only needs to be internally consistent with package.json.
 */

import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const functionsDir = dirname(dirname(fileURLToPath(import.meta.url)));
const repoRoot = dirname(functionsDir);
const manifestPath = join(functionsDir, 'package.json');
const lockPath = join(functionsDir, 'package-lock.json');
const pnpmLockPath = join(repoRoot, 'pnpm-lock.yaml');
const rootManifestPath = join(repoRoot, 'package.json');

function fail(message) {
  console.error(`deploy-lockfile: ${message}`);
  process.exit(1);
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/**
 * Extracts the resolved production dependency versions of the `functions`
 * importer from the root pnpm-lock.yaml. Deliberately narrow: it only
 * understands the stable, indentation-based shape of the importers block
 *
 *   importers:
 *     functions:
 *       dependencies:
 *         firebase-admin:
 *           specifier: ^13.0.0
 *           version: 13.10.0
 *
 * and fails loudly on anything unexpected rather than guessing.
 */
function pnpmProdVersions() {
  const lines = readFileSync(pnpmLockPath, 'utf8').split('\n');
  const versions = new Map();
  let inImporters = false;
  let inFunctions = false;
  let inDependencies = false;
  let currentDep = null;

  for (const line of lines) {
    if (/^importers:\s*$/.test(line)) {
      inImporters = true;
      continue;
    }
    if (inImporters && /^\S/.test(line) && line.trim() !== '') {
      break; // left the importers block (e.g. reached `packages:`)
    }
    if (!inImporters) continue;

    if (/^ {2}\S/.test(line)) {
      inFunctions = /^ {2}functions:\s*$/.test(line);
      inDependencies = false;
      currentDep = null;
      continue;
    }
    if (!inFunctions) continue;

    if (/^ {4}\S/.test(line)) {
      inDependencies = /^ {4}dependencies:\s*$/.test(line);
      currentDep = null;
      continue;
    }
    if (!inDependencies) continue;

    const depMatch = line.match(/^ {6}'?([^':]+)'?:\s*$/);
    if (depMatch) {
      currentDep = depMatch[1];
      continue;
    }
    const versionMatch = line.match(/^ {8}version:\s*(\S+)\s*$/);
    if (versionMatch && currentDep) {
      // Strip pnpm's peer-resolution suffix, e.g. 7.2.5(firebase-admin@13.10.0)
      versions.set(currentDep, versionMatch[1].replace(/\(.*$/, ''));
      currentDep = null;
    }
  }

  if (versions.size === 0) {
    fail(`could not parse the functions importer dependencies from ${pnpmLockPath} — has its format changed?`);
  }
  return versions;
}

function runNpm(args) {
  const result = spawnSync('npm', args, { cwd: functionsDir, stdio: 'inherit' });
  if (result.status !== 0) {
    fail(`\`npm ${args.join(' ')}\` failed with exit code ${result.status}`);
  }
}

function deepEqual(a, b) {
  return JSON.stringify(a ?? {}) === JSON.stringify(b ?? {});
}

function check() {
  if (!existsSync(lockPath)) {
    fail('functions/package-lock.json is missing. Run `pnpm run lockfile:sync` and commit the result.');
  }
  const manifest = readJson(manifestPath);
  const lock = readJson(lockPath);
  const problems = [];

  if (typeof lock.lockfileVersion !== 'number' || lock.lockfileVersion < 2) {
    problems.push(`package-lock.json lockfileVersion must be >= 2 (got ${lock.lockfileVersion}).`);
  }

  // 1. Lock root importer must mirror package.json exactly — catches a
  //    package.json edit that skipped `lockfile:sync`.
  const lockRoot = lock.packages?.[''] ?? {};
  if (!deepEqual(lockRoot.dependencies, manifest.dependencies)) {
    problems.push('package-lock.json is stale: its root "dependencies" differ from package.json.');
  }
  if (!deepEqual(lockRoot.devDependencies, manifest.devDependencies)) {
    problems.push('package-lock.json is stale: its root "devDependencies" differ from package.json.');
  }

  // 2. Production deps must resolve to the pnpm-lock (CI-tested) versions —
  //    catches the two lockfiles drifting apart after a dependency bump.
  const pnpmVersions = pnpmProdVersions();
  for (const name of Object.keys(manifest.dependencies ?? {})) {
    const pnpmVersion = pnpmVersions.get(name);
    const npmVersion = lock.packages?.[`node_modules/${name}`]?.version;
    if (!pnpmVersion) {
      problems.push(`"${name}" is in package.json but not in the pnpm-lock.yaml functions importer — run \`pnpm install\` first.`);
    } else if (npmVersion !== pnpmVersion) {
      problems.push(
        `"${name}" is ${npmVersion ?? 'missing'} in package-lock.json but ${pnpmVersion} in pnpm-lock.yaml — the deploy would not run the CI-tested version.`,
      );
    }
  }

  // 3. Root pnpm overrides (transitive security pins) must be mirrored in the
  //    functions manifest so the npm lock enforces them for the deployed tree.
  const rootOverrides = readJson(rootManifestPath).pnpm?.overrides ?? {};
  for (const [key, value] of Object.entries(rootOverrides)) {
    if ((manifest.overrides ?? {})[key] !== value) {
      problems.push(
        `root package.json pnpm override "${key}": "${value}" is not mirrored in functions/package.json "overrides" — the deployed npm tree would skip this pin.`,
      );
    }
  }

  if (problems.length > 0) {
    for (const problem of problems) console.error(`deploy-lockfile: ${problem}`);
    console.error('deploy-lockfile: run `pnpm run lockfile:sync` in functions/ and commit the result.');
    process.exit(1);
  }
  console.log('deploy-lockfile: functions/package-lock.json is in sync with package.json and pnpm-lock.yaml.');
}

function sync() {
  const manifestBefore = readFileSync(manifestPath, 'utf8');
  runNpm(['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund']);

  // npm resolves the latest versions satisfying package.json ranges, which may
  // be newer than what pnpm-lock.yaml (and therefore CI) uses. Re-pin any
  // production dep that diverged, then restore the original semver ranges —
  // npm keeps lock entries that still satisfy the manifest.
  const pnpmVersions = pnpmProdVersions();
  const manifest = JSON.parse(manifestBefore);
  let repinned = false;
  for (const name of Object.keys(manifest.dependencies ?? {})) {
    const pnpmVersion = pnpmVersions.get(name);
    if (!pnpmVersion) {
      fail(`"${name}" is in package.json but not in the pnpm-lock.yaml functions importer — run \`pnpm install\` first.`);
    }
    const npmVersion = readJson(lockPath).packages?.[`node_modules/${name}`]?.version;
    if (npmVersion !== pnpmVersion) {
      runNpm(['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund', '--save-exact', `${name}@${pnpmVersion}`]);
      repinned = true;
    }
  }
  if (repinned) {
    writeFileSync(manifestPath, manifestBefore);
    runNpm(['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund']);
  }
  check();
}

const mode = process.argv[2];
if (mode === 'check') {
  check();
} else if (mode === 'sync') {
  sync();
} else {
  fail('usage: node scripts/deploy-lockfile.mjs <check|sync>');
}
