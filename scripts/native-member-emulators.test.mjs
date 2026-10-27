import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
const script = resolve('scripts/native-member-emulators.mjs');
test('native fixture setup rejects cloud or mixed emulator destinations before SDK access', () => {
  for (const env of [
    { GCLOUD_PROJECT: 'production', FIRESTORE_EMULATOR_HOST: '127.0.0.1:18088', FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:19098' },
    { GCLOUD_PROJECT: 'demo-kcc-ios-ci', FIRESTORE_EMULATOR_HOST: 'cloud.example:18088', FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:19098' },
    { GCLOUD_PROJECT: 'demo-kcc-ios-ci', FIRESTORE_EMULATOR_HOST: '127.0.0.1:18088', FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099' },
  ]) {
    const result = spawnSync(process.execPath, [script, 'seed'], { env: {...process.env, ...env}, encoding:'utf8' });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Exact disposable native emulators required/);
  }
});
test('prepared Firebase config resolves a real relative functions source and stays loopback', () => {
  const directory = mkdtempSync(resolve(tmpdir(), 'kcc-native-config-'));
  try {
    const result = spawnSync(process.execPath, [script, 'prepare', directory], {encoding:'utf8'});
    assert.equal(result.status, 0, result.stderr);
    const config = JSON.parse(readFileSync(resolve(directory, 'firebase.json'), 'utf8'));
    assert.equal(config.functions.source, 'functions');
    assert.match(readFileSync(resolve(directory, config.functions.source, 'index.cjs'), 'utf8'), /exports.account=\{listWarnings:warnings.listOwnWarnings\}/);
    for (const name of ['auth','functions','firestore']) assert.equal(config.emulators[name].host, '127.0.0.1');
  } finally { rmSync(directory, {recursive:true,force:true}); }
});
