import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { matchesGlob } from 'node:path';
import test from 'node:test';

function workflow(name) {
  const source = readFileSync(
    new URL(`../.github/workflows/deploy-firebase-${name}.yml`, import.meta.url),
    'utf8',
  );
  const push = source.split('  push:\n')[1]?.split('  workflow_dispatch:')[0];
  assert.ok(push?.includes('    branches: [main]'));
  const paths = [...push.matchAll(/^      - '([^']+)'$/gm)].map((match) => match[1]);
  assert.ok(paths.length > 0, 'Production push must have a nonempty path filter');
  assert.ok(source.includes('  workflow_dispatch:'));
  assert.ok(source.includes('    environment: production'));
  return (path) =>
    paths.reduce((included, pattern) => {
      const excluded = pattern.startsWith('!');
      return matchesGlob(path, excluded ? pattern.slice(1) : pattern) ? !excluded : included;
    }, false);
}

const functions = workflow('functions');
const hosting = workflow('hosting');

test('mobile, docs, validation and policy-only merges do not deploy production', () => {
  for (const path of [
    'apps/ios/KCC/App.swift',
    'apps/android/app/build.gradle.kts',
    'docs/deployment.md',
    'scripts/deploy-workflow-paths.test.mjs',
    '.github/workflows/validate-ios.yml',
    '.github/workflows/deploy-firebase-functions.yml',
    '.github/workflows/deploy-firebase-hosting.yml',
    'contracts/localization/sv.json',
    'functions/src/auth/login.test.ts',
    'functions/src/auth/login.emulator.test.ts',
    'functions/certs/README.md',
    'apps/admin/src/features/map.test.tsx',
    'apps/admin/tests/map.spec.ts',
    'apps/admin/README.md',
    'packages/shared/tests/events.test.mjs',
  ]) {
    assert.equal(functions(path), false, `Functions: ${path}`);
    assert.equal(hosting(path), false, `Hosting: ${path}`);
  }
});

test('backend runtime, trust roots and build inputs still deploy Functions', () => {
  for (const path of [
    'functions/src/index.ts',
    'functions/src/auth/recordLogin.ts',
    'functions/certs/AppleRootCA-G3.cer',
    'functions/package.json',
    'functions/package-lock.json',
    'functions/tsconfig.json',
    'functions/scripts/deploy-lockfile.mjs',
    'functions/.npmrc',
  ]) {
    assert.equal(functions(path), true, path);
  }
  assert.equal(hosting('functions/src/index.ts'), false);
});

test('frontend runtime, public assets and build inputs still deploy Hosting', () => {
  for (const path of [
    'apps/admin/src/App.tsx',
    'apps/admin/index.html',
    'apps/admin/vite.config.ts',
    'apps/public-events/index.html',
    'packages/shared/src/events.ts',
    'packages/shared/package.json',
    'packages/shared/tsconfig.json',
    'package-lock.json',
    'functions/package.json',
    'functions/package-lock.json',
    'functions/.npmrc',
  ]) {
    assert.equal(hosting(path), true, path);
  }
  assert.equal(functions('apps/admin/src/App.tsx'), false);
});

test('shared deployment configuration and lockfiles trigger both targets', () => {
  for (const path of [
    'firebase.json',
    '.firebaserc',
    'package.json',
    'pnpm-lock.yaml',
    'pnpm-workspace.yaml',
    '.npmrc',
    'tsconfig.base.json',
  ]) {
    assert.equal(functions(path), true, path);
    assert.equal(hosting(path), true, path);
  }
  // GitHub triggers a mixed commit when any changed path matches.
  assert.ok(['apps/ios/KCC/App.swift', 'functions/src/index.ts'].some(functions));
});

test('legal compatibility pages deploy Hosting without redeploying Functions', () => {
  for (const path of [
    'apps/admin/public/privacy/index.html',
    'apps/admin/public/terms/index.html',
    'apps/admin/public/support/index.html',
  ]) {
    assert.equal(hosting(path), true, path);
    assert.equal(functions(path), false, path);
  }
});
