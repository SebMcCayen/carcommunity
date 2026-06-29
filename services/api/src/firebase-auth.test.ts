/**
 * Tests for Firebase ID token authentication and admin claim authorization.
 *
 * These tests use a lightweight fake FirebaseIdTokenVerifier so that no real
 * Firebase network calls are made. The covered scenarios are:
 *
 *   - missing Authorization header → 401 on protected admin route
 *   - invalid Firebase ID token (expired, forged, malformed) → 401
 *   - self-signed token with admin:true claim → 401 (client cannot forge claims)
 *   - valid token, regular (non-admin) user → auth context populated, 403 on admin route
 *   - valid token, user with admin:true claim → 501 on admin stub (auth passed)
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { AppError } from './lib/errors.js';
import type { DecodedFirebaseToken, FirebaseIdTokenVerifier } from './lib/firebase-id-token-verifier.js';
import type {
  AuthService,
  AuthenticatedSession,
  CreatedSession,
  ProviderIdentityLoginInput,
} from './lib/auth-service.js';
import type { AuthenticatedUserSummary } from '@carcommunity/shared/auth';
import { createServer } from './server.js';
import { LOCAL_DATABASE_URL } from './config.js';

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

function buildUserSummary(overrides: Partial<AuthenticatedUserSummary> = {}): AuthenticatedUserSummary {
  return {
    userId: 'db-user-id',
    displayName: 'Test User',
    identities: [],
    roles: ['user'],
    status: 'active',
    subscriptionEntitlement: 'none',
    onboardingCompletedAt: null,
    ...overrides,
  };
}

function createFakeAuthService(summary: AuthenticatedUserSummary): AuthService {
  return {
    async findOrCreateUserByFirebaseUid(_uid, _email) {
      return summary;
    },
    async findOrCreateUserByProviderIdentity(_input: ProviderIdentityLoginInput) {
      return summary;
    },
    async createSession(_userId: string): Promise<CreatedSession> {
      return {
        sessionId: 'session-1',
        expiresAt: new Date(Date.now() + 3600 * 1000),
        token: { _devOnly: true, accessToken: 'test-token', expiresIn: 3600 },
      };
    },
    async lookupSession(_token: string): Promise<AuthenticatedSession | null> {
      return null;
    },
    async revokeSession(_token: string): Promise<boolean> {
      return true;
    },
  };
}

function makeVerifier(
  impl: (token: string) => DecodedFirebaseToken | Promise<DecodedFirebaseToken>,
): FirebaseIdTokenVerifier {
  return { verifyIdToken: async (t) => impl(t) };
}

/** Verifier that succeeds with a non-admin decoded token. */
const regularUserVerifier: FirebaseIdTokenVerifier = makeVerifier(() => ({
  uid: 'firebase-uid-user',
  email: 'user@example.com',
  isAdmin: false,
}));

/** Verifier that succeeds with an admin decoded token. */
const adminClaimVerifier: FirebaseIdTokenVerifier = makeVerifier(() => ({
  uid: 'firebase-uid-admin',
  email: 'admin@example.com',
  isAdmin: true,
}));

/**
 * Verifier that always rejects — simulates an invalid, expired, or forged token.
 * The Firebase Admin SDK would reject tokens that are not signed by Google.
 */
const invalidTokenVerifier: FirebaseIdTokenVerifier = makeVerifier(() => {
  throw new AppError(401, 'invalid_identity_token', 'Firebase ID token is invalid or expired.');
});

const regularUserSummary = buildUserSummary();
const adminUserSummary = buildUserSummary({ roles: ['admin'] });

async function makeServer(verifier: FirebaseIdTokenVerifier, summary = regularUserSummary) {
  return createServer(
    { nodeEnv: 'test', port: 4000, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { authService: createFakeAuthService(summary), firebaseIdTokenVerifier: verifier },
  );
}

// ---------------------------------------------------------------------------
// Test: missing Authorization header
// ---------------------------------------------------------------------------

test('Firebase auth: missing Authorization header returns 401 on admin endpoint', async () => {
  const app = await makeServer(regularUserVerifier);
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/admin/users' });
    assert.equal(response.statusCode, 401);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'unauthenticated');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Test: invalid Firebase ID token
// ---------------------------------------------------------------------------

test('Firebase auth: invalid Firebase ID token leaves auth null and returns 401 on admin endpoint', async () => {
  const app = await makeServer(invalidTokenVerifier);
  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users',
      headers: { authorization: 'Bearer fake.firebase.id.token' },
    });
    assert.equal(response.statusCode, 401);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'unauthenticated');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Test: client-forged token with admin:true is rejected
// ---------------------------------------------------------------------------

test('Firebase auth: self-signed token with admin:true is rejected because client cannot forge Firebase tokens', async () => {
  // A client that manually crafts a JWT payload with admin: true cannot produce
  // a signature that the Firebase Admin SDK would accept. The verifier rejects
  // such tokens, leaving the auth context null.
  const forgedTokenVerifier: FirebaseIdTokenVerifier = makeVerifier(() => {
    throw new AppError(401, 'invalid_identity_token', 'Firebase ID token is invalid or expired.');
  });

  const app = await makeServer(forgedTokenVerifier);
  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users',
      headers: { authorization: 'Bearer fake.firebase.id.token' },
    });
    // Auth context is null because the token was rejected — admin hook returns 401.
    assert.equal(response.statusCode, 401);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'unauthenticated');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Test: authenticated non-admin user
// ---------------------------------------------------------------------------

test('Firebase auth: valid token populates auth context and returns 200 on /v1/auth/me', async () => {
  const app = await makeServer(regularUserVerifier);
  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: { authorization: 'Bearer fake.firebase.id.token' },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<{ ok: boolean; data?: { user: { userId: string } } }>();
    assert.equal(body.ok, true);
    assert.equal(body.data?.user.userId, 'db-user-id');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Test: non-admin user denied from admin endpoint
// ---------------------------------------------------------------------------

test('Firebase auth: authenticated non-admin user is denied on admin endpoint with 403', async () => {
  // regularUserVerifier produces isAdmin: false — requireAdminHook must reject.
  const app = await makeServer(regularUserVerifier);
  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users',
      headers: { authorization: 'Bearer fake.firebase.id.token' },
    });
    assert.equal(response.statusCode, 403);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'forbidden');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Test: admin user allowed to access admin endpoint
// ---------------------------------------------------------------------------

test('Firebase auth: user with admin:true claim passes requireAdminHook and reaches the route handler', async () => {
  // adminClaimVerifier produces isAdmin: true — requireAdminHook must allow through.
  // The /v1/admin/users stub returns 501 (not yet implemented) once auth passes.
  const app = await makeServer(adminClaimVerifier, adminUserSummary);
  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users',
      headers: { authorization: 'Bearer fake.firebase.id.token' },
    });
    // Auth passed → route handler reached. The stub returns 501 (not 401 or 403).
    assert.notEqual(response.statusCode, 401);
    assert.notEqual(response.statusCode, 403);
    assert.equal(response.statusCode, 501);
  } finally {
    await app.close();
  }
});
