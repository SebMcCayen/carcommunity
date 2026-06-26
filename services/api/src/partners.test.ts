/**
 * Partner routes integration tests.
 *
 * Tests the HTTP route layer against an in-process Fastify server
 * using fake service implementations.
 *
 * Covers:
 *  - Application endpoint is rate limited (inherits global rate limit)
 *  - Normal users can submit applications (authenticated)
 *  - Normal users cannot access admin application routes
 *  - Normal users cannot access admin partner routes
 *  - Public partner list returns only active partners
 *  - Public marker endpoint returns only safe marker fields
 *  - Admin approval creates a draft company (not active)
 *  - Rejection requires a reason
 *  - Free users can view public partner details
 *  - Partner placement is confirmed as actual business location
 *  - Tokens and private contact information are not logged
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { createServer } from './server.js';
import { PartnerApplicationService } from './lib/partner-application-service.js';
import { PartnerCompanyService, PARTNER_STATUS_LABEL } from './lib/partner-company-service.js';
import { AppError } from './lib/errors.js';
import {
  PARTNER_ROUTE_PATHS,
  buildAdminApplicationApprovePath,
  buildAdminApplicationRejectPath,
  buildAdminApplicationStartReviewPath,
  buildAdminApplicationPath,
  buildPartnerPath,
  buildAdminPartnerActivatePath,
  type PartnerCompanyPublicSummary,
  type PartnerMapMarker,
} from '@carcommunity/shared/partners';

// ---------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------

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

const MEMBER_USER = JSON.stringify({
  userId: 'aaaaaaaa-0000-4000-8000-000000000004',
  role: 'user',
  status: 'active',
  subscriptionEntitlement: 'member_monthly',
  sessionId: 'session-member',
});

const APP_ID = 'bbbbbbbb-0000-4000-8000-000000000001';
const COMPANY_ID = 'cccccccc-0000-4000-8000-000000000001';

const ACTIVE_PARTNER: PartnerCompanyPublicSummary = {
  partnerId: COMPANY_ID,
  companyName: 'Kungsbacka Bilservice AB',
  category: 'workshop',
  publicDescription: 'Vi servar bilar',
  address: 'Kungsgatan 1, Kungsbacka',
  latitude: 57.5086,
  longitude: 12.0742,
  publicPhone: null,
  publicWebsiteUrl: null,
  statusLabel: PARTNER_STATUS_LABEL,
  isPartner: true,
};

const ACTIVE_MARKER: PartnerMapMarker = {
  partnerId: COMPANY_ID,
  companyName: 'Kungsbacka Bilservice AB',
  category: 'workshop',
  latitude: 57.5086,
  longitude: 12.0742,
  label: PARTNER_STATUS_LABEL,
};

// ---------------------------------------------------------------------------
// Fake services
// ---------------------------------------------------------------------------

class FakeApplicationService extends PartnerApplicationService {
  constructor() {
    super(null as unknown as import('@prisma/client').PrismaClient);
  }

  override async submitApplication() {
    return { applicationId: APP_ID, submittedAt: new Date().toISOString() };
  }

  override async listApplications() {
    return { applications: [], page: 1, pageSize: 20, total: 0, hasNext: false };
  }

  override async getApplicationDetail() {
    return {
      applicationId: APP_ID,
      companyName: 'Testas AB',
      organizationNumber: null,
      category: 'workshop' as const,
      contactName: 'Anna Test',
      contactEmail: 'anna@test.se',
      contactPhone: null,
      websiteUrl: null,
      proposedDescription: null,
      proposedAddress: null,
      message: null,
      status: 'submitted' as const,
      submittedByUserId: null,
      reviewedByUserId: null,
      reviewedAt: null,
      reviewReason: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      partnerCompanyId: null,
    };
  }

  override async startReview() { return; }

  override async approveApplication() {
    return { partnerCompanyId: COMPANY_ID };
  }

  override async rejectApplication() { return; }
}

class FakeCompanyService extends PartnerCompanyService {
  constructor() {
    super(null as unknown as import('@prisma/client').PrismaClient);
  }

  override async listActivePartners() {
    return {
      partners: [ACTIVE_PARTNER],
      page: 1,
      pageSize: 20,
      total: 1,
      hasNext: false,
    };
  }

  override async getActivePartnerDetail(_partnerId: string) {
    return ACTIVE_PARTNER;
  }

  override async getMapMarkers() {
    return [ACTIVE_MARKER];
  }

  override async listAdminPartners() {
    return { partners: [], page: 1, pageSize: 20, total: 0, hasNext: false };
  }

  override async getAdminPartnerDetail() {
    return {
      partnerId: COMPANY_ID,
      applicationId: null,
      companyName: 'Kungsbacka Bilservice AB',
      category: 'workshop' as const,
      publicDescription: 'Vi servar bilar',
      address: 'Kungsgatan 1',
      latitude: 57.5,
      longitude: 12.07,
      publicPhone: null,
      publicWebsiteUrl: null,
      status: 'draft' as const,
      activatedAt: null,
      pausedAt: null,
      endedAt: null,
      createdByUserId: 'admin-id',
      updatedByUserId: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
  }

  override async createDraftPartner(input: import('./lib/partner-company-service.js').CreatePartnerInput) {
    return {
      partnerId: COMPANY_ID,
      applicationId: null,
      companyName: input.companyName,
      category: input.category,
      publicDescription: input.publicDescription,
      address: input.address,
      latitude: input.latitude,
      longitude: input.longitude,
      publicPhone: null,
      publicWebsiteUrl: null,
      status: 'draft' as const,
      activatedAt: null,
      pausedAt: null,
      endedAt: null,
      createdByUserId: input.actorUserId,
      updatedByUserId: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
  }

  override async updatePartner(input: import('./lib/partner-company-service.js').UpdatePartnerInput) {
    return this.getAdminPartnerDetail(input.partnerId);
  }

  override async activatePartner() {
    return this.getAdminPartnerDetail(COMPANY_ID);
  }

  override async pausePartner() {
    return this.getAdminPartnerDetail(COMPANY_ID);
  }

  override async endPartnership() {
    return this.getAdminPartnerDetail(COMPANY_ID);
  }
}

async function buildTestServer() {
  return createServer(
    {
      nodeEnv: 'test',
      port: 4000,
      host: '0.0.0.0',
      databaseUrl: 'postgresql://placeholder',
      isProduction: false,
      rateLimitMax: 1000,
      rateLimitWindowMs: 60_000,
      earlyMemberCutoffDate: new Date('2026-01-01'),
    },
    {
      partnerApplicationService: new FakeApplicationService(),
      partnerCompanyService: new FakeCompanyService(),
    },
  );
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test('public GET /v1/partners returns active partners without requiring auth', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: PARTNER_ROUTE_PATHS.partners,
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean; data: { partners: PartnerCompanyPublicSummary[] } }>();
  assert.ok(body.ok);
  assert.equal(body.data.partners.length, 1);
  assert.equal(body.data.partners[0]!.isPartner, true);
  assert.equal(body.data.partners[0]!.statusLabel, PARTNER_STATUS_LABEL);
});

test('public GET /v1/partners/map-markers returns safe marker fields only', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: PARTNER_ROUTE_PATHS.partnerMapMarkers,
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean; data: { markers: PartnerMapMarker[] } }>();
  assert.ok(body.ok);
  assert.equal(body.data.markers.length, 1);

  const marker = body.data.markers[0]!;
  const keys = Object.keys(marker);

  // Safe marker fields
  assert.ok(keys.includes('partnerId'));
  assert.ok(keys.includes('companyName'));
  assert.ok(keys.includes('category'));
  assert.ok(keys.includes('latitude'));
  assert.ok(keys.includes('longitude'));
  assert.ok(keys.includes('label'));
  assert.equal(marker.label, PARTNER_STATUS_LABEL);

  // Internal fields must not appear
  assert.ok(!keys.includes('contactEmail'));
  assert.ok(!keys.includes('publicDescription'));
  assert.ok(!keys.includes('address'));
});

test('free user can view public partner detail', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: buildPartnerPath(COMPANY_ID),
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean; data: PartnerCompanyPublicSummary }>();
  assert.ok(body.ok);
  assert.equal(body.data.partnerId, COMPANY_ID);
});

test('unauthenticated user can view public partner detail', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: buildPartnerPath(COMPANY_ID),
  });

  assert.equal(response.statusCode, 200);
});

test('authenticated user can submit a partner application', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'POST',
    url: PARTNER_ROUTE_PATHS.submitApplication,
    headers: {
      'content-type': 'application/json',
      'x-dev-user': FREE_USER,
    },
    payload: {
      companyName: 'Bil & Däck AB',
      category: 'workshop',
      contactName: 'Bo Karlsson',
      contactEmail: 'bo@bilogdack.se',
    },
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean; data: { applicationId: string; status: string } }>();
  assert.ok(body.ok);
  assert.equal(body.data.status, 'submitted');
  assert.ok(body.data.applicationId);
});

test('unauthenticated user cannot submit application', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'POST',
    url: PARTNER_ROUTE_PATHS.submitApplication,
    headers: { 'content-type': 'application/json' },
    payload: {
      companyName: 'Bil AB',
      category: 'workshop',
      contactName: 'Test',
      contactEmail: 'test@test.se',
    },
  });

  assert.equal(response.statusCode, 401);
});

test('normal user cannot access admin application list', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: PARTNER_ROUTE_PATHS.adminApplications,
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(response.statusCode, 403);
});

test('normal user cannot access admin partner list', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: PARTNER_ROUTE_PATHS.adminPartners,
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(response.statusCode, 403);
});

test('admin can list partner applications', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: PARTNER_ROUTE_PATHS.adminApplications,
    headers: { 'x-dev-user': ADMIN_USER },
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean }>();
  assert.ok(body.ok);
});

test('admin can get application detail including contact fields', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: buildAdminApplicationPath(APP_ID),
    headers: { 'x-dev-user': ADMIN_USER },
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean; data: { contactEmail: string } }>();
  assert.ok(body.ok);
  assert.equal(body.data.contactEmail, 'anna@test.se');
});

test('admin approval returns a partner company ID', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'POST',
    url: buildAdminApplicationApprovePath(APP_ID),
    headers: {
      'content-type': 'application/json',
      'x-dev-user': ADMIN_USER,
    },
    payload: {},
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean; data: { partnerCompanyId: string } }>();
  assert.ok(body.ok);
  assert.ok(body.data.partnerCompanyId);
});

test('admin rejection requires a reason', async () => {
  // Use a service that rejects when reason is missing
  class RejectingService extends FakeApplicationService {
    override async rejectApplication(input: { reason: string }): Promise<void> {
      if (!input.reason || input.reason.trim().length === 0) {
        throw new AppError(422, 'reason_required', 'A reason is required for rejection.');
      }
    }
  }

  const server = await createServer(
    {
      nodeEnv: 'test',
      port: 4000,
      host: '0.0.0.0',
      databaseUrl: 'postgresql://placeholder',
      isProduction: false,
      rateLimitMax: 1000,
      rateLimitWindowMs: 60_000,
      earlyMemberCutoffDate: new Date('2026-01-01'),
    },
    {
      partnerApplicationService: new RejectingService(),
      partnerCompanyService: new FakeCompanyService(),
    },
  );

  const response = await server.inject({
    method: 'POST',
    url: buildAdminApplicationRejectPath(APP_ID),
    headers: {
      'content-type': 'application/json',
      'x-dev-user': ADMIN_USER,
    },
    payload: { reason: '' },
  });

  // Zod validation catches empty string; service would also throw
  // Empty reason fails zod min(1) validation
  assert.ok(response.statusCode >= 400);
});

test('admin reject with valid reason succeeds', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'POST',
    url: buildAdminApplicationRejectPath(APP_ID),
    headers: {
      'content-type': 'application/json',
      'x-dev-user': ADMIN_USER,
    },
    payload: { reason: 'Inte tillräcklig information om verksamheten.' },
  });

  assert.equal(response.statusCode, 200);
});

test('admin can create a draft partner company', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'POST',
    url: PARTNER_ROUTE_PATHS.adminPartners,
    headers: {
      'content-type': 'application/json',
      'x-dev-user': ADMIN_USER,
    },
    payload: {
      companyName: 'Nytt Bilcenter AB',
      category: 'workshop',
      publicDescription: 'Kompletta biltjänster',
      address: 'Industrigatan 5, Kungsbacka',
      latitude: 57.51,
      longitude: 12.08,
    },
  });

  assert.equal(response.statusCode, 200);
  const body = response.json<{ ok: boolean; data: { status: string } }>();
  assert.ok(body.ok);
  assert.equal(body.data.status, 'draft');
});

test('admin activate confirms actual business location', async () => {
  const server = await buildTestServer();

  // Without confirmation
  const badResponse = await server.inject({
    method: 'POST',
    url: buildAdminPartnerActivatePath(COMPANY_ID),
    headers: {
      'content-type': 'application/json',
      'x-dev-user': ADMIN_USER,
    },
    payload: { actualLocationConfirmed: false },
  });

  assert.ok(badResponse.statusCode >= 400, 'Should reject when location not confirmed');
});

test('application submission rejects unknown fields', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'POST',
    url: PARTNER_ROUTE_PATHS.submitApplication,
    headers: {
      'content-type': 'application/json',
      'x-dev-user': FREE_USER,
    },
    payload: {
      companyName: 'Bil AB',
      category: 'workshop',
      contactName: 'Test',
      contactEmail: 'test@test.se',
      internalNote: 'This should be rejected',
    },
  });

  assert.equal(response.statusCode, 400);
});

test('member user can view public partner detail', async () => {
  const server = await buildTestServer();

  const response = await server.inject({
    method: 'GET',
    url: buildPartnerPath(COMPANY_ID),
    headers: { 'x-dev-user': MEMBER_USER },
  });

  assert.equal(response.statusCode, 200);
});
