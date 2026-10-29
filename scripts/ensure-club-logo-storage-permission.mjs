#!/usr/bin/env node
/** One reviewed project-level cross-service grant; dry-run unless --apply. */
import { spawnSync } from 'node:child_process';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PROJECT = 'kungsbacka-car-community';
export const PROJECT_NUMBER = '187456469503';
export const BUCKET = `${PROJECT}.firebasestorage.app`;
export const MEMBER = `serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-firebasestorage.iam.gserviceaccount.com`;
export const ROLE = 'roles/firebaserules.firestoreServiceAgent';
const STORAGE_ROLE = 'roles/firebasestorage.serviceAgent';
const COMMON = [`--project=${PROJECT}`, '--quiet', '--verbosity=error', '--format=json'];

class SafeError extends Error {}
const refuse = (message) => {
  throw new SafeError(message);
};
const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

export function parseArgs(args) {
  const result = { apply: false, gcloud: 'gcloud', help: false };
  const seen = new Set();
  for (const arg of args) {
    const key = arg.startsWith('--gcloud=') ? '--gcloud' : arg;
    if (seen.has(key)) refuse('Duplicate option; use --help.');
    seen.add(key);
    if (arg === '--apply') result.apply = true;
    else if (arg === '--help') result.help = true;
    else if (key === '--gcloud' && isAbsolute(arg.slice('--gcloud='.length))) {
      result.gcloud = arg.slice('--gcloud='.length);
    } else
      refuse('Unsupported option. Project, principal and role cannot be overridden; use --help.');
  }
  return result;
}

/** Capture only in memory: never echo subprocess stdout/stderr, policy or credentials. */
export function gcloudJson(binary, args, spawn = spawnSync) {
  if (process.env.KCC_TEST_NO_CLOUD === '1' && spawn === spawnSync) {
    refuse('Live gcloud is disabled by the no-cloud test guard.');
  }
  let result;
  try {
    result = spawn(binary, [...args, ...COMMON], {
      encoding: 'utf8',
      shell: false,
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 60_000,
      maxBuffer: 16 * 1024 * 1024,
    });
  } catch {
    refuse('gcloud execution failed; raw output withheld.');
  }
  if (result.error || result.status !== 0) {
    refuse(
      'gcloud failed or timed out; raw output withheld. If apply started, reread IAM before retrying.',
    );
  }
  try {
    return JSON.parse(result.stdout);
  } catch {
    refuse('gcloud returned invalid JSON; raw output withheld.');
  }
}

function validatePolicy(policy) {
  if (
    !object(policy) ||
    typeof policy.etag !== 'string' ||
    !policy.etag.trim() ||
    !Array.isArray(policy.bindings) ||
    ![1, 3].includes(policy.version ?? 1)
  ) {
    refuse('IAM policy is incomplete or has an unsupported version; refusing an unsafe update.');
  }
  for (const binding of policy.bindings) {
    if (
      !object(binding) ||
      typeof binding.role !== 'string' ||
      !binding.role ||
      binding.role.includes('_withcond_') ||
      !Array.isArray(binding.members) ||
      binding.members.some((member) => typeof member !== 'string' || !member)
    ) {
      refuse('IAM binding is malformed or hides a condition; reread the full policy.');
    }
    if (
      Object.hasOwn(binding, 'condition') &&
      (!object(binding.condition) ||
        typeof binding.condition.expression !== 'string' ||
        !binding.condition.expression.trim() ||
        policy.version !== 3)
    ) {
      refuse('IAM condition is malformed or policy version is unsafe.');
    }
  }
  return policy;
}

const hasGrant = (policy, role) =>
  policy.bindings.some(
    (binding) =>
      binding.role === role &&
      !Object.hasOwn(binding, 'condition') &&
      binding.members.includes(MEMBER),
  );

/** Pure preflight: all authority comes from fixed constants, never caller supplied targets. */
export function planGrant(project, bucket, role, rawPolicy) {
  if (
    !object(project) ||
    project.projectId !== PROJECT ||
    String(project.projectNumber) !== PROJECT_NUMBER ||
    project.lifecycleState !== 'ACTIVE'
  ) {
    refuse('Unexpected project ID, project number or lifecycle state.');
  }
  if (
    !object(bucket) ||
    bucket.name !== BUCKET ||
    String(bucket.projectNumber) !== PROJECT_NUMBER
  ) {
    refuse('Unexpected Storage bucket or owning project number.');
  }
  if (
    !object(role) ||
    role.name !== ROLE ||
    role.deleted === true ||
    !Array.isArray(role.includedPermissions) ||
    role.includedPermissions.length !== 1 ||
    role.includedPermissions[0] !== 'datastore.entities.get'
  ) {
    refuse('The predefined role is missing, deleted or has unexpected permissions.');
  }
  const policy = validatePolicy(rawPolicy);
  if (!hasGrant(policy, STORAGE_ROLE)) {
    refuse(
      'Expected Storage service-agent identity/grant is absent; do not repair other identities here.',
    );
  }
  if (hasGrant(policy, ROLE)) return 'already-present';
  if (
    policy.bindings.some((binding) => binding.role === ROLE && binding.members.includes(MEMBER))
  ) {
    refuse('The target grant is conditional; refusing to widen an existing condition.');
  }
  return 'would-grant';
}

function canonical(value) {
  function sorted(entry) {
    if (Array.isArray(entry)) return entry.map(sorted);
    if (object(entry))
      return Object.fromEntries(
        Object.keys(entry)
          .sort()
          .map((key) => [key, sorted(entry[key])]),
      );
    return entry;
  }
  return JSON.stringify(sorted(value));
}

/** Verify preservation without printing any member names or policy contents. */
export function verifyReadback(before, rawAfter) {
  validatePolicy(before);
  const after = validatePolicy(rawAfter);
  if (!hasGrant(after, ROLE))
    refuse('Readback does not contain the exact unconditional target grant.');
  for (const binding of before.bindings) {
    const { members, ...properties } = binding;
    const survivors = after.bindings.filter(
      ({ members: ignored, ...candidate }) => canonical(candidate) === canonical(properties),
    );
    if (
      !members.every((member) => survivors.some((candidate) => candidate.members.includes(member)))
    ) {
      refuse(
        'Readback differs: an existing binding/condition/member is missing. Stop and review concurrent IAM activity; do not restore an old policy.',
      );
    }
  }
  // The standard binding command must preserve all other policy fields. IAM may
  // normalize version 3 to 1 when no conditions remain, and etag must change.
  for (const key of Object.keys(before).filter(
    (key) => !['bindings', 'etag', 'version'].includes(key),
  )) {
    if (canonical(before[key]) !== canonical(after[key])) {
      refuse(
        'Readback differs: existing policy metadata/audit configuration changed. Stop for review; do not overwrite it.',
      );
    }
  }
}

export function execute({ apply = false } = {}, run) {
  if (typeof apply !== 'boolean' || typeof run !== 'function') refuse('Invalid execution options.');
  const project = run(['projects', 'describe', PROJECT]);
  const bucket = run(['storage', 'buckets', 'describe', `gs://${BUCKET}`, '--raw']);
  const role = run(['iam', 'roles', 'describe', ROLE]);
  const before = run(['projects', 'get-iam-policy', PROJECT]);
  const status = planGrant(project, bucket, role, before);
  const summary = {
    project: PROJECT,
    projectNumber: PROJECT_NUMBER,
    bucket: BUCKET,
    member: MEMBER,
    role: ROLE,
  };
  if (status === 'already-present' || !apply) return { ...summary, status, apply };

  // gcloud rereads current policy, changes only this binding, and carries its
  // etag into setIamPolicy. Never submit our preflight snapshot with set-iam-policy.
  const updated = run([
    'projects',
    'add-iam-policy-binding',
    PROJECT,
    `--member=${MEMBER}`,
    `--role=${ROLE}`,
    '--condition=None',
  ]);
  verifyReadback(before, updated);
  const after = run(['projects', 'get-iam-policy', PROJECT]);
  verifyReadback(before, after);
  verifyReadback(updated, after);
  return { ...summary, status: 'verified-after-apply', apply: true };
}

export function main(args, { spawn = spawnSync, log = console.log, error = console.error } = {}) {
  try {
    const options = parseArgs(args);
    if (options.help) {
      log(
        'Usage: node scripts/ensure-club-logo-storage-permission.mjs [--apply] [--gcloud=/absolute/path]\nDefault: read-only production preflight and plan. --apply adds only the fixed cross-service grant.',
      );
      return 0;
    }
    const result = execute(options, (command) => gcloudJson(options.gcloud, command, spawn));
    log(JSON.stringify(result));
    return 0;
  } catch (cause) {
    error(
      cause instanceof SafeError
        ? cause.message
        : 'Unexpected failure; raw output withheld. If apply started, reread IAM before retrying.',
    );
    return 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
