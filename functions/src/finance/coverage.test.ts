import { describe, expect, it } from 'vitest';
import {
  DAY_MS,
  readEvidence,
  usageSchema,
  type BillingEvidence,
  type UsageEvidence,
} from './evidence';
import { estimateFinance } from './model';
import { DAYS_PER_MONTH, FUNCTIONS, USD_TO_SEK } from './pricing';
import { reportedBilling } from './reporting';
import { WORKLOADS } from './workloads';
import { CALLABLE_COST_CLASS } from './inventory';
import { workloadOverridesSchema } from './settings-core';

const now = new Date('2026-11-15T12:00:00Z');
const input = {
  now,
  memberCount: 65,
  memberCountSource: 'metrics-snapshot' as const,
  memberCountAsOf: '2026-11-15',
};
const usage: UsageEvidence = {
  capturedAtMs: now.getTime(),
  startMs: Date.parse('2026-10-16T00:00:00Z'),
  endMs: Date.parse('2026-11-15T00:00:00Z'),
  functions: [
    { id: 'roadCameras.photo', requests: 1000, sentBytes: 1024 ** 3, billableSeconds: 50 },
  ],
};
const billing: BillingEvidence = {
  capturedAtMs: now.getTime(),
  latestExportAt: now.toISOString(),
  month: '2026-11',
  throughDate: '2026-11-14',
  currency: 'SEK',
  services: [
    { id: 'run', label: 'Cloud Run', netCost: 30 },
    { id: 'build', label: 'Cloud Build', netCost: -2 },
  ],
};

describe('complete workload accounting', () => {
  it('requires a workload for every explicitly costed callable', () => {
    const defined = new Set(WORKLOADS.map((w) => w.id));
    for (const [name, cls] of Object.entries(CALLABLE_COST_CLASS))
      if (cls === 'explicit-workload') expect(defined.has(name), name).toBe(true);
  });
  it('allocates the model subtotal exactly once, after shared free quotas', () => {
    const est = estimateFinance(input);
    expect(
      est.workloads.reduce((sum, w) => sum + w.sekPerMonth, 0) + est.otherModelledSekPerMonth,
    ).toBeCloseTo(est.googleCloud.totalSekPerMonth, 8);
    expect(est.grandTotalSekPerMonth).toBeCloseTo(
      est.googleCloud.totalSekPerMonth +
        est.mapbox.sekPerMonth +
        est.recurringCosts.totalSekPerMonth,
      8,
    );
    expect(est.workloads.every((w) => w.source === 'assumption')).toBe(true);
  });
  it('uses observed requests/bytes/billable time, while database operations remain estimated', () => {
    const est = estimateFinance({
      ...input,
      usageEvidence: { state: 'ready', data: usage, lastAttemptFailed: false },
    });
    const photo = est.workloads.find((w) => w.id === 'roadCameras.photo')!;
    expect(photo.source).toBe('measured-usage');
    expect(photo.eventsPerMonth).toBeCloseTo((1000 * DAYS_PER_MONTH) / 30);
    expect(photo.resources.reads).toBe(photo.eventsPerMonth * photo.readsPerEvent);
    expect(photo.resources.cpuSeconds).toBeCloseTo((50 * DAYS_PER_MONTH) / 30);
    expect(photo.resources.sentBytes).toBeCloseTo((1024 ** 3 * DAYS_PER_MONTH) / 30);
    expect(est.workloads.find((w) => w.id === 'roadCameras.catalog')!.source).toBe('assumption');
  });
  it('does not turn missing metric series into observed zero usage, but accepts a measured zero', () => {
    for (const requests of [null, 0]) {
      const data = { ...usage, functions: [{ ...usage.functions[0]!, requests }] };
      const est = estimateFinance({
        ...input,
        usageEvidence: { state: 'ready', data, lastAttemptFailed: false },
      });
      const photo = est.workloads[0]!;
      expect(photo.source).toBe(requests === null ? 'assumption' : 'measured-usage');
      if (requests === 0) expect(photo.eventsPerMonth).toBe(0);
    }
  });
  it('expands the legacy envelope for high traffic, without adding the baseline twice', () => {
    const data = {
      ...usage,
      functions: [
        { ...usage.functions[0]!, requests: 1e7, sentBytes: 100 * 1024 ** 3, billableSeconds: 2e6 },
      ],
    };
    const est = estimateFinance({
      ...input,
      memberCount: 0,
      usageEvidence: { state: 'ready', data, lastAttemptFailed: false },
    });
    const egress = est.googleCloud.services.find((s) => s.driver.startsWith('Internet data'))!;
    expect(egress.gross).toBeCloseTo((100 * DAYS_PER_MONTH) / 30);
    expect(egress.sekPerMonth).toBeCloseTo(
      ((100 * DAYS_PER_MONTH) / 30) * FUNCTIONS.egressGiBUsd * USD_TO_SEK,
    );
    expect(est.workloads.reduce((sum, w) => sum + w.sekPerMonth, 0)).toBeLessThanOrEqual(
      est.googleCloud.totalSekPerMonth,
    );
  });
  it('uses assumptions for stale evidence and hypothetical growth rather than reusing absolute observed traffic', () => {
    const est = estimateFinance({
      ...input,
      usageEvidence: { state: 'stale', data: usage, lastAttemptFailed: true },
    });
    expect(est.workloads.every((w) => w.source === 'assumption')).toBe(true);
    const measured = estimateFinance({
      ...input,
      usageEvidence: { state: 'ready', data: usage, lastAttemptFailed: false },
    });
    expect(measured.projection).toEqual(est.projection);
  });
  it('adjusts fallback frequencies, preserves measured precedence and rejects unsafe settings', () => {
    const overrides = [{ id: 'roadCameras.photo', eventsPerMemberPerDay: 0 }];
    expect(
      estimateFinance({ ...input, workloadOverrides: overrides }).workloads[0]!.eventsPerMonth,
    ).toBe(0);
    expect(
      estimateFinance({
        ...input,
        workloadOverrides: overrides,
        usageEvidence: { state: 'ready', data: usage, lastAttemptFailed: false },
      }).workloads[0]!.eventsPerMonth,
    ).toBeGreaterThan(0);
    for (const bad of [
      [...overrides, ...overrides],
      [{ id: 'unknown', eventsPerMemberPerDay: 2 }],
      [{ id: 'roadCameras.photo', eventsPerMemberPerDay: Infinity }],
      [{ id: 'roadCameras.photo', eventsPerMemberPerDay: -1 }],
    ])
      expect(workloadOverridesSchema.safeParse(bad).success).toBe(false);
  });
});

describe('billing reconciliation', () => {
  it('includes credits and all reported services, but never adds reported spend to the model', () => {
    const evidence = { state: 'ready' as const, data: billing, lastAttemptFailed: false };
    const est = estimateFinance({ ...input, billingEvidence: evidence });
    expect(est.reportedBilling.totalSek).toBe(28);
    expect(est.reportedBilling.forecastSek).toBe(60);
    expect(est.reportedBilling.unallocatedToFunctionsSek).toBe(28);
    expect(est.grandTotalSekPerMonth).toBe(estimateFinance(input).grandTotalSekPerMonth);
    expect(est.reportedBilling.differenceSek).toBeCloseTo(
      28 - (est.googleCloud.totalSekPerMonth / DAYS_PER_MONTH) * 14,
    );
  });
  it('keeps unavailable values null and suppresses stale/previous-month forecasts', () => {
    expect(reportedBilling(undefined, now, 60).totalSek).toBeNull();
    for (const data of [
      { ...billing, month: '2026-10' },
      { ...billing, latestExportAt: '2026-11-01T00:00:00Z' },
    ]) {
      const result = reportedBilling({ state: 'ready', data, lastAttemptFailed: false }, now, 60);
      expect(result.state).toBe('stale');
      expect(result.forecastSek).toBeNull();
      expect(result.differenceSek).toBeNull();
    }
  });
  it('rejects malformed and future snapshots; retains old good data with a stale label', () => {
    expect(readEvidence(usageSchema, {}, now.getTime()).data).toBeNull();
    expect(
      readEvidence(
        usageSchema,
        { state: 'ready', data: { ...usage, capturedAtMs: now.getTime() + DAY_MS } },
        now.getTime(),
      ).data,
    ).toBeNull();
    const result = readEvidence(
      usageSchema,
      {
        state: 'ready',
        data: {
          ...usage,
          capturedAtMs: now.getTime() - 4 * DAY_MS,
          startMs: usage.startMs - 4 * DAY_MS,
          endMs: usage.endMs - 4 * DAY_MS,
        },
        lastAttemptFailed: true,
      },
      now.getTime(),
    );
    expect(result.state).toBe('stale');
    expect(result.data).not.toBeNull();
    expect(result.lastAttemptFailed).toBe(true);
  });
});
