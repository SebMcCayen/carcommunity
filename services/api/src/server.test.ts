import assert from 'node:assert/strict';
import test from 'node:test';

import { createServer } from './server.js';

test('GET /health returns service status', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4000,
    databaseUrl: 'postgresql://' + 'local-user:local-password@localhost:5432/carcommunity_api?schema=public',
    isProduction: false,
  });

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

  await app.close();
});
