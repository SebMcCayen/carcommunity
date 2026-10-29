#!/usr/bin/env node
/** Metadata-only planner/verifier. Logging v2 provides no sink etag CAS: never write. */
import { spawnSync } from 'node:child_process';
import { existsSync, realpathSync, mkdtempSync, chmodSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PROJECT = 'kungsbacka-car-community';
export const PROJECT_NUMBER = '187456469503';
export const REGION = 'europe-west1';
export const FUNCTION_ID = 'clubCalendar-feed';
export const SERVICE = 'clubcalendar-feed';
export const EXCLUSION_NAME = 'club-calendar-feed-request-url';
export const FILTER = [
  `logName="projects/${PROJECT}/logs/run.googleapis.com%2Frequests"`,
  'resource.type="cloud_run_revision"',
  `resource.labels.project_id="${PROJECT}"`,
  `resource.labels.location="${REGION}"`,
  `resource.labels.service_name="${SERVICE}"`,
].join(' AND ');
export const PERMISSIONS = [
  'logging.sinks.list',
  'logging.sinks.get',
  'logging.sinks.update',
  'logging.exclusions.list',
  'logging.exclusions.create',
  'logging.exclusions.update',
];
const ROOT = `projects/${PROJECT}/locations/${REGION}`;
const CONTROLS = ['driver-clubsManage', 'crownHunt-submitClaim'];
const AUDIT_IDS = ['cloudaudit', 'externalaudit'].flatMap((prefix) =>
  ['activity', 'system_event', 'access_transparency'].map(
    (kind) => `${prefix}.googleapis.com/${kind}`,
  ),
);
const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
class SafeError extends Error {}
const fail = (stage, category) => {
  throw new SafeError(`[${stage}] ${category}; raw output withheld.`);
};

export function parseArgs(args) {
  const options = { apply: false, verify: false, help: false, gcloud: 'gcloud', backupDir: '/tmp' };
  const seen = new Set();
  for (const arg of args) {
    const [key, ...parts] = arg.split('=');
    const value = parts.join('=');
    if (seen.has(key)) fail('arguments', 'duplicate-option');
    seen.add(key);
    if (['--apply', '--verify', '--help'].includes(arg)) options[arg.slice(2)] = true;
    else if (['--gcloud', '--backup-dir'].includes(key) && isAbsolute(value)) {
      options[key === '--gcloud' ? 'gcloud' : 'backupDir'] = value;
    } else fail('arguments', 'unsupported-option');
  }
  if (options.apply && options.verify) fail('arguments', 'conflicting-modes');
  return options;
}

/** The only POST is a permission query. There is deliberately no write route. */
export function createMetadataReader(token, transport = fetch) {
  return async (kind, pageToken = '') => {
    if (process.env.KCC_TEST_NO_CLOUD === '1' && transport === fetch) fail('transport', 'no-cloud');
    const routes = {
      project: `https://cloudresourcemanager.googleapis.com/v1/projects/${PROJECT}`,
      sinks: `https://logging.googleapis.com/v2/projects/${PROJECT}/sinks`,
      exclusions: `https://logging.googleapis.com/v2/projects/${PROJECT}/exclusions`,
      permissions: `https://cloudresourcemanager.googleapis.com/v1/projects/${PROJECT}:testIamPermissions`,
      ...Object.fromEntries(
        [...CONTROLS, FUNCTION_ID].map((id) => [
          id,
          `https://cloudfunctions.googleapis.com/v2/${ROOT}/functions/${id}`,
        ]),
      ),
    };
    if (!Object.hasOwn(routes, kind)) fail('transport', 'unsupported-metadata-route');
    const url = new URL(routes[kind]);
    if (kind === 'sinks' || kind === 'exclusions') {
      url.searchParams.set('pageSize', '1000');
      if (pageToken) url.searchParams.set('pageToken', pageToken);
    } else if (pageToken) fail('transport', 'unexpected-pagination');
    let response;
    try {
      response = await transport(url, {
        method: kind === 'permissions' ? 'POST' : 'GET',
        headers: {
          Authorization: `Bearer ${token}`,
          'x-goog-user-project': PROJECT,
          'Content-Type': 'application/json',
        },
        ...(kind === 'permissions' ? { body: JSON.stringify({ permissions: PERMISSIONS }) } : {}),
        signal: AbortSignal.timeout(40_000),
        redirect: 'error',
      });
    } catch {
      fail(kind, 'metadata-transport-failed');
    }
    if (kind === FUNCTION_ID && response.status === 404) return null;
    if (!response.ok)
      fail(kind, response.status === 403 ? 'permission-or-api-denied' : 'metadata-http-failed');
    try {
      return await response.json();
    } catch {
      fail(kind, 'invalid-json');
    }
  };
}

async function pages(read, kind) {
  const values = [];
  const tokens = new Set();
  let token = '';
  for (let page = 0; page < 20; page++) {
    const result = await read(kind, token);
    if (
      !object(result) ||
      !Array.isArray(result[kind] ?? []) ||
      (result.nextPageToken !== undefined && typeof result.nextPageToken !== 'string')
    )
      fail(kind, 'invalid-page');
    values.push(...(result[kind] ?? []));
    token = result.nextPageToken ?? '';
    if (!token) return values;
    if (tokens.has(token)) fail(kind, 'repeated-page-token');
    tokens.add(token);
  }
  fail(kind, 'bounded-inventory-incomplete');
}

export async function collectSnapshot(read) {
  const project = await read('project');
  if (
    !object(project) ||
    project.projectId !== PROJECT ||
    String(project.projectNumber) !== PROJECT_NUMBER ||
    project.lifecycleState !== 'ACTIVE'
  ) {
    fail('project', 'unexpected-project');
  }
  const permissions = await read('permissions');
  if (!object(permissions)) fail('permissions', 'invalid-permission-response');
  return {
    observedAt: new Date().toISOString(),
    project,
    sinks: await pages(read, 'sinks'),
    exclusions: await pages(read, 'exclusions'),
    controls: await Promise.all(CONTROLS.map((id) => read(id))),
    feed: await read(FUNCTION_ID),
    permissions: { ...permissions, permissions: permissions.permissions ?? [] },
  };
}

export async function collectLive(gcloud) {
  if (process.env.KCC_TEST_NO_CLOUD === '1') fail('auth', 'no-cloud');
  let result;
  try {
    result = spawnSync(
      gcloud,
      ['auth', 'print-access-token', `--project=${PROJECT}`, '--quiet', '--verbosity=error'],
      {
        shell: false,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: 60_000,
        maxBuffer: 1024 * 1024,
      },
    );
  } catch {
    fail('auth', 'credential-command-failed');
  }
  if (result.error || result.status !== 0 || !result.stdout?.trim())
    fail('auth', 'credential-command-failed');
  // Token remains in this closure, never in the snapshot or the private backup.
  return collectSnapshot(createMetadataReader(result.stdout.trim()));
}

export function exclusionMatches(exclusion) {
  return (
    object(exclusion) &&
    (exclusion.disabled === undefined || exclusion.disabled === false) &&
    typeof exclusion.filter === 'string' &&
    exclusion.filter.replace(/\s+/g, ' ').trim() === FILTER
  );
}

function requiredIsAuditOnly(sink) {
  if (
    !sink ||
    sink.disabled === true ||
    (sink.exclusions ?? []).length ||
    typeof sink.filter !== 'string'
  )
    return false;
  const terms = sink.filter
    .trim()
    .replace(/^\((.*)\)$/s, '$1')
    .split(/\s+OR\s+/i);
  return (
    terms.length === AUDIT_IDS.length &&
    AUDIT_IDS.every((id) => terms.some((term) => term.trim() === `LOG_ID("${id}")`))
  );
}

function functionMatches(metadata, id, service) {
  return (
    object(metadata) &&
    metadata.state === 'ACTIVE' &&
    metadata.name === `${ROOT}/functions/${id}` &&
    metadata.serviceConfig?.service === `${ROOT}/services/${service}`
  );
}

/** No arbitrary-filter proof: require the exact narrow exclusion on EVERY non-required sink. */
export function buildPlan(snapshot) {
  if (
    !object(snapshot) ||
    !object(snapshot.project) ||
    snapshot.project.projectId !== PROJECT ||
    String(snapshot.project.projectNumber) !== PROJECT_NUMBER ||
    snapshot.project.lifecycleState !== 'ACTIVE' ||
    !Array.isArray(snapshot.sinks) ||
    !Array.isArray(snapshot.exclusions) ||
    !Array.isArray(snapshot.controls) ||
    !Array.isArray(snapshot.permissions?.permissions) ||
    snapshot.permissions.permissions.some((permission) => typeof permission !== 'string')
  )
    fail('plan', 'incomplete-inventory');
  const blockers = [];
  // An ancestor can route independently, including non-intercepting aggregated
  // sinks not returned by a project's in_scope(ALL). Never assert coverage here.
  if (snapshot.project.parent != null) blockers.push('ancestor-sinks-not-verified');
  const names = new Set();
  for (const sink of snapshot.sinks) {
    if (
      !object(sink) ||
      typeof sink.name !== 'string' ||
      !/^[A-Za-z0-9_.-]{1,100}$/.test(sink.name) ||
      names.has(sink.name) ||
      !Array.isArray(sink.exclusions ?? []) ||
      (sink.disabled !== undefined && typeof sink.disabled !== 'boolean') ||
      (sink.resourceName && sink.resourceName !== `projects/${PROJECT}/sinks/${sink.name}`)
    )
      fail('plan', 'unexpected-sink-metadata');
    names.add(sink.name);
  }
  for (const exclusion of [
    ...snapshot.exclusions,
    ...snapshot.sinks.flatMap((sink) => sink.exclusions ?? []),
  ]) {
    if (
      !object(exclusion) ||
      typeof exclusion.name !== 'string' ||
      typeof exclusion.filter !== 'string' ||
      (exclusion.disabled !== undefined && typeof exclusion.disabled !== 'boolean')
    )
      fail('plan', 'unexpected-exclusion-metadata');
  }
  const required = snapshot.sinks.find((sink) => sink.name === '_Required');
  const defaultSink = snapshot.sinks.find((sink) => sink.name === '_Default');
  if (!requiredIsAuditOnly(required)) blockers.push('required-audit-filter-unverified');
  if (!defaultSink) blockers.push('default-sink-missing');
  const controlMappingsVerified = CONTROLS.every((id, i) =>
    functionMatches(snapshot.controls[i], id, id.toLowerCase()),
  );
  if (!controlMappingsVerified) blockers.push('existing-function-mapping-mismatch');
  const feedMappingVerified = functionMatches(snapshot.feed, FUNCTION_ID, SERVICE);
  if (snapshot.feed !== null && !feedMappingVerified)
    blockers.push('feed-function-mapping-mismatch');
  const targets = snapshot.sinks.filter((sink) => sink.name !== '_Required');
  let conflicts = 0;
  const targetPlans = targets.map((sink, index) => {
    const exclusions = sink.exclusions ?? [];
    if (exclusions.some((e) => e.name === EXCLUSION_NAME && !exclusionMatches(e))) conflicts++;
    return {
      sink: sink.name === '_Default' ? '_Default' : `custom-${index + 1}`,
      disabled: sink.disabled === true,
      covered: exclusions.some(exclusionMatches),
    };
  });
  if (conflicts) blockers.push('managed-exclusion-name-conflict');
  const defaultCoverage = (defaultSink?.exclusions ?? []).some(exclusionMatches);
  if (defaultCoverage !== snapshot.exclusions.some(exclusionMatches))
    blockers.push('default-exclusion-inventory-mismatch');
  const needsChange = targetPlans.some((target) => !target.covered);
  const granted = new Set(snapshot.permissions.permissions);
  const missingReadPermissions = ['logging.sinks.list', 'logging.exclusions.list'].filter(
    (permission) => !granted.has(permission),
  );
  const missingWritePermissions =
    needsChange && !granted.has('logging.sinks.update') ? ['logging.sinks.update'] : [];
  if (missingReadPermissions.length || missingWritePermissions.length)
    blockers.push('insufficient-existing-permissions');
  // LogSink/LogExclusion expose no etag. sinks.update exposes no conditional
  // precondition. Even a synthetic etag in a response does not enable CAS.
  if (needsChange) blockers.push('sink-etag-cas-unavailable');
  const loggingCoverageVerified = blockers.length === 0 && targets.length > 0 && !needsChange;
  return {
    project: PROJECT,
    projectNumber: PROJECT_NUMBER,
    functionId: FUNCTION_ID,
    serviceName: SERVICE,
    proposedExclusion: { name: EXCLUSION_NAME, filter: FILTER, disabled: false },
    nonRequiredSinkCount: targets.length,
    targets: targetPlans,
    blockers,
    missingPermissions: [...missingReadPermissions, ...missingWritePermissions],
    controlMappingsVerified,
    feedMappingVerified,
    loggingCoverageVerified,
    activationReady: false,
    sinkEtagCasSupported: false,
    writeImplemented: false,
    status: loggingCoverageVerified ? 'covered-metadata-snapshot' : 'blocked',
  };
}

/** Backups can contain private filters/destinations; never write them in any Git checkout. */
export function writePrivateBackup(snapshot, base = '/tmp') {
  if (!isAbsolute(base)) fail('backup', 'absolute-directory-required');
  let directory;
  try {
    directory = realpathSync(base);
  } catch {
    fail('backup', 'base-directory-unavailable');
  }
  for (let cursor = directory; ; cursor = dirname(cursor)) {
    if (existsSync(join(cursor, '.git'))) fail('backup', 'git-checkout-forbidden');
    if (dirname(cursor) === cursor) break;
  }
  let target;
  try {
    target = mkdtempSync(join(directory, 'cc-club-calendar-logging-'));
    chmodSync(target, 0o700);
    writeFileSync(join(target, 'metadata.json'), JSON.stringify(snapshot), {
      encoding: 'utf8',
      mode: 0o600,
      flag: 'wx',
    });
  } catch {
    fail('backup', 'private-backup-failed');
  }
  return target;
}

export async function main(
  args,
  {
    collect = collectLive,
    backup = writePrivateBackup,
    log = console.log,
    error = console.error,
  } = {},
) {
  try {
    const options = parseArgs(args);
    if (options.help) {
      log(
        'Usage: node scripts/configure-club-calendar-request-logging.mjs [--verify | --apply] [--gcloud=/absolute/path] [--backup-dir=/private/existing/directory]\nDefault: read-only metadata plan. Logging v2 has no sink etag CAS; --apply cannot write.',
      );
      return 0;
    }
    const snapshot = await collect(options.gcloud);
    const plan = buildPlan(snapshot);
    const backupDirectory = backup(snapshot, options.backupDir);
    log(
      JSON.stringify({
        ...plan,
        mode: options.apply ? 'apply-refused-or-noop' : options.verify ? 'verify' : 'dry-run',
        backupDirectory,
      }),
    );
    if (options.apply && !plan.loggingCoverageVerified)
      error('[apply] safe-sink-cas-unavailable-or-coverage-blocked; no configuration was changed.');
    return plan.loggingCoverageVerified ? 0 : 2;
  } catch (cause) {
    error(
      cause instanceof SafeError
        ? cause.message
        : '[metadata] unexpected-failure; raw output withheld.',
    );
    return 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
