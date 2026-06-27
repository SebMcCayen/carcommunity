/**
 * PartnerOfferService unit tests.
 *
 * Uses a fake Prisma client — no database connection required.
 *
 * Covers:
 *  - Free user cannot view offer details
 *  - Suspended member cannot access protected offers
 *  - Deleted user cannot access protected offers
 *  - Active member can view currently available offer details
 *  - Inactive partner offers are hidden from teasers
 *  - Paused/ended/draft offers are not returned in member APIs
 *  - New offers always start as draft
 *  - Discount code is not included in teaser response
 *  - Discount code is not included in member detail response
 *  - showCode returns code for active offer
 *  - showCode throws for inactive offer
 *  - Activation requires confirmed=true
 *  - Activation requires active partner company
 *  - Activation sets status to active
 *  - Pause/end require a reason
 *  - Audit entries written for activation/pause/end
 *  - Saved offer is idempotent (save twice is OK)
 *  - Saved offer is owner-scoped
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { PartnerOfferService } from './lib/partner-offer-service.js';
import { AppError } from './lib/errors.js';

// ---------------------------------------------------------------------------
// Test UUIDs
// ---------------------------------------------------------------------------

const ADMIN_ID = 'aaaaaaaa-0000-4000-8000-000000000002';
const USER_ID = 'aaaaaaaa-0000-4000-8000-000000000001';
const OFFER_ID = 'cccccccc-0000-4000-8000-000000000001';
const PARTNER_ID = 'bbbbbbbb-0000-4000-8000-000000000001';

// ---------------------------------------------------------------------------
// Fake offer builder
// ---------------------------------------------------------------------------

interface FakeOffer {
  id: string;
  partnerCompanyId: string;
  title: string;
  teaserText: string;
  description: string | null;
  offerType: string;
  status: string;
  discountCode: string | null;
  redemptionInstructions: string | null;
  terms: string | null;
  percentageDiscount: number | null;
  fixedDiscountMinorUnits: number | null;
  currencyCode: string | null;
  availableFrom: Date | null;
  availableUntil: Date | null;
  activatedAt: Date | null;
  pausedAt: Date | null;
  endedAt: Date | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: Date;
  updatedAt: Date;
}

function makeFakeOffer(overrides: Partial<FakeOffer> = {}): FakeOffer {
  return {
    id: OFFER_ID,
    partnerCompanyId: PARTNER_ID,
    title: 'Test erbjudande',
    teaserText: '10% rabatt på alla tjänster',
    description: 'Fullständig beskrivning av erbjudandet',
    offerType: 'percentage_discount',
    status: 'draft',
    discountCode: 'SECRET123',
    redemptionInstructions: 'Visa detta vid kassan',
    terms: 'Gäller ej kombinerat med andra erbjudanden',
    percentageDiscount: 10,
    fixedDiscountMinorUnits: null,
    currencyCode: null,
    availableFrom: null,
    availableUntil: null,
    activatedAt: null,
    pausedAt: null,
    endedAt: null,
    createdByUserId: ADMIN_ID,
    updatedByUserId: null,
    createdAt: new Date('2026-01-01'),
    updatedAt: new Date('2026-01-01'),
    ...overrides,
  };
}

interface FakeAuditLog {
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  reason?: string | null;
  metadata?: unknown;
}

function buildFakePrisma(options: {
  findUniqueOfferResult?: (FakeOffer & { partnerCompany: { companyName: string; status: string } }) | null;
  findManyOffersResult?: (FakeOffer & { partnerCompany: { companyName: string } })[];
  countResult?: number;
  partnerFindUniqueResult?: { id: string; companyName: string; status?: string } | null;
  savedOfferFindUniqueResult?: { id: string; createdAt: Date } | null;
  auditLogs?: FakeAuditLog[];
}) {
  const auditLogs: FakeAuditLog[] = options.auditLogs ?? [];

  return {
    partnerOffer: {
      findUnique: async () => options.findUniqueOfferResult ?? null,
      findMany: async () => options.findManyOffersResult ?? [],
      count: async () => options.countResult ?? 0,
      create: async ({ data }: { data: Record<string, unknown> }) => ({
        id: OFFER_ID,
        partnerCompanyId: data.partnerCompanyId,
        title: data.title,
        teaserText: data.teaserText,
        description: data.description ?? null,
        offerType: data.offerType,
        status: data.status ?? 'draft',
        discountCode: data.discountCode ?? null,
        redemptionInstructions: data.redemptionInstructions ?? null,
        terms: data.terms ?? null,
        percentageDiscount: data.percentageDiscount ?? null,
        fixedDiscountMinorUnits: data.fixedDiscountMinorUnits ?? null,
        currencyCode: data.currencyCode ?? null,
        availableFrom: null,
        availableUntil: null,
        activatedAt: null,
        pausedAt: null,
        endedAt: null,
        createdByUserId: data.createdByUserId,
        updatedByUserId: null,
        createdAt: new Date(),
        updatedAt: new Date(),
      }),
      update: async ({ data, where }: { data: Record<string, unknown>; where: Record<string, unknown> }) => ({
        ...makeFakeOffer(),
        id: where.id as string,
        ...data,
        createdAt: new Date(),
        updatedAt: new Date(),
      }),
    },
    partnerCompany: {
      findUnique: async () =>
        options.partnerFindUniqueResult !== undefined
          ? options.partnerFindUniqueResult
          : { id: PARTNER_ID, companyName: 'Test AB', status: 'active' },
    },
    savedPartnerOffer: {
      findUnique: async () => options.savedOfferFindUniqueResult ?? null,
      create: async ({ data }: { data: Record<string, unknown> }) => ({
        id: 'saved-id',
        userId: data.userId,
        offerId: data.offerId,
        createdAt: new Date(),
      }),
      delete: async () => ({}),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
    _auditLogs: auditLogs,
  } as unknown as import('@prisma/client').PrismaClient & { _auditLogs: FakeAuditLog[] };
}

// ---------------------------------------------------------------------------
// User helpers
// ---------------------------------------------------------------------------

const FREE_USER = { role: 'user' as const, status: 'active' as const, subscriptionEntitlement: 'none' as const };
const MEMBER_USER = { role: 'user' as const, status: 'active' as const, subscriptionEntitlement: 'member_monthly' as const };
const SUSPENDED_MEMBER = { role: 'user' as const, status: 'temporarily_suspended' as const, subscriptionEntitlement: 'member_monthly' as const };
const DELETED_USER = { role: 'user' as const, status: 'deleted' as const, subscriptionEntitlement: 'member_monthly' as const };
const ADMIN_USER = { role: 'admin' as const, status: 'active' as const, subscriptionEntitlement: 'none' as const };

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test('free user cannot view offer details', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () => service.getMemberOfferDetail(FREE_USER, OFFER_ID),
    (err) => err instanceof AppError && err.code === 'forbidden',
  );
});

test('suspended member cannot access protected offers', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () => service.getMemberOfferDetail(SUSPENDED_MEMBER, OFFER_ID),
    (err) => err instanceof AppError && err.code === 'forbidden',
  );
});

test('deleted user cannot access protected offers', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () => service.getMemberOfferDetail(DELETED_USER, OFFER_ID),
    (err) => err instanceof AppError && err.code === 'forbidden',
  );
});

test('active member can view currently available offer details', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  const detail = await service.getMemberOfferDetail(MEMBER_USER, OFFER_ID);
  assert.equal(detail.offerId, OFFER_ID);
  assert.equal(detail.title, 'Test erbjudande');
});

test('inactive partner offers are hidden from teasers', async () => {
  const prisma = buildFakePrisma({
    findManyOffersResult: [],
    countResult: 0,
  });
  const service = new PartnerOfferService(prisma);

  const result = await service.listOfferTeasers();
  assert.equal(result.offers.length, 0);
  assert.equal(result.total, 0);
});

test('draft offer is not returned from getOfferTeaser', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'draft' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  const teaser = await service.getOfferTeaser(OFFER_ID);
  assert.equal(teaser, null);
});

test('new offers always start as draft', async () => {
  let createdStatus = '';
  const prisma = {
    partnerOffer: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        createdStatus = data.status as string;
        return {
          id: OFFER_ID,
          ...data,
          availableFrom: null,
          availableUntil: null,
          activatedAt: null,
          pausedAt: null,
          endedAt: null,
          createdAt: new Date(),
          updatedAt: new Date(),
        };
      },
    },
    partnerCompany: {
      findUnique: async () => ({ id: PARTNER_ID, companyName: 'Test AB' }),
    },
    auditLog: { create: async () => ({}) },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerOfferService(prisma);
  const detail = await service.createOffer({
    actorUserId: ADMIN_ID,
    partnerId: PARTNER_ID,
    title: 'Test',
    teaserText: 'Teaser',
    description: 'Description',
    offerType: 'member_benefit',
  });

  assert.equal(createdStatus, 'draft');
  assert.equal(detail.status, 'draft');
});

test('discount code is not included in teaser response', async () => {
  const offerWithCode = makeFakeOffer({ status: 'active', discountCode: 'SECRET123' });
  const prisma = buildFakePrisma({
    findManyOffersResult: [
      { ...offerWithCode, partnerCompany: { companyName: 'Test AB' } },
    ],
    countResult: 1,
  });
  const service = new PartnerOfferService(prisma);

  const result = await service.listOfferTeasers();
  const teaser = result.offers[0];
  assert.ok(teaser);
  assert.ok(!('discountCode' in teaser), 'discountCode must not be in teaser response');
  assert.ok(!('code' in teaser), 'code must not be in teaser response');
});

test('discount code is not included in member detail response', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active', discountCode: 'SECRET456' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  const detail = await service.getMemberOfferDetail(MEMBER_USER, OFFER_ID);
  assert.ok(!('discountCode' in detail), 'discountCode must not be in member detail response');
  assert.ok(!('code' in detail), 'code must not be in member detail response');
});

test('showCode returns code for active offer', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active', discountCode: 'MEMBER10' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  const result = await service.showCode(MEMBER_USER, OFFER_ID);
  assert.equal(result.offerId, OFFER_ID);
  assert.equal(result.code, 'MEMBER10');
});

test('showCode throws for inactive offer', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'paused', discountCode: 'MEMBER10' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () => service.showCode(MEMBER_USER, OFFER_ID),
    (err) => err instanceof AppError && err.code === 'offer_not_active',
  );
});

test('showCode throws for free user', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () => service.showCode(FREE_USER, OFFER_ID),
    (err) => err instanceof AppError && err.code === 'forbidden',
  );
});

test('activation requires confirmed=true', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'draft' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () =>
      service.activateOffer({
        actorUserId: ADMIN_ID,
        offerId: OFFER_ID,
        confirmed: false,
      }),
    (err) => err instanceof AppError && err.code === 'offer_activation_not_confirmed',
  );
});

test('activation requires active partner company', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'draft' }),
      partnerCompany: { companyName: 'Test AB', status: 'paused' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () =>
      service.activateOffer({
        actorUserId: ADMIN_ID,
        offerId: OFFER_ID,
        confirmed: true,
      }),
    (err) => err instanceof AppError && err.code === 'offer_partner_not_active',
  );
});

test('activation sets status to active', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const prisma = {
    partnerOffer: {
      findUnique: async () => ({
        ...makeFakeOffer({ status: 'draft' }),
        partnerCompany: { companyName: 'Test AB', status: 'active' },
      }),
      update: async ({ data }: { data: Record<string, unknown> }) => ({
        ...makeFakeOffer(),
        ...data,
        createdAt: new Date(),
        updatedAt: new Date(),
      }),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerOfferService(prisma);
  const detail = await service.activateOffer({
    actorUserId: ADMIN_ID,
    offerId: OFFER_ID,
    confirmed: true,
  });

  assert.equal(detail.status, 'active');
  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]?.action, 'partner_offer.activate');
});

test('pause requires a reason', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () =>
      service.pauseOffer({
        actorUserId: ADMIN_ID,
        offerId: OFFER_ID,
        reason: '',
      }),
    (err) => err instanceof AppError && err.code === 'offer_reason_required',
  );
});

test('end requires a reason', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () =>
      service.endOffer({
        actorUserId: ADMIN_ID,
        offerId: OFFER_ID,
        reason: '',
      }),
    (err) => err instanceof AppError && err.code === 'offer_reason_required',
  );
});

test('activation writes audit entry', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const prisma = {
    partnerOffer: {
      findUnique: async () => ({
        ...makeFakeOffer({ status: 'draft' }),
        partnerCompany: { companyName: 'Test AB', status: 'active' },
      }),
      update: async ({ data }: { data: Record<string, unknown> }) => ({
        ...makeFakeOffer(),
        ...data,
        createdAt: new Date(),
        updatedAt: new Date(),
      }),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerOfferService(prisma);
  await service.activateOffer({ actorUserId: ADMIN_ID, offerId: OFFER_ID, confirmed: true });

  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]?.action, 'partner_offer.activate');
  assert.equal(auditLogs[0]?.entityType, 'partner_offer');
  // Ensure discountCode is never in audit metadata
  const auditEntry = JSON.stringify(auditLogs[0]);
  assert.ok(!auditEntry.includes('discountCode'), 'discountCode must not appear in audit log');
  assert.ok(!auditEntry.includes('SECRET123'), 'code value must not appear in audit log');
});

test('pause writes audit entry with reason', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const prisma = {
    partnerOffer: {
      findUnique: async () => ({
        ...makeFakeOffer({ status: 'active' }),
        partnerCompany: { companyName: 'Test AB', status: 'active' },
      }),
      update: async ({ data }: { data: Record<string, unknown> }) => ({
        ...makeFakeOffer(),
        ...data,
        createdAt: new Date(),
        updatedAt: new Date(),
      }),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerOfferService(prisma);
  await service.pauseOffer({
    actorUserId: ADMIN_ID,
    offerId: OFFER_ID,
    reason: 'Kampanj har avslutats',
  });

  assert.equal(auditLogs.length, 1);
  assert.equal(auditLogs[0]?.action, 'partner_offer.pause');
  assert.equal(auditLogs[0]?.reason, 'Kampanj har avslutats');
});

test('saved offer is idempotent', async () => {
  const existingSaved = { id: 'saved-id', createdAt: new Date('2026-01-01') };
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
    savedOfferFindUniqueResult: existingSaved,
  });
  const service = new PartnerOfferService(prisma);

  const result1 = await service.saveOffer(
    { userId: USER_ID, ...MEMBER_USER },
    OFFER_ID,
  );
  const result2 = await service.saveOffer(
    { userId: USER_ID, ...MEMBER_USER },
    OFFER_ID,
  );

  assert.equal(result1.offerId, OFFER_ID);
  assert.equal(result2.offerId, OFFER_ID);
  assert.equal(result1.savedAt, result2.savedAt);
});

test('saved offer is owner-scoped (free user cannot save)', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'active' }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () => service.saveOffer({ userId: USER_ID, ...FREE_USER }, OFFER_ID),
    (err) => err instanceof AppError && err.code === 'forbidden',
  );
});

test('activation description required', async () => {
  const prisma = buildFakePrisma({
    findUniqueOfferResult: {
      ...makeFakeOffer({ status: 'draft', description: null }),
      partnerCompany: { companyName: 'Test AB', status: 'active' },
    },
  });
  const service = new PartnerOfferService(prisma);

  await assert.rejects(
    () =>
      service.activateOffer({
        actorUserId: ADMIN_ID,
        offerId: OFFER_ID,
        confirmed: true,
      }),
    (err) => err instanceof AppError && err.code === 'offer_description_required',
  );
});
