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
import type {
  AuthService,
  AuthenticatedSession,
  CreatedSession,
  ProviderIdentityLoginInput,
} from './lib/auth-service.js';

function createFakeAuthService(): AuthService {
  const usersByProviderSubject = new Map<string, { userId: string }>();
  const userProfiles = new Map<
    string,
    {
      userId: string;
      displayName: string | null;
      role: 'user' | 'admin' | 'owner';
      status: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
      subscriptionEntitlement: 'none' | 'member_monthly';
      identities: Array<{ provider: 'apple' | 'google'; providerSubject: string }>;
      lastActiveAt: string | null;
    }
  >();
  const sessions = new Map<string, AuthenticatedSession>();
  let userCounter = 0;
  let sessionCounter = 0;

  return {
    async findOrCreateUserByProviderIdentity(input: ProviderIdentityLoginInput) {
      const key = `${input.provider}:${input.providerSubject}`;
      let existing = usersByProviderSubject.get(key);

      if (!existing) {
        userCounter += 1;
        const userId = `user-${userCounter}`;
        existing = { userId };
        usersByProviderSubject.set(key, existing);
        userProfiles.set(userId, {
          userId,
          displayName: null,
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
          identities: [{ provider: input.provider, providerSubject: input.providerSubject }],
          lastActiveAt: null,
        });
      }

      const profile = userProfiles.get(existing.userId)!;
      return {
        userId: profile.userId,
        displayName: profile.displayName,
        identities: profile.identities,
        roles: [profile.role],
      };
    },

    async createSession(userId: string): Promise<CreatedSession> {
      sessionCounter += 1;
      const token = `dev-token-${sessionCounter}`;
      const expiresAt = new Date(Date.now() + 1000 * 60 * 60);
      const profile = userProfiles.get(userId)!;
      const sessionId = `session-${sessionCounter}`;
      sessions.set(token, {
        sessionId,
        userId,
        role: profile.role,
        status: profile.status,
        subscriptionEntitlement: profile.subscriptionEntitlement,
        displayName: profile.displayName,
        lastActiveAt: new Date(),
        expiresAt,
        user: {
          userId: profile.userId,
          displayName: profile.displayName,
          identities: profile.identities,
          roles: [profile.role],
        },
      });

      return {
        sessionId,
        expiresAt,
        token: {
          _devOnly: true,
          accessToken: token,
          expiresIn: 3600,
        },
      };
    },

    async lookupSession(rawToken: string) {
      return sessions.get(rawToken) ?? null;
    },

    async revokeSession(rawToken: string) {
      return sessions.delete(rawToken);
    },
  };
}

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

test('GET /v1/auth/me returns unauthenticated when no valid session exists', async () => {
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

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'unauthenticated',
        message: 'No valid authenticated session.',
      },
    });
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/login creates or finds user by provider + providerSubject', async () => {
  const authService = createFakeAuthService();
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4043,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { authService },
  );

  try {
    const first = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'placeholder-token',
        providerSubject: 'stable-subject-1',
      },
    });
    const second = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'placeholder-token',
        providerSubject: 'stable-subject-1',
      },
    });

    assert.equal(first.statusCode, 200);
    assert.equal(second.statusCode, 200);

    const firstBody = first.json<{ ok: true; data: { user: { userId: string }; token: { accessToken: string } } }>();
    const secondBody = second.json<{ ok: true; data: { user: { userId: string }; token: { accessToken: string } } }>();

    assert.equal(firstBody.data.user.userId, secondBody.data.user.userId);
    assert.notEqual(firstBody.data.token.accessToken, secondBody.data.token.accessToken);
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/login does not use email as identity key', async () => {
  const authService = createFakeAuthService();
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4044,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { authService },
  );

  try {
    const apple = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'placeholder-token',
        providerSubject: 'same-email-subject-a',
      },
    });
    const google = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'google',
        identityToken: 'placeholder-token',
        providerSubject: 'same-email-subject-b',
      },
    });

    const appleBody = apple.json<{ ok: true; data: { user: { userId: string } } }>();
    const googleBody = google.json<{ ok: true; data: { user: { userId: string } } }>();

    assert.notEqual(appleBody.data.user.userId, googleBody.data.user.userId);
  } finally {
    await app.close();
  }
});

test('GET /v1/auth/me returns user for a valid session token', async () => {
  const authService = createFakeAuthService();
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4045,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { authService },
  );

  try {
    const login = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'placeholder-token',
        providerSubject: 'subject-auth-me',
      },
    });
    const token = login.json<{ ok: true; data: { token: { accessToken: string } } }>().data.token.accessToken;

    const response = await app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: {
        authorization: 'Bearer ' + token,
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<{ ok: true; data: { user: { userId: string } } }>();
    assert.equal(body.ok, true);
    assert.equal(typeof body.data.user.userId, 'string');
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/logout revokes current session and stays idempotent', async () => {
  const authService = createFakeAuthService();
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4046,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { authService },
  );

  try {
    const login = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'placeholder-token',
        providerSubject: 'subject-logout',
      },
    });
    const token = login.json<{ ok: true; data: { token: { accessToken: string } } }>().data.token.accessToken;

    const firstLogout = await app.inject({
      method: 'POST',
      url: '/v1/auth/logout',
      payload: {},
      headers: { authorization: 'Bearer ' + token },
    });
    const secondLogout = await app.inject({
      method: 'POST',
      url: '/v1/auth/logout',
      payload: {},
      headers: { authorization: 'Bearer ' + token },
    });

    assert.equal(firstLogout.statusCode, 200);
    assert.equal(secondLogout.statusCode, 200);
    assert.deepEqual(firstLogout.json(), { ok: true, data: { revoked: true } });
    assert.deepEqual(secondLogout.json(), { ok: true, data: { revoked: false } });
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

test('GET /v1/users/me returns 401 unauthenticated when no auth is provided', async () => {
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

    assert.equal(response.statusCode, 401);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'unauthenticated',
        message: 'Authentication required.',
      },
    });
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/users returns 401 unauthenticated when no auth is provided', async () => {
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

    assert.equal(response.statusCode, 401);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'unauthenticated',
        message: 'Authentication required.',
      },
    });
  } finally {
    await app.close();
  }
});

const devUserAuth = (overrides: {
  userId?: string;
  role?: string;
  status?: string;
  subscriptionEntitlement?: string;
  sessionId?: string;
}) =>
  JSON.stringify({
    userId: 'dev-user-id',
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'member_monthly',
    sessionId: 'dev-session-id',
    ...overrides,
  });

test('GET /v1/users/me returns current profile summary for authenticated user', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4040,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
      headers: { 'x-dev-user': devUserAuth({}) },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), {
      ok: true,
      data: {
        user: {
          id: 'dev-user-id',
          displayName: null,
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
          lastActiveAt: null,
        },
      },
    });
  } finally {
    await app.close();
  }
});

test('GET /v1/users/me blocks suspended users safely', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4047,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
      headers: { 'x-dev-user': devUserAuth({ status: 'temporarily_suspended' }) },
    });

    assert.equal(response.statusCode, 403);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'suspended',
        message: 'Your account has been suspended.',
      },
    });
  } finally {
    await app.close();
  }
});

test('GET /v1/users/me blocks deleted users safely', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4048,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/users/me',
      headers: { 'x-dev-user': devUserAuth({ status: 'deleted' }) },
    });

    assert.equal(response.statusCode, 403);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'forbidden',
        message: 'Your account has been deleted.',
      },
    });
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/users with admin dev auth returns 501 not_implemented before admin backend exists', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4041,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users',
      headers: { 'x-dev-user': devUserAuth({ role: 'admin', subscriptionEntitlement: 'none' }) },
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

test('GET /v1/admin/users with non-admin dev auth returns 403 forbidden', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4042,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users',
      headers: { 'x-dev-user': devUserAuth({ role: 'user' }) },
    });

    assert.equal(response.statusCode, 403);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'forbidden',
        message: 'Admin access required.',
      },
    });
  } finally {
    await app.close();
  }
});
