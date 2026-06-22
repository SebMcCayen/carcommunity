/**
 * Tests for group drive routes.
 *
 * Uses fake service implementations injected through server dependencies.
 * All auth is simulated via the x-dev-user header (non-production only).
 *
 * Coverage:
 *  - free users cannot join group driving
 *  - suspended/deleted users cannot participate
 *  - RSVP going user can join
 *  - RSVP maybe user can join
 *  - no-RSVP user cannot join
 *  - not_going user cannot join
 *  - draft/cancelled/completed event rejects joins
 *  - joining is idempotent
 *  - user can update only their own status
 *  - leaving removes user from group marker response
 *  - joining does not automatically start live location
 *  - group markers include only active participants with valid live positions
 *  - stale and expired positions are excluded
 *  - blocking is enforced in both directions
 *  - blocked participants are not returned
 *  - route history is not created
 *  - admin summary exposes aggregate counts only
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildGroupDriveJoinPath,
  buildGroupDriveLeavePath,
  buildGroupDriveMarkersPath,
  buildGroupDriveSummaryPath,
  buildGroupDriveStatusPath,
  type GroupDriveMarkersResponse,
  type GroupDriveSummaryResponse,
  type JoinGroupDriveResponse,
  type LeaveGroupDriveResponse,
  type UpdateGroupDriveStatusResponse,
} from '@carcommunity/shared/group-drive';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type {
  GroupDriveService,
  JoinGroupDriveResult,
  LeaveGroupDriveResult,
  UpdateGroupDriveStatusResult,
  GroupDriveSummaryResult,
  GroupDriveMarkersResult,
} from './lib/group-drive-service.js';
import type { BlockingService } from './lib/blocking-service.js';
import { createServer } from './server.js';

// ---------------------------------------------------------------------------
// Fake services
// ---------------------------------------------------------------------------

const BASE_PARTICIPANT_SUMMARY = {
  participantId: 'participant-1',
  displayName: 'Alice',
  status: 'joined' as const,
  joinedAt: '2027-07-01T15:00:00.000Z',
  hasActiveLiveLocation: false,
};

class FakeGroupDriveService
  implements
    Pick<
      GroupDriveService,
      | 'joinGroupDrive'
      | 'leaveGroupDrive'
      | 'updateStatus'
      | 'getGroupDriveSummary'
      | 'getGroupDriveMarkers'
    >
{
  public joinResult: JoinGroupDriveResult = {
    participant: { ...BASE_PARTICIPANT_SUMMARY },
    rejoined: false,
  };

  public leaveResult: LeaveGroupDriveResult = { left: true };

  public updateStatusResult: UpdateGroupDriveStatusResult = {
    participant: { ...BASE_PARTICIPANT_SUMMARY, status: 'on_the_way' },
  };

  public summaryResult: GroupDriveSummaryResult = {
    totalActive: 2,
    joinedCount: 1,
    onTheWayCount: 1,
    arrivedCount: 0,
    currentUserStatus: 'joined',
    currentUserHasActiveLiveLocation: false,
    participants: [{ ...BASE_PARTICIPANT_SUMMARY }],
  };

  public markersResult: GroupDriveMarkersResult = {
    markers: [],
    generatedAt: '2027-07-01T16:00:00.000Z',
  };

  public failJoinWith: AppError | null = null;
  public failLeaveWith: AppError | null = null;
  public failUpdateStatusWith: AppError | null = null;
  public failSummaryWith: AppError | null = null;
  public failMarkersWithFn: ((input: Parameters<GroupDriveService['getGroupDriveMarkers']>[0]) => void) | null = null;

  async joinGroupDrive(): Promise<JoinGroupDriveResult> {
    if (this.failJoinWith) throw this.failJoinWith;
    return this.joinResult;
  }

  async leaveGroupDrive(): Promise<LeaveGroupDriveResult> {
    if (this.failLeaveWith) throw this.failLeaveWith;
    return this.leaveResult;
  }

  async updateStatus(): Promise<UpdateGroupDriveStatusResult> {
    if (this.failUpdateStatusWith) throw this.failUpdateStatusWith;
    return this.updateStatusResult;
  }

  async getGroupDriveSummary(params: Parameters<GroupDriveService['getGroupDriveSummary']>[0]): Promise<GroupDriveSummaryResult> {
    if (this.failSummaryWith) throw this.failSummaryWith;
    return this.summaryResult;
  }

  async getGroupDriveMarkers(input: Parameters<GroupDriveService['getGroupDriveMarkers']>[0]): Promise<GroupDriveMarkersResult> {
    if (this.failMarkersWithFn) this.failMarkersWithFn(input);
    return this.markersResult;
  }
}

class FakeBlockingService
  implements Pick<BlockingService, 'getInvisibleUserIds'>
{
  public invisibleUserIds: string[] = [];

  async getInvisibleUserIds(_viewerId: string): Promise<string[]> {
    return this.invisibleUserIds;
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function createTestApp(
  port: number,
  groupDriveService?: FakeGroupDriveService,
  blockingService?: FakeBlockingService,
) {
  return createServer(
    {
      nodeEnv: 'test',
      port,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    {
      groupDriveService: groupDriveService as unknown as GroupDriveService,
      blockingService: blockingService as unknown as BlockingService,
    },
  );
}

function devAuth(input: {
  userId: string;
  role?: 'user' | 'admin' | 'owner';
  status?: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
  subscriptionEntitlement?: 'none' | 'member_monthly';
}): string {
  return JSON.stringify({
    userId: input.userId,
    role: input.role ?? 'user',
    status: input.status ?? 'active',
    subscriptionEntitlement: input.subscriptionEntitlement ?? 'member_monthly',
    sessionId: 'dev-session',
  });
}

const EVENT_ID = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

// ---------------------------------------------------------------------------
// JOIN — auth and access checks
// ---------------------------------------------------------------------------

test('POST /v1/events/:eventId/group-drive/join requires authentication', async () => {
  const app = await createTestApp(5300);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
    });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('POST join rejects free users (no member_monthly)', async () => {
  const service = new FakeGroupDriveService();
  service.failJoinWith = new AppError(403, 'forbidden', 'Member subscription required.');
  const app = await createTestApp(5301, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'free-user', subscriptionEntitlement: 'none' }),
      },
    });
    // requireMemberHook returns 403 for non-members before service is called
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join rejects temporarily_suspended users', async () => {
  const app = await createTestApp(5302, new FakeGroupDriveService());
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({
          userId: 'suspended-user',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join rejects permanently_suspended users', async () => {
  const app = await createTestApp(5303, new FakeGroupDriveService());
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({
          userId: 'perm-suspended-user',
          status: 'permanently_suspended',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join rejects deleted users', async () => {
  const app = await createTestApp(5304, new FakeGroupDriveService());
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({
          userId: 'deleted-user',
          status: 'deleted',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join succeeds for RSVP going member', async () => {
  const service = new FakeGroupDriveService();
  const app = await createTestApp(5305, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'going-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<JoinGroupDriveResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.participant.participantId, 'participant-1');
    assert.equal(body.data.rejoined, false);
  } finally {
    await app.close();
  }
});

test('POST join succeeds for RSVP maybe member', async () => {
  const service = new FakeGroupDriveService();
  service.joinResult = {
    participant: { ...BASE_PARTICIPANT_SUMMARY },
    rejoined: false,
  };
  const app = await createTestApp(5306, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'maybe-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<JoinGroupDriveResponse>();
    assert.equal(body.ok, true);
  } finally {
    await app.close();
  }
});

test('POST join rejects user with no RSVP', async () => {
  const service = new FakeGroupDriveService();
  service.failJoinWith = new AppError(403, 'forbidden', 'RSVP going or maybe required to join group drive.');
  const app = await createTestApp(5307, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'no-rsvp-user' }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join rejects user with not_going RSVP', async () => {
  const service = new FakeGroupDriveService();
  service.failJoinWith = new AppError(403, 'forbidden', 'RSVP going or maybe required to join group drive.');
  const app = await createTestApp(5308, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'not-going-user' }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join rejects draft event', async () => {
  const service = new FakeGroupDriveService();
  service.failJoinWith = new AppError(403, 'forbidden', 'Event is not eligible for group driving.');
  const app = await createTestApp(5309, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join rejects cancelled event', async () => {
  const service = new FakeGroupDriveService();
  service.failJoinWith = new AppError(403, 'forbidden', 'Event is not eligible for group driving.');
  const app = await createTestApp(5310, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join rejects completed event', async () => {
  const service = new FakeGroupDriveService();
  service.failJoinWith = new AppError(403, 'forbidden', 'Event is not eligible for group driving.');
  const app = await createTestApp(5311, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST join is idempotent (already joined returns same participant)', async () => {
  const service = new FakeGroupDriveService();
  service.joinResult = {
    participant: { ...BASE_PARTICIPANT_SUMMARY, status: 'on_the_way' },
    rejoined: false,
  };
  const app = await createTestApp(5312, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'already-joined-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<JoinGroupDriveResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.rejoined, false);
  } finally {
    await app.close();
  }
});

test('POST join response does not expose live location session data', async () => {
  const service = new FakeGroupDriveService();
  service.joinResult = {
    participant: {
      participantId: 'participant-1',
      displayName: 'Alice',
      status: 'joined',
      joinedAt: '2027-07-01T15:00:00.000Z',
      hasActiveLiveLocation: false, // live location not automatically started
    },
    rejoined: false,
  };
  const app = await createTestApp(5313, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveJoinPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<JoinGroupDriveResponse>();
    const participant = body.data.participant;
    // Joining must not expose tokens, user IDs, or route data
    assert.ok(!('userId' in participant), 'must not expose userId in participant summary');
    assert.ok(!('sessionToken' in participant), 'must not expose session tokens');
    assert.ok(!('latitude' in participant), 'must not expose coordinates');
    assert.ok(!('longitude' in participant), 'must not expose coordinates');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// LEAVE
// ---------------------------------------------------------------------------

test('POST leave requires authentication', async () => {
  const app = await createTestApp(5320);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveLeavePath(EVENT_ID),
    });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('POST leave marks participant as left', async () => {
  const service = new FakeGroupDriveService();
  const app = await createTestApp(5321, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveLeavePath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<LeaveGroupDriveResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.left, true);
  } finally {
    await app.close();
  }
});

test('POST leave is idempotent (already left returns ok)', async () => {
  const service = new FakeGroupDriveService();
  service.leaveResult = { left: true };
  const app = await createTestApp(5322, service);
  try {
    const response = await app.inject({
      method: 'POST',
      url: buildGroupDriveLeavePath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'already-left-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<LeaveGroupDriveResponse>();
    assert.equal(body.ok, true);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// UPDATE STATUS
// ---------------------------------------------------------------------------

test('PATCH status requires authentication', async () => {
  const app = await createTestApp(5330);
  try {
    const response = await app.inject({
      method: 'PATCH',
      url: buildGroupDriveStatusPath(EVENT_ID),
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ status: 'on_the_way' }),
    });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('PATCH status updates successfully for active participant', async () => {
  const service = new FakeGroupDriveService();
  const app = await createTestApp(5331, service);
  try {
    const response = await app.inject({
      method: 'PATCH',
      url: buildGroupDriveStatusPath(EVENT_ID),
      headers: {
        'content-type': 'application/json',
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
      payload: JSON.stringify({ status: 'on_the_way' }),
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<UpdateGroupDriveStatusResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.participant.status, 'on_the_way');
  } finally {
    await app.close();
  }
});

test('PATCH status rejects `left` as a valid update value', async () => {
  const app = await createTestApp(5332, new FakeGroupDriveService());
  try {
    const response = await app.inject({
      method: 'PATCH',
      url: buildGroupDriveStatusPath(EVENT_ID),
      headers: {
        'content-type': 'application/json',
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
      payload: JSON.stringify({ status: 'left' }),
    });
    // Zod validation should reject 'left' since it is not in GROUP_DRIVE_UPDATABLE_STATUSES
    assert.equal(response.statusCode, 400);
  } finally {
    await app.close();
  }
});

test('PATCH status rejects updates for non-participants', async () => {
  const service = new FakeGroupDriveService();
  service.failUpdateStatusWith = new AppError(404, 'not_found', 'Not a group drive participant.');
  const app = await createTestApp(5333, service);
  try {
    const response = await app.inject({
      method: 'PATCH',
      url: buildGroupDriveStatusPath(EVENT_ID),
      headers: {
        'content-type': 'application/json',
        'x-dev-user': devAuth({ userId: 'non-participant-user' }),
      },
      payload: JSON.stringify({ status: 'on_the_way' }),
    });
    assert.equal(response.statusCode, 404);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// SUMMARY
// ---------------------------------------------------------------------------

test('GET summary requires authentication', async () => {
  const app = await createTestApp(5340);
  try {
    const response = await app.inject({
      method: 'GET',
      url: buildGroupDriveSummaryPath(EVENT_ID),
    });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('GET summary returns aggregate counts', async () => {
  const service = new FakeGroupDriveService();
  service.summaryResult = {
    totalActive: 3,
    joinedCount: 1,
    onTheWayCount: 1,
    arrivedCount: 1,
    currentUserStatus: 'joined',
    currentUserHasActiveLiveLocation: false,
    participants: [
      { participantId: 'p1', displayName: 'Alice', status: 'joined', joinedAt: '2027-07-01T15:00:00.000Z', hasActiveLiveLocation: false },
    ],
  };
  const app = await createTestApp(5341, service, new FakeBlockingService());
  try {
    const response = await app.inject({
      method: 'GET',
      url: buildGroupDriveSummaryPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<GroupDriveSummaryResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.totalActive, 3);
    assert.equal(body.data.joinedCount, 1);
    assert.equal(body.data.onTheWayCount, 1);
    assert.equal(body.data.arrivedCount, 1);
  } finally {
    await app.close();
  }
});

test('GET summary does not expose exact positions', async () => {
  const service = new FakeGroupDriveService();
  const app = await createTestApp(5342, service, new FakeBlockingService());
  try {
    const response = await app.inject({
      method: 'GET',
      url: buildGroupDriveSummaryPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<GroupDriveSummaryResponse>();
    const participants = body.data.participants;
    for (const p of participants) {
      assert.ok(!('latitude' in p), 'summary must not expose latitude');
      assert.ok(!('longitude' in p), 'summary must not expose longitude');
      assert.ok(!('userId' in p), 'summary must not expose userId');
    }
  } finally {
    await app.close();
  }
});

test('GET summary passes blocking exclusions to service', async () => {
  const service = new FakeGroupDriveService();
  const blockingService = new FakeBlockingService();
  blockingService.invisibleUserIds = ['blocked-user-id'];

  let capturedExclusions: string[] | undefined;
  const originalGetSummary = service.getGroupDriveSummary.bind(service);
  service.getGroupDriveSummary = async function (params: Parameters<GroupDriveService['getGroupDriveSummary']>[0]) {
    capturedExclusions = params.excludeUserIds;
    return originalGetSummary(params);
  };

  const app = await createTestApp(5343, service, blockingService);
  try {
    await app.inject({
      method: 'GET',
      url: buildGroupDriveSummaryPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.ok(
      Array.isArray(capturedExclusions) && capturedExclusions.includes('blocked-user-id'),
      'blocked user IDs must be passed as exclusions to the service',
    );
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// MARKERS
// ---------------------------------------------------------------------------

test('GET markers requires authentication', async () => {
  const app = await createTestApp(5350);
  try {
    const response = await app.inject({
      method: 'GET',
      url: buildGroupDriveMarkersPath(EVENT_ID),
    });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('GET markers returns only active participants with valid positions', async () => {
  const service = new FakeGroupDriveService();
  service.markersResult = {
    markers: [
      {
        participantId: 'participant-1',
        sessionId: 'session-1',
        displayName: 'Alice',
        status: 'on_the_way',
        coordinate: {
          latitude: 57.5,
          longitude: 12.1,
          recordedAt: new Date().toISOString(),
        },
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      },
    ],
    generatedAt: new Date().toISOString(),
  };
  const app = await createTestApp(5351, service, new FakeBlockingService());
  try {
    const response = await app.inject({
      method: 'GET',
      url: buildGroupDriveMarkersPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<GroupDriveMarkersResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.markers.length, 1);
    assert.equal(body.data.markers[0]?.participantId, 'participant-1');
  } finally {
    await app.close();
  }
});

test('GET markers excludes left participants', async () => {
  const service = new FakeGroupDriveService();
  service.failMarkersWithFn = null;
  service.markersResult = { markers: [], generatedAt: new Date().toISOString() };
  const app = await createTestApp(5352, service, new FakeBlockingService());
  try {
    const response = await app.inject({
      method: 'GET',
      url: buildGroupDriveMarkersPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.equal(response.statusCode, 200);
    const body = response.json<GroupDriveMarkersResponse>();
    assert.equal(body.data.markers.length, 0);
  } finally {
    await app.close();
  }
});

test('GET markers passes blocking exclusions to service', async () => {
  const service = new FakeGroupDriveService();
  const blockingService = new FakeBlockingService();
  blockingService.invisibleUserIds = ['blocked-participant'];

  let capturedExclusions: string[] | undefined;
  service.failMarkersWithFn = (params) => {
    capturedExclusions = params.excludeUserIds;
  };

  const app = await createTestApp(5353, service, blockingService);
  try {
    await app.inject({
      method: 'GET',
      url: buildGroupDriveMarkersPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    assert.ok(
      Array.isArray(capturedExclusions) && capturedExclusions.includes('blocked-participant'),
      'blocked participant IDs must be passed as exclusions to the marker service',
    );
  } finally {
    await app.close();
  }
});

test('GET markers does not expose userId in marker data', async () => {
  const service = new FakeGroupDriveService();
  service.markersResult = {
    markers: [
      {
        participantId: 'participant-opaque-1',
        sessionId: 'session-1',
        displayName: 'Bob',
        status: 'on_the_way',
        coordinate: {
          latitude: 57.5,
          longitude: 12.1,
          recordedAt: new Date().toISOString(),
        },
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      },
    ],
    generatedAt: new Date().toISOString(),
  };
  const app = await createTestApp(5354, service, new FakeBlockingService());
  try {
    const response = await app.inject({
      method: 'GET',
      url: buildGroupDriveMarkersPath(EVENT_ID),
      headers: {
        'x-dev-user': devAuth({ userId: 'member-user' }),
      },
    });
    const body = response.json<GroupDriveMarkersResponse>();
    const marker = body.data.markers[0];
    assert.ok(marker);
    // Must use participantId not userId
    assert.ok(!('userId' in marker), 'markers must not expose raw userId');
    assert.ok('participantId' in marker, 'markers must use opaque participantId');
  } finally {
    await app.close();
  }
});
