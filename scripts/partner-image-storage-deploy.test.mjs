import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import {
  assertCommittedSources,
  ARTIFACT,
  BASELINE_SHA256,
  assertLiveSource,
  assertNarrowArtifact,
  assertPreparingPolicy,
} from './partner-image-storage-deploy.mjs';

const artifact = readFileSync(new URL(`../${ARTIFACT}`, import.meta.url), 'utf8');

test('preparation artifact preserves every unrelated live rule byte', () => {
  assert.match(assertNarrowArtifact(artifact), /^[a-f0-9]{64}$/);
  assert.equal(assertLiveSource(artifact, artifact).alreadyCurrent, true);
  assert.equal(BASELINE_SHA256.length, 64);
});

test('changing legacy member or Driver access fails the release guard', () => {
  for (const changed of [
    artifact.replace('return isAuthenticated() && isNotSuspended();', 'return false;'),
    artifact.replace(
      'match /driverThreadMedia/{userId}/{imageId}',
      'match /changedDriver/{userId}/{imageId}',
    ),
    artifact.replace('allow read: if isAuthenticated();', 'allow read: if true;'),
  ])
    assert.throws(() => assertNarrowArtifact(changed), /Unrelated Storage policy/);
});

test('live release drift never becomes an implicit rollback', () => {
  assert.throws(() => assertLiveSource(artifact + '\n// another release\n', artifact), /drifted/);
});

test('only the existing explicit preparation policy admits this artifact', () => {
  assert.doesNotThrow(() =>
    assertPreparingPolicy({
      fields: {
        enforceInitialized: { booleanValue: false },
        rolloutState: { stringValue: 'preparing' },
      },
    }),
  );
  for (const document of [
    undefined,
    {},
    { fields: {} },
    {
      fields: {
        enforceInitialized: { booleanValue: true },
        rolloutState: { stringValue: 'active' },
      },
    },
  ])
    assert.throws(() => assertPreparingPolicy(document), /preparation/);
});

test('deployment rejects untracked, staged-only and changed artifacts before cloud access', () => {
  const directory = mkdtempSync(join(tmpdir(), 'partner-image-git-'));
  const file = join(directory, 'rules');
  const git = (...args) => execFileSync('git', args, { cwd: directory, stdio: 'ignore' });
  try {
    git('init');
    git('config', 'user.name', 'Local test');
    git('config', 'user.email', 'test@example.invalid');
    writeFileSync(file, 'reviewed rules');
    assert.throws(() => assertCommittedSources(directory, ['rules']), /committed and unchanged/);
    git('add', 'rules');
    assert.throws(() => assertCommittedSources(directory, ['rules']), /committed and unchanged/);
    git('commit', '-m', 'Disposable reviewed fixture');
    assert.doesNotThrow(() => assertCommittedSources(directory, ['rules']));
    writeFileSync(file, 'unreviewed rules');
    assert.throws(() => assertCommittedSources(directory, ['rules']), /committed and unchanged/);
    git('add', 'rules');
    assert.throws(() => assertCommittedSources(directory, ['rules']), /committed and unchanged/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
