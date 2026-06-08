import assert from 'node:assert/strict';
import test from 'node:test';

import { DEFAULT_FEATURE_FLAGS } from '@carcommunity/shared/feature-flags';
import { LOCAL_DATABASE_URL } from './config.js';
import { createServer } from './server.js';

test('shared default feature flags include the MVP baseline keys', () => {
  assert.deepEqual(Object.keys(DEFAULT_FEATURE_FLAGS).sort(), [
    'chat',
    'crownHunt',
    'digitalBillboards',
    'externalDataSources',
    'liveLocation',
    'partnerStats',
    'pushNotifications',
    'socialSharing',
  ]);

  assert.deepEqual(DEFAULT_FEATURE_FLAGS, {
    liveLocation: true,
    chat: true,
    crownHunt: true,
    partnerStats: true,
    pushNotifications: true,
    socialSharing: true,
    externalDataSources: true,
    digitalBillboards: true,
  });
});

test('GET /health returns service status', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4000,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/health',
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), {
      ok: true,
      data: {
        service: '@carcommunity/api',
        status: 'ok',
      },
    });
  } finally {
    await app.close();
  }
});

test('GET /v1/feature-flags returns all flags with metadata', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4001,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/feature-flags',
    });

    assert.equal(response.statusCode, 200);

    const body = response.json<{
      ok: boolean;
      data: { flags: Record<string, boolean>; updatedAt: string; source: string };
    }>();

    assert.equal(body.ok, true);
    assert.equal(body.data.source, 'static');
    assert.equal(typeof body.data.updatedAt, 'string');

    // All default flags must be present with their expected values.
    assert.deepEqual(body.data.flags, DEFAULT_FEATURE_FLAGS);
  } finally {
    await app.close();
  }
});
