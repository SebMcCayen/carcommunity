/**
 * Subscription entitlement foundation tests.
 *
 * These tests cover the shared access helpers and API route behaviour for
 * the subscription entitlement foundation. Full database integration is
 * avoided in favour of in-memory stubs to keep the test suite fast.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canAccessMemberFeatures,
  canAccessAdminFeatures,
  hasMemberEntitlement,
} from '@carcommunity/shared/users';
import {
  getEffectiveEntitlement,
  isSubscriptionActiveStatus,
} from '@carcommunity/shared/subscription';
import type { SubscriptionSourceSummary } from '@carcommunity/shared/subscription';
import { LOCAL_DATABASE_URL } from './config.js';
import { createServer } from './server.js';
import type { SubscriptionService } from './lib/subscription-service.js';

// ---------------------------------------------------------------------------
// isSubscriptionActiveStatus
// ---------------------------------------------------------------------------

test('isSubscriptionActiveStatus returns true for active and grace_period', () => {
  assert.equal(isSubscriptionActiveStatus('active'), true);
  assert.equal(isSubscriptionActiveStatus('grace_period'), true);
  assert.equal(isSubscriptionActiveStatus('inactive'), false);
  assert.equal(isSubscriptionActiveStatus('expired'), false);
  assert.equal(isSubscriptionActiveStatus('revoked'), false);
  assert.equal(isSubscriptionActiveStatus('cancelled'), false);
});

// ---------------------------------------------------------------------------
// getEffectiveEntitlement
// ---------------------------------------------------------------------------

test('getEffectiveEntitlement returns none when no records exist', () => {
  assert.equal(getEffectiveEntitlement([]), 'none');
});

test('getEffectiveEntitlement returns none when all records are inactive/expired', () => {
  const records: SubscriptionSourceSummary[] = [
    {
      platform: 'apple',
      status: 'expired',
      entitlement: 'member_monthly',
      startsAt: null,
      expiresAt: null,
    },
    {
      platform: 'google',
      status: 'cancelled',
      entitlement: 'member_monthly',
      startsAt: null,
      expiresAt: null,
    },
  ];
  assert.equal(getEffectiveEntitlement(records), 'none');
});

test('getEffectiveEntitlement returns member_monthly for active record', () => {
  const records: SubscriptionSourceSummary[] = [
    {
      platform: 'apple',
      status: 'active',
      entitlement: 'member_monthly',
      startsAt: null,
      expiresAt: null,
    },
  ];
  assert.equal(getEffectiveEntitlement(records), 'member_monthly');
});

test('getEffectiveEntitlement returns member_monthly for grace_period record', () => {
  const records: SubscriptionSourceSummary[] = [
    {
      platform: 'google',
      status: 'grace_period',
      entitlement: 'member_monthly',
      startsAt: null,
      expiresAt: null,
    },
  ];
  assert.equal(getEffectiveEntitlement(records), 'member_monthly');
});

// ---------------------------------------------------------------------------
// Access rule: free user has no member access
// ---------------------------------------------------------------------------

test('free user with no subscription has no member access', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'active', subscriptionEntitlement: 'none' }),
    false,
    'free user must not access member features',
  );
  assert.equal(hasMemberEntitlement('none'), false);
});

// ---------------------------------------------------------------------------
// Access rule: active member_monthly user has member access
// ---------------------------------------------------------------------------

test('active member_monthly user has member access', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'active', subscriptionEntitlement: 'member_monthly' }),
    true,
    'active member must access member features',
  );
  assert.equal(hasMemberEntitlement('member_monthly'), true);
});

// ---------------------------------------------------------------------------
// Access rule: suspended member_monthly user has no member access
// ---------------------------------------------------------------------------

test('temporarily_suspended member_monthly user has no member access', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'temporarily_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
    'suspended user must not access member features even with active subscription',
  );
});

test('permanently_suspended member_monthly user has no member access', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'permanently_suspended', subscriptionEntitlement: 'member_monthly' }),
    false,
    'permanently suspended user must not access member features',
  );
});

// ---------------------------------------------------------------------------
// Access rule: deleted member_monthly user has no member access
// ---------------------------------------------------------------------------

test('deleted member_monthly user has no member access', () => {
  assert.equal(
    canAccessMemberFeatures({ role: 'user', status: 'deleted', subscriptionEntitlement: 'member_monthly' }),
    false,
    'deleted user must not access member features even with active subscription',
  );
});

// ---------------------------------------------------------------------------
// Access rule: admin access does not require subscription
// ---------------------------------------------------------------------------

test('admin access does not require subscription', () => {
  assert.equal(
    canAccessAdminFeatures({ role: 'admin', status: 'active' }),
    true,
    'admin must access admin features without subscription',
  );
  assert.equal(
    canAccessAdminFeatures({ role: 'owner', status: 'active' }),
    true,
    'owner must access admin features without subscription',
  );
});

// ---------------------------------------------------------------------------
// API: GET /v1/subscription/me — unauthenticated
// ---------------------------------------------------------------------------

test('GET /v1/subscription/me returns 401 when not authenticated', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4060,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/subscription/me',
    });

    assert.equal(response.statusCode, 401);
    assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'unauthenticated');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// API: GET /v1/subscription/me — authenticated, stub service
// ---------------------------------------------------------------------------

function createStubSubscriptionService(
  subscriptionResult: Awaited<ReturnType<SubscriptionService['getSubscriptionForUser']>>,
  adminResult: Awaited<ReturnType<SubscriptionService['getAdminSubscriptionForUser']>> | null = null,
): Pick<SubscriptionService, 'getSubscriptionForUser' | 'getAdminSubscriptionForUser'> {
  return {
    getSubscriptionForUser: async () => subscriptionResult,
    getAdminSubscriptionForUser: async () => adminResult,
  };
}

const devHeader = (overrides: {
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

test('GET /v1/subscription/me returns entitlement and no sensitive provider data', async () => {
  const stubService = createStubSubscriptionService({
    userId: 'dev-user-id',
    entitlement: 'member_monthly',
    subscription: {
      platform: 'apple',
      status: 'active',
      entitlement: 'member_monthly',
      startsAt: '2026-01-01T00:00:00.000Z',
      expiresAt: '2027-01-01T00:00:00.000Z',
    },
  });

  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4061,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { subscriptionService: stubService },
  );

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/subscription/me',
      headers: { 'x-dev-user': devHeader({}) },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<{
      ok: true;
      data: {
        entitlement: string;
        subscription: {
          platform: string;
          status: string;
          entitlement: string;
          startsAt: string | null;
          expiresAt: string | null;
        } | null;
      };
    }>();
    assert.equal(body.ok, true);
    assert.equal(body.data.entitlement, 'member_monthly');
    // Must not contain raw tokens or sensitive fields.
    const subKeys = body.data.subscription ? Object.keys(body.data.subscription) : [];
    assert.ok(!subKeys.includes('externalPurchaseTokenHash'), 'raw token hash must not be exposed');
    assert.ok(!subKeys.includes('externalOriginalTransactionId'), 'raw transaction ID must not be exposed');
    assert.ok(!subKeys.includes('metadata'), 'raw metadata must not be exposed');
  } finally {
    await app.close();
  }
});

test('GET /v1/subscription/me returns none entitlement for free user', async () => {
  const stubService = createStubSubscriptionService({
    userId: 'dev-user-id',
    entitlement: 'none',
    subscription: null,
  });

  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4062,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { subscriptionService: stubService },
  );

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/subscription/me',
      headers: {
        'x-dev-user': devHeader({ subscriptionEntitlement: 'none' }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<{ ok: true; data: { entitlement: string; subscription: null } }>();
    assert.equal(body.data.entitlement, 'none');
    assert.equal(body.data.subscription, null);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// API: POST /v1/subscription/refresh-placeholder — safety checks
// ---------------------------------------------------------------------------

test('POST /v1/subscription/refresh-placeholder returns 401 when not authenticated', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4063,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/subscription/refresh-placeholder',
    });

    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('POST /v1/subscription/refresh-placeholder is clearly non-production and safe', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4064,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/subscription/refresh-placeholder',
      headers: { 'x-dev-user': devHeader({}) },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<{ ok: true; data: { _placeholder: boolean; message: string } }>();
    assert.equal(body.ok, true);
    assert.equal(body.data._placeholder, true, 'placeholder flag must be set');
    assert.equal(typeof body.data.message, 'string', 'message must be a string');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// API: GET /v1/admin/users/:userId/subscription — access control
// ---------------------------------------------------------------------------

test('GET /v1/admin/users/:userId/subscription returns 401 when not authenticated', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4065,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users/f47ac10b-58cc-4372-a567-0e02b2c3d479/subscription',
    });

    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/users/:userId/subscription returns 403 for regular user', async () => {
  const app = await createServer({
    nodeEnv: 'test',
    port: 4066,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users/f47ac10b-58cc-4372-a567-0e02b2c3d479/subscription',
      headers: { 'x-dev-user': devHeader({ role: 'user' }) },
    });

    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/users/:userId/subscription does not expose raw tokens', async () => {
  const stubService = createStubSubscriptionService(
    {
      userId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
      entitlement: 'member_monthly',
      subscription: {
        platform: 'apple',
        status: 'active',
        entitlement: 'member_monthly',
        startsAt: null,
        expiresAt: null,
      },
    },
    {
      userId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
      entitlement: 'member_monthly',
      subscription: {
        platform: 'apple',
        status: 'active',
        entitlement: 'member_monthly',
        startsAt: null,
        expiresAt: null,
      },
      isSuspendedWithActiveSubscription: false,
    },
  );

  const app = await createServer(
    {
      nodeEnv: 'test',
      port: 4067,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { subscriptionService: stubService },
  );

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/users/f47ac10b-58cc-4372-a567-0e02b2c3d479/subscription',
      headers: {
        'x-dev-user': devHeader({ role: 'admin', subscriptionEntitlement: 'none' }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<{
      ok: true;
      data: {
        subscription: {
          userId: string;
          entitlement: string;
          subscription: Record<string, unknown> | null;
          isSuspendedWithActiveSubscription: boolean;
        };
      };
    }>();
    assert.equal(body.ok, true);
    assert.equal(body.data.subscription.entitlement, 'member_monthly');

    // Must not contain raw tokens or sensitive fields.
    const subKeys = body.data.subscription.subscription
      ? Object.keys(body.data.subscription.subscription)
      : [];
    assert.ok(!subKeys.includes('externalPurchaseTokenHash'), 'raw token hash must not be exposed');
    assert.ok(
      !subKeys.includes('externalOriginalTransactionId'),
      'raw transaction ID must not be exposed',
    );
    assert.ok(!subKeys.includes('metadata'), 'raw metadata must not be exposed');
  } finally {
    await app.close();
  }
});
