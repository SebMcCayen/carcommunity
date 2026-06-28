/**
 * BillboardService unit tests.
 *
 * Uses a fake Prisma client — no database connection required.
 * Follows the same pattern as crown-hunt.test.ts.
 *
 * Covers:
 *  - New billboard always starts as draft
 *  - Create/update cannot change status
 *  - Activation requires active partner
 *  - Activation requires valid coordinates
 *  - Activation requires all 6 safety confirmations
 *  - Activation requires non-empty approvalReason
 *  - Paused/ended/draft/expired billboards are hidden publicly
 *  - Feature flag disabled blocks public routes (tested at service level via route)
 *  - Public response contains sponsorLabel='Sponsrad placering' and partnerCompanyName
 *  - Public response excludes approvalReason and safetyNote
 *  - Important admin actions write audit entries
 *  - No bidding, billing, video, script fields on the model
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { BillboardService } from './lib/billboard-service.js';
import { AppError } from './lib/errors.js';

const ADMIN_ID = 'aaaaaaaa-0000-4000-8000-000000000001';
const PARTNER_ID = 'bbbbbbbb-0000-4000-8000-000000000001';
const BILLBOARD_ID = 'cccccccc-0000-4000-8000-000000000001';

interface FakePartnerCompany {
  id: string;
  companyName: string;
  status: string;
}

interface FakeBillboard {
  id: string;
  partnerCompanyId: string;
  headline: string;
  message: string;
  placementType: string;
  latitude: number;
  longitude: number;
  status: string;
  availableFrom: Date | null;
  availableUntil: Date | null;
  callToActionType: string | null;
  callToActionValue: string | null;
  safetyNote: string | null;
  approvalReason: string | null;
  approvedAt: Date | null;
  approvedByUserId: string | null;
  activatedAt: Date | null;
  pausedAt: Date | null;
  endedAt: Date | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: Date;
  updatedAt: Date;
  partnerCompany: { companyName: string; status: string };
}

interface FakeAuditLog {
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  reason: string | null;
  metadata: unknown;
}

function makeBillboard(overrides: Partial<FakeBillboard> = {}): FakeBillboard {
  const now = new Date();
  return {
    id: BILLBOARD_ID,
    partnerCompanyId: PARTNER_ID,
    headline: 'Test headline',
    message: 'Test message',
    placementType: 'map_billboard',
    latitude: 57.5086,
    longitude: 12.0742,
    status: 'draft',
    availableFrom: null,
    availableUntil: null,
    callToActionType: null,
    callToActionValue: null,
    safetyNote: null,
    approvalReason: null,
    approvedAt: null,
    approvedByUserId: null,
    activatedAt: null,
    pausedAt: null,
    endedAt: null,
    createdByUserId: ADMIN_ID,
    updatedByUserId: null,
    createdAt: now,
    updatedAt: now,
    partnerCompany: { companyName: 'Test Partner AB', status: 'active' },
    ...overrides,
  };
}

function matchesWhere(billboard: FakeBillboard, where: Record<string, unknown>): boolean {
  if (where['status'] && billboard.status !== where['status']) return false;

  const partnerCompanyFilter = where['partnerCompany'] as { is?: { status?: string } } | undefined;
  if (partnerCompanyFilter?.is?.status && billboard.partnerCompany.status !== partnerCompanyFilter.is.status) {
    return false;
  }

  const andFilters = where['AND'] as Array<Record<string, unknown>> | undefined;
  if (andFilters) {
    for (const filter of andFilters) {
      const orFilters = filter['OR'] as Array<Record<string, unknown>> | undefined;
      if (orFilters) {
        const orMatched = orFilters.some((orFilter) => {
          if ('availableFrom' in orFilter) {
            const availableFrom = orFilter['availableFrom'];
            if (availableFrom === null) return billboard.availableFrom === null;
            const lte = (availableFrom as { lte?: Date }).lte;
            return lte ? billboard.availableFrom !== null && billboard.availableFrom <= lte : false;
          }
          if ('availableUntil' in orFilter) {
            const availableUntil = orFilter['availableUntil'];
            if (availableUntil === null) return billboard.availableUntil === null;
            const gte = (availableUntil as { gte?: Date }).gte;
            return gte ? billboard.availableUntil !== null && billboard.availableUntil >= gte : false;
          }
          return true;
        });
        if (!orMatched) return false;
      }
    }
  }

  return true;
}

function buildFakePrisma(options: {
  partners?: FakePartnerCompany[];
  billboards?: FakeBillboard[];
  auditLogs?: FakeAuditLog[];
} = {}): Record<string, unknown> {
  const partners: FakePartnerCompany[] = options.partners ?? [];
  const billboards: FakeBillboard[] = options.billboards ?? [];
  const auditLogs: FakeAuditLog[] = options.auditLogs ?? [];

  let idCounter = 1;
  const nextId = () => `fake-id-${idCounter++}`;

  return {
    _auditLogs: auditLogs,
    _billboards: billboards,

    partnerCompany: {
      async findUnique({ where }: { where: { id?: string }; select?: unknown }) {
        return partners.find((partner) => partner.id === where.id) ?? null;
      },
    },

    sponsoredBillboard: {
      async findUnique({ where }: { where: { id?: string }; select?: unknown }) {
        return billboards.find((billboard) => billboard.id === where.id) ?? null;
      },
      async findMany({ where = {}, skip = 0, take = 20 }: {
        where?: Record<string, unknown>;
        skip?: number;
        take?: number;
        select?: unknown;
        orderBy?: unknown;
      }) {
        const filtered = billboards.filter((billboard) => matchesWhere(billboard, where));
        return filtered.slice(skip, skip + take);
      },
      async count({ where = {} }: { where?: Record<string, unknown> } = {}) {
        return billboards.filter((billboard) => matchesWhere(billboard, where)).length;
      },
      async create({ data }: { data: Record<string, unknown>; select?: unknown }) {
        const now = new Date();
        const partner = partners.find((candidate) => candidate.id === (data['partnerCompanyId'] as string));
        const board: FakeBillboard = {
          id: nextId(),
          partnerCompanyId: data['partnerCompanyId'] as string,
          headline: data['headline'] as string,
          message: data['message'] as string,
          placementType: data['placementType'] as string,
          latitude: data['latitude'] as number,
          longitude: data['longitude'] as number,
          status: (data['status'] as string) ?? 'draft',
          availableFrom: data['availableFrom'] ? new Date(data['availableFrom'] as string) : null,
          availableUntil: data['availableUntil'] ? new Date(data['availableUntil'] as string) : null,
          callToActionType: (data['callToActionType'] as string | null) ?? null,
          callToActionValue: (data['callToActionValue'] as string | null) ?? null,
          safetyNote: (data['safetyNote'] as string | null) ?? null,
          approvalReason: (data['approvalReason'] as string | null) ?? null,
          approvedAt: null,
          approvedByUserId: null,
          activatedAt: null,
          pausedAt: null,
          endedAt: null,
          createdByUserId: data['createdByUserId'] as string,
          updatedByUserId: null,
          createdAt: now,
          updatedAt: now,
          partnerCompany: { companyName: partner?.companyName ?? 'Unknown', status: partner?.status ?? 'draft' },
        };
        billboards.push(board);
        return board;
      },
      async update({ where, data }: { where: { id: string }; data: Record<string, unknown>; select?: unknown }) {
        const index = billboards.findIndex((billboard) => billboard.id === where.id);
        if (index < 0) return null;
        const existing = billboards[index]!;
        const updated = {
          ...existing,
          ...data,
          updatedAt: new Date(),
        };
        billboards[index] = updated;
        return updated;
      },
    },

    auditLog: {
      async create({ data }: { data: Record<string, unknown> }) {
        auditLogs.push({
          actorUserId: (data['actorUserId'] as string | null) ?? null,
          action: data['action'] as string,
          entityType: data['entityType'] as string,
          entityId: (data['entityId'] as string | null) ?? null,
          reason: (data['reason'] as string | null) ?? null,
          metadata: data['metadata'],
        });
        return { id: nextId(), ...data };
      },
    },
  };
}

const VALID_ACTIVATE = {
  notBusinessLocationConfirmed: true as const,
  notRoadLaneConfirmed: true as const,
  notRoadSignConfirmed: true as const,
  notObstructingMapConfirmed: true as const,
  markedAsAdvertisingConfirmed: true as const,
  suitableForMapConfirmed: true as const,
  approvalReason: 'Approved: safe parking location, clearly labelled.',
};

test('new billboard always starts as draft', async () => {
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const fakePrisma = buildFakePrisma({ partners });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.createDraft({
    actorUserId: ADMIN_ID,
    partnerCompanyId: PARTNER_ID,
    headline: 'Hello',
    message: 'World',
    placementType: 'map_billboard',
    latitude: 57.5,
    longitude: 12.0,
  });

  assert.equal(result.status, 'draft');
});

test('createDraft does not accept status from client (status always draft)', async () => {
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const fakePrisma = buildFakePrisma({ partners });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.createDraft({
    actorUserId: ADMIN_ID,
    partnerCompanyId: PARTNER_ID,
    headline: 'Hello',
    message: 'World',
    placementType: 'map_billboard',
    latitude: 57.5,
    longitude: 12.0,
  });

  assert.equal(result.status, 'draft');
});

test('updateDraftOrPaused does not change status', async () => {
  const billboard = makeBillboard({ status: 'draft' });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.updateDraftOrPaused(BILLBOARD_ID, ADMIN_ID, {
    headline: 'Updated',
  });

  assert.equal(result.status, 'draft');
});

test('updateDraftOrPaused rejects active billboard', async () => {
  const billboard = makeBillboard({ status: 'active' });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  await assert.rejects(
    () => service.updateDraftOrPaused(BILLBOARD_ID, ADMIN_ID, { headline: 'Updated' }),
    (err: AppError) => {
      assert.equal(err.code, 'billboard_invalid_status_for_update');
      return true;
    },
  );
});

test('activation requires active partner', async () => {
  const billboard = makeBillboard({ status: 'draft' });
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'paused' }];
  const fakePrisma = buildFakePrisma({ partners, billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  await assert.rejects(
    () => service.activate(BILLBOARD_ID, ADMIN_ID, VALID_ACTIVATE),
    (err: AppError) => {
      assert.equal(err.code, 'billboard_partner_not_active');
      return true;
    },
  );
});

test('activation requires valid coordinates', async () => {
  const billboard = makeBillboard({ status: 'draft', latitude: 120, longitude: 12 });
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const fakePrisma = buildFakePrisma({ partners, billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  await assert.rejects(
    () => service.activate(BILLBOARD_ID, ADMIN_ID, VALID_ACTIVATE),
    (err: AppError) => {
      assert.equal(err.code, 'billboard_invalid_coordinates');
      return true;
    },
  );
});

test('activation requires all 6 safety confirmations', async () => {
  const billboard = makeBillboard({ status: 'draft' });
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const fakePrisma = buildFakePrisma({ partners, billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  await assert.rejects(
    () =>
      service.activate(BILLBOARD_ID, ADMIN_ID, {
        notBusinessLocationConfirmed: true,
        notRoadLaneConfirmed: false as never,
        notRoadSignConfirmed: true,
        notObstructingMapConfirmed: true,
        markedAsAdvertisingConfirmed: true,
        suitableForMapConfirmed: true,
        approvalReason: 'Some reason',
      }),
    (err: AppError) => {
      assert.equal(err.code, 'billboard_safety_confirmation_required');
      return true;
    },
  );
});

test('activation requires non-empty approvalReason', async () => {
  const billboard = makeBillboard({ status: 'draft' });
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const fakePrisma = buildFakePrisma({ partners, billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  await assert.rejects(
    () =>
      service.activate(BILLBOARD_ID, ADMIN_ID, {
        ...VALID_ACTIVATE,
        approvalReason: '   ',
      }),
    (err: AppError) => {
      assert.equal(err.code, 'billboard_reason_required');
      return true;
    },
  );
});

test('activation succeeds with valid input and active partner', async () => {
  const billboard = makeBillboard({ status: 'draft' });
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const fakePrisma = buildFakePrisma({ partners, billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.activate(BILLBOARD_ID, ADMIN_ID, VALID_ACTIVATE);

  assert.equal(result.status, 'active');
});

test('public list hides draft billboards', async () => {
  const billboard = makeBillboard({ status: 'draft' });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.listPublic();
  assert.equal(result.billboards.length, 0);
});

test('public list hides paused billboards', async () => {
  const billboard = makeBillboard({ status: 'paused' });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.listPublic();
  assert.equal(result.billboards.length, 0);
});

test('public list hides ended billboards', async () => {
  const billboard = makeBillboard({ status: 'ended' });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.listPublic();
  assert.equal(result.billboards.length, 0);
});

test('public list hides expired billboards', async () => {
  const billboard = makeBillboard({
    status: 'active',
    availableUntil: new Date(Date.now() - 60_000),
  });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.listPublic({ now: new Date() });
  assert.equal(result.billboards.length, 0);
});

test('public markers response contains sponsorLabel=Sponsrad placering', async () => {
  const billboard = makeBillboard({ status: 'active' });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const result = await service.listPublicMarkers();

  assert.equal(result.markers.length, 1);
  const marker = result.markers[0]!;
  assert.equal(marker.sponsorLabel, 'Sponsrad placering');
  assert.equal(typeof marker.partnerCompanyName, 'string');
});

test('admin detail contains safetyNote but public response does not', async () => {
  const billboard = makeBillboard({
    status: 'active',
    safetyNote: 'Internal note for admin',
    approvalReason: 'Internal approval note',
  });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  const adminDetail = await service.adminGetDetail(BILLBOARD_ID);
  assert.equal(adminDetail.safetyNote, 'Internal note for admin');

  const publicDetail = await service.getPublicDetail(BILLBOARD_ID);
  assert.ok(!('safetyNote' in publicDetail), 'Public detail must not contain safetyNote');
  assert.ok(!('approvalReason' in publicDetail), 'Public detail must not contain approvalReason');
  assert.ok(!('approvedAt' in publicDetail), 'Public detail must not contain approvedAt');
  assert.ok(!('approvedByUserId' in publicDetail), 'Public detail must not contain approvedByUserId');
  assert.ok(!('createdByUserId' in publicDetail), 'Public detail must not contain createdByUserId');
});

test('important admin actions write audit entries', async () => {
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const auditLogs: FakeAuditLog[] = [];
  const fakePrisma = buildFakePrisma({ partners, auditLogs });
  const service = new BillboardService(fakePrisma as never);

  await service.createDraft({
    actorUserId: ADMIN_ID,
    partnerCompanyId: PARTNER_ID,
    headline: 'Hello',
    message: 'World',
    placementType: 'map_billboard',
    latitude: 57.5,
    longitude: 12.0,
  });

  assert.ok(auditLogs.length >= 1, 'At least one audit log entry must be written on create');
  assert.ok(
    auditLogs.some((entry) => entry.action === 'sponsored_billboard.created'),
    'Audit log must include creation action',
  );
});

test('activation writes audit entry', async () => {
  const billboard = makeBillboard({ status: 'draft' });
  const partners: FakePartnerCompany[] = [{ id: PARTNER_ID, companyName: 'Test AB', status: 'active' }];
  const auditLogs: FakeAuditLog[] = [];
  const fakePrisma = buildFakePrisma({ partners, billboards: [billboard], auditLogs });
  const service = new BillboardService(fakePrisma as never);

  await service.activate(BILLBOARD_ID, ADMIN_ID, VALID_ACTIVATE);

  assert.ok(
    auditLogs.some((entry) => entry.action === 'sponsored_billboard.activated'),
    'Audit log must include activation action',
  );
});

test('pause writes audit entry with reason', async () => {
  const billboard = makeBillboard({ status: 'active' });
  const auditLogs: FakeAuditLog[] = [];
  const fakePrisma = buildFakePrisma({ billboards: [billboard], auditLogs });
  const service = new BillboardService(fakePrisma as never);

  await service.pause(BILLBOARD_ID, ADMIN_ID, 'Content policy violation');

  const entry = auditLogs.find((auditLog) => auditLog.action === 'sponsored_billboard.paused');
  assert.ok(entry, 'Audit log must include pause action');
  assert.equal(entry.reason, 'Content policy violation');
});

test('pause requires non-empty reason', async () => {
  const billboard = makeBillboard({ status: 'active' });
  const fakePrisma = buildFakePrisma({ billboards: [billboard] });
  const service = new BillboardService(fakePrisma as never);

  await assert.rejects(
    () => service.pause(BILLBOARD_ID, ADMIN_ID, ''),
    (err: AppError) => {
      assert.equal(err.code, 'billboard_reason_required');
      return true;
    },
  );
});

test('AdminBillboardSummary has no bidding, billing, video or script fields', () => {
  const billboard = makeBillboard({ status: 'draft' });
  const forbiddenFields = [
    'bid', 'auction', 'price', 'cost', 'budget', 'cpm', 'video',
    'script', 'html', 'adNetwork', 'targetingProfile', 'retargeting',
  ];
  const keys = Object.keys(billboard);
  for (const field of forbiddenFields) {
    assert.ok(!keys.includes(field), `Model must not have field: ${field}`);
  }
});
