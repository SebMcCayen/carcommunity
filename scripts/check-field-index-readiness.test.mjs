import { test } from 'node:test';
import assert from 'node:assert/strict';
import { expectedGroupFields, fieldIndexesReady } from './check-field-index-readiness.mjs';

const field = {
  group: 'messages',
  field: 'authorUserId',
  indexes: [{ queryScope: 'COLLECTION_GROUP', order: 'ASCENDING' }],
};
const index = {
  state: 'READY',
  queryScope: 'COLLECTION_GROUP',
  fields: [
    { fieldPath: 'authorUserId', order: 'ASCENDING' },
    { fieldPath: '__name__', order: 'ASCENDING' },
  ],
};
const actual = (value) => ({ indexConfig: { indexes: [value] } });

test('checks collection-group overrides without turning TTL or collection defaults into requirements', () => {
  assert.deepEqual(
    expectedGroupFields({
      fieldOverrides: [
        {
          collectionGroup: 'messages',
          fieldPath: 'authorUserId',
          indexes: [{ queryScope: 'COLLECTION', order: 'ASCENDING' }, ...field.indexes],
        },
        { collectionGroup: 'events', fieldPath: 'expireAt', ttl: true, indexes: [] },
      ],
    }),
    [field],
  );
  assert.deepEqual(expectedGroupFields({}), []);
});

test('requires usable ready indexes, not a merely accepted deployment', () => {
  assert.equal(fieldIndexesReady(field, actual(index)), true);
  for (const candidate of [
    { ...index, state: 'CREATING' },
    { ...index, state: 'NEEDS_REPAIR' },
    { ...index, queryScope: 'COLLECTION' },
    { ...index, fields: [] },
    { ...index, fields: [{ fieldPath: 'authorUid', order: 'ASCENDING' }] },
    { ...index, fields: [{ fieldPath: 'authorUserId', order: 'DESCENDING' }] },
  ])
    assert.equal(fieldIndexesReady(field, actual(candidate)), false);
  assert.equal(fieldIndexesReady(field, {}), false);
});

test('requires every declared direction and distinguishes array indexes', () => {
  const both = {
    ...field,
    indexes: [...field.indexes, { queryScope: 'COLLECTION_GROUP', order: 'DESCENDING' }],
  };
  assert.equal(fieldIndexesReady(both, actual(index)), false);
  const array = {
    ...field,
    indexes: [{ queryScope: 'COLLECTION_GROUP', arrayConfig: 'CONTAINS' }],
  };
  assert.equal(fieldIndexesReady(array, actual(index)), false);
  assert.equal(
    fieldIndexesReady(
      array,
      actual({ ...index, fields: [{ fieldPath: 'authorUserId', arrayConfig: 'CONTAINS' }] }),
    ),
    true,
  );
});
