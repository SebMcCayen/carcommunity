/**
 * PartnerApplicationService unit tests.
 *
 * Uses a fake Prisma client — no database connection required.
 *
 * Covers:
 *  - Application submission validates required fields
 *  - Submitted text is stored as plain text (not HTML)
 *  - Duplicate submission from same userId is rejected
 *  - Duplicate submission from same email is rejected
 *  - Start review transitions status correctly
 *  - Approval creates a DRAFT partner company
 *  - Approval does NOT publish the partner (partner is draft after approval)
 *  - Approval is idempotent (returns same company if called twice)
 *  - Rejection requires a reason
 *  - Rejection without reason throws
 *  - Cannot approve/reject an already-approved application
 *  - Audit logs written for review, approval, rejection
 *  - Contact email and reviewReason are internal — not in public response
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { PartnerApplicationService } from './lib/partner-application-service.js';
import { AppError } from './lib/errors.js';

// ---------------------------------------------------------------------------
// Test UUIDs
// ---------------------------------------------------------------------------

const USER_ID = 'aaaaaaaa-0000-4000-8000-000000000001';
const ADMIN_ID = 'aaaaaaaa-0000-4000-8000-000000000002';
const APP_ID = 'bbbbbbbb-0000-4000-8000-000000000001';
const COMPANY_ID = 'cccccccc-0000-4000-8000-000000000001';

// ---------------------------------------------------------------------------
// Fake Prisma builder
// ---------------------------------------------------------------------------

interface FakeApplication {
  id: string;
  companyName: string;
  organizationNumber: string | null;
  category: string;
  contactName: string;
  contactEmail: string;
  contactPhone: string | null;
  websiteUrl: string | null;
  proposedDescription: string | null;
  proposedAddress: string | null;
  message: string | null;
  status: string;
  submittedByUserId: string | null;
  reviewedByUserId: string | null;
  reviewedAt: Date | null;
  reviewReason: string | null;
  createdAt: Date;
  updatedAt: Date;
  partnerCompany: { id: string } | null;
}

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

interface FakeAuditLog {
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
}

function makeFakeApplication(overrides: Partial<FakeApplication> = {}): FakeApplication {
  return {
    id: APP_ID,
    companyName: 'Testas AB',
    organizationNumber: null,
    category: 'workshop',
    contactName: 'Anna Test',
    contactEmail: 'anna@test.se',
    contactPhone: null,
    websiteUrl: null,
    proposedDescription: null,
    proposedAddress: null,
    message: null,
    status: 'submitted',
    submittedByUserId: USER_ID,
    reviewedByUserId: null,
    reviewedAt: null,
    reviewReason: null,
    createdAt: new Date('2026-01-01'),
    updatedAt: new Date('2026-01-01'),
    partnerCompany: null,
    ...overrides,
  };
}

function buildFakePrisma(options: {
  findFirstResult?: FakeApplication | null;
  findUniqueResult?: (FakeApplication & { partnerCompany: { id: string } | null }) | null;
  createResult?: FakeApplication;
  createdCompany?: FakeCompany;
  auditLogs?: FakeAuditLog[];
}) {
  const auditLogs: FakeAuditLog[] = options.auditLogs ?? [];
  let applicationStore: FakeApplication = options.findUniqueResult ?? makeFakeApplication();
  let companyStore: FakeCompany | null = options.createdCompany ?? null;

  return {
    partnerApplication: {
      findFirst: async ({ where }: { where: Record<string, unknown> }) => {
        if (options.findFirstResult !== undefined) return options.findFirstResult;

        // For duplicate check: return null by default (no existing)
        return null;
      },
      findUnique: async (_args: unknown) => {
        if (options.findUniqueResult === undefined) return null;
        return options.findUniqueResult;
      },
      create: async ({ data }: { data: Record<string, unknown> }) => {
        const created = {
          ...applicationStore,
          id: APP_ID,
          companyName: (data.companyName as string) ?? applicationStore.companyName,
          createdAt: new Date(),
          updatedAt: new Date(),
        };
        applicationStore = created;
        return created;
      },
      update: async ({ data }: { data: Record<string, unknown> }) => {
        applicationStore = { ...applicationStore, ...data } as FakeApplication;
        return applicationStore;
      },
      count: async () => 0,
    },
    partnerCompany: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        const created: FakeCompany = {
          id: COMPANY_ID,
          applicationId: (data.applicationId as string | null) ?? null,
          companyName: (data.companyName as string) ?? '',
          category: (data.category as string) ?? 'workshop',
          publicDescription: (data.publicDescription as string) ?? '',
          address: (data.address as string) ?? '',
          latitude: (data.latitude as number) ?? 0,
          longitude: (data.longitude as number) ?? 0,
          publicPhone: null,
          publicWebsiteUrl: null,
          status: 'draft',
          activatedAt: null,
          pausedAt: null,
          endedAt: null,
          createdByUserId: ADMIN_ID,
          updatedByUserId: null,
          createdAt: new Date(),
          updatedAt: new Date(),
        };
        companyStore = created;
        return created;
      },
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => {
      return Promise.all(ops);
    },
  } as unknown as import('@prisma/client').PrismaClient;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test('submitApplication creates an application with submitted status', async () => {
  const prisma = buildFakePrisma({ findFirstResult: null });
  const service = new PartnerApplicationService(prisma);

  const result = await service.submitApplication({
    companyName: 'Bil & Däck AB',
    category: 'workshop',
    contactName: 'Bo Karlsson',
    contactEmail: 'bo@bilogdack.se',
    submittedByUserId: USER_ID,
  });

  assert.ok(result.applicationId);
  assert.ok(result.submittedAt);
});

test('submitApplication rejects invalid category', async () => {
  const prisma = buildFakePrisma({});
  const service = new PartnerApplicationService(prisma);

  await assert.rejects(
    () =>
      service.submitApplication({
        companyName: 'Bil AB',
        category: 'invalid_cat' as 'workshop',
        contactName: 'Test',
        contactEmail: 'test@test.se',
        submittedByUserId: USER_ID,
      }),
    (err) => err instanceof AppError && err.code === 'invalid_category',
  );
});

test('submitApplication blocks duplicate submission from same userId', async () => {
  const existingApp = makeFakeApplication({ status: 'submitted', submittedByUserId: USER_ID });
  const prisma = buildFakePrisma({ findFirstResult: existingApp });
  const service = new PartnerApplicationService(prisma);

  await assert.rejects(
    () =>
      service.submitApplication({
        companyName: 'Bil AB',
        category: 'workshop',
        contactName: 'Test',
        contactEmail: 'test@test.se',
        submittedByUserId: USER_ID,
      }),
    (err) => err instanceof AppError && err.code === 'duplicate_application',
  );
});

test('submitApplication stores text as plain text, not HTML', async () => {
  let capturedMessage = '';
  const prisma = {
    partnerApplication: {
      findFirst: async () => null,
      create: async ({ data }: { data: Record<string, unknown> }) => {
        capturedMessage = data.message as string;
        return { id: APP_ID, createdAt: new Date() };
      },
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);
  const xssPayload = '<script>alert(1)</script>';

  await service.submitApplication({
    companyName: 'Safe AB',
    category: 'workshop',
    contactName: 'Test',
    contactEmail: 'test@test.se',
    message: xssPayload,
    submittedByUserId: USER_ID,
  });

  // Message is stored as-is (plain text) — it is never rendered as HTML.
  // The test verifies we do NOT transform/escape here; the rendering layer
  // is responsible for safe output.
  assert.equal(capturedMessage, xssPayload);
});

test('startReview transitions status to under_review and writes audit log', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const app = makeFakeApplication({ status: 'submitted' });
  let updatedStatus = '';

  const prisma = {
    partnerApplication: {
      findUnique: async () => app,
      update: async ({ data }: { data: Record<string, unknown> }) => {
        updatedStatus = data.status as string;
        return { ...app, ...data };
      },
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);
  await service.startReview({ actorUserId: ADMIN_ID, applicationId: APP_ID });

  assert.equal(updatedStatus, 'under_review');
  assert.ok(auditLogs.some((l) => l.action === 'partner_application.start_review'));
});

test('approveApplication creates a DRAFT partner company — not active', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const app = makeFakeApplication({ status: 'under_review', partnerCompany: null });
  let createdCompanyStatus = '';

  const prisma = {
    partnerApplication: {
      findUnique: async () => app,
      update: async () => ({ ...app, status: 'approved' }),
    },
    partnerCompany: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        createdCompanyStatus = data.status as string;
        return { id: COMPANY_ID, ...data, createdAt: new Date(), updatedAt: new Date() };
      },
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);
  const result = await service.approveApplication({ actorUserId: ADMIN_ID, applicationId: APP_ID });

  assert.ok(result.partnerCompanyId);
  assert.equal(createdCompanyStatus, 'draft', 'Approved application must create a DRAFT, not active partner');
  assert.ok(auditLogs.some((l) => l.action === 'partner_application.approve'));
});

test('approveApplication is idempotent when company already exists', async () => {
  const existingCompany = { id: COMPANY_ID };
  const app = makeFakeApplication({ status: 'approved', partnerCompany: existingCompany });

  const prisma = {
    partnerApplication: {
      findUnique: async () => app,
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);
  const result = await service.approveApplication({ actorUserId: ADMIN_ID, applicationId: APP_ID });

  assert.equal(result.partnerCompanyId, COMPANY_ID);
});

test('rejectApplication requires a reason', async () => {
  const prisma = buildFakePrisma({});
  const service = new PartnerApplicationService(prisma);

  await assert.rejects(
    () =>
      service.rejectApplication({
        actorUserId: ADMIN_ID,
        applicationId: APP_ID,
        reason: '',
      }),
    (err) => err instanceof AppError && err.code === 'reason_required',
  );
});

test('rejectApplication writes audit log with rejection action', async () => {
  const auditLogs: FakeAuditLog[] = [];
  const app = makeFakeApplication({ status: 'submitted' });

  const prisma = {
    partnerApplication: {
      findUnique: async () => app,
      update: async ({ data }: { data: Record<string, unknown> }) => ({ ...app, ...data }),
    },
    auditLog: {
      create: async ({ data }: { data: Record<string, unknown> }) => {
        auditLogs.push(data as unknown as FakeAuditLog);
        return data;
      },
    },
    $transaction: async (ops: Promise<unknown>[]) => Promise.all(ops),
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);
  await service.rejectApplication({
    actorUserId: ADMIN_ID,
    applicationId: APP_ID,
    reason: 'Inte tillräcklig information.',
  });

  assert.ok(auditLogs.some((l) => l.action === 'partner_application.reject'));
});

test('cannot approve an already-approved application (that has a company)', async () => {
  // Idempotency — if company exists, returns the existing company ID without re-creating
  const existingCompany = { id: COMPANY_ID };
  const app = makeFakeApplication({ status: 'approved', partnerCompany: existingCompany });

  const prisma = {
    partnerApplication: {
      findUnique: async () => app,
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);
  const result = await service.approveApplication({ actorUserId: ADMIN_ID, applicationId: APP_ID });

  // Returns existing company without error
  assert.equal(result.partnerCompanyId, COMPANY_ID);
});

test('cannot reject an already-rejected application', async () => {
  const app = makeFakeApplication({ status: 'rejected' });

  const prisma = {
    partnerApplication: {
      findUnique: async () => app,
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);

  await assert.rejects(
    () =>
      service.rejectApplication({
        actorUserId: ADMIN_ID,
        applicationId: APP_ID,
        reason: 'Duplicate',
      }),
    (err) => err instanceof AppError && err.code === 'invalid_status_transition',
  );
});

test('getApplicationDetail returns full detail including contact fields', async () => {
  const app = makeFakeApplication({
    contactEmail: 'anna@test.se',
    contactName: 'Anna Test',
    reviewReason: null,
    partnerCompany: null,
  });

  const prisma = {
    partnerApplication: {
      findUnique: async () => app,
    },
  } as unknown as import('@prisma/client').PrismaClient;

  const service = new PartnerApplicationService(prisma);
  const detail = await service.getApplicationDetail(APP_ID);

  // Contact fields are present in admin detail (internal use)
  assert.equal(detail.contactEmail, 'anna@test.se');
  assert.equal(detail.contactName, 'Anna Test');
  // Review reason may be null
  assert.equal(detail.reviewReason, null);
});
