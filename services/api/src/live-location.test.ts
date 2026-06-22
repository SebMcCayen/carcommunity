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
  type AdminLiveLocationSummaryResponse,
  type PublicLiveLocationMarkerResponse,
} from '@carcommunity/shared/live-location';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type {
  AdminLiveLocationSummaryResult,
  HideMeNowResult,
  LiveLocationService,
  StartSessionResult,
  StopSessionResult,
  UpdatePositionResult,
  VisibleMarkersResult,
} from './lib/live-location-service.js';
import type { BlockingService } from './lib/blocking-service.js';
import { createServer } from './server.js';

class FakeLiveLocationService implements Pick<LiveLocationService,
  'startSession' | 'updateLatestPosition' | 'stopSession' | 'hideMeNow' | 'getVisibleMarkers' | 'getAdminSummary'> {
  public startResult: StartSessionResult = {
    session: {
      id: 'session-1',
      status: 'active',
      duration: '2h',
      startedAt: '2026-06-09T10:00:00.000Z',
      expiresAt: '2026-06-09T12:00:00.000Z',
      stoppedAt: null,
    },
    latestPosition: null,
    latestPositionRemoved: false,
  };

  public updateResult: UpdatePositionResult = {
    ...this.startResult,
    latestPosition: {
      latitude: 57.7,
      longitude: 12,
      recordedAt: '2026-06-09T10:05:00.000Z',
    },
  };

  public stopResult: StopSessionResult = {
    ...this.startResult,
    session: {
      ...this.startResult.session,
      status: 'stopped',
      stoppedAt: '2026-06-09T10:10:00.000Z',
    },
    latestPositionRemoved: true,
  };

  public hideResult: HideMeNowResult = {
    stoppedSessionCount: 1,
    removedLatestPositionCount: 1,
  };

  public markersResult: VisibleMarkersResult = {
    markers: [
      {
        userId: 'member-2',
        sessionId: 'session-2',
        status: 'active',
        coordinate: {
          latitude: 57.7,
          longitude: 12.1,
          recordedAt: '2026-06-09T10:11:00.000Z',
        },
      },
    ],
    total: 1,
    hasNext: false,
    generatedAt: '2026-06-09T10:12:00.000Z',
  };

  public adminSummaryResult: AdminLiveLocationSummaryResult = {
    activeSessionCount: 1,
    expiredSessionCount: 2,
    latestPositionUpdatedAt: '2026-06-09T10:12:00.000Z',
  };

  public failMarkersWith: AppError | null = null;

  async startSession(): Promise<StartSessionResult> {
    return this.startResult;
  }

  async updateLatestPosition(): Promise<UpdatePositionResult> {
    return this.updateResult;
  }

  async stopSession(): Promise<StopSessionResult> {
    return this.stopResult;
  }

  async hideMeNow(): Promise<HideMeNowResult> {
    return this.hideResult;
  }

  async getVisibleMarkers(): Promise<VisibleMarkersResult> {
    if (this.failMarkersWith) {
      throw this.failMarkersWith;
    }
    return this.markersResult;
  }

  async getAdminSummary(): Promise<AdminLiveLocationSummaryResult> {
    return this.adminSummaryResult;
  }
}

class FakeBlockingService implements Pick<BlockingService, 'getInvisibleUserIds'> {
  public invisibleUserIds: string[] = [];

  async getInvisibleUserIds(_viewerId: string): Promise<string[]> {
    return this.invisibleUserIds;
  }
}

async function createTestApp(
  port: number,
  liveLocationService?: FakeLiveLocationService,
  options?: { liveLocationFeatureEnabled?: boolean; blockingService?: FakeBlockingService },
) {
  return createServer(
    {
      nodeEnv: 'test',
      port,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    {
      liveLocationService: liveLocationService as unknown as LiveLocationService,
      liveLocationFeatureEnabled: options?.liveLocationFeatureEnabled,
      blockingService: options?.blockingService as unknown as BlockingService,
    },
  );
}

function createDevAuthHeader(input: {
  userId: string;
  role: 'user' | 'admin' | 'owner';
  status: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
  subscriptionEntitlement: 'none' | 'member_monthly';
}) {
  return JSON.stringify({
    ...input,
    sessionId: 'dev-session-id',
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

test('POST /v1/live-location/sessions requires auth', async () => {
  const app = await createTestApp(4110, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      payload: { duration: '2h' },
    });

    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/sessions rejects unsupported durations', async () => {
  const app = await createTestApp(4111, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: { duration: '24h' },
    });

    assert.equal(response.statusCode, 400);
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/sessions returns persisted session payload', async () => {
  const app = await createTestApp(4112, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: { duration: '2h' },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.ok, true);
    assert.equal(body.data.session.status, 'active');
    assert.equal(body.meta.source, 'database');
  } finally {
    await app.close();
  }
});

test('suspended user cannot start sharing live location', async () => {
  const app = await createTestApp(4120, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'suspended-user',
          role: 'user',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
      payload: { duration: '2h' },
    });

    assert.equal(response.statusCode, 403);
    const body = response.json<{ ok: false; error: { code: string } }>();
    assert.equal(body.error.code, 'suspended');
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/sessions/:sessionId/position validates coordinates and returns latest-only payload', async () => {
  const app = await createTestApp(4113, new FakeLiveLocationService());
  const sessionId = 'cb8f7c4f-e930-4e01-ae85-61d2d93248cb';

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildLiveLocationPositionPath(sessionId),
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: {
        coordinate: {
          latitude: 57.4875,
          longitude: 12.0762,
          recordedAt: '2026-06-09T10:15:00.000Z',
        },
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.data.latestPosition.latitude, 57.7);

    const invalid = await app.inject({
      method: 'POST',
      url: buildLiveLocationPositionPath(sessionId),
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: {
        coordinate: {
          latitude: 200,
          longitude: 12.0762,
          recordedAt: '2026-06-09T10:15:00.000Z',
        },
      },
    });

    assert.equal(invalid.statusCode, 400);
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/sessions/:sessionId/stop returns stopped session payload', async () => {
  const app = await createTestApp(4114, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/live-location/sessions/cb8f7c4f-e930-4e01-ae85-61d2d93248cb/stop',
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: { reason: 'user_stop' },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.data.session.status, 'stopped');
    assert.equal(body.data.latestPositionRemoved, true);
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/hide-me-now returns stop/delete summary', async () => {
  const app = await createTestApp(4115, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.hideMeNow,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: {},
    });

    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.data.stoppedSessionCount, 1);
    assert.equal(body.data.removedLatestPositionCount, 1);
  } finally {
    await app.close();
  }
});

test('POST /v1/live-location/hide-me-now remains available for authenticated suspended users', async () => {
  const app = await createTestApp(4121, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.hideMeNow,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'suspended-user',
          role: 'user',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: {},
    });

    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.data.stoppedSessionCount, 1);
  } finally {
    await app.close();
  }
});

test('GET /v1/live-location/markers denies free users but allows member users', async () => {
  const deniedService = new FakeLiveLocationService();
  deniedService.failMarkersWith = new AppError(403, 'forbidden', 'Member subscription required.');
  const deniedApp = await createTestApp(4116, deniedService);

  try {
    const deniedResponse = await deniedApp.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.markers}?page=1&pageSize=5`,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'free-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(deniedResponse.statusCode, 403);

    const allowedService = new FakeLiveLocationService();
    const allowedApp = await createTestApp(4117, allowedService, { blockingService: new FakeBlockingService() });

    try {
      const allowedResponse = await allowedApp.inject({
        method: 'GET',
        url: `${LIVE_LOCATION_ROUTE_PATHS.markers}?page=1&pageSize=5`,
        headers: {
          'x-dev-user': createDevAuthHeader({
            userId: 'member-user',
            role: 'user',
            status: 'active',
            subscriptionEntitlement: 'member_monthly',
          }),
        },
      });

      assert.equal(allowedResponse.statusCode, 200);
      const body = allowedResponse.json<PublicLiveLocationMarkerResponse>();
      assert.equal(body.data.markers.length, 1);
      assert.equal(body.data.markers[0]?.userId, 'member-2');
    } finally {
      await allowedApp.close();
    }
  } finally {
    await deniedApp.close();
  }
});

test('GET /v1/live-location/markers does not grant admin marker access without member entitlement', async () => {
  const app = await createTestApp(4122, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.markers}?page=1&pageSize=5`,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'admin-user',
          role: 'admin',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 403);
    const body = response.json<{ ok: false; error: { code: string; message: string } }>();
    assert.equal(body.error.code, 'forbidden');
    assert.equal(body.error.message, 'Member subscription required.');
  } finally {
    await app.close();
  }
});

test('suspended user cannot access live location routes', async () => {
  const app = await createTestApp(4118, new FakeLiveLocationService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.markers}?page=1&pageSize=5`,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'suspended-user',
          role: 'user',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });

    assert.equal(response.statusCode, 403);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.error.code, 'suspended');
  } finally {
    await app.close();
  }
});

test('deleted user cannot use sharing or marker routes', async () => {
  const app = await createTestApp(4123, new FakeLiveLocationService());

  try {
    const startResponse = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'deleted-user',
          role: 'user',
          status: 'deleted',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
      payload: { duration: '2h' },
    });
    assert.equal(startResponse.statusCode, 403);

    const markersResponse = await app.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.markers}?page=1&pageSize=5`,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'deleted-user',
          role: 'user',
          status: 'deleted',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });
    assert.equal(markersResponse.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('disabled liveLocation flag blocks start/update/markers but not hide-me-now', async () => {
  const app = await createTestApp(4124, new FakeLiveLocationService(), {
    liveLocationFeatureEnabled: false,
  });
  const sessionId = 'cb8f7c4f-e930-4e01-ae85-61d2d93248cb';
  const headers = {
    'x-dev-user': createDevAuthHeader({
      userId: 'member-user',
      role: 'user',
      status: 'active',
      subscriptionEntitlement: 'member_monthly',
    }),
  };

  try {
    const startResponse = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.sessions,
      headers,
      payload: { duration: '2h' },
    });
    assert.equal(startResponse.statusCode, 403);
    assert.equal(startResponse.json<{ ok: false; error: { code: string } }>().error.code, 'feature_disabled');

    const updateResponse = await app.inject({
      method: 'POST',
      url: buildLiveLocationPositionPath(sessionId),
      headers,
      payload: {
        coordinate: {
          latitude: 57.4875,
          longitude: 12.0762,
          recordedAt: '2026-06-09T10:15:00.000Z',
        },
      },
    });
    assert.equal(updateResponse.statusCode, 403);
    assert.equal(updateResponse.json<{ ok: false; error: { code: string } }>().error.code, 'feature_disabled');

    const markersResponse = await app.inject({
      method: 'GET',
      url: `${LIVE_LOCATION_ROUTE_PATHS.markers}?page=1&pageSize=5`,
      headers,
    });
    assert.equal(markersResponse.statusCode, 403);
    assert.equal(markersResponse.json<{ ok: false; error: { code: string } }>().error.code, 'feature_disabled');

    const hideResponse = await app.inject({
      method: 'POST',
      url: LIVE_LOCATION_ROUTE_PATHS.hideMeNow,
      headers,
      payload: {},
    });
    assert.equal(hideResponse.statusCode, 200);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/live-location requires admin and returns operational-only summary', async () => {
  const app = await createTestApp(4119, new FakeLiveLocationService());

  try {
    const unauthenticated = await app.inject({
      method: 'GET',
      url: LIVE_LOCATION_ROUTE_PATHS.adminSummary,
    });
    assert.equal(unauthenticated.statusCode, 401);

    const forbidden = await app.inject({
      method: 'GET',
      url: LIVE_LOCATION_ROUTE_PATHS.adminSummary,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'user-1',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });
    assert.equal(forbidden.statusCode, 403);

    const admin = await app.inject({
      method: 'GET',
      url: LIVE_LOCATION_ROUTE_PATHS.adminSummary,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'admin-1',
          role: 'admin',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(admin.statusCode, 200);
    const body = admin.json<AdminLiveLocationSummaryResponse>();
    assert.equal(body.data.activeSessionCount, 1);
    assert.equal(body.data.expiredSessionCount, 2);
    assert.equal(body.data.latestPositionUpdatedAt, '2026-06-09T10:12:00.000Z');
    assert.deepEqual(Object.keys(body.data).sort(), ['activeSessionCount', 'expiredSessionCount', 'latestPositionUpdatedAt']);

    const owner = await app.inject({
      method: 'GET',
      url: LIVE_LOCATION_ROUTE_PATHS.adminSummary,
      headers: {
        'x-dev-user': createDevAuthHeader({
          userId: 'owner-1',
          role: 'owner',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });
    assert.equal(owner.statusCode, 200);
  } finally {
    await app.close();
  }
});
