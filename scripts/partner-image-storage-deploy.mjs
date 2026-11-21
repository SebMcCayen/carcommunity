/** Explicit, preparation-compatible business-image rules release. No default deploy changes. */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

export const PROJECT = 'kungsbacka-car-community';
export const BASELINE_SHA256 = 'ed288a02b7f74777e5d4dc18f840a8bf81b2b1fbe4020c5d5c622f06a83ccaeb';
export const ARTIFACT = 'firebase/storage.partner-images-preparing.rules';
export const CONFIG = 'firebase.partner-images-preparing.json';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const hash = (value) => createHash('sha256').update(value).digest('hex');
const paths = [
  'companyImages/{companyId}/{imageId}',
  'billboardImages/{billboardId}/{imageId}',
  'offerImages/{companyId}/{imageId}',
];

/** Any edit outside the reviewed helper and three write predicates fails closed. */
export function assertNarrowArtifact(source) {
  const start = source.indexOf('    // Preparation-compatible authority for business images only.');
  const end = source.indexOf('    // Mirrors the Firestore rules helpers:');
  if (start < 0 || end < start) throw Error('Missing scoped authority helper.');
  let baseline = source.slice(0, start) + source.slice(end);
  for (const path of paths) {
    const match = `    match /${path} {\n      allow read: if isAuthenticated();\n      allow write: if isCurrentPartnerImageAdmin();\n    }`;
    if (baseline.split(match).length !== 2) throw Error(`Unexpected image match: ${path}`);
    baseline = baseline.replace(match, match.replace('isCurrentPartnerImageAdmin()', 'isAdmin()'));
  }
  if (hash(baseline) !== BASELINE_SHA256)
    throw Error('Unrelated Storage policy differs from reviewed baseline.');
  return hash(source);
}

export function assertLiveSource(live, artifact) {
  const artifactHash = assertNarrowArtifact(artifact);
  const liveHash = hash(live);
  if (![BASELINE_SHA256, artifactHash].includes(liveHash)) {
    throw Error(
      'Live Storage rules drifted; refusing to overwrite. Reconcile and review a new artifact.',
    );
  }
  return { liveHash, artifactHash, alreadyCurrent: liveHash === artifactHash };
}

export function assertPreparingPolicy(document) {
  if (
    document?.fields?.enforceInitialized?.booleanValue !== false ||
    document?.fields?.rolloutState?.stringValue !== 'preparing'
  )
    throw Error(
      'Separate access rollout is no longer in preparation; do not use this temporary artifact.',
    );
}

function command(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', ...options });
  if (result.error || result.status !== 0) throw Error(`Command failed: ${command}`);
  return result.stdout;
}

/** Production artifacts must already belong to the immutable reviewed commit. */
export function assertCommittedSources(repositoryRoot, paths) {
  try {
    for (const path of paths)
      command('git', ['cat-file', '-e', `HEAD:${path}`], { cwd: repositoryRoot });
    command('git', ['diff', '--exit-code', 'HEAD', '--', ...paths], { cwd: repositoryRoot });
  } catch {
    throw Error('Deployment sources must be committed and unchanged.');
  }
}

async function main() {
  const mode = process.argv[2];
  if (!['verify', 'deploy'].includes(mode) || process.argv.length !== 3) {
    throw Error('Usage: node scripts/partner-image-storage-deploy.mjs verify|deploy');
  }
  const source = readFileSync(resolve(root, ARTIFACT), 'utf8');
  assertNarrowArtifact(source);
  const config = JSON.parse(readFileSync(resolve(root, CONFIG), 'utf8'));
  if (JSON.stringify(config) !== JSON.stringify({ storage: { rules: ARTIFACT } })) {
    throw Error('Configuration must contain only the scoped Storage artifact.');
  }
  // A local edit must not silently become production code after PR verification.
  assertCommittedSources(root, [ARTIFACT, CONFIG, 'scripts/partner-image-storage-deploy.mjs']);
  const token = command(process.env.KCC_GCLOUD_BIN ?? 'gcloud', [
    'auth',
    'print-access-token',
  ]).trim();
  if (!token) throw Error('No existing operator access token.');
  async function get(url) {
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}`, 'x-goog-user-project': PROJECT },
    });
    if (!response.ok) throw Error(`Read-only deployment preflight failed (${response.status}).`);
    return response.json();
  }
  const policyUrl = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/carCommunityPolicy/access`;
  assertPreparingPolicy(await get(policyUrl));
  const releaseUrl = `https://firebaserules.googleapis.com/v1/projects/${PROJECT}/releases/firebase.storage/${PROJECT}.firebasestorage.app`;
  const release = await get(releaseUrl);
  const ruleset = await get(`https://firebaserules.googleapis.com/v1/${release.rulesetName}`);
  const files = ruleset?.source?.files;
  if (files?.length !== 1) throw Error('Expected a single reviewed Storage source file.');
  const checked = assertLiveSource(files[0].content, source);
  console.log(JSON.stringify({ project: PROJECT, ...checked, mode }));
  if (mode === 'verify' || checked.alreadyCurrent) return;
  // The normal broad deployment workflows must stay paused for this controlled window.
  // No --force and no functions, Hosting, Firestore, policy or migration writes.
  command(
    process.execPath,
    [
      resolve(root, 'functions/node_modules/firebase-tools/lib/bin/firebase.js'),
      'deploy',
      '--only',
      'storage',
      '--config',
      CONFIG,
      '--project',
      PROJECT,
      '--non-interactive',
    ],
    { stdio: 'inherit' },
  );
  const after = await get(releaseUrl);
  const final = await get(`https://firebaserules.googleapis.com/v1/${after.rulesetName}`);
  if (final?.source?.files?.length !== 1 || final.source.files[0].content !== source) {
    throw Error('Storage release readback does not match the reviewed artifact.');
  }
  assertPreparingPolicy(await get(policyUrl));
  console.log('Scoped Storage source verified; access preparation policy preserved.');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
