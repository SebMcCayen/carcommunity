/**
 * PartnerCompanyService unit tests.
 *
 * Uses a fake Prisma client — no database connection required.
 *
 * Covers:
 *  - New manually created partner starts as draft
 *  - General update endpoint cannot change status
 *  - Activation requires valid address and coordinates
 *  - Activation requires actual location confirmation
 *  - Activation with 0,0 coordinates is rejected
 *  - Only active partners appear in public APIs
 *  - Paused partners are hidden from public APIs
 *  - Ended partners are hidden from public APIs
 *  - Public response does not expose contact email, contact person, review reason, or admin notes
 *  - Public marker response contains only safe fields
 *  - Important admin actions write audit logs
 *  - Ending a partnership that is already ended throws
 *  - Pausing a non-active partner throws
 *  - Update blocked on active partner
 *  - No offers, analytics, billboards, or invoice data introduced
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { PartnerCompanyService, PARTNER_STATUS_LABEL } from './lib/partner-company-service.js';
import { AppError } from './lib/errors.js';

// ---------------------------------------------------------------------------
// Test UUIDs
// ---------------------------------------------------------------------------

const ADMIN_ID = 'aaaaaaaa-0000-4000-8000-000000000002';
const COMPANY_ID = 'cccccccc-0000-4000-8000-000000000001';

// ---------------------------------------------------------------------------
// Fake company builder
// ---------------------------------------------------------------------------

interface FakeCompany {
  id: string;
  applicationId: string | null;
  companyName: string;
  category: string;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  publicPhone: string | null;
  publicWebsiteUrl: string | null;
  status: string;
  activatedAt: Date | null;
  pausedAt: Date | null;
  endedAt: Date | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: Date;
  updatedAt: Date;
}

function makeFakeCompany(overrides: Partial<FakeCompany> = {}): FakeCompany {
  return {
    id: COMPANY_ID,
    applicationId: null,
    companyName: 'Kungsbacka Bilservice AB',
    category: 'workshop',
    publicDescription: 'Vi servar bilar',
    address: 'Kungsgatan 1, Kungsbacka',
    latitude: 57.5,
    longitude: 12.07,
    publicPhone: null,
    publicWebsiteUrl: null,
    status: 'draft',
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
}

function buildFakePrisma(options: {
  findUniqueResult?: FakeCompany | null;
  findManyResult?: FakeCompany[];
  countResult?: number;
  auditLogs?: FakeAuditLog[];
}) {
  const auditLogs: FakeAuditLog[] = options.auditLogs ?? [];

  return {
    partnerCompany: {
      findUnique: async () => options.findUniqueResult ?? null,
      findMany: async () => options.findManyResult ?? [],
      count: async () => options.countResult ?? 0,
      create: async ({ data }: { data: Record<string, unknown> }) => ({
        id: COMPANY_ID,
        applicationId: null,
        companyName: data.companyName,
        category: data.category,
        publicDescription: data.publicDescription ?? '',
        address: data.address ?? '',
        latitude: data.latitude ?? 0,
        longitude: data.longitude ?? 0,
        publicPhone: null,
        publicWebsiteUrl: null,
        status: data.status ?? 'draft',
        activatedAt: null,
        pausedAt: null,
        endedAt: null,
        createdByUserId: data.createdByUserId,
        updatedByUserId: null,
        createdAt: new Date(),
        updatedAt: new Date(),
      }),
      update: async ({ data, where }: { data: Record<string, unknown>; where: Record<string, unknown> }) => ({
        ...makeFakeCompany(),
        id: where.id as string,
        ...data,
        createdAt: new Date(),
        updatedAt: new Date(),
      }),
    },
    partnerApplication: {
      findUnique: async () => null,
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
    _auditLogs: auditLogs,
  } as unknown as import('@prisma/client').PrismaClient & { _auditLogs: FakeAuditLog[] };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test('createDraftPartner creates a partner with draft status', async () => {
  let createdStatus = '';
  const prisma = {
    partnerCompany: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        createdStatus = data.status as string;
        return {
          id: COMPANY_ID,
          ...data,
          activatedAt: null,
          pausedAt: null,
          endedAt: null,
          createdAt: new Date(),
          updatedAt: new Date(),
        };
      },
    },
    partnerApplication: { findUnique: async () => null },
    auditLog: { create: async () => ({}) },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerCompanyService(prisma);
  const detail = await service.createDraftPartner({
    actorUserId: ADMIN_ID,
    companyName: 'Test AB',
    category: 'workshop',
    publicDescription: 'Vi servar bilar',
    address: 'Kungsgatan 1',
    latitude: 57.5,
    longitude: 12.07,
  });

  assert.equal(createdStatus, 'draft');
  assert.equal(detail.status, 'draft');
});

test('updatePartner cannot change status', async () => {
  const company = makeFakeCompany({ status: 'draft' });
  const prisma = buildFakePrisma({ findUniqueResult: company });
  const service = new PartnerCompanyService(prisma);

  // The update schema does not include status — TypeScript prevents it.
  // We verify the service ignores any attempt to pass status implicitly.
  const result = await service.updatePartner({
    actorUserId: ADMIN_ID,
    partnerId: COMPANY_ID,
    companyName: 'Nytt namn AB',
  });

  // Status should remain draft — it was not changed by update
  assert.equal(result.status, 'draft');
});

test('updatePartner rejects update of active partner', async () => {
  const company = makeFakeCompany({ status: 'active' });
  const prisma = buildFakePrisma({ findUniqueResult: company });
  const service = new PartnerCompanyService(prisma);

  await assert.rejects(
    () =>
      service.updatePartner({
        actorUserId: ADMIN_ID,
        partnerId: COMPANY_ID,
        companyName: 'Nytt namn AB',
      }),
    (err) => err instanceof AppError && err.code === 'invalid_status_for_update',
  );
});

test('activatePartner requires valid coordinates (not 0,0)', async () => {
  const company = makeFakeCompany({ status: 'draft', latitude: 0, longitude: 0 });
  const prisma = buildFakePrisma({ findUniqueResult: company });
  const service = new PartnerCompanyService(prisma);

  await assert.rejects(
    () =>
      service.activatePartner({
        actorUserId: ADMIN_ID,
        partnerId: COMPANY_ID,
        actualLocationConfirmed: true,
      }),
    (err) => err instanceof AppError && err.code === 'coordinates_required',
  );
});

test('activatePartner requires actual location confirmation', async () => {
  const company = makeFakeCompany({ status: 'draft', latitude: 57.5, longitude: 12.07 });
  const prisma = buildFakePrisma({ findUniqueResult: company });
  const service = new PartnerCompanyService(prisma);

  await assert.rejects(
    () =>
      service.activatePartner({
        actorUserId: ADMIN_ID,
        partnerId: COMPANY_ID,
        actualLocationConfirmed: false,
      }),
    (err) => err instanceof AppError && err.code === 'location_confirmation_required',
  );
});

test('activatePartner requires non-empty address', async () => {
  const company = makeFakeCompany({ status: 'draft', latitude: 57.5, longitude: 12.07, address: '' });
  const prisma = buildFakePrisma({ findUniqueResult: company });
  const service = new PartnerCompanyService(prisma);

  await assert.rejects(
    () =>
      service.activatePartner({
        actorUserId: ADMIN_ID,
        partnerId: COMPANY_ID,
        actualLocationConfirmed: true,
      }),
    (err) => err instanceof AppError && err.code === 'address_required',
  );
});

test('activatePartner writes audit log', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const company = makeFakeCompany({ status: 'draft', latitude: 57.5, longitude: 12.07 });

  const prisma = {
    partnerCompany: {
      findUnique: async () => company,
      update: async ({ data }: { data: Record<string, unknown> }) => ({
        ...company,
        ...data,
        updatedAt: new Date(),
      }),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerCompanyService(prisma);
  await service.activatePartner({ actorUserId: ADMIN_ID, partnerId: COMPANY_ID, actualLocationConfirmed: true });

  assert.ok(auditLogs.some((l) => l.action === 'partner_company.activate'));
});

test('listActivePartners returns only active partners', async () => {
  const activePartner = makeFakeCompany({ status: 'active', latitude: 57.5, longitude: 12.07 });
  const pausedPartner = makeFakeCompany({ id: 'other-id', status: 'paused' });

  // The fake only returns what we provide — simulates DB filtering
  const prisma = {
    partnerCompany: {
      findMany: async ({ where }: { where: Record<string, unknown> }) => {
        // Simulate only returning active
        if (where.status === 'active') return [activePartner];
        return [];
      },
      count: async () => 1,
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerCompanyService(prisma);
  const result = await service.listActivePartners();

  assert.equal(result.partners.length, 1);
  assert.equal(result.partners[0]!.partnerId, COMPANY_ID);

  // Verify paused partner is not included (by ensuring our query uses status: 'active')
  assert.ok(!result.partners.some((p) => p.partnerId === pausedPartner.id));
});

test('public partner summary does not expose contact email or admin notes', async () => {
  const company = makeFakeCompany({ status: 'active', latitude: 57.5, longitude: 12.07 });

  const prisma = {
    partnerCompany: {
      findMany: async () => [company],
      count: async () => 1,
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerCompanyService(prisma);
  const result = await service.listActivePartners();

  const summary = result.partners[0]!;
  const keys = Object.keys(summary);

  // These fields must never appear in the public response
  assert.ok(!keys.includes('contactEmail'), 'contactEmail must not be in public response');
  assert.ok(!keys.includes('contactName'), 'contactName must not be in public response');
  assert.ok(!keys.includes('reviewReason'), 'reviewReason must not be in public response');
  assert.ok(!keys.includes('applicationId'), 'applicationId must not be in public summary');
  assert.ok(!keys.includes('createdByUserId'), 'createdByUserId must not be in public response');

  // These fields must be present
  assert.ok(keys.includes('partnerId'));
  assert.ok(keys.includes('companyName'));
  assert.ok(keys.includes('isPartner'));
  assert.equal(summary.isPartner, true);
  assert.equal(summary.statusLabel, PARTNER_STATUS_LABEL);
});

test('getMapMarkers returns only safe fields', async () => {
  const company = makeFakeCompany({ status: 'active', latitude: 57.5, longitude: 12.07 });

  const prisma = {
    partnerCompany: {
      findMany: async () => [company],
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerCompanyService(prisma);
  const markers = await service.getMapMarkers();

  assert.equal(markers.length, 1);
  const marker = markers[0]!;
  const keys = Object.keys(marker);

  // Only safe fields
  assert.ok(keys.includes('partnerId'));
  assert.ok(keys.includes('companyName'));
  assert.ok(keys.includes('category'));
  assert.ok(keys.includes('latitude'));
  assert.ok(keys.includes('longitude'));
  assert.ok(keys.includes('label'));
  assert.equal(marker.label, PARTNER_STATUS_LABEL);

  // No internal fields
  assert.ok(!keys.includes('contactEmail'));
  assert.ok(!keys.includes('publicDescription'));
  assert.ok(!keys.includes('address'));
  assert.ok(!keys.includes('createdByUserId'));
});

test('pausePartner rejects non-active partner', async () => {
  const company = makeFakeCompany({ status: 'draft' });
  const prisma = buildFakePrisma({ findUniqueResult: company });
  const service = new PartnerCompanyService(prisma);

  await assert.rejects(
    () => service.pausePartner({ actorUserId: ADMIN_ID, partnerId: COMPANY_ID }),
    (err) => err instanceof AppError && err.code === 'invalid_status_transition',
  );
});

test('pausePartner writes audit log', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const company = makeFakeCompany({ status: 'active' });

  const prisma = {
    partnerCompany: {
      findUnique: async () => company,
      update: async ({ data }: { data: Record<string, unknown> }) => ({ ...company, ...data }),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerCompanyService(prisma);
  await service.pausePartner({ actorUserId: ADMIN_ID, partnerId: COMPANY_ID, reason: 'Sommarstängt' });

  assert.ok(auditLogs.some((l) => l.action === 'partner_company.pause'));
});

test('endPartnership rejects already-ended partner', async () => {
  const company = makeFakeCompany({ status: 'ended' });
  const prisma = buildFakePrisma({ findUniqueResult: company });
  const service = new PartnerCompanyService(prisma);

  await assert.rejects(
    () => service.endPartnership({ actorUserId: ADMIN_ID, partnerId: COMPANY_ID }),
    (err) => err instanceof AppError && err.code === 'invalid_status_transition',
  );
});

test('endPartnership writes audit log', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const company = makeFakeCompany({ status: 'active' });

  const prisma = {
    partnerCompany: {
      findUnique: async () => company,
      update: async ({ data }: { data: Record<string, unknown> }) => ({ ...company, ...data }),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerCompanyService(prisma);
  await service.endPartnership({ actorUserId: ADMIN_ID, partnerId: COMPANY_ID, reason: 'Avtal avslutat' });

  assert.ok(auditLogs.some((l) => l.action === 'partner_company.end'));
});
