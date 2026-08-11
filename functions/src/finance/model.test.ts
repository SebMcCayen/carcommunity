/**
 * Finance cost model — unit tests for the arithmetic.
 *
 * This is finance code driving real money decisions, so the maths is tested
 * directly: free-tier subtraction, the Trafikverket committed write cost, FX
 * conversion, the uncosted-function flag, member-count resolution and the
 * fixed-subscription normalisation. Pure — no emulator, no I/O.
 */

import { describe, expect, it } from 'vitest';
import {
  DAYS_PER_MONTH,
  FIRESTORE,
  MAPBOX,
  SCHEDULER,
  USD_TO_SEK,
} from './pricing';
import {
  FALLBACK_MEMBER_COUNT,
  NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH,
  NAV_USING_FRACTION,
  TRAFIKVERKET_SITUATIONS_PER_RUN,
} from './assumptions';
import { SCHEDULED_JOBS, unknownCallables, uncostedCallables } from './inventory';
import { estimateFinance, resolveMemberCount, type ServiceLine } from './model';

function line(services: ServiceLine[], service: string, driverIncludes: string): ServiceLine {
  const found = services.find((l) => l.service === service && l.driver.includes(driverIncludes));
  if (!found) throw new Error(`no line ${service} / ${driverIncludes}`);
  return found;
}

const baseInput = {
  memberCountSource: 'metrics-snapshot' as const,
  memberCountAsOf: '2026-07-30',
  now: new Date('2026-07-31T00:00:00Z'),
};

describe('estimateFinance — Firestore writes / free tier / Trafikverket', () => {
  it('subtracts the DAILY free tier and bills the remainder (Trafikverket dominates)', () => {
    // Isolate committed writes: member count 0.
    const est = estimateFinance({ ...baseInput, memberCount: 0 });
    const writes = line(est.googleCloud.services, 'Cloud Firestore', 'Document writes');

    // Recompute the committed gross from the SAME declared inputs, so the test
    // pins the wiring (free-tier subtraction + monthly scaling) without a
    // brittle magic literal.
    const committedWritesPerDay = SCHEDULED_JOBS.reduce((sum, j) => {
      const perRun =
        j.id === 'incidents-syncTrafikverket' ? TRAFIKVERKET_SITUATIONS_PER_RUN : j.writesPerRun;
      return sum + perRun * j.runsPerDay;
    }, 0);
    const expectedBillablePerMonth =
      Math.max(0, committedWritesPerDay - FIRESTORE.free.writesPerDay) * DAYS_PER_MONTH;
    const expectedSek = expectedBillablePerMonth * FIRESTORE.writeUsd * USD_TO_SEK;

    expect(writes.gross).toBeCloseTo(committedWritesPerDay * DAYS_PER_MONTH, 3);
    expect(writes.freeTier).toBeCloseTo(FIRESTORE.free.writesPerDay * DAYS_PER_MONTH, 3);
    expect(writes.billable).toBeCloseTo(expectedBillablePerMonth, 3);
    expect(writes.sekPerMonth).toBeCloseTo(expectedSek, 6);
    expect(writes.free).toBe(false);

    // Trafikverket is the overwhelming majority of the write cost.
    const trafikPerDay = TRAFIKVERKET_SITUATIONS_PER_RUN * 48;
    expect(trafikPerDay / committedWritesPerDay).toBeGreaterThan(0.98);
    expect(est.googleCloud.trafikverketWritesSekPerMonth).toBeGreaterThan(0.98 * writes.sekPerMonth);
    expect(est.googleCloud.trafikverketSituationsPerRun).toBe(TRAFIKVERKET_SITUATIONS_PER_RUN);
  });

  it('keeps reads under the free tier at small scale (0 SEK) but bills them at large scale', () => {
    const small = estimateFinance({ ...baseInput, memberCount: 0 });
    const reads = line(small.googleCloud.services, 'Cloud Firestore', 'Document reads');
    expect(reads.free).toBe(true);
    expect(reads.sekPerMonth).toBe(0);

    const large = estimateFinance({ ...baseInput, memberCount: 5000 });
    const readsLarge = line(large.googleCloud.services, 'Cloud Firestore', 'Document reads');
    expect(readsLarge.free).toBe(false);
    expect(readsLarge.sekPerMonth).toBeGreaterThan(0);
  });
});

describe('estimateFinance — FX conversion', () => {
  it('exposes the single dated FX rate and converts through it', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 0 });
    expect(est.fx.usdToSek).toBe(USD_TO_SEK);
    expect(est.fx.capturedOn).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    // Every SEK figure is a USD figure × the rate: writes line proves it.
    const writes = line(est.googleCloud.services, 'Cloud Firestore', 'Document writes');
    const usd = writes.billable * FIRESTORE.writeUsd;
    expect(writes.sekPerMonth).toBeCloseTo(usd * USD_TO_SEK, 6);
  });
});

describe('estimateFinance — Cloud Scheduler committed line', () => {
  it('charges only jobs beyond the 3 free', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 0 });
    const sched = line(est.googleCloud.services, 'Cloud Scheduler', 'Scheduled jobs');
    const expectedBillable = Math.max(0, SCHEDULED_JOBS.length - SCHEDULER.freeJobs);
    expect(sched.gross).toBe(SCHEDULED_JOBS.length);
    expect(sched.freeTier).toBe(SCHEDULER.freeJobs);
    expect(sched.billable).toBe(expectedBillable);
    expect(sched.sekPerMonth).toBeCloseTo(
      expectedBillable * SCHEDULER.usdPerJobPerMonth * USD_TO_SEK,
      6,
    );
  });
});

describe('estimateFinance — Mapbox is the corrected MOBILE (MAU + trip) model', () => {
  /** Marginal tiered USD — mirrors the model helper, to pin the pricing wiring. */
  function tiered(units: number, tiers: readonly { upTo: number; usdPerUnit: number }[]): number {
    let cost = 0;
    let lower = 0;
    for (const t of tiers) {
      if (units <= lower) break;
      const upper = Math.min(units, t.upTo);
      cost += (upper - lower) * t.usdPerUnit;
      lower = upper;
    }
    return cost;
  }
  const mapLine = (est: ReturnType<typeof estimateFinance>, id: string) => {
    const found = est.mapbox.lines.find((l) => l.id === id);
    if (!found) throw new Error(`no mapbox line ${id}`);
    return found;
  };

  it('keeps the Maps SDK basemap FREE below 25,000 MAU and out of the Google Cloud total', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 2_500 });
    const maps = mapLine(est, 'maps-sdk-mau');
    // 2,500 members = 2,500 Maps MAU, well under the 25k free tier.
    expect(2_500).toBeLessThan(MAPBOX.mapsSdkMobile.freeMau);
    expect(maps.free).toBe(true);
    expect(maps.sekPerMonth).toBe(0);

    // Mapbox is NOT part of the Google Cloud subtotal (still a separate vendor).
    const gcHasMapbox = est.googleCloud.services.some((l) => /mapbox/i.test(l.service));
    expect(gcHasMapbox).toBe(false);
  });

  it('bills the Maps SDK basemap with correct MARGINAL tiers above 25,000 MAU', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 130_000 });
    const maps = mapLine(est, 'maps-sdk-mau');
    // 130,000 MAU: (125k−25k)×$0.004 + (130k−125k)×$0.0032 — never a flat rate.
    const expectedUsd = tiered(130_000, MAPBOX.mapsSdkMobile.tiers);
    expect(expectedUsd).toBeCloseTo(100_000 * 0.004 + 5_000 * 0.0032, 6); // = $416
    expect(maps.free).toBe(false);
    expect(maps.sekPerMonth).toBeCloseTo(expectedUsd * USD_TO_SEK, 6);
  });

  it('models Navigation SDK as the real driver: MAU + trips from the labelled assumptions', () => {
    const members = 500;
    const est = estimateFinance({ ...baseInput, memberCount: members });
    const navMauLine = mapLine(est, 'nav-sdk-mau');
    const navTripsLine = mapLine(est, 'nav-sdk-trips');

    const navMau = Math.round(members * NAV_USING_FRACTION); // 250
    const navTrips = navMau * NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH; // 2,000
    const expectedMauUsd = tiered(navMau, MAPBOX.navigationSdk.mauTiers); // (250−100)×0.30
    const expectedTripsUsd = tiered(navTrips, MAPBOX.navigationSdk.tripTiers); // (2000−1000)×0.08

    expect(expectedMauUsd).toBeCloseTo(150 * 0.3, 6);
    expect(expectedTripsUsd).toBeCloseTo(1_000 * 0.08, 6);
    expect(navMauLine.sekPerMonth).toBeCloseTo(expectedMauUsd * USD_TO_SEK, 6);
    expect(navTripsLine.sekPerMonth).toBeCloseTo(expectedTripsUsd * USD_TO_SEK, 6);

    // Navigation is the dominant Mapbox cost at this scale (basemap is free).
    const navSek = navMauLine.sekPerMonth + navTripsLine.sekPerMonth;
    expect(navSek).toBeGreaterThan(0);
    expect(navSek).toBeGreaterThan(mapLine(est, 'maps-sdk-mau').sekPerMonth);

    // The two assumptions are surfaced for transparency.
    expect(est.mapbox.assumptions.navUsingFraction).toBe(NAV_USING_FRACTION);
    expect(est.mapbox.assumptions.navTripsPerNavigatingMemberPerMonth).toBe(
      NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH,
    );
  });

  it('keeps the admin web map picker negligible (free) and the subtotal = sum of lines', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 2_500 });
    const admin = mapLine(est, 'admin-web-loads');
    expect(admin.free).toBe(true);
    expect(admin.sekPerMonth).toBe(0);

    const sumOfLines = est.mapbox.lines.reduce((s, l) => s + l.sekPerMonth, 0);
    expect(est.mapbox.sekPerMonth).toBeCloseTo(sumOfLines, 6);
    // Four lines: basemap, nav MAU, nav trips, admin web.
    expect(est.mapbox.lines.map((l) => l.id)).toEqual([
      'maps-sdk-mau',
      'nav-sdk-mau',
      'nav-sdk-trips',
      'admin-web-loads',
    ]);
  });

  it('is dramatically cheaper than the old per-load web model would have been', () => {
    // Sanity floor: at 2,500 members the corrected Mapbox subtotal is a few
    // thousand kr, NOT the six-figure the web per-load model produced.
    const est = estimateFinance({ ...baseInput, memberCount: 2_500 });
    expect(est.mapbox.sekPerMonth).toBeGreaterThan(0);
    expect(est.mapbox.sekPerMonth).toBeLessThan(50_000);
  });
});

describe('estimateFinance — fixed subscriptions (separate section)', () => {
  it('leaves an unset subscription as null (never a fabricated number)', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 16 });
    const claude = est.fixedSubscriptions.items.find((s) => s.id === 'claude');
    expect(claude).toBeDefined();
    expect(claude!.amount).toBeNull();
    expect(claude!.sekPerMonth).toBeNull();
    expect(est.fixedSubscriptions.hasUnset).toBe(true);
    // An unset sub contributes 0 to the total (not a guess).
    expect(est.fixedSubscriptions.totalSekPerMonth).toBe(0);
  });
});

describe('estimateFinance — grand total composition', () => {
  it('grand total = Google Cloud + Mapbox + fixed subscriptions', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 250 });
    expect(est.grandTotalSekPerMonth).toBeCloseTo(
      est.googleCloud.totalSekPerMonth +
        est.mapbox.sekPerMonth +
        est.fixedSubscriptions.totalSekPerMonth,
      6,
    );
    // Committed + variable partitions the Google Cloud total.
    expect(est.googleCloud.committedSekPerMonth + est.googleCloud.variableSekPerMonth).toBeCloseTo(
      est.googleCloud.totalSekPerMonth,
      6,
    );
  });
});

describe('estimateFinance — variable half scales with member count', () => {
  it('a larger community costs more (RTDB/Storage/reads grow)', () => {
    const small = estimateFinance({ ...baseInput, memberCount: 50 });
    const big = estimateFinance({ ...baseInput, memberCount: 5000 });
    expect(big.googleCloud.variableSekPerMonth).toBeGreaterThan(
      small.googleCloud.variableSekPerMonth,
    );
  });
});

describe('resolveMemberCount', () => {
  it('reads the live count from the latest snapshot', () => {
    expect(resolveMemberCount(137, '2026-07-30')).toEqual({
      count: 137,
      source: 'metrics-snapshot',
      asOf: '2026-07-30',
    });
  });

  it('falls back to a labelled default when there is no snapshot', () => {
    expect(resolveMemberCount(null, null)).toEqual({
      count: FALLBACK_MEMBER_COUNT,
      source: 'fallback',
      asOf: null,
    });
  });
});

describe('uncosted / unknown function surfacing', () => {
  it('every classified callable is costed (none deliberately left uncosted today)', () => {
    expect(uncostedCallables()).toEqual([]);
  });

  it('a function absent from the cost map surfaces as unknown (not silently zeroed)', () => {
    expect(unknownCallables(['finance.brandNewThing'])).toEqual(['finance.brandNewThing']);
    expect(unknownCallables(['dm.sendMessage'])).toEqual([]);
  });

  it('the estimate reports the uncosted list for the board to flag', () => {
    const est = estimateFinance({ ...baseInput, memberCount: 16 });
    expect(est.functionInventory.uncosted).toEqual([]);
    expect(est.functionInventory.totalCallables).toBeGreaterThan(100);
    expect(est.functionInventory.scheduledJobs).toBe(SCHEDULED_JOBS.length);
  });
});
