import assert from 'node:assert/strict';
import test from 'node:test';

import type {
  ModerationActionSummary,
  ModerationResponse,
} from '@carcommunity/shared/users';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type { ModerationService } from './lib/moderation-service.js';
import { createServer } from './server.js';

// ---------------------------------------------------------------------------
// Fake ModerationService
// ---------------------------------------------------------------------------

const TARGET_USER_ID = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';
const ONE_DAY_MS = 86_400_000;

function makeActionSummary(actionType: ModerationActionSummary['actionType']): ModerationActionSummary {
  return {
    id: 'action-1',
    targetUserId: TARGET_USER_ID,
    actorUserId: 'admin-user-id',
    actionType,
    reason: 'Test reason',
    createdAt: '2026-06-12T10:00:00.000Z',
    expiresAt: actionType === 'temporary_suspension' ? '2026-07-12T10:00:00.000Z' : null,
  };
}

class FakeModerationService
  implements Pick<ModerationService, 'warnUser' | 'suspendTemporary' | 'suspendPermanent' | 'restoreAccess'>
{
  public error: AppError | null = null;

  async warnUser(): Promise<ModerationActionSummary> {
    if (this.error) throw this.error;
    return makeActionSummary('warning');
  }

  async suspendTemporary(): Promise<ModerationActionSummary> {
    if (this.error) throw this.error;
    return makeActionSummary('temporary_suspension');
  }

  async suspendPermanent(): Promise<ModerationActionSummary> {
    if (this.error) throw this.error;
    return makeActionSummary('permanent_suspension');
  }

  async restoreAccess(): Promise<ModerationActionSummary> {
    if (this.error) throw this.error;
    return makeActionSummary('restore_access');
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function createTestApp(port: number, moderationService?: FakeModerationService) {
  return createServer(
    {
      nodeEnv: 'test',
      port,
      databaseUrl: LOCAL_DATABASE_URL,
      isProduction: false,
    },
    { moderationService: moderationService as unknown as ModerationService },
  );
}

function devAuthHeader(overrides?: {
  userId?: string;
  role?: 'user' | 'admin' | 'owner';
  status?: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
  subscriptionEntitlement?: 'none' | 'member_monthly';
}) {
  return JSON.stringify({
    userId: 'admin-user-id',
    role: 'admin',
    status: 'active',
    subscriptionEntitlement: 'none',
    sessionId: 'dev-session-id',
    ...overrides,
  });
}

const validWarnBody = { reason: 'Violated community guidelines' };
const validSuspendTempBody = {
  reason: 'Repeated offences',
  expiresAt: new Date(Date.now() + ONE_DAY_MS).toISOString(),
};
const validSuspendPermBody = { reason: 'Severe policy violation' };
const validRestoreBody = { reason: 'Appeal accepted' };

// ---------------------------------------------------------------------------
// requireAdminHook: all moderation endpoints return 401 when unauthenticated
// ---------------------------------------------------------------------------

test('all admin moderation endpoints return 401 when unauthenticated', async () => {
  const app = await createTestApp(4200, new FakeModerationService());

  try {
    const routes: Array<{ method: 'POST' | 'GET'; url: string; payload?: object }> = [
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/warn`, payload: validWarnBody },
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/suspend-temporary`, payload: validSuspendTempBody },
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/suspend-permanent`, payload: validSuspendPermBody },
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/restore-access`, payload: validRestoreBody },
      { method: 'GET', url: '/v1/admin/audit-log' },
    ];

    for (const route of routes) {
      const response = await app.inject({ method: route.method, url: route.url, payload: route.payload });
      assert.equal(response.statusCode, 401, `${route.method} ${route.url} should return 401`);
      assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'unauthenticated');
    }
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// requireAdminHook: all moderation endpoints return 403 for regular users
// ---------------------------------------------------------------------------

test('all admin moderation endpoints return 403 for regular users', async () => {
  const app = await createTestApp(4201, new FakeModerationService());

  try {
    const headers = { 'x-dev-user': devAuthHeader({ role: 'user' }) };

    const routes: Array<{ method: 'POST' | 'GET'; url: string; payload?: object }> = [
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/warn`, payload: validWarnBody },
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/suspend-temporary`, payload: validSuspendTempBody },
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/suspend-permanent`, payload: validSuspendPermBody },
      { method: 'POST', url: `/v1/admin/users/${TARGET_USER_ID}/restore-access`, payload: validRestoreBody },
      { method: 'GET', url: '/v1/admin/audit-log' },
    ];

    for (const route of routes) {
      const response = await app.inject({ method: route.method, url: route.url, headers, payload: route.payload });
      assert.equal(response.statusCode, 403, `${route.method} ${route.url} should return 403`);
      assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'forbidden');
    }
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Validation: reason is required and must not be empty
// ---------------------------------------------------------------------------

test('POST warn returns 400 when reason is missing', async () => {
  const app = await createTestApp(4202, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/warn`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: {},
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('POST warn returns 400 when reason is empty string', async () => {
  const app = await createTestApp(4203, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/warn`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: { reason: '' },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Validation: expiresAt is required and must be a valid ISO datetime
// ---------------------------------------------------------------------------

test('POST suspend-temporary returns 400 when expiresAt is missing', async () => {
  const app = await createTestApp(4204, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/suspend-temporary`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: { reason: 'Repeated offences' },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('POST suspend-temporary returns 400 when expiresAt is not a valid ISO datetime', async () => {
  const app = await createTestApp(4205, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/suspend-temporary`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: { reason: 'Repeated offences', expiresAt: 'not-a-date' },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Owner protection: service-level 403 propagates correctly
// ---------------------------------------------------------------------------

test('POST warn returns 403 when service throws owner protection error', async () => {
  const service = new FakeModerationService();
  service.error = new AppError(403, 'forbidden', 'Admin users cannot moderate owner accounts.');
  const app = await createTestApp(4206, service);

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/warn`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: validWarnBody,
    });

    assert.equal(response.statusCode, 403);
    assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'forbidden');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Success: POST warn
// ---------------------------------------------------------------------------

test('POST warn returns 200 with correct payload for admin', async () => {
  const app = await createTestApp(4207, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/warn`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: validWarnBody,
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<ModerationResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.action.actionType, 'warning');
    assert.equal(body.data.action.targetUserId, TARGET_USER_ID);
    assert.equal(body.data.action.expiresAt, null);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Success: POST suspend-temporary
// ---------------------------------------------------------------------------

test('POST suspend-temporary returns 200 with correct payload for admin', async () => {
  const app = await createTestApp(4208, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/suspend-temporary`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: validSuspendTempBody,
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<ModerationResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.action.actionType, 'temporary_suspension');
    assert.notEqual(body.data.action.expiresAt, null);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Success: POST suspend-permanent
// ---------------------------------------------------------------------------

test('POST suspend-permanent returns 200 with correct payload for admin', async () => {
  const app = await createTestApp(4209, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/suspend-permanent`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: validSuspendPermBody,
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<ModerationResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.action.actionType, 'permanent_suspension');
    assert.equal(body.data.action.expiresAt, null);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Success: POST restore-access
// ---------------------------------------------------------------------------

test('POST restore-access returns 200 with correct payload for admin', async () => {
  const app = await createTestApp(4210, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/restore-access`,
      headers: { 'x-dev-user': devAuthHeader() },
      payload: validRestoreBody,
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<ModerationResponse>();
    assert.equal(body.ok, true);
    assert.equal(body.data.action.actionType, 'restore_access');
    assert.equal(body.data.action.expiresAt, null);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Validation: invalid UUID for userId returns 400
// ---------------------------------------------------------------------------

test('POST warn returns 400 when userId is not a valid UUID', async () => {
  const app = await createTestApp(4211, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/admin/users/not-a-uuid/warn',
      headers: { 'x-dev-user': devAuthHeader() },
      payload: validWarnBody,
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json<{ ok: false; error: { code: string } }>().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// owner role can also use moderation endpoints
// ---------------------------------------------------------------------------

test('POST warn accepts owner role', async () => {
  const app = await createTestApp(4212, new FakeModerationService());

  try {
    const response = await app.inject({
      method: 'POST',
      url: `/v1/admin/users/${TARGET_USER_ID}/warn`,
      headers: { 'x-dev-user': devAuthHeader({ role: 'owner' }) },
      payload: validWarnBody,
    });

    assert.equal(response.statusCode, 200);
  } finally {
    await app.close();
  }
});
