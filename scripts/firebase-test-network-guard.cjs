'use strict';
// Loaded before test code in Node and inherited by Functions emulator workers.
// This is test-only. It must never be loaded into production Functions.
const net = require('node:net');
const dgram = require('node:dgram');
const path = require('node:path');
if (
  process.env.KCC_TEST_NO_CLOUD !== '1' ||
  !/^demo-[a-z0-9-]+$/.test(process.env.GCLOUD_PROJECT || '')
) {
  throw new Error('No-cloud tests require an explicit disposable demo project');
}
// Firebase CLI may inject discovered ADC into a Functions worker after its
// own launch. Remove it again in every preloaded child before SDK bootstrap.
for (const name of [
  'GOOGLE_APPLICATION_CREDENTIALS',
  'GOOGLE_OAUTH_ACCESS_TOKEN',
  'CLOUDSDK_AUTH_ACCESS_TOKEN',
  'CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE',
  'FIREBASE_TOKEN',
])
  delete process.env[name];
if (process.env.KCC_TEST_EMPTY_CLOUD_CONFIG)
  process.env.CLOUDSDK_CONFIG = process.env.KCC_TEST_EMPTY_CLOUD_CONFIG;
const loopback = new Set(['localhost', '127.0.0.1', '::1', '[::1]', '::ffff:127.0.0.1']);
function deny() {
  const error = new Error('Test network blocked: only local emulators are permitted');
  error.code = 'KCC_TEST_CLOUD_ACCESS_BLOCKED';
  throw error;
}
function ipc(candidate) {
  const root = process.env.KCC_TEST_IPC_ROOT;
  if (
    !root ||
    !path.isAbsolute(candidate) ||
    !path.resolve(candidate).startsWith(path.resolve(root) + path.sep)
  )
    deny();
  return 'localhost';
}
function connectHost(args) {
  let options = args[0];
  if (Array.isArray(options)) options = options[0];
  if (options && typeof options === 'object') {
    if (options.fd !== undefined) return deny();
    if (options.path) return ipc(String(options.path));
    return options.host || options.hostname || 'localhost';
  }
  if (typeof options === 'string' && !/^\d+$/.test(options)) return ipc(options);
  return typeof args[1] === 'string' ? args[1] : 'localhost';
}
const originalConnect = net.Socket.prototype.connect;
net.Socket.prototype.connect = function (...args) {
  if (!loopback.has(String(connectHost(args)).toLowerCase())) deny();
  return originalConnect.apply(this, args);
};
// No test in this harness requires outbound datagrams. Blocking UDP also closes
// an alternate transport without depending on proxy settings or DNS behavior.
dgram.Socket.prototype.send = deny;
dgram.Socket.prototype.connect = deny;
globalThis[Symbol.for('kcc.test.noCloud')] = true;
