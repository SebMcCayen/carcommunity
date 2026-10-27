#!/usr/bin/env node
/** Run test processes with loopback-only Node transport and no cloud credentials. */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const args = process.argv.slice(2);
if (args[0] === '--') args.shift();
if (!args.length) throw Error('Expected a test command after --');
const originalHome = homedir();
const directory = mkdtempSync(resolve(tmpdir(), 'kcc-no-cloud-'));
const home = resolve(directory, 'home');
mkdirSync(home);
const env = { ...process.env };
for (const name of Object.keys(env))
  if (
    name.startsWith('GOOGLE_') ||
    name.startsWith('CLOUDSDK_') ||
    ['FIREBASE_TOKEN', 'GCLOUD_ACCESS_TOKEN', 'GCE_METADATA_HOST', 'GCE_METADATA_IP'].includes(name)
  )
    delete env[name];
const project = env.GCLOUD_PROJECT ?? 'demo-test';
if (!/^demo-[a-z0-9-]+$/.test(project)) throw Error('Tests may only use a disposable demo project');
if (env.FIREBASE_CONFIG) {
  let config;
  try {
    config = JSON.parse(env.FIREBASE_CONFIG);
  } catch {
    throw Error('Tests reject non-demo Firebase configuration');
  }
  if (config.projectId !== project) throw Error('Tests reject non-demo Firebase configuration');
}
const guard = resolve(dirname(fileURLToPath(import.meta.url)), 'firebase-test-network-guard.cjs');
Object.assign(env, {
  KCC_TEST_NO_CLOUD: '1',
  KCC_TEST_EMPTY_CLOUD_CONFIG: resolve(home, 'empty-gcloud'),
  KCC_TEST_IPC_ROOT: directory,
  TMPDIR: directory,
  GCLOUD_PROJECT: project,
  GOOGLE_CLOUD_PROJECT: project,
  CLOUDSDK_CONFIG: resolve(home, 'empty-gcloud'),
  XDG_CONFIG_HOME: resolve(home, 'empty-config'),
  FIREBASE_EMULATORS_PATH:
    process.env.FIREBASE_EMULATORS_PATH ?? resolve(originalHome, '.cache/firebase/emulators'),
  FIREBASE_CLI_DISABLE_TELEMETRY: '1',
  FIREBASE_CLI_DISABLE_UPDATE_CHECK: '1',
  CI: env.CI ?? 'true',
  NODE_OPTIONS: `${env.NODE_OPTIONS ?? ''} --require ${JSON.stringify(guard)}`.trim(),
});
const child = spawn(args[0], args.slice(1), { stdio: 'inherit', env, shell: false });
let done = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('error', () => {
  if (done) return;
  done = true;
  rmSync(directory, { recursive: true, force: true });
  process.exitCode = 1;
});
child.on('exit', (code, signal) => {
  if (done) return;
  done = true;
  rmSync(directory, { recursive: true, force: true });
  process.exitCode = signal ? 1 : (code ?? 1);
});
