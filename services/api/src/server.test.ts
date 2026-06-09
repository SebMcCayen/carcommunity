import assert from 'node:assert/strict';
import test from 'node:test';

import {
  IdentityProvider,
  ModerationActionType,
  OrganizationRole,
  SubscriptionEntitlement,
  UserRole,
  UserStatus,
} from '@prisma/client';
import { AUTH_PROVIDERS } from '@carcommunity/shared/auth';
import { DEFAULT_FEATURE_FLAGS } from '@carcommunity/shared/feature-flags';
import {
  MODERATION_ACTION_TYPES,
  SUBSCRIPTION_ENTITLEMENTS,
  USER_ROLES,
  USER_STATUSES,
  hasBackendAccess,
} from '@carcommunity/shared/users';
import { LOCAL_DATABASE_URL } from './config.js';
import { createServer } from './server.js';

test('shared default feature flags match the MVP baseline contract', () => {
  const expectedKeys = [
    'chat',
    'crownHunt',
    'digitalBillboards',
    'externalDataSources',
    'liveLocation',
    'partnerStats',
    'pushNotifications',
    'socialSharing',
  ] as const;

  assert.deepEqual(Object.keys(DEFAULT_FEATURE_FLAGS).sort(), [...expectedKeys].sort());

  for (const key of expectedKeys) {
    assert.equal(DEFAULT_FEATURE_FLAGS[key], true);
  }
});

test('shared auth providers are limited to apple and google', () => {
  assert.deepEqual(AUTH_PROVIDERS, ['apple', 'google']);
});

test('identity provider and organization role enums expose expected MVP values', () => {
  assert.deepEqual(new Set(Object.values(IdentityProvider)), new Set(['apple', 'google']));
  assert.deepEqual(
    new Set(Object.values(OrganizationRole)),
    new Set([
      'owner',
      'admin',
      'user',
      'moderator',
      'event_manager',
      'partner_manager',
      'support',
    ]),
  );
});

test('user foundation enums stay aligned between Prisma and shared contracts', () => {
  assert.deepEqual(new Set(Object.values(UserRole)), new Set(USER_ROLES));
  assert.deepEqual(new Set(Object.values(UserStatus)), new Set(USER_STATUSES));
  assert.deepEqual(new Set(Object.values(SubscriptionEntitlement)), new Set(SUBSCRIPTION_ENTITLEMENTS));
  assert.deepEqual(new Set(Object.values(ModerationActionType)), new Set(MODERATION_ACTION_TYPES));
});

test('suspension status always blocks backend access even with entitlement', () => {
  assert.equal(
    hasBackendAccess({
      role: 'user',
      status: 'temporarily_suspended',
      subscriptionEntitlement: 'member_monthly',
    }),
    false,
  );
  assert.equal(
    hasBackendAccess({
      role: 'user',
      status: 'permanently_suspended',
      subscriptionEntitlement: 'member_monthly',
    }),
    false,
  );
  assert.equal(
    hasBackendAccess({
      role: 'admin',
      status: 'active',
      subscriptionEntitlement: 'none',
    }),
    true,
  );
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

test('POST /v1/auth/login rejects unsupported providers', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4002,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'facebook',
        identityToken: 'placeholder-token',
      },
    });

    assert.equal(response.statusCode, 400);
    const body = response.json<{
      ok: boolean;
      error: { code: string; message: string; details?: { issues?: Array<{ path: string }> } };
    }>();

    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'validation_error');
    assert.equal(body.error.message, 'Request validation failed.');
    assert.equal(body.error.details?.issues?.[0]?.path, 'provider');
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/login does not accept placeholder login as real auth in production', async () => {
  const app = await createServer({
    nodeEnv: 'production',
    port: 4003,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: true,
  });

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'placeholder-token',
      },
    });

    assert.equal(response.statusCode, 501);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'provider_verification_not_implemented',
        message: 'Mobile provider verification is not implemented. Login is disabled in production.',
      },
    });
  } finally {
    await app.close();
  }
});

test('GET /v1/auth/me returns 501 not_implemented before sessions exist', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4004,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/auth/me',
    });

    assert.equal(response.statusCode, 501);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'not_implemented',
        message: 'Current user endpoint is not implemented until backend sessions exist.',
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

test('GET /v1/users/me returns 501 not_implemented before sessions exist', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4005,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
    });

    assert.equal(response.statusCode, 501);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'not_implemented',
        message: 'User profile endpoint is not implemented until backend sessions exist.',
      },
    });
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/users returns 501 not_implemented before admin auth exists', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4006,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users',
    });

    assert.equal(response.statusCode, 501);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'not_implemented',
        message: 'Admin users endpoint is not implemented until backend admin authorization exists.',
      },
    });
  } finally {
    await app.close();
  }
});
