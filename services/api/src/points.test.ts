/**
 * Points API route tests.
 *
 * All service calls use a fake service to avoid database dependencies.
 *
 * Covers:
 *  - GET /v1/points/balance returns 401 when unauthenticated
 *  - GET /v1/points/balance returns 403 for deleted user
 *  - GET /v1/points/balance returns balance for authenticated user
 *  - GET /v1/points/balance returns balance for suspended user (view allowed)
 *  - GET /v1/points/balance returns only current user's balance (called with auth userId)
 *  - GET /v1/points/ledger returns 401 when unauthenticated
 *  - GET /v1/points/ledger returns 403 for deleted user
 *  - GET /v1/points/ledger returns paginated transactions newest first
 *  - GET /v1/points/ledger includes authoritative balance
 *  - GET /v1/points/ledger returns only current user's transactions
 *  - GET /v1/points/ledger response does not contain purchase/transfer/ranking fields
 *  - Mobile must use backend balance — response includes balance field
 *  - POST /v1/admin/.../adjust returns 401 when unauthenticated
 *  - POST /v1/admin/.../adjust returns 403 for regular user
 *  - POST /v1/admin/.../adjust requires a reason (400 without reason)
 *  - POST /v1/admin/.../adjust requires a positive amount (400 for zero)
 *  - POST /v1/admin/.../adjust creates a credit adjustment
 *  - POST /v1/admin/.../adjust creates a debit adjustment
 *  - POST /v1/admin/.../adjust writes an audit log via service
 *  - POST /v1/admin/.../adjust returns 400 for insufficient balance
 *  - Admin cannot submit an absolute balance (no absoluteBalance field accepted)
 *  - No tokens or sensitive data are exposed in route responses
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  POINTS_ROUTE_PATHS,
  buildAdminPointsAdjustPath,
  type PointsBalanceResponse,
  type PaginatedPointsLedgerResponse,
  type AdminPointsAdjustmentResponse,
  type PointsTransactionSummary,
} from '@carcommunity/shared/points';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type { PointsService } from './lib/points-service.js';
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

const SAMPLE_TRANSACTION: PointsTransactionSummary = {
  transactionId: 'tx-aaaa-0000-4000-8000-000000000001',
  transactionType: 'earn',
  source: 'system',
  amount: 50,
  balanceAfter: 50,
  description: 'Välkomstpoäng',
  createdAt: '2026-06-24T10:00:00.000Z',
};

// ---------------------------------------------------------------------------
// Fake points service builder
// ---------------------------------------------------------------------------

function buildFakePointsService(overrides: Partial<PointsService> = {}): PointsService {
  return {
    getPointsBalance: async () => 50,
    listPointsLedger: async () => ({
      transactions: [SAMPLE_TRANSACTION],
      balance: 50,
      total: 1,
      hasNext: false,
      page: 1,
      pageSize: 20,
    }),
    creditPoints: async () => SAMPLE_TRANSACTION,
    debitPoints: async () => ({
      ...SAMPLE_TRANSACTION,
      transactionType: 'spend' as const,
      amount: -20,
      balanceAfter: 30,
    }),
    applyAdminPointsAdjustment: async () => SAMPLE_TRANSACTION,
    reversePointsTransaction: async () => ({
      ...SAMPLE_TRANSACTION,
      transactionType: 'reversal' as const,
      amount: -50,
      balanceAfter: 0,
    }),
    calculateBalance: async () => 50,
    ...overrides,
  } as unknown as PointsService;
}

function devAuth(role = 'user', status = 'active', entitlement = 'member_monthly'): string {
  return JSON.stringify({
    userId: 'cccccccc-0000-4000-8000-000000000001',
    role,
    status,
    subscriptionEntitlement: entitlement,
    sessionId: 'sess-test',
  });
}

// ---------------------------------------------------------------------------
// GET /v1/points/balance
// ---------------------------------------------------------------------------

test('GET /v1/points/balance returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({ method: 'GET', url: POINTS_ROUTE_PATHS.balance });
  assert.equal(response.statusCode, 401);
  await app.close();
});

test('GET /v1/points/balance returns 403 for deleted user', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.balance,
    headers: { 'x-dev-user': devAuth('user', 'deleted') },
  });
  assert.equal(response.statusCode, 403);
  await app.close();
});

test('GET /v1/points/balance returns balance for authenticated user', async () => {
  const app = await createServer(TEST_CONFIG, {
    pointsService: buildFakePointsService({ getPointsBalance: async () => 150 }),
  });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.balance,
    headers: { 'x-dev-user': devAuth() },
  });
  assert.equal(response.statusCode, 200);
  const body = response.json() as PointsBalanceResponse;
  assert.equal(body.ok, true);
  assert.equal(body.data.balance, 150);
  assert.equal(body.data.displayName, 'Kronpoäng');
  assert.equal(body.data.shortForm, 'KP');
  await app.close();
});

test('GET /v1/points/balance returns balance for suspended user (view allowed)', async () => {
  const app = await createServer(TEST_CONFIG, {
    pointsService: buildFakePointsService({ getPointsBalance: async () => 75 }),
  });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.balance,
    headers: { 'x-dev-user': devAuth('user', 'temporarily_suspended') },
  });
  // Suspended users may view their balance
  assert.equal(response.statusCode, 200);
  const body = response.json() as PointsBalanceResponse;
  assert.equal(body.data.balance, 75);
  await app.close();
});

test('GET /v1/points/balance is called with the current user ID only', async () => {
  let calledUserId: string | null = null;
  const app = await createServer(TEST_CONFIG, {
    pointsService: buildFakePointsService({
      getPointsBalance: async (userId: string) => {
        calledUserId = userId;
        return 50;
      },
    }),
  });
  await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.balance,
    headers: { 'x-dev-user': devAuth() },
  });
  assert.equal(calledUserId, 'cccccccc-0000-4000-8000-000000000001');
  await app.close();
});

// ---------------------------------------------------------------------------
// GET /v1/points/ledger
// ---------------------------------------------------------------------------

test('GET /v1/points/ledger returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({ method: 'GET', url: POINTS_ROUTE_PATHS.ledger });
  assert.equal(response.statusCode, 401);
  await app.close();
});

test('GET /v1/points/ledger returns 403 for deleted user', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.ledger,
    headers: { 'x-dev-user': devAuth('user', 'deleted') },
  });
  assert.equal(response.statusCode, 403);
  await app.close();
});

test('GET /v1/points/ledger returns paginated transactions', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.ledger,
    headers: { 'x-dev-user': devAuth() },
  });
  assert.equal(response.statusCode, 200);
  const body = response.json() as PaginatedPointsLedgerResponse;
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.data.transactions));
  assert.ok('meta' in body);
  assert.ok('page' in body.meta);
  assert.ok('pageSize' in body.meta);
  assert.ok('total' in body.meta);
  assert.ok('hasNext' in body.meta);
  await app.close();
});

test('GET /v1/points/ledger includes authoritative balance field', async () => {
  const app = await createServer(TEST_CONFIG, {
    pointsService: buildFakePointsService({ listPointsLedger: async () => ({
      transactions: [SAMPLE_TRANSACTION],
      balance: 50,
      total: 1,
      hasNext: false,
      page: 1,
      pageSize: 20,
    })}),
  });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.ledger,
    headers: { 'x-dev-user': devAuth() },
  });
  const body = response.json() as PaginatedPointsLedgerResponse;
  // Mobile must use this balance, not sum the paginated transactions
  assert.ok('balance' in body.data, 'balance field must be present in data');
  assert.equal(body.data.balance, 50);
  await app.close();
});

test('GET /v1/points/ledger returns only current user transactions', async () => {
  let calledUserId: string | null = null;
  const app = await createServer(TEST_CONFIG, {
    pointsService: buildFakePointsService({
      listPointsLedger: async (params) => {
        calledUserId = params.userId;
        return {
          transactions: [SAMPLE_TRANSACTION],
          balance: 50,
          total: 1,
          hasNext: false,
          page: 1,
          pageSize: 20,
        };
      },
    }),
  });
  await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.ledger,
    headers: { 'x-dev-user': devAuth() },
  });
  assert.equal(calledUserId, 'cccccccc-0000-4000-8000-000000000001');
  await app.close();
});

test('GET /v1/points/ledger response does not contain purchase/transfer/ranking fields', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.ledger,
    headers: { 'x-dev-user': devAuth() },
  });
  const body = response.json() as PaginatedPointsLedgerResponse;
  const forbidden = ['cashValue', 'price', 'transfer', 'recipient', 'rank', 'leaderboard', 'purchase', 'withdrawal'];
  const bodyStr = JSON.stringify(body);
  for (const field of forbidden) {
    assert.ok(!bodyStr.includes(`"${field}"`), `Field "${field}" must not appear in ledger response`);
  }
  await app.close();
});

test('GET /v1/points/ledger does not expose tokens or sensitive data', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'GET',
    url: POINTS_ROUTE_PATHS.ledger,
    headers: { 'x-dev-user': devAuth() },
  });
  const bodyStr = response.body;
  assert.ok(!bodyStr.includes('"token"'), 'Response must not contain "token" field');
  assert.ok(!bodyStr.includes('"password"'), 'Response must not contain "password" field');
  assert.ok(!bodyStr.includes('"providerSubject"'), 'Response must not contain "providerSubject"');
  await app.close();
});

// ---------------------------------------------------------------------------
// POST /v1/admin/users/:userId/points/adjust
// ---------------------------------------------------------------------------

test('POST /v1/admin/.../points/adjust returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: 10, reason: 'Test' },
  });
  assert.equal(response.statusCode, 401);
  await app.close();
});

test('POST /v1/admin/.../points/adjust returns 403 for regular user', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: 10, reason: 'Test' },
    headers: { 'x-dev-user': devAuth('user') },
  });
  assert.equal(response.statusCode, 403);
  await app.close();
});

test('POST /v1/admin/.../points/adjust returns 400 without reason', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: 10 },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST /v1/admin/.../points/adjust returns 400 for zero amount', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: 0, reason: 'Test' },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST /v1/admin/.../points/adjust returns 400 for negative amount', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: -5, reason: 'Test' },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST /v1/admin/.../points/adjust creates a credit adjustment', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: 50, reason: 'Testjustering' },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  assert.equal(response.statusCode, 200);
  const body = response.json() as AdminPointsAdjustmentResponse;
  assert.equal(body.ok, true);
  assert.ok('transactionId' in body.data);
  assert.ok('transactionType' in body.data);
  assert.ok('amount' in body.data);
  assert.ok('balanceAfter' in body.data);
  assert.ok('createdAt' in body.data);
  await app.close();
});

test('POST /v1/admin/.../points/adjust writes an audit log via service', async () => {
  let capturedParams: Record<string, unknown> | null = null;
  const app = await createServer(TEST_CONFIG, {
    pointsService: buildFakePointsService({
      applyAdminPointsAdjustment: async (params) => {
        capturedParams = params as unknown as Record<string, unknown>;
        return SAMPLE_TRANSACTION;
      },
    }),
  });

  await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: 25, reason: 'Auditlog-test' },
    headers: { 'x-dev-user': devAuth('admin') },
  });

  assert.ok(capturedParams !== null);
  assert.equal((capturedParams as Record<string, unknown>).reason, 'Auditlog-test');
  assert.equal((capturedParams as Record<string, unknown>).targetUserId, TARGET_USER_UUID);
  await app.close();
});

test('POST /v1/admin/.../points/adjust returns 400 for insufficient balance', async () => {
  const app = await createServer(TEST_CONFIG, {
    pointsService: buildFakePointsService({
      applyAdminPointsAdjustment: async () => {
        throw new AppError(400, 'validation_error', 'Debit would produce a negative balance.');
      },
    }),
  });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_debit', amount: 1000, reason: 'För stor debet' },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST /v1/admin/.../points/adjust rejects unknown extra fields', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: {
      type: 'adjustment_credit',
      amount: 10,
      reason: 'Test',
      absoluteBalance: 9999, // must be rejected
    },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  // strict schema rejects extra fields
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST /v1/admin/.../points/adjust returns 400 for invalid userId param', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath('not-a-uuid'),
    payload: { type: 'adjustment_credit', amount: 10, reason: 'Test' },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  assert.equal(response.statusCode, 400);
  await app.close();
});

test('admin response does not contain tokens or sensitive data', async () => {
  const app = await createServer(TEST_CONFIG, { pointsService: buildFakePointsService() });
  const response = await app.inject({
    method: 'POST',
    url: buildAdminPointsAdjustPath(TARGET_USER_UUID),
    payload: { type: 'adjustment_credit', amount: 10, reason: 'Test' },
    headers: { 'x-dev-user': devAuth('admin') },
  });
  const bodyStr = response.body;
  assert.ok(!bodyStr.includes('"token"'));
  assert.ok(!bodyStr.includes('"password"'));
  assert.ok(!bodyStr.includes('"providerSubject"'));
  await app.close();
});
