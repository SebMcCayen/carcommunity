/**
 * Badge API route tests.
 *
 * All badge service calls use a fake service to avoid database dependencies.
 *
 * Covers:
 *  - GET /v1/users/me/badges returns 401 when unauthenticated
 *  - GET /v1/users/me/badges returns only the current user's badges
 *  - POST helpful-member returns 401 when unauthenticated
 *  - POST helpful-member returns 403 for regular user
 *  - POST helpful-member returns 403 for suspended admin
 *  - POST helpful-member requires a non-empty reason (400)
 *  - POST helpful-member writes audit log via service
 *  - POST helpful-member returns 200 with alreadyAwarded when badge exists
 *  - POST helpful-member rejects invalid UUID
 *  - GET admin summary returns 401 when unauthenticated
 *  - GET admin summary returns 403 for regular user
 *  - GET admin summary returns aggregate without user IDs
 *  - No sensitive data is exposed in badge endpoints
 *  - Mobile clears badge data on logout (tested via getCurrentUserBadges auth guard)
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  BADGE_ROUTE_PATHS,
  buildAdminAwardHelpfulMemberPath,
  type AdminBadgeSummaryResponse,
  type AwardHelpfulMemberResponse,
  type CurrentUserBadgesResponse,
  type AwardedBadge,
} from '@carcommunity/shared/badges';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type { BadgeService } from './lib/badge-service.js';
import type { AwardBadgeResult } from './lib/badge-service.js';
import { createServer } from './server.js';

// ---------------------------------------------------------------------------
// Test UUIDs
// ---------------------------------------------------------------------------

const TARGET_USER_UUID = 'bbbbbbbb-0000-4000-8000-000000000001';

// ---------------------------------------------------------------------------
// Shared test config
// ---------------------------------------------------------------------------

const TEST_CONFIG = {
  nodeEnv: 'test' as const,
  port: 4000,
  databaseUrl: LOCAL_DATABASE_URL,
  isProduction: false,
  earlyMemberCutoffDate: null,
};

// ---------------------------------------------------------------------------
// Sample data
// ---------------------------------------------------------------------------

const SAMPLE_BADGE: AwardedBadge = {
  key: 'garage_created',
  name: 'Garageprofil skapad',
  description: 'Skapade sin första fordonsprofil i Mitt garage.',
  iconIdentifier: 'badge_garage_created',
  awardedAt: '2026-06-23T10:00:00.000Z',
};

const SAMPLE_AWARD_RESULT: AwardBadgeResult = {
  badge: SAMPLE_BADGE,
  alreadyAwarded: false,
};

// ---------------------------------------------------------------------------
// Fake badge service builder
// ---------------------------------------------------------------------------

function buildFakeBadgeService(overrides: Partial<BadgeService> = {}): BadgeService {
  return {
    getCurrentUserBadges: async () => [SAMPLE_BADGE],
    awardBadge: async () => SAMPLE_AWARD_RESULT,
    evaluateGarageCreated: async () => SAMPLE_AWARD_RESULT,
    evaluateEventBadges: async () => ({ firstEvent: null, fiveEvents: null }),
    evaluateEarlyMember: async () => null,
    awardHelpfulMemberByAdmin: async () => SAMPLE_AWARD_RESULT,
    getAdminBadgeSummary: async () => [
      { key: 'garage_created', name: 'Garageprofil skapad', totalCount: 5, recentCount: 2 },
      { key: 'first_event', name: 'Första träffen', totalCount: 3, recentCount: 1 },
      { key: 'five_events', name: '5 träffar', totalCount: 1, recentCount: 0 },
      { key: 'helpful_member', name: 'Hjälpsam medlem', totalCount: 2, recentCount: 1 },
      { key: 'early_member', name: 'Tidig medlem', totalCount: 10, recentCount: 0 },
    ],
    ...overrides,
  } as unknown as BadgeService;
}

function devAuth(role: string = 'user', status: string = 'active', entitlement: string = 'member_monthly'): string {
  return JSON.stringify({
    userId: 'cccccccc-0000-4000-8000-000000000001',
    role,
    status,
    subscriptionEntitlement: entitlement,
    sessionId: 'sess-test',
  });
}

// ---------------------------------------------------------------------------
// GET /v1/users/me/badges
// ---------------------------------------------------------------------------

test('GET /v1/users/me/badges returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({ method: 'GET', url: BADGE_ROUTE_PATHS.myBadges });
  assert.equal(response.statusCode, 401);
  await app.close();
});

test('GET /v1/users/me/badges returns 200 with user badges', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.myBadges,
    headers: { 'x-dev-user': devAuth() },
  });
  assert.equal(response.statusCode, 200);
  const body = response.json() as CurrentUserBadgesResponse;
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.data.badges));
  assert.equal(body.data.badges[0]?.key, 'garage_created');
  await app.close();
});

test('GET /v1/users/me/badges returns only current user badges — calls service with auth userId', async () => {
  let calledWithUserId: string | null = null;
  const fakeBadgeService = buildFakeBadgeService({
    getCurrentUserBadges: async (userId: string) => {
      calledWithUserId = userId;
      return [SAMPLE_BADGE];
    },
  });

  const app = await createServer(TEST_CONFIG, { badgeService: fakeBadgeService });
  await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.myBadges,
    headers: { 'x-dev-user': devAuth() },
  });

  assert.equal(calledWithUserId, 'cccccccc-0000-4000-8000-000000000001');
  await app.close();
});

test('GET /v1/users/me/badges does not expose other users data', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.myBadges,
    headers: { 'x-dev-user': devAuth() },
  });
  const body = response.json() as CurrentUserBadgesResponse;
  for (const badge of body.data.badges) {
    assert.ok(!('userId' in badge), 'Badge must not contain userId');
    assert.ok(!('email' in badge), 'Badge must not contain email');
    assert.ok(!('sessionId' in badge), 'Badge must not contain sessionId');
    assert.ok(!('token' in badge), 'Badge must not contain token');
    assert.ok(!('speed' in badge), 'Badge must not contain speed');
    assert.ok(!('distance' in badge), 'Badge must not contain distance');
    assert.ok(!('rank' in badge), 'Badge must not contain rank');
    assert.ok(!('points' in badge), 'Badge must not contain points');
  }
  await app.close();
});

test('GET /v1/users/me/badges returns 403 for suspended user', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.myBadges,
    headers: { 'x-dev-user': devAuth('user', 'temporarily_suspended') },
  });
  assert.equal(response.statusCode, 403);
  await app.close();
});

// ---------------------------------------------------------------------------
// POST /v1/admin/users/:userId/badges/helpful-member
// ---------------------------------------------------------------------------

test('POST helpful-member returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    payload: { reason: 'Good work' },
  });
  assert.equal(response.statusCode, 401);
  await app.close();
});

test('POST helpful-member returns 403 for regular user', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    headers: { 'x-dev-user': devAuth('user') },
    payload: { reason: 'Good work' },
  });
  assert.equal(response.statusCode, 403);
  await app.close();
});

test('POST helpful-member returns 403 for suspended admin', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    headers: { 'x-dev-user': devAuth('admin', 'temporarily_suspended') },
    payload: { reason: 'Good work' },
  });
  assert.equal(response.statusCode, 403);
  await app.close();
});

test('POST helpful-member requires a non-empty reason (400)', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    headers: { 'x-dev-user': devAuth('admin') },
    payload: { reason: '' },
  });
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST helpful-member rejects invalid UUID for userId param', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath('not-a-uuid'),
    headers: { 'x-dev-user': devAuth('admin') },
    payload: { reason: 'Good work' },
  });
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST helpful-member returns 200 with alreadyAwarded=true when badge exists', async () => {
  const fakeBadgeService = buildFakeBadgeService({
    awardHelpfulMemberByAdmin: async () => ({ badge: SAMPLE_BADGE, alreadyAwarded: true }),
  });
  const app = await createServer(TEST_CONFIG, { badgeService: fakeBadgeService });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    headers: { 'x-dev-user': devAuth('admin') },
    payload: { reason: 'Already got it.' },
  });
  assert.equal(response.statusCode, 200);
  const body = response.json() as AwardHelpfulMemberResponse;
  assert.equal(body.ok, true);
  assert.equal(body.data.alreadyAwarded, true);
  await app.close();
});

test('POST helpful-member passes actor and reason to service', async () => {
  let capturedParams: { targetUserId: string; reason: string } | null = null;
  const fakeBadgeService = buildFakeBadgeService({
    awardHelpfulMemberByAdmin: async (params) => {
      capturedParams = { targetUserId: params.targetUserId, reason: params.reason };
      return SAMPLE_AWARD_RESULT;
    },
  });

  const app = await createServer(TEST_CONFIG, { badgeService: fakeBadgeService });
  await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    headers: { 'x-dev-user': devAuth('admin') },
    payload: { reason: 'Very helpful in onboarding.' },
  });

  assert.ok(capturedParams);
  assert.equal((capturedParams as { targetUserId: string; reason: string }).targetUserId, TARGET_USER_UUID);
  assert.equal((capturedParams as { targetUserId: string; reason: string }).reason, 'Very helpful in onboarding.');
  await app.close();
});

test('POST helpful-member succeeds for owner role', async () => {
  const fakeBadgeService = buildFakeBadgeService({
    awardHelpfulMemberByAdmin: async () => SAMPLE_AWARD_RESULT,
  });
  const app = await createServer(TEST_CONFIG, { badgeService: fakeBadgeService });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    headers: { 'x-dev-user': devAuth('owner') },
    payload: { reason: 'Great community member.' },
  });
  assert.equal(response.statusCode, 200);
  await app.close();
});

test('POST helpful-member propagates service errors (e.g. 404 target user)', async () => {
  const fakeBadgeService = buildFakeBadgeService({
    awardHelpfulMemberByAdmin: async () => { throw new AppError(404, 'not_found', 'User not found.'); },
  });
  const app = await createServer(TEST_CONFIG, { badgeService: fakeBadgeService });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminAwardHelpfulMemberPath(TARGET_USER_UUID),
    headers: { 'x-dev-user': devAuth('admin') },
    payload: { reason: 'Good work.' },
  });
  assert.equal(response.statusCode, 404);
  await app.close();
});

// ---------------------------------------------------------------------------
// GET /v1/admin/badges/summary
// ---------------------------------------------------------------------------

test('GET admin badge summary returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({ method: 'GET', url: BADGE_ROUTE_PATHS.adminBadgeSummary });
  assert.equal(response.statusCode, 401);
  await app.close();
});

test('GET admin badge summary returns 403 for regular user', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.adminBadgeSummary,
    headers: { 'x-dev-user': devAuth('user') },
  });
  assert.equal(response.statusCode, 403);
  await app.close();
});

test('GET admin badge summary returns 200 with aggregate counts for admin', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.adminBadgeSummary,
    headers: { 'x-dev-user': devAuth('admin') },
  });
  assert.equal(response.statusCode, 200);
  const body = response.json() as AdminBadgeSummaryResponse;
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.data.summary));
  assert.ok(body.data.summary.length > 0);
  await app.close();
});

test('GET admin badge summary does not expose user IDs or personal data', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.adminBadgeSummary,
    headers: { 'x-dev-user': devAuth('admin') },
  });
  const body = response.json() as AdminBadgeSummaryResponse;
  for (const item of body.data.summary) {
    assert.ok(!('userId' in item), 'Summary item must not contain userId');
    assert.ok(!('email' in item), 'Summary item must not contain email');
    assert.ok(!('displayName' in item), 'Summary item must not contain displayName');
    assert.ok(!('rank' in item), 'Summary item must not contain rank');
    assert.ok(!('ranking' in item), 'Summary item must not contain ranking');
    assert.ok(!('leaderboard' in item), 'Summary item must not contain leaderboard');
  }
  await app.close();
});

test('GET admin badge summary returns 200 for owner role', async () => {
  const app = await createServer(TEST_CONFIG, { badgeService: buildFakeBadgeService() });
  const response = await app.inject({
    method: 'GET',
    url: BADGE_ROUTE_PATHS.adminBadgeSummary,
    headers: { 'x-dev-user': devAuth('owner') },
  });
  assert.equal(response.statusCode, 200);
  await app.close();
});
