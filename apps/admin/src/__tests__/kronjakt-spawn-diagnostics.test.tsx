/**
 * Tests for the auto-spawn DIAGNOSTICS feature:
 *  - the pure countdown / queue-ETA / candidate helpers (the math the panel
 *    turns raw callable facts into), verified without a network;
 *  - the SpawnDiagnosticsPanel rendering candidate cells, blockers and the OSM
 *    attribution from a mocked crownHunt.spawnDiagnostics response.
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { translate } from '@/i18n';

const t = (key: string) => translate('sv', key);

const mocks = vi.hoisted(() => ({ diag: vi.fn() }));

// Cut the crown-hunt barrel's path to lib/firebase (env reads at import) so the
// REAL pure helpers load via importOriginal; only the network fn is stubbed.
vi.mock('@/lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('@/lib/firestore', () => ({
  getAdminFirestore: () => ({}),
  isFirestoreEmulatorEnabled: () => false,
}));

vi.mock('@/features/crown-hunt', async (importOriginal) => {
  const actual = (await importOriginal()) as Record<string, unknown>;
  return { ...actual, adminSpawnDiagnostics: mocks.diag };
});

import {
  countdownSeconds,
  estimateAreaService,
  candidateCells,
  type AdminCrownSpawnDiagnosticsResponse,
  type CrownSpawnDiagnosticCell,
} from '@/features/crown-hunt';
import { SpawnDiagnosticsPanel } from '@/app/kronjakt/SpawnDiagnosticsPanel';

describe('countdownSeconds', () => {
  it('returns whole seconds remaining, rounded up', () => {
    const now = Date.parse('2026-08-07T12:00:00.000Z');
    expect(countdownSeconds('2026-08-07T12:02:30.000Z', now)).toBe(150);
    expect(countdownSeconds('2026-08-07T12:00:00.400Z', now)).toBe(1);
  });

  it('never goes negative once the target has passed', () => {
    const now = Date.parse('2026-08-07T12:05:00.000Z');
    expect(countdownSeconds('2026-08-07T12:00:00.000Z', now)).toBe(0);
  });
});

describe('estimateAreaService', () => {
  const base = {
    maxAreasPerRun: 10,
    nextRunAtMs: Date.parse('2026-08-07T12:10:00.000Z'),
    runIntervalMs: 600_000,
  };

  it('serves on the next run when the queue fits within one run', () => {
    expect(estimateAreaService({ ...base, areasAhead: 3 })).toEqual({
      runsUntilServed: 0,
      serviceAtMs: base.nextRunAtMs,
    });
  });

  it('adds whole runs for a queue longer than the per-run budget', () => {
    // 25 ahead / 10 per run = 2 whole runs beyond the next.
    expect(estimateAreaService({ ...base, areasAhead: 25 })).toEqual({
      runsUntilServed: 2,
      serviceAtMs: base.nextRunAtMs + 2 * base.runIntervalMs,
    });
  });
});

describe('candidateCells', () => {
  it('keeps only eligible cells', () => {
    const cells = [
      { cellKey: 'a', eligible: true },
      { cellKey: 'b', eligible: false },
      { cellKey: 'c', eligible: true },
    ] as CrownSpawnDiagnosticCell[];
    expect(candidateCells(cells).map((c) => c.cellKey)).toEqual(['a', 'c']);
  });
});

// ---------------------------------------------------------------------------
// Panel component
// ---------------------------------------------------------------------------

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

function cell(overrides: Partial<CrownSpawnDiagnosticCell>): CrownSpawnDiagnosticCell {
  return {
    cellKey: '5900_1300',
    center: { lat: 59.005, lon: 13.005 },
    bounds: { minLat: 59.0, maxLat: 59.01, minLon: 13.0, maxLon: 13.01 },
    activityScore: 3.2,
    target: 3,
    liveCount: 0,
    deficit: 3,
    poiCount: 2,
    poiCountCapped: false,
    poiAnchors: [],
    reason: 'would_spawn',
    eligible: true,
    ...overrides,
  };
}

function makeResponse(
  overrides: Partial<AdminCrownSpawnDiagnosticsResponse> = {},
): AdminCrownSpawnDiagnosticsResponse {
  const now = new Date();
  return {
    areaId: 'a1',
    name: 'Test area',
    shape: { type: 'circle', center: { lat: 59.005, lon: 13.005 }, radiusMeters: 300 },
    flagEnabled: true,
    active: true,
    safeAreaConfirmed: true,
    areaPoiCount: 4,
    poisRefreshedAt: now.toISOString(),
    serverTime: now.toISOString(),
    nextRunAt: new Date(now.getTime() + 300_000).toISOString(),
    runIntervalSeconds: 600,
    activeAreaCount: 2,
    areasAhead: 0,
    maxAreasPerRun: 10,
    maxAreaCellsPerRun: 60,
    lastSpawnPassAt: null,
    totalCells: 4,
    cellsTruncated: false,
    nextCellOffset: 0,
    cellsScanned: 2,
    candidateCellCount: 1,
    cells: [
      cell({ cellKey: 'CAND_1', reason: 'would_spawn', eligible: true }),
      cell({ cellKey: 'FULL_1', reason: 'at_target', eligible: false, liveCount: 3, deficit: 0 }),
    ],
    blockers: [],
    ...overrides,
  };
}

async function renderPanel() {
  await act(async () => {
    root.render(<SpawnDiagnosticsPanel areaId="a1" areaName="Test area" onClose={() => {}} />);
  });
  await act(async () => {}); // flush the load effect
}

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  mocks.diag.mockReset();
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

describe('SpawnDiagnosticsPanel', () => {
  it('renders the next-run facts, candidate cells and OSM attribution', async () => {
    mocks.diag.mockResolvedValue(makeResponse());
    await renderPanel();

    const text = container.textContent ?? '';
    expect(mocks.diag).toHaveBeenCalledWith('a1');
    expect(text).toContain(t('crownHunt.diagNextRunLabel'));
    expect(text).toContain(t('crownHunt.diagWhereTitle'));
    // Healthy area → no blockers banner, and both scanned cells shown.
    expect(text).toContain(t('crownHunt.diagNoBlockers'));
    expect(text).toContain('CAND_1');
    expect(text).toContain('FULL_1');
    expect(text).toContain(t('crownHunt.diagCellReason.would_spawn'));
    // ODbL attribution must appear wherever POI-derived data is shown.
    expect(text).toContain(t('crownHunt.osmAttribution'));
  });

  it('filters to candidate cells when "candidates only" is ticked', async () => {
    mocks.diag.mockResolvedValue(makeResponse());
    await renderPanel();

    const checkbox = [
      ...container.querySelectorAll('input[type="checkbox"]'),
    ][0] as HTMLInputElement;
    await act(async () => {
      checkbox.click();
    });

    const text = container.textContent ?? '';
    expect(text).toContain('CAND_1');
    expect(text).not.toContain('FULL_1');
  });

  it('lists the area-level blockers when the engine is placing nothing', async () => {
    mocks.diag.mockResolvedValue(
      makeResponse({
        flagEnabled: false,
        active: false,
        safeAreaConfirmed: false,
        areaPoiCount: 0,
        blockers: ['spawn_flag_off', 'area_inactive', 'no_area_pois'],
      }),
    );
    await renderPanel();

    const text = container.textContent ?? '';
    expect(text).toContain(t('crownHunt.diagBlocker.spawn_flag_off'));
    expect(text).toContain(t('crownHunt.diagBlocker.area_inactive'));
    expect(text).toContain(t('crownHunt.diagBlocker.no_area_pois'));
    expect(text).not.toContain(t('crownHunt.diagNoBlockers'));
  });
});
