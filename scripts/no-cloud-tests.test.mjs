import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
const run = (code, env = {}) =>
  spawnSync(process.execPath, ['scripts/run-no-cloud.mjs', '--', process.execPath, '-e', code], {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
test('test egress rejects remote TCP, HTTPS and metadata before opening a socket', () => {
  const result = run(
    `const assert=require('node:assert/strict');for(const connect of [()=>require('node:net').connect(443,'example.com'),()=>require('node:https').get('https://firestore.googleapis.com'),()=>require('node:net').connect(80,'169.254.169.254')])assert.throws(connect,{code:'KCC_TEST_CLOUD_ACCESS_BLOCKED'});`,
  );
  assert.equal(result.status, 0, result.stderr);
});
test('test child keeps loopback access and removes credential environment', () => {
  const result = run(
    `const assert=require('node:assert/strict');assert.equal(process.env.GOOGLE_APPLICATION_CREDENTIALS,undefined);assert.equal(process.env.CLOUDSDK_AUTH_ACCESS_TOKEN,undefined);assert.equal(process.env.FIREBASE_TOKEN,undefined);assert.ok(globalThis[Symbol.for('kcc.test.noCloud')]);const net=require('node:net');const server=net.createServer(socket=>socket.end('ok')).listen(0,'127.0.0.1',()=>{const client=net.connect(server.address().port,'127.0.0.1');client.on('data',data=>{assert.equal(data.toString(),'ok');client.end();server.close();});});`,
    {
      GOOGLE_APPLICATION_CREDENTIALS: '/not/a/real/credential',
      CLOUDSDK_AUTH_ACCESS_TOKEN: 'synthetic-test-only',
      FIREBASE_TOKEN: 'synthetic-test-only',
    },
  );
  assert.equal(result.status, 0, result.stderr);
});
test('production projects are rejected before starting tests', () => {
  const result = run(`throw Error('must not execute');`, { GCLOUD_PROJECT: 'not-a-demo-project' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /disposable demo project/);
});

test('Functions IPC is limited to the private test directory', () => {
  const result = run(
    `const assert=require('node:assert/strict');const net=require('node:net');assert.throws(()=>net.connect('/cloudsql/not-permitted'),{code:'KCC_TEST_CLOUD_ACCESS_BLOCKED'});const socket=require('node:path').join(process.env.TMPDIR,'test.sock');const server=net.createServer(client=>client.end('ipc')).listen(socket,()=>{const client=net.connect(socket);client.on('data',data=>{assert.equal(data.toString(),'ipc');client.end();server.close();});});`,
  );
  assert.equal(result.status, 0, result.stderr);
});
