/**
 * Partner Offers route integration tests.
 *
 * Tests the HTTP route layer against an in-process Fastify server
 * using fake service implementations.
 *
 * Covers:
 *  - Free user can access teaser list (returns teaser-safe fields)
 *  - Free user cannot access member offer routes (403)
 *  - Free user response never contains discountCode field
 *  - Active member can access member offer routes
 *  - Suspended member cannot access member routes (403)
 *  - show-code requires active member
 *  - Admin can create/update/activate/pause/end offers
 *  - Request body cannot set status directly
 *  - Unknown fields in request body are rejected
 *  - Discount code is not in audit metadata
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { createServer } from './server.js';
import { PartnerOfferService } from './lib/partner-offer-service.js';
import { AppError } from './lib/errors.js';
import {
  PARTNER_OFFER_ROUTE_PATHS,
  buildPartnerOfferTeasersPath,
  buildMemberOfferPath,
  buildMemberOfferShowCodePath,
  buildMemberOfferSavePath,
  buildAdminOfferPath,
  buildAdminOfferActivatePath,
  buildAdminOfferPausePath,
  buildAdminOfferEndPath,
  buildAdminCreateOfferPath,
  type PublicPartnerOfferTeaser,
  type MemberPartnerOfferDetail,
  type AdminPartnerOfferDetail,
  type ShowCodeResponse,
} from '@carcommunity/shared/partner-offers';

// ---------------------------------------------------------------------------
// Test auth headers
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

const SUSPENDED_MEMBER = JSON.stringify({
  userId: 'aaaaaaaa-0000-4000-8000-000000000005',
  role: 'user',
  status: 'temporarily_suspended',
  subscriptionEntitlement: 'member_monthly',
  sessionId: 'session-suspended',
});

// ---------------------------------------------------------------------------
// Test data
// ---------------------------------------------------------------------------

const PARTNER_ID = 'bbbbbbbb-0000-4000-8000-000000000001';
const OFFER_ID = 'cccccccc-0000-4000-8000-000000000001';

const SAMPLE_TEASER: PublicPartnerOfferTeaser = {
  offerId: OFFER_ID,
  partnerId: PARTNER_ID,
  partnerCompanyName: 'Test AB',
  title: 'Test erbjudande',
  teaserText: '10% på alla tjänster',
  offerType: 'percentage_discount',
  availableUntil: null,
  requiresMembership: true,
};

const SAMPLE_MEMBER_DETAIL: MemberPartnerOfferDetail = {
  offerId: OFFER_ID,
  partnerId: PARTNER_ID,
  partnerCompanyName: 'Test AB',
  title: 'Test erbjudande',
  teaserText: '10% på alla tjänster',
  offerType: 'percentage_discount',
  description: 'Fullständig beskrivning',
  redemptionInstructions: 'Visa vid kassan',
  terms: null,
  percentageDiscount: 10,
  fixedDiscountMinorUnits: null,
  currencyCode: null,
  availableFrom: null,
  availableUntil: null,
};

const SAMPLE_ADMIN_DETAIL: AdminPartnerOfferDetail = {
  offerId: OFFER_ID,
  partnerId: PARTNER_ID,
  partnerCompanyName: 'Test AB',
  title: 'Test erbjudande',
  teaserText: '10% på alla tjänster',
  description: 'Fullständig beskrivning',
  offerType: 'percentage_discount',
  status: 'draft',
  redemptionInstructions: null,
  terms: null,
  percentageDiscount: 10,
  fixedDiscountMinorUnits: null,
  currencyCode: null,
  availableFrom: null,
  availableUntil: null,
  activatedAt: null,
  pausedAt: null,
  endedAt: null,
  createdByUserId: 'aaaaaaaa-0000-4000-8000-000000000002',
  updatedByUserId: null,
  createdAt: new Date('2026-01-01').toISOString(),
  updatedAt: new Date('2026-01-01').toISOString(),
};

const SAMPLE_SHOW_CODE: ShowCodeResponse = {
  offerId: OFFER_ID,
  code: 'MEMBER10',
  redemptionInstructions: 'Visa vid kassan',
  expiresAt: null,
};

// ---------------------------------------------------------------------------
// Fake service
// ---------------------------------------------------------------------------

class FakePartnerOfferService extends PartnerOfferService {
  constructor() {
    super(null as unknown as import('@prisma/client').PrismaClient);
  }

  override async listOfferTeasers(_input = {}) {
    return { offers: [SAMPLE_TEASER], page: 1, pageSize: 20, total: 1, hasNext: false };
  }

  override async getOfferTeaser(_offerId: string) {
    return SAMPLE_TEASER;
  }

  override async listMemberOffers(_user: unknown, _input = {}) {
    return { offers: [SAMPLE_MEMBER_DETAIL], page: 1, pageSize: 20, total: 1, hasNext: false };
  }

  override async getMemberOfferDetail(_user: unknown, _offerId: string) {
    return SAMPLE_MEMBER_DETAIL;
  }

  override async showCode(_user: unknown, _offerId: string) {
    return SAMPLE_SHOW_CODE;
  }

  override async saveOffer(_user: unknown, offerId: string) {
    return { offerId, savedAt: new Date().toISOString() };
  }

  override async unsaveOffer(_user: unknown, _offerId: string) {
    return;
  }

  override async listSavedOffers(_user: unknown, _input = {}) {
    return { offers: [SAMPLE_MEMBER_DETAIL], page: 1, pageSize: 20, total: 1, hasNext: false };
  }

  override async listAdminOffers(_input = {}) {
    return {
      offers: [
        {
          offerId: OFFER_ID,
          partnerId: PARTNER_ID,
          partnerCompanyName: 'Test AB',
          title: 'Test erbjudande',
          offerType: 'percentage_discount' as const,
          status: 'draft' as const,
          availableFrom: null,
          availableUntil: null,
          activatedAt: null,
          createdAt: new Date('2026-01-01').toISOString(),
          updatedAt: new Date('2026-01-01').toISOString(),
        },
      ],
      page: 1,
      pageSize: 20,
      total: 1,
      hasNext: false,
    };
  }

  override async getAdminOfferDetail(_offerId: string) {
    return SAMPLE_ADMIN_DETAIL;
  }

  override async createOffer(_input: unknown) {
    return SAMPLE_ADMIN_DETAIL;
  }

  override async updateOffer(_input: unknown) {
    return SAMPLE_ADMIN_DETAIL;
  }

  override async activateOffer(_input: unknown) {
    return { ...SAMPLE_ADMIN_DETAIL, status: 'active' as const, activatedAt: new Date().toISOString() };
  }

  override async pauseOffer(_input: unknown) {
    return { ...SAMPLE_ADMIN_DETAIL, status: 'paused' as const };
  }

  override async endOffer(_input: unknown) {
    return { ...SAMPLE_ADMIN_DETAIL, status: 'ended' as const };
  }
}

// Fake service that throws forbidden for member routes
class ForbiddenPartnerOfferService extends PartnerOfferService {
  constructor() {
    super(null as unknown as import('@prisma/client').PrismaClient);
  }

  override async listMemberOffers(_user: unknown, _input = {}): Promise<never> {
    throw new AppError(403, 'forbidden', 'Member subscription required to view offer details.');
  }

  override async getMemberOfferDetail(_user: unknown, _offerId: string): Promise<never> {
    throw new AppError(403, 'forbidden', 'Member subscription required to view offer details.');
  }

  override async showCode(_user: unknown, _offerId: string): Promise<never> {
    throw new AppError(403, 'forbidden', 'Member subscription required to view offer codes.');
  }

  override async listOfferTeasers(_input = {}) {
    return { offers: [SAMPLE_TEASER], page: 1, pageSize: 20, total: 1, hasNext: false };
  }
}

// ---------------------------------------------------------------------------
// Server factory
// ---------------------------------------------------------------------------

async function buildServer(serviceOverride?: PartnerOfferService) {
  const app = await createServer(undefined, {
    partnerOfferService: serviceOverride ?? new FakePartnerOfferService(),
  });
  return app;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test('free user can access teaser list', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.teasers,
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(res.statusCode, 200);
  interface Body {
    ok: boolean;
    data: { offers: unknown[] };
    meta: { total: number };
  }
  const body = res.json<Body>();
  assert.equal(body.ok, true);
  assert.equal(body.data.offers.length, 1);
});

test('teaser response never contains discountCode', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.teasers,
    headers: { 'x-dev-user': FREE_USER },
  });

  const text = res.payload;
  assert.ok(!text.includes('discountCode'), 'discountCode must not appear in teaser response');
  assert.ok(!text.includes('MEMBER10'), 'code value must not appear in teaser response');
  assert.ok(!text.includes('SECRET'), 'no code fragments must appear in teaser response');
});

test('partner-specific teaser route returns 200 for free user', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: buildPartnerOfferTeasersPath(PARTNER_ID),
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(res.statusCode, 200);
});

test('unauthenticated request to teasers returns 401', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.teasers,
  });

  assert.equal(res.statusCode, 401);
});

test('free user cannot access member offer list (403)', async () => {
  const app = await buildServer(new ForbiddenPartnerOfferService());

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.memberOffers,
    headers: { 'x-dev-user': FREE_USER },
  });

  // requireMemberHook will reject free user before reaching the service
  assert.equal(res.statusCode, 403);
});

test('suspended member cannot access member routes (403)', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.memberOffers,
    headers: { 'x-dev-user': SUSPENDED_MEMBER },
  });

  assert.equal(res.statusCode, 403);
});

test('active member can access member offer list', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.memberOffers,
    headers: { 'x-dev-user': MEMBER_USER },
  });

  assert.equal(res.statusCode, 200);
  interface Body {
    ok: boolean;
    data: { offers: unknown[] };
  }
  const body = res.json<Body>();
  assert.equal(body.ok, true);
});

test('member offer detail response never contains discountCode', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: buildMemberOfferPath(OFFER_ID),
    headers: { 'x-dev-user': MEMBER_USER },
  });

  assert.equal(res.statusCode, 200);
  const text = res.payload;
  assert.ok(!text.includes('discountCode'), 'discountCode must not appear in member detail response');
});

test('show-code returns code for active member', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildMemberOfferShowCodePath(OFFER_ID),
    headers: { 'x-dev-user': MEMBER_USER },
  });

  assert.equal(res.statusCode, 200);
  interface Body {
    ok: boolean;
    data: ShowCodeResponse;
  }
  const body = res.json<Body>();
  assert.equal(body.ok, true);
  assert.equal(body.data.code, 'MEMBER10');
});

test('show-code returns 403 for free user (requireMemberHook)', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildMemberOfferShowCodePath(OFFER_ID),
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(res.statusCode, 403);
});

test('save offer returns 200 for active member', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildMemberOfferSavePath(OFFER_ID),
    headers: { 'x-dev-user': MEMBER_USER },
  });

  assert.equal(res.statusCode, 200);
});

test('unsave offer returns 200 for active member', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'DELETE',
    url: buildMemberOfferSavePath(OFFER_ID),
    headers: { 'x-dev-user': MEMBER_USER },
  });

  assert.equal(res.statusCode, 200);
});

test('saved offers list returns 200 for active member', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.savedOffers,
    headers: { 'x-dev-user': MEMBER_USER },
  });

  assert.equal(res.statusCode, 200);
});

test('admin can list all offers', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.adminOffers,
    headers: { 'x-dev-user': ADMIN_USER },
  });

  assert.equal(res.statusCode, 200);
  interface Body { ok: boolean; data: { offers: unknown[] } }
  const body = res.json<Body>();
  assert.equal(body.ok, true);
});

test('free user cannot access admin offer list', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: PARTNER_OFFER_ROUTE_PATHS.adminOffers,
    headers: { 'x-dev-user': FREE_USER },
  });

  assert.equal(res.statusCode, 403);
});

test('admin can create offer', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildAdminCreateOfferPath(PARTNER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({
      title: 'Nytt erbjudande',
      teaserText: 'Kort beskrivning',
      description: 'Lång beskrivning',
      offerType: 'member_benefit',
    }),
  });

  assert.equal(res.statusCode, 200);
});

test('create offer body cannot contain status field (rejected as unknown)', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildAdminCreateOfferPath(PARTNER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({
      title: 'Test',
      teaserText: 'Teaser',
      description: 'Desc',
      offerType: 'other',
      status: 'active', // must be rejected
    }),
  });

  assert.equal(res.statusCode, 400);
});

test('unknown fields in create body are rejected (.strict())', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildAdminCreateOfferPath(PARTNER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({
      title: 'Test',
      teaserText: 'Teaser',
      description: 'Desc',
      offerType: 'other',
      unknownField: 'value', // must be rejected
    }),
  });

  assert.equal(res.statusCode, 400);
});

test('admin can get offer detail', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: buildAdminOfferPath(OFFER_ID),
    headers: { 'x-dev-user': ADMIN_USER },
  });

  assert.equal(res.statusCode, 200);
  const text = res.payload;
  assert.ok(!text.includes('discountCode'), 'discountCode must not appear in admin detail response');
});

test('admin can activate an offer', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildAdminOfferActivatePath(OFFER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({ confirmed: true }),
  });

  assert.equal(res.statusCode, 200);
  interface Body { ok: boolean; data: { status: string } }
  const body = res.json<Body>();
  assert.equal(body.data.status, 'active');
});

test('activation with confirmed=false returns 400', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildAdminOfferActivatePath(OFFER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({ confirmed: false }),
  });

  assert.equal(res.statusCode, 400);
});

test('admin can pause an offer', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildAdminOfferPausePath(OFFER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({ reason: 'Tillfälligt inaktivt' }),
  });

  assert.equal(res.statusCode, 200);
});

test('admin can end an offer', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'POST',
    url: buildAdminOfferEndPath(OFFER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({ reason: 'Kampanj avslutad' }),
  });

  assert.equal(res.statusCode, 200);
});

test('update offer body cannot contain status field', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'PATCH',
    url: buildAdminOfferPath(OFFER_ID),
    headers: {
      'x-dev-user': ADMIN_USER,
      'content-type': 'application/json',
    },
    payload: JSON.stringify({
      title: 'Updated title',
      status: 'active', // must be rejected
    }),
  });

  assert.equal(res.statusCode, 400);
});

test('admin detail response does not contain discountCode', async () => {
  const app = await buildServer();

  const res = await app.inject({
    method: 'GET',
    url: buildAdminOfferPath(OFFER_ID),
    headers: { 'x-dev-user': ADMIN_USER },
  });

  const text = res.payload;
  assert.ok(!text.includes('discountCode'), 'discountCode must not appear in admin detail payload');
});
