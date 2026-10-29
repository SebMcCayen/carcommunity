import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, statSync, rmSync, symlinkSync } from 'node:fs';
import { join } from 'node:path';
import {
  PROJECT,
  PROJECT_NUMBER,
  REGION,
  FUNCTION_ID,
  SERVICE,
  FILTER,
  EXCLUSION_NAME,
  PERMISSIONS,
  parseArgs,
  createMetadataReader,
  collectSnapshot,
  buildPlan,
  exclusionMatches,
  writePrivateBackup,
  main,
} from './configure-club-calendar-request-logging.mjs';

const ROOT = `projects/${PROJECT}/locations/${REGION}`;
const PRIVATE = 'synthetic-private-destination';
const requiredFilter = ['cloudaudit', 'externalaudit']
  .flatMap((prefix) =>
    ['activity', 'system_event', 'access_transparency'].map(
      (id) => `LOG_ID("${prefix}.googleapis.com/${id}")`,
    ),
  )
  .join(' OR ');
const metadata = (id, service = id.toLowerCase()) => ({
  name: `${ROOT}/functions/${id}`,
  state: 'ACTIVE',
  serviceConfig: { service: `${ROOT}/services/${service}` },
});
const exclusion = () => ({ name: EXCLUSION_NAME, filter: FILTER, disabled: false });
function fixture(covered = false) {
  return {
    observedAt: 'synthetic-time',
    project: { projectId: PROJECT, projectNumber: PROJECT_NUMBER, lifecycleState: 'ACTIVE' },
    sinks: [
      { name: '_Required', destination: PRIVATE, filter: requiredFilter },
      { name: '_Default', destination: PRIVATE, exclusions: covered ? [exclusion()] : [] },
    ],
    exclusions: covered ? [exclusion()] : [],
    controls: [metadata('driver-clubsManage'), metadata('crownHunt-submitClaim')],
    feed: null,
    permissions: { permissions: [...PERMISSIONS] },
  };
}
function readFixture(snapshot = fixture()) {
  return async (kind) => {
    if (kind === 'sinks' || kind === 'exclusions') return { [kind]: snapshot[kind] };
    if (kind === 'driver-clubsManage') return snapshot.controls[0];
    if (kind === 'crownHunt-submitClaim') return snapshot.controls[1];
    if (kind === FUNCTION_ID) return snapshot.feed;
    return snapshot[kind];
  };
}

test('parser has fixed scope, dry-run default and mutually exclusive explicit modes', () => {
  assert.equal(parseArgs([]).apply, false);
  assert.equal(parseArgs(['--verify']).verify, true);
  assert.equal(parseArgs(['--apply', '--gcloud=/trusted/gcloud']).apply, true);
  for (const args of [
    ['--project=other'],
    ['--filter=severity>INFO'],
    ['--sink=_Required'],
    ['--apply', '--verify'],
    ['--apply', '--apply'],
    ['--backup-dir=relative'],
    ['--force'],
  ]) {
    assert.throws(() => parseArgs(args));
  }
});

test('filter targets exact feed service request stream across statuses and severities only', () => {
  const entry = {
    logName: `projects/${PROJECT}/logs/run.googleapis.com%2Frequests`,
    resource: {
      type: 'cloud_run_revision',
      labels: { project_id: PROJECT, location: REGION, service_name: SERVICE },
    },
  };
  const matches = (value) =>
    FILTER.split(' AND ').every((term) => {
      const [, path, wanted] = /^([\w.]+)="([^"]+)"$/.exec(term);
      return path.split('.').reduce((result, key) => result?.[key], value) === wanted;
    });
  for (const severity of ['DEBUG', 'INFO', 'WARNING', 'ERROR', 'CRITICAL']) {
    for (const status of [200, 304, 404, 405, 429, 503])
      assert.equal(
        matches({
          ...entry,
          severity,
          httpRequest: { status, requestUrl: 'synthetic-secret-never-inspected' },
        }),
        true,
      );
  }
  for (const log of [
    'run.googleapis.com%2Fstdout',
    'run.googleapis.com%2Fstderr',
    'cloudaudit.googleapis.com%2Factivity',
    'cloudaudit.googleapis.com%2Fdata_access',
  ]) {
    assert.equal(
      matches({ ...entry, severity: 'ERROR', logName: `projects/${PROJECT}/logs/${log}` }),
      false,
    );
  }
  for (const [field, value] of [
    ['service_name', 'clubcalendar-manage'],
    ['service_name', 'clubCalendar-feed'],
    ['project_id', 'demo-other'],
    ['location', 'us-central1'],
  ]) {
    const other = structuredClone(entry);
    other.resource.labels[field] = value;
    assert.equal(matches(other), false);
  }
  assert.equal(
    matches({ ...entry, resource: { ...entry.resource, type: 'cloud_function' } }),
    false,
  );
});

test('current missing exclusion is blocked by unsupported CAS even with synthetic etags', () => {
  const f = fixture();
  f.sinks.forEach((sink) => {
    sink.etag = 'synthetic';
  });
  const plan = buildPlan(f);
  assert.equal(plan.status, 'blocked');
  assert.deepEqual(plan.blockers, ['sink-etag-cas-unavailable']);
  assert.equal(plan.writeImplemented, false);
  assert.equal(plan.sinkEtagCasSupported, false);
  assert.equal(plan.activationReady, false);
  assert.equal(JSON.stringify(plan).includes(PRIVATE), false);
});

test('all non-required sinks including disabled exports must carry exact exclusion', () => {
  const f = fixture(true);
  for (const [index, disabled] of [false, true].entries()) {
    f.sinks.push({
      name: `synthetic-export-${index}`,
      disabled,
      destination: `pubsub.googleapis.com/${PRIVATE}`,
      exclusions: [],
    });
  }
  let plan = buildPlan(f);
  assert.equal(plan.nonRequiredSinkCount, 3);
  assert.equal(plan.loggingCoverageVerified, false);
  assert.equal(plan.targets.filter((target) => !target.covered).length, 2);
  assert.equal(JSON.stringify(plan).includes(PRIVATE), false);
  f.sinks.slice(2).forEach((sink) => {
    sink.exclusions = [exclusion()];
  });
  plan = buildPlan(f);
  assert.equal(plan.loggingCoverageVerified, true);
  assert.equal(plan.feedMappingVerified, false);
  assert.equal(plan.activationReady, false);
});

test('covered snapshot is idempotent verification, never deployment/activation proof', () => {
  const f = fixture(true);
  f.feed = metadata(FUNCTION_ID);
  const before = structuredClone(f);
  const plan = buildPlan(f);
  assert.equal(plan.status, 'covered-metadata-snapshot');
  assert.equal(plan.feedMappingVerified, true);
  assert.equal(plan.activationReady, false);
  assert.deepEqual(f, before);
});

test('ancestors, unexpected Required filters and mapping mismatches prevent verification', () => {
  for (const [mutate, reason] of [
    [
      (f) => {
        f.project.parent = { type: 'organization', id: 'synthetic' };
      },
      'ancestor-sinks-not-verified',
    ],
    [
      (f) => {
        f.sinks[0].filter = 'severity>=ERROR';
      },
      'required-audit-filter-unverified',
    ],
    [
      (f) => {
        f.controls[0].serviceConfig.service = `${ROOT}/services/unexpected`;
      },
      'existing-function-mapping-mismatch',
    ],
    [
      (f) => {
        f.feed = metadata(FUNCTION_ID, 'wrong-service');
      },
      'feed-function-mapping-mismatch',
    ],
  ]) {
    const f = fixture(true);
    mutate(f);
    assert.ok(buildPlan(f).blockers.includes(reason));
    assert.equal(buildPlan(f).loggingCoverageVerified, false);
  }
});

test('disabled, partial, sampled and conflicting exclusions cannot establish coverage', () => {
  for (const candidate of [
    { ...exclusion(), disabled: true },
    { ...exclusion(), filter: 'resource.type="cloud_run_revision"' },
    { ...exclusion(), filter: `${FILTER} AND severity<ERROR` },
    { ...exclusion(), filter: `${FILTER} AND sample(insertId,0.5)` },
  ]) {
    assert.equal(exclusionMatches(candidate), false);
    const f = fixture();
    f.sinks[1].exclusions = [candidate];
    f.exclusions = [candidate];
    assert.ok(buildPlan(f).blockers.includes('managed-exclusion-name-conflict'));
  }
  assert.equal(exclusionMatches({ ...exclusion(), disabled: 'false' }), false);
});

test('wrong project, duplicate/foreign sink and malformed exclusions fail closed', () => {
  for (const mutate of [
    (f) => {
      f.project.projectNumber = '123';
    },
    (f) => {
      f.sinks.push(f.sinks[1]);
    },
    (f) => {
      f.sinks[1].resourceName = 'projects/demo-other/sinks/_Default';
    },
    (f) => {
      f.sinks[1].exclusions = [null];
    },
    (f) => {
      f.sinks[1].exclusions = [{ ...exclusion(), disabled: 'false' }];
    },
  ]) {
    const f = fixture();
    mutate(f);
    assert.throws(() => buildPlan(f));
  }
});

test('permission gaps and Default inventory disagreement are reported, never repaired', () => {
  const f = fixture();
  f.permissions.permissions = ['logging.sinks.list', 'logging.exclusions.list'];
  assert.deepEqual(buildPlan(f).missingPermissions, ['logging.sinks.update']);
  assert.ok(buildPlan(f).blockers.includes('insufficient-existing-permissions'));
  const mismatch = fixture(true);
  mismatch.exclusions = [];
  assert.ok(buildPlan(mismatch).blockers.includes('default-exclusion-inventory-mismatch'));
});

test('collector paginates complete sink inventory and never requests log entries', async () => {
  const read = readFixture();
  const calls = [];
  const snapshot = await collectSnapshot(async (kind, token) => {
    calls.push(kind);
    if (kind === 'sinks' && !token)
      return { sinks: [fixture().sinks[0]], nextPageToken: 'synthetic-page' };
    if (kind === 'sinks') return { sinks: [fixture().sinks[1]] };
    return read(kind);
  });
  assert.equal(snapshot.sinks.length, 2);
  assert.equal(calls.filter((kind) => kind === 'sinks').length, 2);
  assert.equal(
    calls.some((kind) => /entries|logs|update|create/.test(kind)),
    false,
  );
});

test('collector rejects repeated or unbounded pagination without declaring completeness', async () => {
  for (const repeated of [true, false]) {
    let count = 0;
    const read = readFixture();
    await assert.rejects(() =>
      collectSnapshot((kind) =>
        kind === 'sinks'
          ? Promise.resolve({
              sinks: [],
              nextPageToken: repeated ? 'synthetic-page' : `page-${count++}`,
            })
          : read(kind),
      ),
    );
  }
});

test('empty permission-query response is an explicit insufficient-permissions plan', async () => {
  const read = readFixture();
  const snapshot = await collectSnapshot((kind) =>
    kind === 'permissions' ? Promise.resolve({}) : read(kind),
  );
  const plan = buildPlan(snapshot);
  assert.ok(plan.blockers.includes('insufficient-existing-permissions'));
  assert.deepEqual(plan.missingPermissions, [
    'logging.sinks.list',
    'logging.exclusions.list',
    'logging.sinks.update',
  ]);
});

test('transport only allows metadata GET and permission-query POST; token stays off snapshot', async () => {
  const calls = [];
  const secret = 'synthetic-token';
  const read = createMetadataReader(secret, async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers.Authorization, `Bearer ${secret}`);
    return { ok: true, json: async () => ({}) };
  });
  for (const kind of [
    'project',
    'sinks',
    'exclusions',
    'permissions',
    'driver-clubsManage',
    'crownHunt-submitClaim',
    FUNCTION_ID,
  ])
    await read(kind);
  assert.deepEqual(
    calls.map((call) => call.options.method),
    ['GET', 'GET', 'GET', 'POST', 'GET', 'GET', 'GET'],
  );
  assert.ok(calls[3].url.pathname.endsWith(':testIamPermissions'));
  assert.deepEqual(JSON.parse(calls[3].options.body), { permissions: PERMISSIONS });
  for (const route of ['entries:list', 'sinks:update', 'exclusions:create'])
    await assert.rejects(() => read(route));
});

test('transport does not inspect or print error response bodies; absent feed is metadata only', async () => {
  const denied = createMetadataReader('synthetic', async () => ({
    ok: false,
    status: 403,
    json: () => assert.fail('must not read private error body'),
  }));
  await assert.rejects(() => denied('sinks'), /permission-or-api-denied/);
  const absent = createMetadataReader('synthetic', async () => ({ ok: false, status: 404 }));
  assert.equal(await absent(FUNCTION_ID), null);
});

test('backup is private, exclusive and outside Git checkouts, including symlink paths', () => {
  const base = mkdtempSync('/tmp/cc-calendar-logging-test-');
  try {
    const backup = writePrivateBackup(fixture(), base);
    assert.equal(statSync(backup).mode & 0o777, 0o700);
    assert.equal(statSync(join(backup, 'metadata.json')).mode & 0o777, 0o600);
    assert.equal(
      JSON.parse(readFileSync(join(backup, 'metadata.json'), 'utf8')).project.projectId,
      PROJECT,
    );
    const repo = join(base, 'repo');
    mkdirSync(repo);
    writePrivateBackup(fixture(), base);
    mkdirSync(join(repo, '.git'));
    assert.throws(() => writePrivateBackup(fixture(), repo), /git-checkout-forbidden/);
    const link = join(base, 'link');
    symlinkSync(repo, link);
    assert.throws(() => writePrivateBackup(fixture(), link), /git-checkout-forbidden/);
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
});

test('CLI default/verify/apply are metadata-only; unsafe apply refuses and hides destinations', async () => {
  for (const args of [[], ['--verify'], ['--apply']]) {
    const output = [];
    const errors = [];
    let backups = 0;
    const code = await main(args, {
      collect: async () => fixture(),
      backup: () => {
        backups++;
        return '/tmp/synthetic-private-backup';
      },
      log: (value) => output.push(value),
      error: (value) => errors.push(value),
    });
    assert.equal(code, 2);
    assert.equal(backups, 1);
    const plan = JSON.parse(output[0]);
    assert.equal(plan.writeImplemented, false);
    assert.equal(plan.activationReady, false);
    assert.equal(output.join('').includes(PRIVATE), false);
    assert.equal(errors.length, args.includes('--apply') ? 1 : 0);
  }
});

test('CLI already-covered --apply is verification-only no-op, never writes', async () => {
  const output = [];
  assert.equal(
    await main(['--apply'], {
      collect: async () => fixture(true),
      backup: () => '/tmp/synthetic',
      log: (value) => output.push(value),
      error: () => assert.fail('unexpected error'),
    }),
    0,
  );
  assert.equal(JSON.parse(output[0]).loggingCoverageVerified, true);
});

test('CLI exceptions and invalid options never print raw errors or contact metadata', async () => {
  const errors = [];
  assert.equal(await main(['--help'], { collect: () => assert.fail('no reads'), log() {} }), 0);
  assert.equal(
    await main(['--project=other'], {
      collect: () => assert.fail('no reads'),
      error: (value) => errors.push(value),
    }),
    1,
  );
  assert.equal(
    await main([], {
      collect: () => {
        throw Error(PRIVATE);
      },
      error: (value) => errors.push(value),
    }),
    1,
  );
  assert.equal(errors.join('').includes(PRIVATE), false);
});
