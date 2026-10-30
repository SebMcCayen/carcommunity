import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, stat, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  CAPS,
  parseOptions,
  runBackfill,
  readState,
  writeState,
  openPrivate,
} from './backfill-club-invitations.mjs';

const args = ['--project', 'review-project', '--state', '/tmp/private-club-state.json'];
const demoArgs = [
  '--project',
  'demo-club-invites',
  '--state',
  '/tmp/private-demo-state.json',
  '--emulator',
];
const demoEnv = {
  GCLOUD_PROJECT: 'demo-club-invites',
  FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080',
  KCC_TEST_NO_CLOUD: '1',
};
const time = (value) => ({ seconds: value / 1000, nanoseconds: 0, toMillis: () => value });
function fixture(clubs, invitations) {
  let calls = 0;
  const queries = [];
  const records = new Map();
  for (let c = 0; c < clubs; c++) {
    const clubId = `c${String(c).padStart(3, '0')}`;
    records.set(`driverClubs/${clubId}`, {
      schemaVersion: 2,
      status: 'active',
      visibility: 'closed',
    });
    for (let i = 0; i < invitations; i++)
      records.set(`driverClubs/${clubId}/invitations/r${String(i).padStart(3, '0')}`, {
        createdAt: time(1000),
        expiresAt: time(10000),
      });
  }
  const snapshot = (path) => ({
    id: path.split('/').at(-1),
    exists: records.has(path),
    data: () => records.get(path),
    ref: { path, collection: (child) => query(`${path}/${child}`) },
  });
  function query(path, maximum, after) {
    return {
      orderBy: () => query(path, maximum, after),
      limit: (limit) => query(path, limit, after),
      startAfter: (id) => query(path, maximum, id),
      get: async () => {
        assert.ok(
          Number.isInteger(maximum) && maximum > 0 && maximum <= 25,
          'Every query must have a bounded limit',
        );
        queries.push({ path, maximum });
        const docs = [...records.keys()]
          .filter((key) => key.startsWith(path + '/') && !key.slice(path.length + 1).includes('/'))
          .sort()
          .map(snapshot)
          .filter((doc) => !after || doc.id > after)
          .slice(0, maximum);
        return { docs, size: docs.length };
      },
    };
  }
  const dependencies = {
    db: { collection: query, doc: (path) => ({ get: async () => snapshot(path) }) },
    service: {
      process: async () => {
        calls++;
        return 'delivered';
      },
    },
    invitationGeneration: (_club, _uid, data) => data,
    now: () => 2000,
  };
  return { dependencies, queries, calls: () => calls };
}

test('requires explicit project/state, defaults to no writes, rejects mismatched or unsafe emulator projects', () => {
  assert.equal(parseOptions(args, {}).apply, false);
  assert.throws(() =>
    parseOptions(['--state', '/tmp/state'], { GCLOUD_PROJECT: 'review-project' }),
  );
  assert.throws(() => parseOptions(args, { GCLOUD_PROJECT: 'wrong-project' }));
  assert.throws(() => parseOptions(args, { FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080' }));
  assert.throws(() =>
    parseOptions(demoArgs, { ...demoEnv, FIRESTORE_EMULATOR_HOST: 'remote.example:8080' }),
  );
  assert.throws(() =>
    parseOptions(demoArgs, { ...demoEnv, GOOGLE_APPLICATION_CREDENTIALS: 'not-read.json' }),
  );
  assert.throws(() => parseOptions([...args, '--emulator'], demoEnv));
  assert.equal(parseOptions(demoArgs, demoEnv).emulator, true);
  assert.equal(parseOptions([...args, '--apply'], {}).apply, true);
});

test('dry-run performs no service writes, reads at most 25 club docs, and keeps cursors out of aggregate output', async () => {
  const f = fixture(30, 1);
  const { summary, cursor } = await runBackfill(f.dependencies);
  assert.equal(f.calls(), 0);
  assert.equal(summary.clubsRead, CAPS.clubs);
  assert.equal(summary.invitationDocsRead, 25);
  assert.equal(summary.candidates, 25);
  assert.equal(summary.incomplete, true);
  assert.equal(cursor.clubAfter, 'c024');
  assert.ok(!JSON.stringify(summary).includes('c024'));
  assert.ok(f.queries.every((query) => query.maximum <= 25));
});

test('apply caps invitation docs at 100 and a resumed cursor processes the remainder', async () => {
  const f = fixture(1, 120);
  const first = await runBackfill({ ...f.dependencies, apply: true });
  assert.equal(first.summary.invitationDocsRead, CAPS.invitations);
  assert.equal(f.calls(), 100);
  assert.equal(first.summary.incomplete, true);
  assert.equal(first.cursor.invitationAfter, 'r099');
  const second = await runBackfill({ ...f.dependencies, apply: true, cursor: first.cursor });
  assert.equal(second.summary.invitationDocsRead, 20);
  assert.equal(f.calls(), 120);
  assert.equal(second.summary.incomplete, false);
});

test('a partial page failure keeps the last completed page cursor for receipt-safe replay', async () => {
  const f = fixture(1, 30);
  let saved;
  let calls = 0;
  f.dependencies.service.process = async () => {
    if (++calls === 27) throw new Error('retry');
    return 'delivered';
  };
  await assert.rejects(
    runBackfill({
      ...f.dependencies,
      apply: true,
      checkpoint: async (cursor) => {
        saved = cursor;
      },
    }),
    /retry/,
  );
  assert.equal(saved.invitationAfter, 'r024');
});

test('state/manifest files are exclusive 0600; dry-run state cannot skip apply candidates', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'club-backfill-test-'));
  try {
    const state = join(directory, 'state.json'),
      manifest = join(directory, 'manifest.jsonl');
    const options = parseOptions(['--project', 'review-project', '--state', state], {});
    const cursor = { clubAfter: 'club-a', clubId: null, invitationAfter: null };
    await writeState(state, options, cursor);
    assert.equal((await stat(state)).mode & 0o777, 0o600);
    assert.deepEqual(await readState(state, options), cursor);
    await assert.rejects(readState(state, { ...options, apply: true }), /scope mismatch/);
    await openPrivate(manifest, (file) => file.writeFile('private\n'));
    assert.equal((await stat(manifest)).mode & 0o777, 0o600);
    await assert.rejects(openPrivate(manifest, () => {}));
    await chmod(state, 0o644);
    await assert.rejects(readState(state, options), /private/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
