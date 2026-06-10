import assert from 'node:assert/strict';
import test from 'node:test';

import {
  EVENT_ROUTE_PATHS,
  buildEventDetailPath,
  buildEventRsvpPath,
  type AdminEventsResponse,
  type EventDetailResponse,
  type EventRsvpResponse,
  type EventTeasersResponse,
} from '@carcommunity/shared/events';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type {
  EventService,
  GetAdminEventsResult,
  GetEventDetailResult,
  GetEventTeasersResult,
  UpsertRsvpResult,
} from './lib/event-service.js';
import { createServer } from './server.js';

// ---------------------------------------------------------------------------
// Fake service
// ---------------------------------------------------------------------------

class FakeEventService
  implements Pick<EventService, 'getEventTeasers' | 'getEventDetail' | 'upsertRsvp' | 'getAdminEvents'>
{
  public teasersResult: GetEventTeasersResult = {
    events: [
      {
        id: 'event-1',
        title: 'Träff i Kungsbacka',
        startsAt: '2027-07-01T16:00:00.000Z',
        endsAt: '2027-07-01T20:00:00.000Z',
        approximateArea: 'Kungsbacka centrum',
        isOfficial: true,
        status: 'published',
      },
    ],
    total: 1,
    nextCursor: null,
  };

  public detailResult: GetEventDetailResult = {
    event: {
      id: 'event-1',
      title: 'Träff i Kungsbacka',
      summary: 'En härlig träff',
      description: 'Kom och häng med oss.',
      startsAt: '2027-07-01T16:00:00.000Z',
      endsAt: '2027-07-01T20:00:00.000Z',
      locationName: 'Kungsbacka Torg',
      address: 'Stortorget 1, Kungsbacka',
      latitude: 57.4875,
      longitude: 12.0762,
      isOfficial: true,
      status: 'published',
      rsvpSummary: { going: 5, maybe: 3, not_going: 1 },
      currentUserRsvp: 'going',
    },
  };

  public rsvpResult: UpsertRsvpResult = {
    eventId: 'event-1',
    userId: 'user-1',
    status: 'going',
    updatedAt: '2027-06-01T10:00:00.000Z',
  };

  public adminResult: GetAdminEventsResult = {
    events: [
      {
        id: 'event-1',
        title: 'Träff i Kungsbacka',
        status: 'published',
        isOfficial: true,
        startsAt: '2027-07-01T16:00:00.000Z',
        endsAt: '2027-07-01T20:00:00.000Z',
        rsvpCounts: { going: 5, maybe: 3, not_going: 1 },
        cancelledAt: null,
        createdAt: '2027-05-01T10:00:00.000Z',
      },
    ],
    total: 1,
    page: 1,
    pageSize: 20,
  };

  public failDetailWith: AppError | null = null;
  public failRsvpWith: AppError | null = null;

  async getEventTeasers(_params?: Parameters<EventService['getEventTeasers']>[0]): Promise<GetEventTeasersResult> {
    return this.teasersResult;
  }

  async getEventDetail(): Promise<GetEventDetailResult> {
    if (this.failDetailWith) throw this.failDetailWith;
    return this.detailResult;
  }

  async upsertRsvp(): Promise<UpsertRsvpResult> {
    if (this.failRsvpWith) throw this.failRsvpWith;
    return this.rsvpResult;
  }

  async getAdminEvents(_params?: { page?: number; pageSize?: number }): Promise<GetAdminEventsResult> {
    return this.adminResult;
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function createTestApp(port: number, eventService?: FakeEventService) {
  return createServer(
    {
      nodeEnv: 'test',
      port,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { eventService: eventService as unknown as EventService },
  );
}

function devAuth(input: {
  userId: string;
  role: 'user' | 'admin' | 'owner';
  status: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
  subscriptionEntitlement: 'none' | 'member_monthly';
}): string {
  return JSON.stringify({ ...input, sessionId: 'dev-session' });
}

// ---------------------------------------------------------------------------
// GET /v1/events/teasers
// ---------------------------------------------------------------------------

test('GET /v1/events/teasers requires authentication', async () => {
  const app = await createTestApp(4200, new FakeEventService());

  try {
    const response = await app.inject({ method: 'GET', url: EVENT_ROUTE_PATHS.teasers });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('GET /v1/events/teasers returns teasers for free authenticated user', async () => {
  const app = await createTestApp(4201, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.teasers,
      headers: {
        'x-dev-user': devAuth({
          userId: 'free-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<EventTeasersResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.events.length, 1);
    assert.equal(body.data.events[0]?.id, 'event-1');
    assert.equal(body.meta.total, 1);
    assert.equal(body.meta.nextCursor, null);
  } finally {
    await app.close();
  }
});

test('GET /v1/events/teasers teaser does not expose exact address or coordinates', async () => {
  const app = await createTestApp(4202, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.teasers,
      headers: {
        'x-dev-user': devAuth({
          userId: 'free-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<EventTeasersResponse>();
    const teaser = body.data.events[0];
    assert.ok(teaser);
    // Teasers must NOT contain address, latitude, longitude, locationName
    assert.ok(!('address' in teaser), 'teaser must not expose address');
    assert.ok(!('latitude' in teaser), 'teaser must not expose latitude');
    assert.ok(!('longitude' in teaser), 'teaser must not expose longitude');
    assert.ok(!('locationName' in teaser), 'teaser must not expose locationName');
  } finally {
    await app.close();
  }
});

test('GET /v1/events/teasers blocks suspended user', async () => {
  const app = await createTestApp(4203, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.teasers,
      headers: {
        'x-dev-user': devAuth({
          userId: 'suspended-user',
          role: 'user',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });

    // requireAuthHook blocks suspended users with 403
    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('GET /v1/events/teasers supports cursor and take pagination', async () => {
  const service = new FakeEventService();
  service.teasersResult = {
    events: [
      {
        id: 'event-2',
        title: 'Nästa träff',
        startsAt: '2027-08-01T16:00:00.000Z',
        endsAt: null,
        approximateArea: 'Göteborg',
        isOfficial: false,
        status: 'published',
      },
    ],
    total: 5,
    nextCursor: 'event-3',
  };
  const app = await createTestApp(4222, service);

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${EVENT_ROUTE_PATHS.teasers}?cursor=cb8f7c4f-e930-4e01-ae85-61d2d93248cb&take=1`,
      headers: {
        'x-dev-user': devAuth({
          userId: 'free-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<EventTeasersResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.meta.total, 5);
    assert.equal(body.meta.nextCursor, 'event-3');
    assert.equal(body.data.events.length, 1);
  } finally {
    await app.close();
  }
});

test('GET /v1/events/teasers blocks deleted user', async () => {
  const app = await createTestApp(4204, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.teasers,
      headers: {
        'x-dev-user': devAuth({
          userId: 'deleted-user',
          role: 'user',
          status: 'deleted',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// GET /v1/events/:eventId
// ---------------------------------------------------------------------------

test('GET /v1/events/:eventId requires authentication', async () => {
  const app = await createTestApp(4205, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: buildEventDetailPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
    });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('GET /v1/events/:eventId blocks free user — no member subscription', async () => {
  const app = await createTestApp(4206, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: buildEventDetailPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'free-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('GET /v1/events/:eventId returns full detail for member', async () => {
  const app = await createTestApp(4207, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: buildEventDetailPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'member-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<EventDetailResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.event.id, 'event-1');
    assert.equal(body.data.event.locationName, 'Kungsbacka Torg');
    assert.equal(body.data.event.latitude, 57.4875);
    assert.equal(body.data.event.currentUserRsvp, 'going');
    assert.deepEqual(body.data.event.rsvpSummary, { going: 5, maybe: 3, not_going: 1 });
  } finally {
    await app.close();
  }
});

test('GET /v1/events/:eventId blocks suspended member', async () => {
  const app = await createTestApp(4208, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: buildEventDetailPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'suspended-member',
          role: 'user',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });

    assert.equal(response.statusCode, 403);
    const body = response.json<{ ok: false; error: { code: string } }>();
    assert.equal(body.error.code, 'suspended');
  } finally {
    await app.close();
  }
});

test('GET /v1/events/:eventId blocks deleted user', async () => {
  const app = await createTestApp(4209, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: buildEventDetailPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'deleted-user',
          role: 'user',
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

// ---------------------------------------------------------------------------
// POST /v1/events/:eventId/rsvp
// ---------------------------------------------------------------------------

test('POST /v1/events/:eventId/rsvp requires authentication', async () => {
  const app = await createTestApp(4210, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildEventRsvpPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      payload: { status: 'going' },
    });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('POST /v1/events/:eventId/rsvp blocks free user', async () => {
  const app = await createTestApp(4211, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildEventRsvpPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'free-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
      payload: { status: 'going' },
    });

    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('POST /v1/events/:eventId/rsvp member can RSVP successfully', async () => {
  const app = await createTestApp(4212, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildEventRsvpPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'member-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
      payload: { status: 'going' },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<EventRsvpResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.rsvp.status, 'going');
    assert.equal(body.data.rsvp.eventId, 'event-1');
  } finally {
    await app.close();
  }
});

test('POST /v1/events/:eventId/rsvp member can update RSVP (upsert)', async () => {
  const service = new FakeEventService();
  service.rsvpResult = { eventId: 'event-1', userId: 'member-user', status: 'maybe', updatedAt: '2027-06-02T10:00:00.000Z' };
  const app = await createTestApp(4213, service);

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildEventRsvpPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'member-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
      payload: { status: 'maybe' },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<EventRsvpResponse>();
    assert.equal(body.data.rsvp.status, 'maybe');
  } finally {
    await app.close();
  }
});

test('POST /v1/events/:eventId/rsvp rejects invalid status', async () => {
  const app = await createTestApp(4214, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildEventRsvpPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'member-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
      payload: { status: 'interested' },
    });

    assert.equal(response.statusCode, 400);
  } finally {
    await app.close();
  }
});

test('POST /v1/events/:eventId/rsvp blocks suspended member', async () => {
  const app = await createTestApp(4215, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildEventRsvpPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'suspended-member',
          role: 'user',
          status: 'permanently_suspended',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
      payload: { status: 'going' },
    });

    assert.equal(response.statusCode, 403);
    const body = response.json<{ ok: false; error: { code: string } }>();
    assert.equal(body.error.code, 'suspended');
  } finally {
    await app.close();
  }
});

test('POST /v1/events/:eventId/rsvp blocks deleted user', async () => {
  const app = await createTestApp(4216, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: buildEventRsvpPath('cb8f7c4f-e930-4e01-ae85-61d2d93248cb'),
      headers: {
        'x-dev-user': devAuth({
          userId: 'deleted-user',
          role: 'user',
          status: 'deleted',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
      payload: { status: 'going' },
    });

    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// GET /v1/admin/events
// ---------------------------------------------------------------------------

test('GET /v1/admin/events requires authentication', async () => {
  const app = await createTestApp(4217, new FakeEventService());

  try {
    const response = await app.inject({ method: 'GET', url: EVENT_ROUTE_PATHS.adminEvents });
    assert.equal(response.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/events blocks non-admin users', async () => {
  const app = await createTestApp(4218, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.adminEvents,
      headers: {
        'x-dev-user': devAuth({
          userId: 'member-user',
          role: 'user',
          status: 'active',
          subscriptionEntitlement: 'member_monthly',
        }),
      },
    });

    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/events allows admin and returns event summary', async () => {
  const app = await createTestApp(4219, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.adminEvents,
      headers: {
        'x-dev-user': devAuth({
          userId: 'admin-user',
          role: 'admin',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<AdminEventsResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.events.length, 1);
    assert.equal(body.data.events[0]?.id, 'event-1');
    assert.equal(body.data.events[0]?.status, 'published');
    assert.equal(body.meta.total, 1);
    assert.equal(body.meta.page, 1);
    assert.equal(body.meta.pageSize, 20);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/events allows owner', async () => {
  const app = await createTestApp(4220, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.adminEvents,
      headers: {
        'x-dev-user': devAuth({
          userId: 'owner-user',
          role: 'owner',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 200);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/events accepts page and pageSize query params', async () => {
  const service = new FakeEventService();
  service.adminResult = { ...service.adminResult, page: 2, pageSize: 5 };
  const app = await createTestApp(4222, service);

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${EVENT_ROUTE_PATHS.adminEvents}?page=2&pageSize=5`,
      headers: {
        'x-dev-user': devAuth({
          userId: 'admin-user',
          role: 'admin',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<AdminEventsResponse>();
    assert.equal(body.meta.page, 2);
    assert.equal(body.meta.pageSize, 5);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/events rejects invalid pageSize above max', async () => {
  const app = await createTestApp(4223, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: `${EVENT_ROUTE_PATHS.adminEvents}?pageSize=200`,
      headers: {
        'x-dev-user': devAuth({
          userId: 'admin-user',
          role: 'admin',
          status: 'active',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 400);
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/events blocks suspended admin', async () => {
  const app = await createTestApp(4221, new FakeEventService());

  try {
    const response = await app.inject({
      method: 'GET',
      url: EVENT_ROUTE_PATHS.adminEvents,
      headers: {
        'x-dev-user': devAuth({
          userId: 'suspended-admin',
          role: 'admin',
          status: 'temporarily_suspended',
          subscriptionEntitlement: 'none',
        }),
      },
    });

    assert.equal(response.statusCode, 403);
  } finally {
    await app.close();
  }
});
