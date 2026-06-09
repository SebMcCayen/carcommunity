import assert from 'node:assert/strict';
import test from 'node:test';

import { LiveLocationSessionStatus } from '@prisma/client';
import {
  LIVE_LOCATION_DURATIONS,
  LIVE_LOCATION_ROUTE_PATHS,
  LIVE_LOCATION_SESSION_STATUSES,
  LIVE_LOCATION_TTL_MINUTES_MAX,
  buildLiveLocationPositionPath,
  calculateLiveLocationExpiresAt,
  canViewOtherUsersLiveLocation,
  type AdminLiveLocationSummaryResponse,
  type PublicLiveLocationMarkerResponse,
} from '@carcommunity/shared/live-location';

import { LOCAL_DATABASE_URL } from './config.js';
import { createServer } from './server.js';

async function createTestApp(port: number) {
  return createServer({
    nodeEnv: 'test',
    port,
    databaseUrl: LOCAL_DATABASE_URL,
    isProduction: false,
  });
}

test('shared live location enums stay aligned between Prisma and shared contracts', () => {
  assert.deepEqual(new Set(Object.values(LiveLocationSessionStatus)), new Set(LIVE_LOCATION_SESSION_STATUSES));
  assert.deepEqual(LIVE_LOCATION_DURATIONS, ['1h', '2h', '4h']);
  assert.equal(LIVE_LOCATION_TTL_MINUTES_MAX, 15);
});

test('live location expiry helper returns the expected ISO expiry', () => {
  assert.equal(calculateLiveLocationExpiresAt('2026-06-09T10:00:00.000Z', '1h'), '2026-06-09T11:00:00.000Z');
  assert.equal(calculateLiveLocationExpiresAt('2026-06-09T10:00:00.000Z', '2h'), '2026-06-09T12:00:00.000Z');
  assert.equal(calculateLiveLocationExpiresAt('2026-06-09T10:00:00.000Z', '4h'), '2026-06-09T14:00:00.000Z');
});

test('live location visibility helper requires member entitlement unless admin bypass applies', () => {
  assert.equal(
    canViewOtherUsersLiveLocation({
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'none',
    }),
    false,
  );
  assert.equal(
    canViewOtherUsersLiveLocation({
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
    }),
    true,
  );
  assert.equal(
    canViewOtherUsersLiveLocation({
      role: 'admin',
      status: 'active',
      subscriptionEntitlement: 'none',
    }),
    true,
  );
  assert.equal(
    canViewOtherUsersLiveLocation({
      role: 'owner',
      status: 'permanently_suspended',
      subscriptionEntitlement: 'member_monthly',
    }),
    false,
  );
});

test('POST /v1/live-location/sessions returns a safe placeholder session response', async () => {
  const app = await createTestApp(4010);

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      payload: {
        duration: '2h',
      },
    });

    assert.equal(response.statusCode, 501);

    const body = response.json<{
      ok: boolean;
      error: { code: string; message: string };
    }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'not_implemented');
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/sessions rejects unsupported durations', async () => {
  const app = await createTestApp(4011);

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      payload: {
        duration: '24h',
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
    assert.equal(body.error.details?.issues?.[0]?.path, 'duration');
  } finally {
    await app.close();
  }
});

test('GET /v1/live-location/markers returns an empty privacy-safe placeholder response', async () => {
  const app = await createTestApp(4012);

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.markers}?page=2&pageSize=5`,
    });

    assert.equal(response.statusCode, 200);

    const body = response.json<PublicLiveLocationMarkerResponse>();
    assert.equal(body.ok, true);
    assert.deepEqual(body.data.markers, []);
    assert.equal(typeof body.data.generatedAt, 'string');
    assert.equal(body.meta.page, 2);
    assert.equal(body.meta.pageSize, 5);
    assert.equal(body.meta.total, 0);
    assert.equal(body.meta.hasNext, false);
    assert.equal(body.meta.source, 'placeholder');
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/hide-me-now returns a placeholder hide response shape', async () => {
  const app = await createTestApp(4013);

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.hideMeNow,
    });

    assert.equal(response.statusCode, 501);

    const body = response.json<{
      ok: boolean;
      error: { code: string; message: string };
    }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'not_implemented');
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/sessions/:sessionId/position accepts a valid coordinate payload', async () => {
  const app = await createTestApp(4014);
  const sessionId = 'cb8f7c4f-e930-4e01-ae85-61d2d93248cb';

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildLiveLocationPositionPath(sessionId),
      payload: {
        coordinate: {
          latitude: 57.4875,
          longitude: 12.0762,
          accuracyMeters: 8,
          recordedAt: '2026-06-09T10:15:00.000Z',
        },
      },
    });

    assert.equal(response.statusCode, 501);

    const body = response.json<{
      ok: boolean;
      error: { code: string; message: string };
    }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'not_implemented');
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/live-location returns 401 unauthenticated when no auth is provided', async () => {
  const app = await createTestApp(4016);

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.adminSummary}?page=1&pageSize=10`,
    });

    assert.equal(response.statusCode, 401);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'unauthenticated');
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/live-location returns a privacy-safe admin summary placeholder', async () => {
  const app = await createTestApp(4015);

  const adminDevAuth = JSON.stringify({
    userId: 'dev-admin-user',
    role: 'admin',
    status: 'active',
    subscriptionEntitlement: 'none',
    sessionId: 'dev-session-id',
  });

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.adminSummary}?page=1&pageSize=10`,
      headers: { 'x-dev-user': adminDevAuth },
    });

    assert.equal(response.statusCode, 200);

    const body = response.json<AdminLiveLocationSummaryResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.activeSessionCount, 0);
    assert.equal(body.data.expiredSessionCount, 0);
    assert.equal(body.data.operationalStatus, 'placeholder_safe_default');
    assert.equal(body.data.featureFlagKey, 'liveLocation');
    assert.equal(body.data.featureFlagEnabled, true);
    assert.equal(body.data.latestPositionTtlMinutesMax, LIVE_LOCATION_TTL_MINUTES_MAX);
    assert.deepEqual(body.data.sessions, []);
    assert.equal(body.meta.page, 1);
    assert.equal(body.meta.pageSize, 10);
    assert.equal(body.meta.total, 0);
    assert.equal(body.meta.hasNext, false);
  } finally {
    await app.close();
  }
});
