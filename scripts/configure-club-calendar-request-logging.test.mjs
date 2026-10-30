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
  createExclusionWriter,
  collectSnapshot,
  buildPlan,
  exclusionMatches,
  writePrivateBackup,
  verifyPreserved,
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
    managed: covered ? exclusion() : null,
    permissions: { permissions: [...PERMISSIONS] },
  };
}
function readFixture(snapshot = fixture()) {
  return async (kind) => {
    if (kind === 'sinks' || kind === 'exclusions') return { [kind]: snapshot[kind] };
    if (kind === 'driver-clubsManage') return snapshot.controls[0];
    if (kind === 'crownHunt-submitClaim') return snapshot.controls[1];
    if (kind === FUNCTION_ID) return snapshot.feed;
    if (kind === 'managed-exclusion') return snapshot.managed;
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

test('current Default-only topology allows independent creation without a sink etag', () => {
  const f = fixture();
  f.sinks.forEach((sink) => {
    sink.etag = 'synthetic';
  });
  const plan = buildPlan(f);
  assert.equal(plan.status, 'would-create');
  assert.deepEqual(plan.blockers, []);
  assert.equal(plan.writeImplemented, true);
  assert.equal(plan.canCreate, true);
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
  assert.equal(plan.loggingCoverageVerified, false);
  assert.ok(plan.blockers.includes('additional-sinks-require-review'));
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
  assert.deepEqual(buildPlan(f).missingPermissions, [
    'logging.exclusions.get',
    'logging.exclusions.create',
  ]);
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
    'logging.exclusions.get',
    'logging.exclusions.create',
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

test('CLI default and verify never create, and summaries hide destinations', async () => {
  for (const args of [[], ['--verify']]) {
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
    assert.equal(code, args.includes('--verify') ? 2 : 0);
    assert.equal(backups, 1);
    const plan = JSON.parse(output[0]);
    assert.equal(plan.writeImplemented, true);
    assert.equal(plan.activationReady, false);
    assert.equal(output.join('').includes(PRIVATE), false);
    assert.equal(errors.length, 0);
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
  assert.equal(JSON.parse(output[0]).outcome, 'already-present');
});

function applyFixture({
  collision = false,
  conflict = false,
  mutateFresh,
  mutateAfter,
  failCreate = false,
} = {}) {
  const initial = fixture();
  const old = {
    name: 'synthetic-existing',
    filter: 'resource.type="gce_instance"',
    disabled: false,
    description: 'synthetic-private-existing-description',
  };
  initial.sinks[1].exclusions.push(structuredClone(old));
  initial.exclusions.push(structuredClone(old));
  const current = structuredClone(initial);
  const calls = { collect: 0, create: 0, get: 0, backups: 0 };
  const output = [];
  const errors = [];
  const dependencies = {
    collect: async () => {
      calls.collect++;
      if (calls.collect === 2) mutateFresh?.(current);
      if (calls.collect === 3) mutateAfter?.(current);
      return structuredClone(current);
    },
    create: async () => {
      calls.create++;
      if (failCreate) throw Error(PRIVATE);
      const record = conflict ? { ...exclusion(), filter: 'severity>=ERROR' } : exclusion();
      current.sinks[1].exclusions.push(structuredClone(record));
      current.exclusions.push(structuredClone(record));
      current.managed = record;
      return collision ? 'already-exists' : 'created';
    },
    get: async () => {
      calls.get++;
      return structuredClone(current.managed);
    },
    backup: () => {
      calls.backups++;
      return '/tmp/synthetic-backup';
    },
    log: (value) => output.push(value),
    error: (value) => errors.push(value),
  };
  return { initial, current, calls, output, errors, dependencies };
}

test('apply independently creates once after refresh/backups and verifies all readback', async () => {
  const f = applyFixture();
  assert.equal(await main(['--apply'], f.dependencies), 0);
  assert.deepEqual(f.calls, { collect: 3, create: 1, get: 1, backups: 2 });
  const result = JSON.parse(f.output[0]);
  assert.equal(result.outcome, 'created');
  assert.equal(result.loggingCoverageVerified, true);
  assert.equal(result.activationReady, false);
  assert.deepEqual(f.errors, []);
  assert.equal(f.output.join('').includes(PRIVATE), false);
  assert.doesNotThrow(() => verifyPreserved(f.initial, f.current));
});

test('409 never updates: exact named readback succeeds, differing predicate fails without rollback', async () => {
  for (const conflict of [false, true]) {
    const f = applyFixture({ collision: true, conflict });
    assert.equal(await main(['--apply'], f.dependencies), conflict ? 1 : 0);
    assert.equal(f.calls.create, 1);
    assert.equal(f.calls.get, 1);
    if (conflict) {
      assert.deepEqual(f.output, []);
      assert.ok(f.errors[0].includes('Create may have committed'));
      assert.equal(f.current.managed.filter, 'severity>=ERROR');
    } else assert.equal(JSON.parse(f.output[0]).outcome, 'already-exists');
  }
});

test('another creator completing before refresh becomes a verified no-op', async () => {
  const f = applyFixture({
    mutateFresh: (current) => {
      current.managed = exclusion();
      current.exclusions.push(exclusion());
      current.sinks[1].exclusions.push(exclusion());
    },
  });
  assert.equal(await main(['--apply'], f.dependencies), 0);
  assert.equal(f.calls.create, 0);
  assert.equal(JSON.parse(f.output[0]).outcome, 'already-present-on-refresh');
});

test('private backup failure prevents a create even with otherwise safe metadata', async () => {
  const f = applyFixture();
  f.dependencies.backup = () => {
    throw Error(PRIVATE);
  };
  assert.equal(await main(['--apply'], f.dependencies), 1);
  assert.equal(f.calls.create, 0);
  assert.equal(f.errors.join('').includes(PRIVATE), false);
});

test('fresh parent/sink/settings drift stops before create; post-create drift stops without rollback', async () => {
  for (const when of ['mutateFresh', 'mutateAfter']) {
    for (const change of [
      (current) => {
        current.project.parent = { type: 'organization', id: 'synthetic' };
      },
      (current) => {
        current.sinks.push({ name: 'synthetic-export', destination: PRIVATE });
      },
      (current) => {
        current.sinks[1].destination = 'synthetic-other-destination';
      },
      (current) => {
        current.exclusions[0].filter = 'severity>=ERROR';
      },
    ]) {
      const f = applyFixture({ [when]: change });
      assert.equal(await main(['--apply'], f.dependencies), 1);
      assert.equal(f.calls.create, when === 'mutateFresh' ? 0 : 1);
      assert.deepEqual(f.output, []);
      if (when === 'mutateAfter') assert.ok(f.current.managed, 'must not roll back new exclusion');
    }
  }
});

test('extra sinks, ancestors, collisions and missing permissions block apply without creating', async () => {
  for (const change of [
    (f) => {
      f.project.parent = { type: 'folder', id: 'synthetic' };
    },
    (f) => {
      f.sinks.push({ name: 'synthetic-export', destination: PRIVATE, disabled: true });
    },
    (f) => {
      f.permissions.permissions = [];
    },
    (f) => {
      f.exclusions = [{ ...exclusion(), disabled: true }];
      f.sinks[1].exclusions = f.exclusions;
      f.managed = f.exclusions[0];
    },
  ]) {
    const initial = fixture();
    change(initial);
    let creates = 0;
    const code = await main(['--apply'], {
      collect: async () => initial,
      create: () => {
        creates++;
        assert.fail('must not create');
      },
      backup: () => '/tmp/synthetic',
      log() {},
      error() {},
    });
    assert.equal(code, 2);
    assert.equal(creates, 0);
  }
});

test('independent concurrent exclusion additions survive while old exclusion mutations are detected', async () => {
  const f = applyFixture({
    mutateAfter: (current) => {
      const extra = { name: 'synthetic-concurrent', filter: 'resource.type="gce_instance"' };
      current.exclusions.push(extra);
      current.sinks[1].exclusions.push(extra);
    },
  });
  assert.equal(await main(['--apply'], f.dependencies), 0);
  assert.equal(f.current.exclusions.length, 3);
  assert.doesNotThrow(() => verifyPreserved(f.initial, f.current));
  const changed = structuredClone(f.current);
  changed.sinks[1].exclusions[0].description = 'changed';
  assert.throws(() => verifyPreserved(f.initial, changed), /existing-sink-exclusion-changed/);
});

test('create timeout/failure is not retried and never claims verification', async () => {
  const f = applyFixture({ failCreate: true });
  assert.equal(await main(['--apply'], f.dependencies), 1);
  assert.equal(f.calls.create, 1);
  assert.deepEqual(f.output, []);
  assert.ok(f.errors[0].includes('No rollback or automatic retry'));
  assert.equal(f.errors[0].includes(PRIVATE), false);
});

test('writer requires explicit apply and uses only fixed independent POST with exact body', async () => {
  let calls = 0;
  const transport = async (url, options) => {
    calls++;
    assert.equal(url, `https://logging.googleapis.com/v2/projects/${PROJECT}/exclusions`);
    assert.equal(options.method, 'POST');
    assert.equal(options.redirect, 'error');
    assert.deepEqual(JSON.parse(options.body), exclusion());
    assert.equal(options.headers.Authorization, 'Bearer synthetic-token');
    return { ok: true, status: 200, json: async () => exclusion() };
  };
  await assert.rejects(
    () => createExclusionWriter('synthetic-token', false, transport)(),
    /explicit-apply-required/,
  );
  assert.equal(calls, 0);
  assert.equal(await createExclusionWriter('synthetic-token', true, transport)(), 'created');
  assert.equal(calls, 1);
  const conflict = createExclusionWriter('synthetic-token', true, async () => ({
    status: 409,
    json: () => assert.fail('never inspect raw conflict payload'),
  }));
  assert.equal(await conflict(), 'already-exists');
});

test('named coverage must agree across GET, exclusion list and Default sink; capacity blocks create', () => {
  const mismatch = fixture(true);
  mismatch.managed = null;
  assert.ok(buildPlan(mismatch).blockers.includes('named-exclusion-readback-mismatch'));
  const full = fixture();
  full.exclusions = Array.from({ length: 10 }, (_, i) => ({
    name: `synthetic-${i}`,
    filter: 'severity=DEBUG',
  }));
  full.sinks[1].exclusions = structuredClone(full.exclusions);
  assert.ok(buildPlan(full).blockers.includes('default-exclusion-capacity'));
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
