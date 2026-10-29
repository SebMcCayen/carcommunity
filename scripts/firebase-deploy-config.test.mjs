import { test } from 'node:test';
import assert from 'node:assert/strict';
import { selectedFirestoreConfig } from './firebase-deploy-config.mjs';

const source = {
  functions: [{ source: 'functions', predeploy: ['do-not-run'] }],
  hosting: {},
  database: {},
  storage: {},
  firestore: { rules: 'firebase/firestore.rules', indexes: 'firebase/firestore.indexes.json' },
};

test('index-only publication cannot compile/publish rules or other Firebase resources', () => {
  assert.deepEqual(selectedFirestoreConfig('firestore:indexes', source), {
    firestore: { indexes: 'firebase/firestore.indexes.json' },
  });
});
test('rules-only publication cannot modify indexes and preserves a declared database', () => {
  assert.deepEqual(
    selectedFirestoreConfig('firestore:rules', {
      ...source,
      firestore: { ...source.firestore, database: '(default)' },
    }),
    { firestore: { database: '(default)', rules: 'firebase/firestore.rules' } },
  );
});
test('fails closed for widened scopes, missing declarations and unsupported database selection', () => {
  for (const target of [
    'functions',
    'hosting',
    'firestore',
    'firestore:indexes,firestore:rules',
    '',
  ])
    assert.throws(() => selectedFirestoreConfig(target, source));
  for (const config of [{}, { firestore: [] }, { firestore: {} }])
    assert.throws(() => selectedFirestoreConfig('firestore:indexes', config));
});
