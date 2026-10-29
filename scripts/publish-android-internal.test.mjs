import test from 'node:test';
import { mkdtemp, writeFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  APPROVED_BUILD,
  verifyBuildRun,
  releaseInternal,
  readApprovedBundle,
} from './publish-android-internal.mjs';

const bundle = Buffer.from('inert signed-bundle fixture; never uploaded');
const sha = createHash('sha256').update(bundle).digest('hex');
const validRun = () => ({
  id: APPROVED_BUILD.runId,
  workflow_id: APPROVED_BUILD.workflowId,
  head_sha: APPROVED_BUILD.source,
  head_branch: 'main',
  repository: { full_name: APPROVED_BUILD.repository },
  head_repository: { full_name: APPROVED_BUILD.repository },
  status: 'completed',
  conclusion: 'success',
  event: 'workflow_dispatch',
});
const track = (name, version, status = 'completed', priority = 0) => ({
  track: name,
  releases: [{ versionCodes: [String(version)], status, inAppUpdatePriority: priority }],
});

function fixture(options = {}) {
  let internal = options.internal ?? track('internal', 59);
  let pending = null,
    committed = false,
    edits = 0;
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, method: init.method, body: init.body });
    assert.equal(init.redirect, 'error');
    assert.equal(init.headers.Authorization, 'Bearer inert-token');
    const u = new URL(url);
    assert.equal(u.hostname, 'androidpublisher.googleapis.com');
    const path = u.pathname.split('/applications/com.kungsbackacarcommunity.app')[1];
    assert.ok(path, 'every request stays on the single approved package');
    const json = (value) => new Response(JSON.stringify(value), { status: 200 });
    if (options.httpError)
      return new Response(JSON.stringify({ error: { message: 'Bearer provider-secret' } }), {
        status: 403,
      });
    if (options.invalidMetadata)
      return new Response('provider-secret invalid body', { status: 200 });
    if (path === '/edits' && init.method === 'POST') return json({ id: `edit-${++edits}` });
    if (path.endsWith('/tracks'))
      return json({
        tracks: [
          internal,
          track('production', 54),
          ...(options.ambiguousAlias ? [track('qa', 59)] : []),
        ],
      });
    if (init.method === 'DELETE') {
      assert.match(path, /^\/edits\/edit-\d+$/);
      return new Response(null, { status: 204 });
    }
    if (path.endsWith('/tracks/production')) {
      assert.equal(init.method, 'GET', 'production can only be read');
      return json(track('production', committed && options.productionDrift ? 55 : 54));
    }
    if (/\/tracks\/(internal|qa)$/.test(path) && init.method === 'GET') return json(internal);
    if (path.endsWith('/bundles') && init.method === 'GET')
      return json({ bundles: options.existing ? [options.existing] : [] });
    if (path.endsWith('/bundles') && init.method === 'POST') {
      assert.match(u.pathname, /^\/upload\//);
      assert.equal(u.search, '?uploadType=media');
      assert.deepEqual(init.body, bundle);
      return json({ versionCode: options.uploadVersion ?? 60, sha256: options.uploadHash ?? sha });
    }
    if (/\/tracks\/(internal|qa)$/.test(path) && init.method === 'PUT') {
      pending = JSON.parse(init.body);
      assert.equal(pending.track, internal.track);
      assert.equal(pending.releases.length, 1);
      assert.deepEqual(pending.releases[0].versionCodes, ['60']);
      assert.equal(pending.releases[0].inAppUpdatePriority, 5);
      assert.equal(pending.releases[0].status, 'completed');
      assert.ok(pending.releases[0].releaseNotes[0].text.length <= 500);
      return json(pending);
    }
    if (path.endsWith(':validate') && init.method === 'POST') return json({ id: 'validated' });
    if (path.endsWith(':commit') && init.method === 'POST') {
      committed = true;
      internal = pending;
      if (options.commitResponseLost) throw new Error('Connection lost after commit');
      return json({ id: 'committed' });
    }
    throw new Error(`Unexpected mock operation ${init.method} ${path}`);
  };
  return {
    calls,
    invoke: (publish = false, extra = {}) =>
      releaseInternal({
        bundle,
        expectedSha256: sha,
        token: 'inert-token',
        fetchImpl,
        publish,
        ...extra,
      }),
  };
}

test('publisher binds run, repository, workflow, branch, commit and successful conclusion', () => {
  assert.equal(verifyBuildRun(validRun()).versionCode, 60);
  for (const change of [
    { id: 1 },
    { workflow_id: 1 },
    { head_sha: 'other' },
    { head_branch: 'feature' },
    { repository: { full_name: 'other/repo' } },
    { head_repository: { full_name: 'fork/repo' } },
    { status: 'in_progress' },
    { conclusion: 'failure' },
    { event: 'pull_request' },
  ])
    assert.throws(() => verifyBuildRun({ ...validRun(), ...change }));
});
test('bundle approval is checked before any provider request', async () => {
  const f = fixture();
  await assert.rejects(f.invoke(true, { expectedSha256: '0'.repeat(64) }), /SHA-256 mismatch/);
  assert.equal(f.calls.length, 0);
});
test('inspection discards only its draft and never uploads, changes a track or commits', async () => {
  const f = fixture();
  const result = await f.invoke();
  assert.equal(result.ready, true);
  assert.equal(result.published, false);
  assert.equal(f.calls.filter((c) => c.method === 'POST').length, 1);
  assert.equal(f.calls.filter((c) => c.method === 'PUT').length, 0);
  assert.equal(f.calls.at(-1).method, 'DELETE');
});
test('publication uploads only the approved bundle, changes internal and verifies production unchanged', async () => {
  const f = fixture();
  const result = await f.invoke(true);
  assert.equal(result.published, true);
  assert.equal(result.productionUnchanged, true);
  assert.equal(result.priority, 5);
  assert.equal(result.sha256, sha);
  assert.equal(f.calls.filter((c) => c.method === 'PUT').length, 1);
  assert.equal(f.calls.filter((c) => c.url.endsWith(':commit')).length, 1);
  assert.equal(f.calls.at(-1).method, 'DELETE');
});
test('unfinished, unexpected or mismatched existing releases stop before upload/commit', async () => {
  for (const options of [
    { internal: track('internal', 59, 'inProgress') },
    { internal: track('internal', 58) },
    { internal: track('internal', 60, 'completed', 0), existing: { versionCode: 60, sha256: sha } },
    { existing: { versionCode: 60, sha256: 'wrong' } },
  ]) {
    const f = fixture(options);
    await assert.rejects(f.invoke(true));
    assert.equal(
      f.calls.filter(
        (c) => c.url.endsWith(':commit') || c.method === 'PUT' || c.url.includes('/upload/'),
      ).length,
      0,
    );
    assert.equal(f.calls.at(-1).method, 'DELETE');
  }
});
test('already published same bundle/policy is idempotent without another commit', async () => {
  const f = fixture({
    internal: track('internal', 60, 'completed', 5),
    existing: { versionCode: 60, sha256: sha },
  });
  const result = await f.invoke(true);
  assert.equal(result.alreadyPublished, true);
  assert.equal(f.calls.filter((c) => c.method === 'PUT' || c.url.endsWith(':commit')).length, 0);
});
test('resolves the provider-documented qa alias, but refuses ambiguous track identities', async () => {
  const f = fixture({ internal: track('qa', 59) });
  assert.equal((await f.invoke(true)).track, 'qa');
  assert.equal(f.calls.filter((c) => c.method === 'PUT' && c.url.endsWith('/tracks/qa')).length, 1);
  const ambiguous = fixture({ ambiguousAlias: true });
  await assert.rejects(ambiguous.invoke(true), /ambiguous/);
  assert.equal(
    ambiguous.calls.filter((c) => c.method === 'PUT' || c.url.endsWith(':commit')).length,
    0,
  );
});
test('provider-confirmed upload version/hash must match before track mutation', async () => {
  for (const options of [{ uploadVersion: 61 }, { uploadHash: 'wrong' }]) {
    const f = fixture(options);
    await assert.rejects(f.invoke(true));
    assert.equal(f.calls.filter((c) => c.method === 'PUT' || c.url.endsWith(':commit')).length, 0);
  }
});
test('readback failure retains the accepted-commit fact instead of claiming no publication', async () => {
  const f = fixture({ productionDrift: true });
  await assert.rejects(f.invoke(true), (error) => error.publicationCommitted === true);
  assert.equal(f.calls.at(-1).method, 'DELETE');
});
test('provider bodies and tokens are excluded from error messages', async () => {
  for (const options of [{ httpError: true }, { invalidMetadata: true }]) {
    const f = fixture(options);
    await assert.rejects(
      f.invoke(true),
      (error) => !error.message.includes('secret') && !error.message.includes('inert-token'),
    );
  }
});

test('lost commit acknowledgement reports unknown publication instead of a false negative', async () => {
  const f = fixture({ commitResponseLost: true });
  await assert.rejects(
    f.invoke(true),
    (error) => error.publicationCommitted === null && error.publicationState === 'unknown',
  );
  assert.equal(f.calls.filter((c) => c.url.endsWith(':commit')).length, 1);
});

test('bundle descriptor rejects links, directories and undersized files before reading', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'play-bundle-'));
  try {
    const regular = join(dir, 'bundle.aab');
    const link = join(dir, 'link.aab');
    const bytes = Buffer.alloc(2048, 1);
    await writeFile(regular, bytes);
    await symlink(regular, link);
    assert.deepEqual(await readApprovedBundle(regular), bytes);
    await assert.rejects(readApprovedBundle(link));
    await assert.rejects(readApprovedBundle(dir));
    await writeFile(regular, 'small');
    await assert.rejects(readApprovedBundle(regular));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
