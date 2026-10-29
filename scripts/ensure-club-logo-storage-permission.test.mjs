import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  PROJECT,
  PROJECT_NUMBER,
  BUCKET,
  MEMBER,
  ROLE,
  parseArgs,
  planGrant,
  verifyReadback,
  execute,
  gcloudJson,
  main,
} from './ensure-club-logo-storage-permission.mjs';

// Synthetic fixtures only: no production policy, credentials or private addresses.
const OTHER = 'user:synthetic@example.invalid';
const condition = {
  title: 'fixture',
  expression: 'request.time < timestamp("2099-01-01T00:00:00Z")',
};
const fixtures = () => ({
  project: { projectId: PROJECT, projectNumber: PROJECT_NUMBER, lifecycleState: 'ACTIVE' },
  bucket: { name: BUCKET, projectNumber: PROJECT_NUMBER },
  role: { name: ROLE, includedPermissions: ['datastore.entities.get'] },
  policy: {
    version: 3,
    etag: 'synthetic-before',
    bindings: [
      { role: 'roles/firebasestorage.serviceAgent', members: [MEMBER] },
      { role: 'roles/viewer', members: [OTHER], condition },
      { role: ROLE, members: ['serviceAccount:fixture@demo-test.iam.gserviceaccount.com'] },
    ],
    auditConfigs: [{ service: 'allServices', auditLogConfigs: [{ logType: 'ADMIN_READ' }] }],
  },
});
const plan = (f) => planGrant(f.project, f.bucket, f.role, f.policy);
function granted(before) {
  const after = structuredClone(before);
  after.etag = 'synthetic-after';
  const binding = after.bindings.find((b) => b.role === ROLE && !b.condition);
  if (binding) binding.members.push(MEMBER);
  else after.bindings.push({ role: ROLE, members: [MEMBER] });
  return after;
}

function cloudMock({ existing = false, concurrent = false, failApply = false, readback } = {}) {
  const f = fixtures();
  if (existing) f.policy = granted(f.policy);
  let policy = f.policy;
  const calls = [];
  return {
    f,
    calls,
    run(args) {
      calls.push(args);
      if (args[0] === 'projects' && args[1] === 'describe') return f.project;
      if (args[0] === 'storage') return f.bucket;
      if (args[0] === 'iam') return f.role;
      if (args[1] === 'get-iam-policy') {
        return structuredClone(
          calls.some((a) => a[1] === 'add-iam-policy-binding') && readback
            ? readback(policy)
            : policy,
        );
      }
      assert.deepEqual(args, [
        'projects',
        'add-iam-policy-binding',
        PROJECT,
        `--member=${MEMBER}`,
        `--role=${ROLE}`,
        '--condition=None',
      ]);
      if (failApply) throw Error('synthetic conflict');
      // Models another writer before the CLI's own fresh read, not a snapshot overwrite.
      if (concurrent)
        policy = {
          ...policy,
          bindings: [
            ...policy.bindings,
            { role: 'roles/browser', members: ['group:synthetic@example.invalid'] },
          ],
        };
      policy = granted(policy);
      return structuredClone(policy);
    },
  };
}

test('parser defaults to dry-run and accepts only explicit apply and an absolute executable', () => {
  assert.deepEqual(parseArgs([]), { apply: false, gcloud: 'gcloud', help: false });
  assert.deepEqual(parseArgs(['--apply', '--gcloud=/trusted/gcloud']), {
    apply: true,
    gcloud: '/trusted/gcloud',
    help: false,
  });
  for (const args of [
    ['--project=other'],
    ['--member=user:synthetic@example.invalid'],
    ['--role=roles/editor'],
    ['--force'],
    ['--apply=true'],
    ['--gcloud=relative'],
    ['--gcloud='],
    ['--apply', '--apply'],
    ['--gcloud=/one', '--gcloud=/two'],
  ]) {
    assert.throws(() => parseArgs(args));
  }
});

test('preflight requires exact active project, bucket and single-permission predefined role', () => {
  assert.equal(plan(fixtures()), 'would-grant');
  for (const mutate of [
    (f) => {
      f.project.projectId = 'demo-other';
    },
    (f) => {
      f.project.projectNumber = '123';
    },
    (f) => {
      f.project.lifecycleState = 'DELETE_REQUESTED';
    },
    (f) => {
      f.bucket.name = `${PROJECT}.appspot.com`;
    },
    (f) => {
      f.bucket.projectNumber = '123';
    },
    (f) => {
      f.role.name = 'roles/editor';
    },
    (f) => {
      f.role.deleted = true;
    },
    (f) => {
      f.role.includedPermissions = [];
    },
    (f) => {
      f.role.includedPermissions.push('datastore.entities.update');
    },
  ]) {
    const f = fixtures();
    mutate(f);
    assert.throws(() => plan(f));
  }
});

test('preflight refuses absent/wrong/conditional Storage identity and conditional-only target grant', () => {
  for (const mutate of [
    (f) => {
      f.policy.bindings.shift();
    },
    (f) => {
      f.policy.bindings[0].members = [MEMBER.replace(PROJECT_NUMBER, '123')];
    },
    (f) => {
      f.policy.bindings[0].condition = condition;
    },
    (f) => {
      f.policy.bindings.push({ role: ROLE, members: [MEMBER], condition });
    },
  ]) {
    const f = fixtures();
    mutate(f);
    assert.throws(() => plan(f));
  }
});

test('missing etag, hidden/malformed conditions and unsupported policies fail closed', () => {
  for (const mutate of [
    (f) => {
      delete f.policy.etag;
    },
    (f) => {
      f.policy.etag = '';
    },
    (f) => {
      f.policy.bindings = null;
    },
    (f) => {
      f.policy.bindings[0].members = null;
    },
    (f) => {
      f.policy.bindings[0].role = 'roles/viewer_withcond_fixture';
    },
    (f) => {
      f.policy.bindings[1].condition = null;
    },
    (f) => {
      f.policy.bindings[1].condition = {};
    },
    (f) => {
      f.policy.version = 1;
    },
    (f) => {
      f.policy.version = 99;
    },
  ]) {
    const f = fixtures();
    mutate(f);
    assert.throws(() => plan(f));
  }
});

test('default execution makes only four metadata reads and returns a safe fixed plan', () => {
  const mock = cloudMock();
  const result = execute({}, mock.run);
  assert.equal(result.status, 'would-grant');
  assert.equal(result.apply, false);
  assert.deepEqual(mock.calls, [
    ['projects', 'describe', PROJECT],
    ['storage', 'buckets', 'describe', `gs://${BUCKET}`, '--raw'],
    ['iam', 'roles', 'describe', ROLE],
    ['projects', 'get-iam-policy', PROJECT],
  ]);
  assert.equal(JSON.stringify(result).includes(OTHER), false);
  assert.equal(JSON.stringify(result).includes('synthetic-before'), false);
});

test('already-present unconditional grant is an idempotent no-op even with --apply', () => {
  for (const apply of [false, true]) {
    const mock = cloudMock({ existing: true });
    assert.equal(execute({ apply }, mock.run).status, 'already-present');
    assert.equal(mock.calls.length, 4);
  }
});

test('apply invokes exactly one fixed standard binding command then verifies fresh readback', () => {
  const mock = cloudMock({ concurrent: true });
  assert.equal(execute({ apply: true }, mock.run).status, 'verified-after-apply');
  assert.equal(mock.calls.length, 6);
  assert.equal(mock.calls.filter((a) => a[1] === 'add-iam-policy-binding').length, 1);
  assert.deepEqual(mock.calls.at(-1), ['projects', 'get-iam-policy', PROJECT]);
  assert.equal(
    mock.calls.some((a) => a.includes('set-iam-policy')),
    false,
  );
});

test('apply can create the first target-role binding without changing other bindings', () => {
  const mock = cloudMock();
  mock.f.policy.bindings = mock.f.policy.bindings.filter((b) => b.role !== ROLE);
  assert.equal(execute({ apply: true }, mock.run).status, 'verified-after-apply');
  assert.equal(mock.calls.length, 6);
});

test('apply conflict propagates without snapshot overwrite, rollback or script retry', () => {
  const mock = cloudMock({ failApply: true });
  assert.throws(() => execute({ apply: true }, mock.run));
  assert.equal(mock.calls.length, 5);
});

test('preflight failure prevents any write', () => {
  const mock = cloudMock();
  mock.f.bucket.projectNumber = '123';
  assert.throws(() => execute({ apply: true }, mock.run));
  assert.equal(
    mock.calls.some((a) => a[1] === 'add-iam-policy-binding'),
    false,
  );
});

test('readback preserves unrelated members, conditions and audit settings; tolerates ordering', () => {
  const before = fixtures().policy;
  const after = granted(before);
  after.bindings.reverse();
  after.bindings.forEach((b) => b.members.reverse());
  assert.doesNotThrow(() => verifyReadback(before, after));
  for (const mutate of [
    (p) => {
      p.bindings = p.bindings.filter((b) => b.role !== 'roles/viewer');
    },
    (p) => {
      p.bindings.find((b) => b.condition).condition.expression = 'true';
    },
    (p) => {
      p.bindings.find((b) => b.role === ROLE).members = [MEMBER];
    },
    (p) => {
      p.bindings.find((b) => b.role === ROLE).members = [OTHER];
    },
    (p) => {
      delete p.auditConfigs;
    },
  ]) {
    const candidate = granted(before);
    mutate(candidate);
    assert.throws(() => verifyReadback(before, candidate));
  }
});

test('failed post-apply readback stops without rollback or a success claim', () => {
  const mock = cloudMock({
    readback: (policy) => ({
      ...policy,
      bindings: policy.bindings.filter((b) => b.role !== 'roles/viewer'),
    }),
  });
  assert.throws(() => execute({ apply: true }, mock.run));
  assert.equal(mock.calls.length, 6);
});

test('runner uses shell-free argv, fixed project and bounded captured output', () => {
  const result = gcloudJson(
    '/trusted/gcloud',
    ['projects', 'describe', PROJECT],
    (binary, args, options) => {
      assert.equal(binary, '/trusted/gcloud');
      assert.deepEqual(args, [
        'projects',
        'describe',
        PROJECT,
        `--project=${PROJECT}`,
        '--quiet',
        '--verbosity=error',
        '--format=json',
      ]);
      assert.equal(options.shell, false);
      assert.deepEqual(options.stdio, ['ignore', 'pipe', 'pipe']);
      assert.equal(options.timeout, 60_000);
      return { status: 0, stdout: '{"ok":true}', stderr: '' };
    },
  );
  assert.deepEqual(result, { ok: true });
});

test('predefined role metadata uses global scope and fixed quota project, matching real CLI semantics', () => {
  const mock = cloudMock();
  const output = [];
  const errors = [];
  const code = main([], {
    spawn(binary, args) {
      const roleRead = args[0] === 'iam';
      // Regression: the real CLI rejects --project for predefined roles/... .
      if (roleRead && args.some((arg) => arg.startsWith('--project='))) {
        return {
          status: 1,
          stderr:
            'Invalid value for [ROLE_ID]: Should not specify the project or organization for predefined roles',
        };
      }
      assert.equal(args.includes(`--billing-project=${PROJECT}`), roleRead);
      assert.equal(args.includes(`--project=${PROJECT}`), !roleRead);
      if (roleRead) assert.deepEqual(args.slice(0, 4), ['iam', 'roles', 'describe', ROLE]);
      return { status: 0, stdout: JSON.stringify(mock.run(args.slice(0, -4))) };
    },
    log: (text) => output.push(text),
    error: (text) => errors.push(text),
  });
  assert.equal(code, 0);
  assert.deepEqual(errors, []);
  assert.equal(JSON.parse(output[0]).status, 'would-grant');
  assert.equal(mock.calls.length, 4);
});

test('CLI failures identify safe stages and categories without outputting raw data', () => {
  const stages = [
    'project-metadata',
    'bucket-metadata',
    'predefined-role-metadata',
    'iam-policy-preflight',
    'iam-grant',
    'iam-policy-readback',
  ];
  for (const [failedIndex, stage] of stages.entries()) {
    const mock = cloudMock();
    const output = [];
    const errors = [];
    let call = 0;
    const code = main(failedIndex >= 4 ? ['--apply'] : [], {
      spawn(binary, args) {
        if (call++ === failedIndex)
          return {
            status: 1,
            stderr: 'PERMISSION_DENIED synthetic-token user:synthetic-private@example.invalid',
          };
        return { status: 0, stdout: JSON.stringify(mock.run(args.slice(0, -4))) };
      },
      log: (text) => output.push(text),
      error: (text) => errors.push(text),
    });
    assert.equal(code, 1);
    assert.deepEqual(output, []);
    assert.equal(call, failedIndex + 1);
    assert.ok(errors[0].startsWith(`[${stage}]`));
    assert.ok(errors[0].includes('class=permission-denied'));
    assert.equal(errors[0].includes('synthetic-'), false);
    assert.equal(errors[0].includes('IAM may already have changed'), failedIndex >= 4);
  }
});

test('safe classification covers CLI argument conflict, timeout, missing executable and invalid JSON', () => {
  for (const [result, category] of [
    [
      {
        status: 1,
        stderr:
          'Invalid value: Should not specify the project or organization for predefined roles',
      },
      'predefined-role-scope',
    ],
    [{ status: null, error: { code: 'ETIMEDOUT' } }, 'timeout'],
    [{ status: null, error: { code: 'ENOENT' } }, 'executable-not-found'],
    [{ status: 1, stderr: 'INVALID_ARGUMENT synthetic-private' }, 'invalid-argument'],
    [{ status: 1, stderr: 'UNAUTHENTICATED synthetic-private' }, 'authentication'],
    [{ status: 1, stderr: 'NOT_FOUND synthetic-private' }, 'not-found'],
    [{ status: 1, stderr: '409 concurrent policy changes synthetic-private' }, 'conflict'],
    [{ status: 0, stdout: 'synthetic-private' }, 'invalid-json'],
  ]) {
    assert.throws(
      () => gcloudJson('/trusted/gcloud', [], () => result),
      (error) => {
        assert.ok(error.message.includes(`class=${category}`));
        assert.equal(error.message.includes('synthetic-private'), false);
        return true;
      },
    );
  }
});

test('plan and preservation failures have separate safe stage labels', () => {
  const invalid = cloudMock();
  invalid.f.bucket.projectNumber = '123';
  assert.throws(() => execute({}, invalid.run), /^Error: \[plan-validation\]/);
  const missing = cloudMock({
    readback: (policy) => ({
      ...policy,
      bindings: policy.bindings.filter((binding) => binding.role !== 'roles/viewer'),
    }),
  });
  assert.throws(() => execute({ apply: true }, missing.run), /^Error: \[readback-verification\]/);
});

test('runner redacts errors and malformed output including potential credentials/policy members', () => {
  const secret = 'synthetic-secret user:synthetic-private@example.invalid';
  for (const spawn of [
    () => ({ status: 1, stdout: secret, stderr: secret }),
    () => ({ status: 0, stdout: secret }),
    () => ({ status: null, error: Error(secret) }),
    () => {
      throw Error(secret);
    },
  ]) {
    assert.throws(
      () => gcloudJson('/trusted/gcloud', [], spawn),
      (error) => {
        assert.equal(error.message.includes(secret), false);
        return true;
      },
    );
  }
});

test('CLI help and invalid flags never spawn gcloud', () => {
  const spawn = () => assert.fail('must not spawn');
  assert.equal(main(['--help'], { spawn, log() {}, error() {} }), 0);
  assert.equal(main(['--project=demo-other'], { spawn, log() {}, error() {} }), 1);
});

test('CLI captures all subprocess policies and prints only the fixed safe summary', () => {
  const mock = cloudMock();
  const output = [];
  const errors = [];
  const code = main([], {
    spawn(binary, args) {
      assert.equal(binary, 'gcloud');
      return { status: 0, stdout: JSON.stringify(mock.run(args.slice(0, -4))), stderr: OTHER };
    },
    log: (text) => output.push(text),
    error: (text) => errors.push(text),
  });
  assert.equal(code, 0);
  assert.deepEqual(errors, []);
  assert.equal(JSON.parse(output[0]).status, 'would-grant');
  assert.equal(output.join('').includes(OTHER), false);
  assert.equal(output.join('').includes('synthetic-before'), false);
});

test('CLI apply error never reports success or prints subprocess identity/secret data', () => {
  const mock = cloudMock();
  const output = [];
  const errors = [];
  const privateFixture = 'synthetic-token user:synthetic-private@example.invalid';
  const code = main(['--apply'], {
    spawn(binary, args) {
      if (args[1] === 'add-iam-policy-binding') {
        return { status: 1, stdout: privateFixture, stderr: privateFixture };
      }
      return { status: 0, stdout: JSON.stringify(mock.run(args.slice(0, -4))) };
    },
    log: (text) => output.push(text),
    error: (text) => errors.push(text),
  });
  assert.equal(code, 1);
  assert.deepEqual(output, []);
  assert.equal(errors.length, 1);
  assert.equal(errors.join('').includes(privateFixture), false);
});

test('no-cloud guard refuses the real gcloud transport before attempting spawn', () => {
  const original = process.env.KCC_TEST_NO_CLOUD;
  process.env.KCC_TEST_NO_CLOUD = '1';
  try {
    assert.throws(() => gcloudJson('/does-not-exist', []), /no-cloud/);
  } finally {
    if (original === undefined) delete process.env.KCC_TEST_NO_CLOUD;
    else process.env.KCC_TEST_NO_CLOUD = original;
  }
});
