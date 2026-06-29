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
import { AppError } from './lib/errors.js';
import type {
  AuthProviderVerifier,
  VerifyIdentityTokenInput,
  VerifiedIdentityToken,
} from './lib/auth-provider-verifier.js';
import { createServer } from './server.js';
import type {
  AuthService,
  AuthenticatedSession,
  CreatedSession,
  ProviderIdentityLoginInput,
} from './lib/auth-service.js';
import type { UserService, UserProfileRecord } from './lib/user-service.js';

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
    async findOrCreateUserByFirebaseUid(firebaseUid: string) {
      userCounter += 1;
      const userId = `firebase-user-${userCounter}`;
      userProfiles.set(userId, {
        userId,
        displayName: null,
        role: 'user',
        status: 'active',
        subscriptionEntitlement: 'member_monthly',
        identities: [],
        lastActiveAt: null,
      });
      return {
        userId,
        displayName: null,
        identities: [],
        roles: ['user' as const],
        status: 'active' as const,
        subscriptionEntitlement: 'member_monthly' as const,
      };
    },

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
        status: profile.status,
        subscriptionEntitlement: profile.subscriptionEntitlement,
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
        onboardingCompletedAt: null,
        lastActiveAt: new Date(),
        expiresAt,
        user: {
          userId: profile.userId,
          displayName: profile.displayName,
          identities: profile.identities,
          roles: [profile.role],
          status: profile.status,
          subscriptionEntitlement: profile.subscriptionEntitlement,
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

function createStrictAuthConfig() {
  return {
    authVerificationMode: 'strict' as const,
    authProviders: {
      apple: {
        allowedAudiences: ['com.example.apple'],
        bundleId: 'com.example.apple',
        serviceId: null,
        issuers: ['https://appleid.apple.com'],
        jwksUrl: 'https://example.test/apple/keys',
      },
      google: {
        allowedClientIds: ['replace-with-google-client-id'],
        issuers: ['https://accounts.google.com', 'accounts.google.com'],
        jwksUrl: 'https://example.test/google/keys',
      },
    },
  };
}

function createFakeAuthProviderVerifier(
  implementation: (input: VerifyIdentityTokenInput) => Promise<VerifiedIdentityToken> | VerifiedIdentityToken,
): AuthProviderVerifier {
  return {
    async verifyIdentityToken(input) {
      return implementation(input);
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
    'partners',
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

test('POST /v1/auth/login in strict mode rejects missing identityToken', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4007,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
    ...createStrictAuthConfig(),
  });

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
      },
    });

    assert.equal(response.statusCode, 400);
    const body = response.json<{ ok: false; error: { code: string; message: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'validation_error');
    assert.equal(body.error.message, 'Request validation failed.');
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

    const firstBody = first.json<{ ok: true; data: { user: { userId: string }; token: { _devOnly: true; accessToken: string } } }>();
    const secondBody = second.json<{ ok: true; data: { user: { userId: string }; token: { _devOnly: true; accessToken: string } } }>();

    assert.equal(firstBody.data.token._devOnly, true);
    assert.equal(firstBody.data.user.userId, secondBody.data.user.userId);
    assert.notEqual(firstBody.data.token.accessToken, secondBody.data.token.accessToken);
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/login in strict mode does not trust client-provided providerSubject', async () => {
  let capturedProviderInput: ProviderIdentityLoginInput | null = null;

  const authService: AuthService = {
    async findOrCreateUserByFirebaseUid() {
      return {
        userId: 'user-strict-1',
        displayName: null,
        identities: [],
        roles: ['user' as const],
        status: 'active' as const,
        subscriptionEntitlement: 'member_monthly' as const,
      };
    },
    async findOrCreateUserByProviderIdentity(input) {
      capturedProviderInput = input;
      return {
        userId: 'user-strict-1',
        displayName: null,
        identities: [{ provider: input.provider, providerSubject: input.providerSubject }],
        roles: ['user'],
        status: 'active',
        subscriptionEntitlement: 'member_monthly',
      };
    },
    async createSession() {
      return {
        sessionId: 'session-strict-1',
        expiresAt: new Date(Date.now() + 60_000),
        token: {
          _devOnly: true,
          accessToken: 'dev-token-strict-1',
          expiresIn: 60,
        },
      };
    },
    async lookupSession() {
      return null;
    },
    async revokeSession() {
      return false;
    },
  };

  const authProviderVerifier = createFakeAuthProviderVerifier(() => ({
    provider: 'apple',
    providerSubject: 'verified-subject-1',
    issuer: 'https://appleid.apple.com',
    audience: 'com.example.apple',
    expiresAt: new Date(Date.now() + 60_000),
    email: 'driver@example.com',
    nonce: null,
  }));

  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4049,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
      ...createStrictAuthConfig(),
    },
    { authService, authProviderVerifier },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'signed-token',
        providerSubject: 'client-subject-should-be-ignored',
      },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(capturedProviderInput, {
      provider: 'apple',
      providerSubject: 'verified-subject-1',
      providerEmail: 'driver@example.com',
    });
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/login in strict mode rejects provider mismatch safely', async () => {
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4052,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
      ...createStrictAuthConfig(),
    },
    {
      authService: createFakeAuthService(),
      authProviderVerifier: createFakeAuthProviderVerifier(() => ({
        provider: 'google',
        providerSubject: 'verified-google-subject',
        issuer: 'https://accounts.google.com',
        audience: 'replace-with-google-client-id',
        expiresAt: new Date(Date.now() + 60_000),
        email: null,
        nonce: null,
      })),
    },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'signed-token',
      },
    });

    assert.equal(response.statusCode, 401);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'invalid_identity_provider',
        message: 'Identity token provider does not match the requested provider.',
      },
    });
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/login in strict mode uses verified provider subject as identity key', async () => {
  const authService = createFakeAuthService();
  const authProviderVerifier = createFakeAuthProviderVerifier(() => ({
    provider: 'apple',
    providerSubject: 'verified-subject-shared',
    issuer: 'https://appleid.apple.com',
    audience: 'com.example.apple',
    expiresAt: new Date(Date.now() + 60_000),
    email: 'same@example.com',
    nonce: null,
  }));
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4053,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
      ...createStrictAuthConfig(),
    },
    { authService, authProviderVerifier },
  );

  try {
    const first = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'token-a',
        providerSubject: 'client-subject-a',
      },
    });
    const second = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: 'token-b',
        providerSubject: 'client-subject-b',
      },
    });

    const firstBody = first.json<{ ok: true; data: { user: { userId: string } } }>();
    const secondBody = second.json<{ ok: true; data: { user: { userId: string } } }>();

    assert.equal(first.statusCode, 200);
    assert.equal(second.statusCode, 200);
    assert.equal(firstBody.data.user.userId, secondBody.data.user.userId);
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

test('POST /v1/auth/login in strict mode does not use email as identity key', async () => {
  const authService = createFakeAuthService();
  const authProviderVerifier = createFakeAuthProviderVerifier((input) => ({
    provider: input.provider,
    providerSubject: input.identityToken === 'google-token-a' ? 'google-subject-a' : 'google-subject-b',
    issuer: 'https://accounts.google.com',
    audience: 'replace-with-google-client-id',
    expiresAt: new Date(Date.now() + 60_000),
    email: 'shared@example.com',
    nonce: null,
  }));
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4054,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
      ...createStrictAuthConfig(),
    },
    { authService, authProviderVerifier },
  );

  try {
    const first = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'google',
        identityToken: 'google-token-a',
      },
    });
    const second = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'google',
        identityToken: 'google-token-b',
      },
    });

    const firstBody = first.json<{ ok: true; data: { user: { userId: string } } }>();
    const secondBody = second.json<{ ok: true; data: { user: { userId: string } } }>();

    assert.notEqual(firstBody.data.user.userId, secondBody.data.user.userId);
  } finally {
    await app.close();
  }
});

test('POST /v1/auth/login in strict mode does not expose identity tokens in errors', async () => {
  const secretToken = 'super-secret-identity-token-value';
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4055,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
      ...createStrictAuthConfig(),
    },
    {
      authService: createFakeAuthService(),
      authProviderVerifier: createFakeAuthProviderVerifier(() => {
        throw new AppError(401, 'invalid_identity_token', 'Invalid identity token.');
      }),
    },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: {
        provider: 'apple',
        identityToken: secretToken,
      },
    });

    assert.equal(response.statusCode, 401);
    assert.equal(response.body.includes(secretToken), false);
    assert.deepEqual(response.json(), {
      ok: false,
      error: {
        code: 'invalid_identity_token',
        message: 'Invalid identity token.',
      },
    });
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

function createFakeUserService(
  profile: Partial<UserProfileRecord> = {},
): UserService {
  const defaultProfile: UserProfileRecord = {
    id: 'dev-user-id',
    displayName: null,
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'member_monthly',
    onboardingCompletedAt: null,
    ageConfirmedAt: null,
    termsAcceptedAt: null,
    privacyPolicyAcceptedAt: null,
    anonymousPartnerStatsOptIn: false,
    ...profile,
  };
  return {
    async getUserProfile() {
      return { ...defaultProfile };
    },
    async updateUserProfile(input) {
      const now = new Date();
      const updated = { ...defaultProfile };
      if ('displayName' in input) updated.displayName = input.displayName ?? null;
      if (input.ageConfirmed) updated.ageConfirmedAt = now;
      if (input.termsAccepted) updated.termsAcceptedAt = now;
      if (input.privacyPolicyAccepted) updated.privacyPolicyAcceptedAt = now;
      if (!updated.onboardingCompletedAt && updated.ageConfirmedAt && updated.termsAcceptedAt && updated.privacyPolicyAcceptedAt) {
        updated.onboardingCompletedAt = now;
      }
      return updated;
    },
    async getPrivacySettings() {
      return { anonymousPartnerStatsOptIn: defaultProfile.anonymousPartnerStatsOptIn };
    },
    async updatePrivacySettings(_userId, anonymousPartnerStatsOptIn) {
      return { anonymousPartnerStatsOptIn };
    },
  };
}

test('GET /v1/users/me returns current profile summary for authenticated user', async () => {
  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4040,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { userService: createFakeUserService() },
  );

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
          onboarding: {
            onboardingCompletedAt: null,
            ageConfirmedAt: null,
            termsAcceptedAt: null,
            privacyPolicyAcceptedAt: null,
          },
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

test('GET /healthz returns alive status', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4050,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/healthz',
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), {
      ok: true,
      data: { status: 'alive' },
    });
  } finally {
    await app.close();
  }
});

test('GET /readyz returns ready status with empty checks', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4051,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/readyz',
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), {
      ok: true,
      data: { status: 'ready', checks: {} },
    });
  } finally {
    await app.close();
  }
});
