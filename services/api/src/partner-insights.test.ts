import assert from 'node:assert/strict';
import test from 'node:test';

import { createServer } from './server.js';
import { PartnerInsightsService } from './lib/partner-insights-service.js';
import {
  PARTNER_INSIGHTS_ROUTE_PATHS,
  buildAdminInsightsPath,
  buildAdminInsightsSummaryPath,
  buildRecordInteractionPath,
  type PartnerInsightsSummary,
  type PartnerInsightsTimeSeriesBucket,
} from '@carcommunity/shared/partner-insights';

const ADMIN_USER = JSON.stringify({
  userId: 'aaaaaaaa-0000-4000-8000-000000000002',
  role: 'admin',
  status: 'active',
  subscriptionEntitlement: 'none',
  sessionId: 'session-admin',
});

const FREE_USER = JSON.stringify({
  userId: 'aaaaaaaa-0000-4000-8000-000000000003',
  role: 'user',
  status: 'active',
  subscriptionEntitlement: 'none',
  sessionId: 'session-free',
});

const PARTNER_ID = 'bbbbbbbb-0000-4000-8000-000000000001';
const OFFER_ID = 'cccccccc-0000-4000-8000-000000000001';

const SUMMARY: PartnerInsightsSummary = {
  partnerId: PARTNER_ID,
  period: 'last_30_days',
  generatedAt: new Date('2026-06-28T12:00:00.000Z').toISOString(),
  metrics: [
    {
      interactionType: 'profile_view',
      totalCount: 12,
      uniqueContributorCount: 12,
      periodStart: new Date('2026-06-01T00:00:00.000Z').toISOString(),
      periodEnd: new Date('2026-06-30T00:00:00.000Z').toISOString(),
      status: 'available',
    },
    {
      interactionType: 'anonymous_pass_by',
      totalCount: 0,
      periodStart: new Date('2026-06-01T00:00:00.000Z').toISOString(),
      periodEnd: new Date('2026-06-30T00:00:00.000Z').toISOString(),
      status: 'insufficient_data',
    },
  ],
};

const BUCKETS: PartnerInsightsTimeSeriesBucket[] = [
  {
    periodStart: new Date('2026-06-21T00:00:00.000Z').toISOString(),
    periodEnd: new Date('2026-06-22T00:00:00.000Z').toISOString(),
    periodType: 'day',
    metrics: [
      {
        interactionType: 'profile_view',
        totalCount: 3,
        uniqueContributorCount: 3,
        periodStart: new Date('2026-06-21T00:00:00.000Z').toISOString(),
        periodEnd: new Date('2026-06-22T00:00:00.000Z').toISOString(),
        status: 'available',
      },
      {
        interactionType: 'anonymous_pass_by',
        totalCount: 0,
        periodStart: new Date('2026-06-21T00:00:00.000Z').toISOString(),
        periodEnd: new Date('2026-06-22T00:00:00.000Z').toISOString(),
        status: 'insufficient_data',
      },
    ],
  },
];

class FakePartnerInsightsService extends PartnerInsightsService {
  constructor() {
    super(null as unknown as import('@prisma/client').PrismaClient);
  }

  override async recordInteraction(_input: unknown) {
    return { recorded: true };
  }

  override async getAdminInsights(_input: unknown) {
    return {
      partnerId: PARTNER_ID,
      period: 'last_30_days' as const,
      buckets: BUCKETS,
      generatedAt: SUMMARY.generatedAt,
    };
  }

  override async getAdminInsightsSummary(_input: unknown) {
    return SUMMARY;
  }
}

async function buildTestServer() {
  return createServer(
    {
      nodeEnv: 'test',
      port: 4000,
      databaseUrl: 'postgresql://placeholder',
      isProduction: false,
    },
    {
      partnerInsightsService: new FakePartnerInsightsService(),
    },
  );
}

test('POST /v1/partners/:partnerId/interactions requires authentication', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'POST',
    url: buildRecordInteractionPath(PARTNER_ID),
    payload: { interactionType: 'profile_view' },
  });

  assert.equal(response.statusCode, 401);
  await server.close();
});

test('POST interaction rejects body with userId field', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'POST',
    url: buildRecordInteractionPath(PARTNER_ID),
    headers: { 'x-dev-user': FREE_USER },
    payload: {
      interactionType: 'profile_view',
      userId: 'malicious-user-id',
    },
  });

  assert.equal(response.statusCode, 400);
  assert.equal(response.json().error.code, 'validation_error');
  await server.close();
});

test('POST interaction rejects body with coordinates field', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'POST',
    url: buildRecordInteractionPath(PARTNER_ID),
    headers: { 'x-dev-user': FREE_USER },
    payload: {
      interactionType: 'profile_view',
      coordinates: { latitude: 57.5, longitude: 12.07 },
    },
  });

  assert.equal(response.statusCode, 400);
  assert.equal(response.json().error.code, 'validation_error');
  await server.close();
});

test('GET admin insights requires admin access', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'GET',
    url: buildAdminInsightsPath(PARTNER_ID),
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(response.statusCode, 403);
  await server.close();
});

test('GET admin insights summary requires admin access', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'GET',
    url: buildAdminInsightsSummaryPath(PARTNER_ID),
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(response.statusCode, 403);
  await server.close();
});

test('admin insights returns aggregate values only', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'GET',
    url: `${buildAdminInsightsPath(PARTNER_ID)}?period=last_30_days`,
    headers: { 'x-dev-user': ADMIN_USER },
  });

  assert.equal(response.statusCode, 200);
  const payload = response.json();
  assert.equal(payload.ok, true);
  assert.equal(payload.data.partnerId, PARTNER_ID);
  assert.equal(Array.isArray(payload.data.buckets), true);
  assert.equal(payload.data.buckets[0].metrics[0].interactionType, 'profile_view');
  assert.equal('userId' in payload.data.buckets[0], false);
  await server.close();
});

test('admin insights preserves insufficient_data suppression', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'GET',
    url: `${buildAdminInsightsSummaryPath(PARTNER_ID)}?period=last_30_days`,
    headers: { 'x-dev-user': ADMIN_USER },
  });

  assert.equal(response.statusCode, 200);
  const payload = response.json();
  const suppressedMetric = payload.data.metrics.find(
    (metric: { interactionType: string }) => metric.interactionType === 'anonymous_pass_by',
  );
  assert.equal(suppressedMetric.status, 'insufficient_data');
  assert.equal(suppressedMetric.totalCount, 0);
  assert.equal('uniqueContributorCount' in suppressedMetric, false);
  await server.close();
});

test('admin insights never returns user-level data', async () => {
  const server = await buildTestServer();
  const response = await server.inject({
    method: 'GET',
    url: buildAdminInsightsSummaryPath(PARTNER_ID),
    headers: { 'x-dev-user': ADMIN_USER },
  });

  assert.equal(response.statusCode, 200);
  const serialized = response.body;
  assert.equal(serialized.includes('userId'), false);
  assert.equal(serialized.includes('email'), false);
  assert.equal(serialized.includes('latitude'), false);
  assert.equal(serialized.includes('longitude'), false);
  assert.equal(serialized.includes('occurredAt'), false);
  await server.close();
});
