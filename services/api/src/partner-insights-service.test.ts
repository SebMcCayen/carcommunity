import assert from 'node:assert/strict';
import test from 'node:test';

import { canContributeAnonymousPartnerStats } from '@carcommunity/shared/users';
import { MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD } from '@carcommunity/shared/partner-insights';

import { AppError } from './lib/errors.js';
import { PartnerInsightsService } from './lib/partner-insights-service.js';

const PARTNER_ID = 'bbbbbbbb-0000-4000-8000-000000000001';
const OTHER_PARTNER_ID = 'bbbbbbbb-0000-4000-8000-000000000002';
const USER_ID = 'aaaaaaaa-0000-4000-8000-000000000001';
const OFFER_ID = 'cccccccc-0000-4000-8000-000000000001';
const NOW = new Date('2026-06-28T12:00:00.000Z');

interface FakeEvent {
  id: string;
  partnerCompanyId: string;
  interactionType: string;
  userReferenceHash: string | null;
  occurredAt: Date;
  aggregationDate: Date;
  expiresAt: Date;
  metadata?: Record<string, unknown>;
}

interface FakeAggregate {
  id: string;
  partnerCompanyId: string;
  interactionType: string;
  periodType: string;
  periodStart: Date;
  periodEnd: Date;
  totalCount: number;
  uniqueContributorCount: number | null;
  resultStatus: string;
  createdAt: Date;
  updatedAt: Date;
}

interface FakeContribution {
  id: string;
  partnerCompanyId: string;
  scopedContributorHash: string;
  aggregationDate: Date;
  expiresAt: Date;
  createdAt: Date;
}

function buildFakePrisma(options?: {
  partner?: { id: string; status: string; latitude?: number; longitude?: number } | null;
  offer?: { id: string; partnerCompanyId: string } | null;
  livePosition?: { latitude: number; longitude: number } | null;
  events?: FakeEvent[];
  aggregates?: FakeAggregate[];
  contributions?: FakeContribution[];
}) {
  const partner = options && 'partner' in options
    ? options.partner
    : { id: PARTNER_ID, status: 'active', latitude: 57.5, longitude: 12.07 };
  const offer = options && 'offer' in options ? options.offer : { id: OFFER_ID, partnerCompanyId: PARTNER_ID };
  const livePosition = options && 'livePosition' in options ? options.livePosition : null;
  const events = [...(options?.events ?? [])];
  const aggregates = [...(options?.aggregates ?? [])];
  const contributions = [...(options?.contributions ?? [])];

  const prisma = {
    partnerCompany: {
      findUnique: async ({ where }: { where: { id: string } }) => {
        if (!partner || where.id !== partner.id) {
          return null;
        }
        return partner;
      },
    },
    partnerOffer: {
      findUnique: async ({ where }: { where: { id: string } }) => {
        if (!offer || where.id !== offer.id) {
          return null;
        }
        return offer;
      },
    },
    partnerInteractionEvent: {
      findFirst: async ({ where }: { where: Record<string, unknown> }) =>
        events.find(
          (event) =>
            event.partnerCompanyId === where.partnerCompanyId &&
            event.interactionType === where.interactionType &&
            event.userReferenceHash === where.userReferenceHash &&
            event.aggregationDate.getTime() === (where.aggregationDate as Date).getTime(),
        ) ?? null,
      create: async ({ data }: { data: Record<string, unknown> }) => {
        const created: FakeEvent = {
          id: `event-${events.length + 1}`,
          partnerCompanyId: data.partnerCompanyId as string,
          interactionType: data.interactionType as string,
          userReferenceHash: (data.userReferenceHash as string | null | undefined) ?? null,
          occurredAt: data.occurredAt as Date,
          aggregationDate: data.aggregationDate as Date,
          expiresAt: data.expiresAt as Date,
          metadata: data.metadata as Record<string, unknown> | undefined,
        };
        events.push(created);
        return created;
      },
      findMany: async ({ where, take }: { where: Record<string, unknown>; take?: number }) => {
        let result = events.filter((event) => {
          if (where.partnerCompanyId && event.partnerCompanyId !== where.partnerCompanyId) return false;
          if (where.interactionType && event.interactionType !== where.interactionType) return false;
          if (where.expiresAt) {
            const expiresAt = where.expiresAt as { lte: Date };
            if (!(event.expiresAt <= expiresAt.lte)) return false;
          }
          if (where.occurredAt) {
            const occurredAt = where.occurredAt as { gte: Date; lt: Date };
            if (!(event.occurredAt >= occurredAt.gte && event.occurredAt < occurredAt.lt)) return false;
          }
          return true;
        });
        result = result.sort((a, b) => a.expiresAt.getTime() - b.expiresAt.getTime());
        return typeof take === 'number' ? result.slice(0, take) : result;
      },
      deleteMany: async ({ where }: { where: { id: { in: string[] } } }) => {
        const before = events.length;
        const ids = new Set(where.id.in);
        for (let index = events.length - 1; index >= 0; index -= 1) {
          if (ids.has(events[index]!.id)) {
            events.splice(index, 1);
          }
        }
        return { count: before - events.length };
      },
    },
    partnerMetricAggregate: {
      upsert: async ({ where, create, update }: { where: Record<string, any>; create: Record<string, any>; update: Record<string, any> }) => {
        const key = where.partnerCompanyId_interactionType_periodType_periodStart;
        const existing = aggregates.find(
          (aggregate) =>
            aggregate.partnerCompanyId === key.partnerCompanyId &&
            aggregate.interactionType === key.interactionType &&
            aggregate.periodType === key.periodType &&
            aggregate.periodStart.getTime() === key.periodStart.getTime(),
        );
        if (existing) {
          Object.assign(existing, update, { updatedAt: NOW });
          return existing;
        }
        const created: FakeAggregate = {
          id: `aggregate-${aggregates.length + 1}`,
          partnerCompanyId: create.partnerCompanyId as string,
          interactionType: create.interactionType as string,
          periodType: create.periodType as string,
          periodStart: create.periodStart as Date,
          periodEnd: create.periodEnd as Date,
          totalCount: create.totalCount as number,
          uniqueContributorCount: (create.uniqueContributorCount as number | null | undefined) ?? null,
          resultStatus: create.resultStatus as string,
          createdAt: NOW,
          updatedAt: NOW,
        };
        aggregates.push(created);
        return created;
      },
      findMany: async ({ where }: { where: Record<string, any> }) =>
        aggregates
          .filter((aggregate) => {
            if (where.partnerCompanyId && aggregate.partnerCompanyId !== where.partnerCompanyId) return false;
            if (where.periodType && aggregate.periodType !== where.periodType) return false;
            if (where.periodStart && aggregate.periodStart < where.periodStart.gte) return false;
            if (where.periodEnd && aggregate.periodEnd > where.periodEnd.lte) return false;
            return true;
          })
          .sort((a, b) => a.periodStart.getTime() - b.periodStart.getTime()),
    },
    partnerPassByContribution: {
      findUnique: async ({ where }: { where: Record<string, any> }) => {
        const key = where.partnerCompanyId_scopedContributorHash_aggregationDate;
        return (
          contributions.find(
            (contribution) =>
              contribution.partnerCompanyId === key.partnerCompanyId &&
              contribution.scopedContributorHash === key.scopedContributorHash &&
              contribution.aggregationDate.getTime() === key.aggregationDate.getTime(),
          ) ?? null
        );
      },
      create: async ({ data }: { data: Record<string, unknown> }) => {
        const created: FakeContribution = {
          id: `contribution-${contributions.length + 1}`,
          partnerCompanyId: data.partnerCompanyId as string,
          scopedContributorHash: data.scopedContributorHash as string,
          aggregationDate: data.aggregationDate as Date,
          expiresAt: data.expiresAt as Date,
          createdAt: NOW,
        };
        contributions.push(created);
        return created;
      },
      findMany: async ({ where, take }: { where: Record<string, any>; take?: number }) => {
        let result = contributions.filter((contribution) => contribution.expiresAt <= where.expiresAt.lte);
        result = result.sort((a, b) => a.expiresAt.getTime() - b.expiresAt.getTime());
        return typeof take === 'number' ? result.slice(0, take) : result;
      },
      deleteMany: async ({ where }: { where: { id: { in: string[] } } }) => {
        const before = contributions.length;
        const ids = new Set(where.id.in);
        for (let index = contributions.length - 1; index >= 0; index -= 1) {
          if (ids.has(contributions[index]!.id)) {
            contributions.splice(index, 1);
          }
        }
        return { count: before - contributions.length };
      },
    },
    liveLocationLatestPosition: {
      findFirst: async () => livePosition,
    },
    $transaction: async (input: unknown) => {
      if (typeof input === 'function') {
        return input(prisma);
      }
      return input;
    },
    _events: events,
    _aggregates: aggregates,
    _contributions: contributions,
  };

  return prisma as unknown as import('@prisma/client').PrismaClient & {
    _events: FakeEvent[];
    _aggregates: FakeAggregate[];
    _contributions: FakeContribution[];
  };
}

test('canContributeAnonymousPartnerStats defaults to false when opt-in is false', () => {
  assert.equal(
    canContributeAnonymousPartnerStats({ status: 'active', anonymousPartnerStatsOptIn: false }),
    false,
  );
});

test('canContributeAnonymousPartnerStats returns false for temporarily suspended users', () => {
  assert.equal(
    canContributeAnonymousPartnerStats({
      status: 'temporarily_suspended',
      anonymousPartnerStatsOptIn: true,
    }),
    false,
  );
});

test('canContributeAnonymousPartnerStats returns false for permanently suspended users', () => {
  assert.equal(
    canContributeAnonymousPartnerStats({
      status: 'permanently_suspended',
      anonymousPartnerStatsOptIn: true,
    }),
    false,
  );
});

test('canContributeAnonymousPartnerStats returns false for deleted users', () => {
  assert.equal(
    canContributeAnonymousPartnerStats({ status: 'deleted', anonymousPartnerStatsOptIn: true }),
    false,
  );
});

test('canContributeAnonymousPartnerStats returns false when active user has not opted in', () => {
  assert.equal(
    canContributeAnonymousPartnerStats({ status: 'active', anonymousPartnerStatsOptIn: false }),
    false,
  );
});

test('canContributeAnonymousPartnerStats returns true for active opted-in users', () => {
  assert.equal(
    canContributeAnonymousPartnerStats({ status: 'active', anonymousPartnerStatsOptIn: true }),
    true,
  );
});

test('canContributeAnonymousPartnerStats returns true for warned opted-in users', () => {
  assert.equal(
    canContributeAnonymousPartnerStats({ status: 'warned', anonymousPartnerStatsOptIn: true }),
    true,
  );
});

test('anonymous pass-by recording is rejected when the user is opted out', async () => {
  const prisma = buildFakePrisma({ livePosition: { latitude: 57.5, longitude: 12.07 } });
  const service = new PartnerInsightsService(prisma, {
    partnerInsightsPassByFeatureEnabled: true,
    partnerInsightsMinThreshold: MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
  });

  const result = await service.recordAnonymousPassBy({
    partnerCompanyId: PARTNER_ID,
    userId: USER_ID,
    userStatus: 'active',
    anonymousPartnerStatsOptIn: false,
    now: NOW,
  });

  assert.deepEqual(result, { counted: false, reason: 'opted_out' });
});

test('recordInteraction throws 404 when the partner is missing', async () => {
  const prisma = buildFakePrisma({ partner: null });
  const service = new PartnerInsightsService(prisma);

  await assert.rejects(
    () =>
      service.recordInteraction({
        partnerCompanyId: PARTNER_ID,
        interactionType: 'profile_view',
        userId: USER_ID,
        now: NOW,
      }),
    (error) => error instanceof AppError && error.statusCode === 404,
  );
});

test('recordInteraction rejects inactive partners', async () => {
  const prisma = buildFakePrisma({ partner: { id: PARTNER_ID, status: 'paused' } });
  const service = new PartnerInsightsService(prisma);

  await assert.rejects(
    () =>
      service.recordInteraction({
        partnerCompanyId: PARTNER_ID,
        interactionType: 'profile_view',
        userId: USER_ID,
        now: NOW,
      }),
    (error) => error instanceof AppError && error.code === 'interaction_partner_inactive',
  );
});

test('recordInteraction rejects unsupported interaction types', async () => {
  const prisma = buildFakePrisma();
  const service = new PartnerInsightsService(prisma);

  await assert.rejects(
    () =>
      service.recordInteraction({
        partnerCompanyId: PARTNER_ID,
        interactionType: 'unsupported' as never,
        userId: USER_ID,
        now: NOW,
      }),
    (error) => error instanceof AppError && error.code === 'interaction_type_unsupported',
  );
});

test('recordInteraction records valid interactions', async () => {
  const prisma = buildFakePrisma();
  const service = new PartnerInsightsService(prisma);

  const result = await service.recordInteraction({
    partnerCompanyId: PARTNER_ID,
    interactionType: 'profile_view',
    userId: USER_ID,
    relatedOfferId: OFFER_ID,
    idempotencyKey: 'idem-1',
    now: NOW,
  });

  assert.deepEqual(result, { recorded: true });
  assert.equal(prisma._events.length, 1);
  assert.notEqual(prisma._events[0]!.userReferenceHash, USER_ID);
  assert.deepEqual(prisma._events[0]!.metadata, { relatedOfferId: OFFER_ID, idempotencyKey: 'idem-1' });
});

test('recordInteraction deduplicates within the same aggregation window', async () => {
  const prisma = buildFakePrisma();
  const service = new PartnerInsightsService(prisma);

  await service.recordInteraction({
    partnerCompanyId: PARTNER_ID,
    interactionType: 'navigate',
    userId: USER_ID,
    now: NOW,
  });
  const second = await service.recordInteraction({
    partnerCompanyId: PARTNER_ID,
    interactionType: 'navigate',
    userId: USER_ID,
    now: NOW,
  });

  assert.deepEqual(second, { recorded: false });
  assert.equal(prisma._events.length, 1);
});

test('recordInteraction rejects offers belonging to another partner', async () => {
  const prisma = buildFakePrisma({ offer: { id: OFFER_ID, partnerCompanyId: OTHER_PARTNER_ID } });
  const service = new PartnerInsightsService(prisma);

  await assert.rejects(
    () =>
      service.recordInteraction({
        partnerCompanyId: PARTNER_ID,
        interactionType: 'offer_view',
        userId: USER_ID,
        relatedOfferId: OFFER_ID,
        now: NOW,
      }),
    (error) => error instanceof AppError && error.code === 'interaction_offer_partner_mismatch',
  );
});

test('checkThreshold returns false below the minimum threshold', () => {
  const service = new PartnerInsightsService(buildFakePrisma());
  assert.deepEqual(service.checkThreshold(9, 10), { meetsThreshold: false, suppressedCount: true });
});

test('checkThreshold returns true at the minimum threshold', () => {
  const service = new PartnerInsightsService(buildFakePrisma());
  assert.deepEqual(service.checkThreshold(10, 10), { meetsThreshold: true, suppressedCount: false });
});

test('aggregatePeriod suppresses anonymous pass-by counts below threshold', async () => {
  const prisma = buildFakePrisma({
    events: [
      {
        id: 'event-1',
        partnerCompanyId: PARTNER_ID,
        interactionType: 'anonymous_pass_by',
        userReferenceHash: 'hash-1',
        occurredAt: NOW,
        aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
        expiresAt: new Date('2026-07-05T00:00:00.000Z'),
      },
    ],
  });
  const service = new PartnerInsightsService(prisma, { partnerInsightsMinThreshold: 10 });

  await service.aggregatePeriod({ partnerCompanyId: PARTNER_ID, date: NOW, periodType: 'day' });

  const aggregate = prisma._aggregates.find((entry) => entry.interactionType === 'anonymous_pass_by');
  assert.equal(aggregate?.resultStatus, 'insufficient_data');
  assert.equal(aggregate?.totalCount, 0);
  assert.equal(aggregate?.uniqueContributorCount, null);
});

test('aggregatePeriod exposes anonymous pass-by counts at or above threshold', async () => {
  const events = Array.from({ length: 10 }, (_, index) => ({
    id: `event-${index + 1}`,
    partnerCompanyId: PARTNER_ID,
    interactionType: 'anonymous_pass_by',
    userReferenceHash: `hash-${index + 1}`,
    occurredAt: NOW,
    aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
    expiresAt: new Date('2026-07-05T00:00:00.000Z'),
  } satisfies FakeEvent));
  const prisma = buildFakePrisma({ events });
  const service = new PartnerInsightsService(prisma, { partnerInsightsMinThreshold: 10 });

  await service.aggregatePeriod({ partnerCompanyId: PARTNER_ID, date: NOW, periodType: 'day' });

  const aggregate = prisma._aggregates.find((entry) => entry.interactionType === 'anonymous_pass_by');
  assert.equal(aggregate?.resultStatus, 'available');
  assert.equal(aggregate?.totalCount, 10);
  assert.equal(aggregate?.uniqueContributorCount, 10);
});

test('aggregatePeriod keeps explicit interactions available when counts exist', async () => {
  const prisma = buildFakePrisma({
    events: [
      {
        id: 'event-1',
        partnerCompanyId: PARTNER_ID,
        interactionType: 'website',
        userReferenceHash: 'hash-1',
        occurredAt: NOW,
        aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
        expiresAt: new Date('2026-07-05T00:00:00.000Z'),
      },
    ],
  });
  const service = new PartnerInsightsService(prisma);

  await service.aggregatePeriod({ partnerCompanyId: PARTNER_ID, date: NOW, periodType: 'day' });

  const aggregate = prisma._aggregates.find((entry) => entry.interactionType === 'website');
  assert.equal(aggregate?.resultStatus, 'available');
  assert.equal(aggregate?.totalCount, 1);
});

test('aggregatePeriod upserts metrics idempotently', async () => {
  const prisma = buildFakePrisma({
    events: [
      {
        id: 'event-1',
        partnerCompanyId: PARTNER_ID,
        interactionType: 'phone',
        userReferenceHash: 'hash-1',
        occurredAt: NOW,
        aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
        expiresAt: new Date('2026-07-05T00:00:00.000Z'),
      },
    ],
  });
  const service = new PartnerInsightsService(prisma);

  await service.aggregatePeriod({ partnerCompanyId: PARTNER_ID, date: NOW, periodType: 'day' });
  await service.aggregatePeriod({ partnerCompanyId: PARTNER_ID, date: NOW, periodType: 'day' });

  const phoneAggregates = prisma._aggregates.filter((entry) => entry.interactionType === 'phone');
  assert.equal(phoneAggregates.length, 1);
});

test('getAdminInsightsSummary never includes user identifiers, coordinates, or raw event timestamps', async () => {
  const prisma = buildFakePrisma({
    aggregates: [
      {
        id: 'aggregate-1',
        partnerCompanyId: PARTNER_ID,
        interactionType: 'profile_view',
        periodType: 'day',
        periodStart: new Date('2026-06-28T00:00:00.000Z'),
        periodEnd: new Date('2026-06-29T00:00:00.000Z'),
        totalCount: 2,
        uniqueContributorCount: 2,
        resultStatus: 'available',
        createdAt: NOW,
        updatedAt: NOW,
      },
    ],
  });
  const service = new PartnerInsightsService(prisma);

  const summary = await service.getAdminInsightsSummary({
    partnerId: PARTNER_ID,
    period: 'last_7_days',
  });

  const serialized = JSON.stringify(summary);
  assert.equal(serialized.includes(USER_ID), false);
  assert.equal(serialized.includes('latitude'), false);
  assert.equal(serialized.includes('longitude'), false);
  assert.equal(serialized.includes('occurredAt'), false);
});

test('cleanupExpiredEvents deletes expired rows in bounded batches', async () => {
  const prisma = buildFakePrisma({
    events: [
      {
        id: 'event-1',
        partnerCompanyId: PARTNER_ID,
        interactionType: 'profile_view',
        userReferenceHash: 'hash-1',
        occurredAt: NOW,
        aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
        expiresAt: new Date('2026-06-01T00:00:00.000Z'),
      },
      {
        id: 'event-2',
        partnerCompanyId: PARTNER_ID,
        interactionType: 'profile_view',
        userReferenceHash: 'hash-2',
        occurredAt: NOW,
        aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
        expiresAt: new Date('2026-06-02T00:00:00.000Z'),
      },
    ],
    contributions: [
      {
        id: 'contribution-1',
        partnerCompanyId: PARTNER_ID,
        scopedContributorHash: 'hash-1',
        aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
        expiresAt: new Date('2026-06-01T00:00:00.000Z'),
        createdAt: NOW,
      },
      {
        id: 'contribution-2',
        partnerCompanyId: PARTNER_ID,
        scopedContributorHash: 'hash-2',
        aggregationDate: new Date('2026-06-28T00:00:00.000Z'),
        expiresAt: new Date('2026-06-02T00:00:00.000Z'),
        createdAt: NOW,
      },
    ],
  });
  const service = new PartnerInsightsService(prisma);

  const result = await service.cleanupExpiredEvents(1);

  assert.deepEqual(result, { deletedEventCount: 1, deletedContributionCount: 1 });
  assert.equal(prisma._events.length, 1);
  assert.equal(prisma._contributions.length, 1);
});

test('cleanupExpiredEvents does not touch retained aggregates', async () => {
  const prisma = buildFakePrisma({
    aggregates: [
      {
        id: 'aggregate-1',
        partnerCompanyId: PARTNER_ID,
        interactionType: 'profile_view',
        periodType: 'day',
        periodStart: new Date('2026-06-28T00:00:00.000Z'),
        periodEnd: new Date('2026-06-29T00:00:00.000Z'),
        totalCount: 1,
        uniqueContributorCount: 1,
        resultStatus: 'available',
        createdAt: NOW,
        updatedAt: NOW,
      },
    ],
  });
  const service = new PartnerInsightsService(prisma);

  await service.cleanupExpiredEvents();

  assert.equal(prisma._aggregates.length, 1);
});
