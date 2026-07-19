/**
 * Unit tests for the Firestore index drift check.
 *
 * These run in plain CI with no credentials — they exercise the normalisation
 * and diff logic against fixtures, which is where the real risk lives (a
 * normalisation bug would make the check either cry wolf on every deployed
 * index or, worse, report "no drift" while indexes are missing).
 */

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { diffIndexes, extractIndexes, indexKey } from './check-index-drift.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const asc = (fieldPath) => ({ fieldPath, order: 'ASCENDING' });
const desc = (fieldPath) => ({ fieldPath, order: 'DESCENDING' });
const contains = (fieldPath) => ({ fieldPath, arrayConfig: 'CONTAINS' });

test('an implicit trailing __name__ is normalised away', () => {
  const declaredForm = {
    collectionGroup: 'friendRequests',
    queryScope: 'COLLECTION',
    fields: [asc('toUid'), asc('status'), desc('createdAt')],
  };
  // What the Admin API returns: same index, plus the implicit document-name
  // field, whose direction follows the last declared field (DESCENDING here).
  const deployedForm = {
    collectionGroup: 'friendRequests',
    queryScope: 'COLLECTION',
    fields: [asc('toUid'), asc('status'), desc('createdAt'), desc('__name__')],
  };
  assert.equal(indexKey(deployedForm), indexKey(declaredForm));
});

test('an explicit __name__ contradicting the last field is kept', () => {
  // ... createdAt DESC + __name__ ASC is a genuinely different index from the
  // implicit ... createdAt DESC (+ __name__ DESC), and satisfies other queries.
  const implicit = { collectionGroup: 'c', fields: [desc('createdAt'), desc('__name__')] };
  const explicit = { collectionGroup: 'c', fields: [desc('createdAt'), asc('__name__')] };
  assert.notEqual(indexKey(explicit), indexKey(implicit));
});

test('an implicit __name__ after an array-contains field is ASCENDING', () => {
  const declaredForm = {
    collectionGroup: 'conversations',
    fields: [contains('members'), desc('lastMessageAt')],
  };
  const deployedForm = {
    collectionGroup: 'conversations',
    fields: [contains('members'), desc('lastMessageAt'), desc('__name__')],
  };
  assert.equal(indexKey(deployedForm), indexKey(declaredForm));

  // CONTAINS as the LAST field: the implicit __name__ is ASCENDING because the
  // array field carries no order of its own.
  const containsOnly = { collectionGroup: 'convoys', fields: [contains('memberUids')] };
  const containsOnlyDeployed = {
    collectionGroup: 'convoys',
    fields: [contains('memberUids'), asc('__name__')],
  };
  assert.equal(indexKey(containsOnlyDeployed), indexKey(containsOnly));
});

test('a lone __name__ field is left alone', () => {
  const index = { collectionGroup: 'c', fields: [asc('__name__')] };
  assert.equal(indexKey(index), 'c [COLLECTION] (__name__:ASCENDING)');
});

test('queryScope defaults to COLLECTION and distinguishes collection groups', () => {
  const noScope = { collectionGroup: 'items', fields: [asc('read')] };
  const collection = { collectionGroup: 'items', queryScope: 'COLLECTION', fields: [asc('read')] };
  const group = { collectionGroup: 'items', queryScope: 'COLLECTION_GROUP', fields: [asc('read')] };
  assert.equal(indexKey(noScope), indexKey(collection));
  assert.notEqual(indexKey(group), indexKey(collection));
});

test('field order is significant', () => {
  const ab = { collectionGroup: 'c', fields: [asc('a'), asc('b')] };
  const ba = { collectionGroup: 'c', fields: [asc('b'), asc('a')] };
  assert.notEqual(indexKey(ab), indexKey(ba));
});

test('CONTAINS is not interchangeable with an equality order', () => {
  const arrayIndex = { collectionGroup: 'c', fields: [contains('members'), asc('x')] };
  const orderIndex = { collectionGroup: 'c', fields: [asc('members'), asc('x')] };
  assert.notEqual(indexKey(arrayIndex), indexKey(orderIndex));
});

test('diff reports both directions, and reproduces the 2026-07-19 outage shape', () => {
  const declared = [
    { collectionGroup: 'friendRequests', fields: [asc('toUid'), asc('status'), desc('createdAt')] },
    {
      collectionGroup: 'friendRequests',
      fields: [asc('fromUid'), asc('status'), desc('createdAt')],
    },
    { collectionGroup: 'rides', fields: [asc('userId'), desc('createdAt')] },
  ];
  // Prod as it actually was: the two renamed-field leftovers deployed, the two
  // current friendRequests indexes missing (that is the `friend-list` failure).
  const deployed = [
    { collectionGroup: 'rides', fields: [asc('userId'), desc('createdAt'), desc('__name__')] },
    {
      collectionGroup: 'friendRequests',
      fields: [asc('receiverId'), asc('status'), desc('createdAt'), desc('__name__')],
    },
    {
      collectionGroup: 'friendRequests',
      fields: [asc('senderId'), asc('status'), desc('createdAt'), desc('__name__')],
    },
  ];

  const result = diffIndexes(declared, deployed);
  assert.equal(result.missing.length, 2);
  assert.ok(result.missing.every((k) => k.startsWith('friendRequests')));
  assert.ok(result.missing.some((k) => k.includes('toUid')));
  assert.equal(result.stale.length, 2);
  assert.ok(result.stale.some((k) => k.includes('receiverId')));
  // `rides` matched across the __name__ normalisation and appears in neither list.
  assert.ok(![...result.missing, ...result.stale].some((k) => k.startsWith('rides')));
  assert.equal(result.declaredCount, 3);
  assert.equal(result.deployedCount, 3);
});

test('identical sets produce no drift', () => {
  const declared = [{ collectionGroup: 'c', fields: [asc('a'), desc('b')] }];
  const deployed = [{ collectionGroup: 'c', fields: [asc('a'), desc('b'), desc('__name__')] }];
  assert.deepEqual(diffIndexes(declared, deployed), {
    missing: [],
    stale: [],
    declaredCount: 1,
    deployedCount: 1,
  });
});

test('extractIndexes accepts every shape the CLI and repo file produce', () => {
  const indexes = [{ collectionGroup: 'c', fields: [asc('a')] }];
  assert.deepEqual(extractIndexes({ indexes }), indexes);
  assert.deepEqual(extractIndexes({ status: 'success', result: { indexes } }), indexes);
  assert.deepEqual(extractIndexes(indexes), indexes);
  assert.throws(() => extractIndexes({ nope: true }), /Could not find an index list/);
});

test('the real firestore.indexes.json parses and every entry keys cleanly', () => {
  // Guards against a malformed hand-edit landing in the index file: a missing
  // fieldPath would otherwise surface as a confusing "undefined:ASCENDING" key
  // in the drift report rather than as a parse failure here.
  const file = JSON.parse(
    readFileSync(resolve(REPO_ROOT, 'firebase/firestore.indexes.json'), 'utf8'),
  );
  const indexes = extractIndexes(file);
  assert.ok(indexes.length > 0);
  const keys = new Set();
  for (const index of indexes) {
    assert.ok(index.collectionGroup, 'every index needs a collectionGroup');
    assert.ok(index.fields?.length > 0, `${index.collectionGroup} has no fields`);
    for (const field of index.fields) {
      assert.ok(field.fieldPath, `${index.collectionGroup} has a field with no fieldPath`);
      assert.ok(
        field.order || field.arrayConfig,
        `${index.collectionGroup}.${field.fieldPath} has neither order nor arrayConfig`,
      );
    }
    const key = indexKey(index);
    // A duplicate declaration is rejected by Firestore at deploy time; catching
    // it here is a much cheaper failure than a broken deploy.
    assert.ok(!keys.has(key), `duplicate index declared: ${key}`);
    keys.add(key);
  }
});
