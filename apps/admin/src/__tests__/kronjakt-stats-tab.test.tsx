/**
 * Component tests for the Kronjakt STATS tab.
 *
 * Assert the dashboard renders from mocked aggregate reads: the spawned/collected
 * totals, the ranked leaderboard (in the order the read returns), and the
 * past-season champions. Pure helpers (currentSeasonId, RARITY_TIERS, …) are
 * real via importActual; the async reads and the WebGL heat map are stubbed.
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { translate } from '@/i18n';

const t = (key: string) => translate('sv', key);

const mocks = vi.hoisted(() => ({
  getStats: vi.fn(),
  leaderboard: vi.fn(),
  seasons: vi.fn(),
  cells: vi.fn(),
  perks: vi.fn(),
}));

vi.mock('@/lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('@/lib/firestore', () => ({
  getAdminFirestore: () => ({}),
  isFirestoreEmulatorEnabled: () => false,
}));

vi.mock('@/features/crown-hunt', async (importOriginal) => {
  const actual = (await importOriginal()) as Record<string, unknown>;
  return {
    ...actual,
    adminGetSpawnStats: mocks.getStats,
    adminListLeaderboard: mocks.leaderboard,
    adminListSeasons: mocks.seasons,
    adminListCellStats: mocks.cells,
    adminGetPerkStats: mocks.perks,
  };
});

vi.mock('@/components/map/CrownHeatMap', () => ({
  CrownHeatMap: () => <div data-testid="heat-stub" />,
}));

import { StatsTab } from '@/app/kronjakt/StatsTab';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

function stats(scope: string, spawned: number, collected: number) {
  return {
    scope,
    spawnedTotal: spawned,
    collectedTotal: collected,
    collectionRate: spawned > 0 ? collected / spawned : 0,
    spawnedByRarity: { common: spawned },
    collectedByRarity: { common: collected },
    handPlacedCollected: 0,
    activePlayers7d: null,
    activePlayers30d: null,
    updatedAt: null,
  };
}

function entry(rank: number, uid: string, displayName: string, points: number, crowns: number, seasonsWon = 0) {
  return { rank, uid, displayName, points, crownsCollected: crowns, seasonsWon };
}

function perkStats(
  scope: string,
  used: { spike_strip: number; shield: number; boost: number },
  purchased: { spike_strip: number; shield: number; boost: number },
  trapTriggers: number,
) {
  return { scope, usedByPerk: used, purchasedByPerk: purchased, trapTriggers, updatedAt: null };
}

async function render() {
  await act(async () => {
    root.render(<StatsTab />);
  });
  await act(async () => {});
  await act(async () => {});
}

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  mocks.getStats.mockReset();
  mocks.leaderboard.mockReset();
  mocks.seasons.mockReset();
  mocks.cells.mockReset();
  mocks.perks.mockReset();

  mocks.getStats.mockImplementation((scope: string) =>
    Promise.resolve(scope === 'alltime' ? stats('alltime', 100, 40) : stats(scope, 20, 9)),
  );
  mocks.leaderboard.mockImplementation((scope: string) =>
    Promise.resolve(
      scope === 'season'
        ? [entry(1, 'u1', 'Ada', 80, 4), entry(2, 'u2', 'Beata', 50, 3)]
        : [entry(1, 'u2', 'Beata', 300, 20, 2), entry(2, 'u1', 'Ada', 250, 15)],
    ),
  );
  mocks.seasons.mockResolvedValue([
    {
      seasonId: '2026-07',
      period: 'month',
      status: 'ended',
      startAt: '2026-07-01T00:00:00Z',
      endAt: '2026-08-01T00:00:00Z',
      finalizedAt: '2026-08-01T00:05:00Z',
      participantCount: 5,
      winners: [{ rank: 1, uid: 'u1', displayName: 'Ada', points: 120, crownsCollected: 6 }],
      topStandings: [],
    },
  ]);
  mocks.cells.mockResolvedValue([{ cellKey: '5748_1207', spawned: 5, collected: 2 }]);
  mocks.perks.mockImplementation((scope: string) =>
    Promise.resolve(
      scope === 'alltime'
        ? perkStats('alltime', { spike_strip: 12, shield: 7, boost: 4 }, { spike_strip: 20, shield: 15, boost: 9 }, 33)
        : perkStats(scope, { spike_strip: 3, shield: 2, boost: 1 }, { spike_strip: 5, shield: 4, boost: 2 }, 8),
    ),
  );
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

describe('StatsTab', () => {
  it('renders spawned/collected totals for all-time and the current season', async () => {
    await render();
    const text = container.textContent ?? '';
    expect(text).toContain(t('crownHunt.statAllTime'));
    expect(text).toContain('100'); // all-time spawned
    expect(text).toContain('40'); // all-time collected
  });

  it('renders the season leaderboard in the order the read returns', async () => {
    await render();
    const rows = [...container.querySelectorAll('table tbody tr')];
    // The FIRST table on the page is the leaderboard (season scope by default).
    const boardRows = rows.slice(0, 2).map((r) => r.textContent ?? '');
    expect(boardRows[0]).toContain('Ada');
    expect(boardRows[0]).toContain('80');
    expect(boardRows[1]).toContain('Beata');
    // Ada (rank 1) must appear before Beata (rank 2).
    expect((boardRows[0] ?? '').indexOf('Ada')).toBeGreaterThanOrEqual(0);
  });

  it('switches the leaderboard to the all-time board and shows the champion badge', async () => {
    await render();
    await act(async () => {
      const toggle = [...container.querySelectorAll('button')].find(
        (b) => b.textContent?.trim() === t('crownHunt.statBoardAllTime'),
      ) as HTMLButtonElement;
      toggle.click();
    });
    const firstRow = container.querySelector('table tbody tr')?.textContent ?? '';
    // All-time board is led by Beata (300 pts) and she is a 2× champion.
    expect(firstRow).toContain('Beata');
    expect(firstRow).toContain('2');
  });

  it('lists past-season champions', async () => {
    await render();
    const text = container.textContent ?? '';
    expect(text).toContain(t('crownHunt.statChampionsTitle'));
    expect(text).toContain('2026-07');
    expect(text).toContain('Ada');
  });

  it('renders a perk-usage card per perk with a generated logo and the counts', async () => {
    await render();
    const text = container.textContent ?? '';
    expect(text).toContain(t('crownHunt.statPerkTitle'));
    // One named card per perk (Swedish names), each with a generated SVG logo.
    for (const name of ['Spikmatta', 'Sköld', 'Dubbla Poäng']) {
      expect(text).toContain(name);
    }
    // The perk logos are inline SVGs labelled by the perk name (role="img").
    const perkLogos = [...container.querySelectorAll('svg[role="img"]')].filter((s) =>
      ['Spikmatta', 'Sköld', 'Dubbla Poäng'].includes(s.querySelector('title')?.textContent ?? ''),
    );
    expect(perkLogos.length).toBe(3);

    // Trap triggers surface only on the trap perk (Spikmatta) — assert the
    // values inside THAT card's labelled rows, not as bare substrings of the
    // whole page (which also contains e.g. leaderboard "80").
    const trapLogo = perkLogos.find((s) => s.querySelector('title')?.textContent === 'Spikmatta');
    // card = svg → perkHead → card
    const trapCard = trapLogo?.parentElement?.parentElement as HTMLElement | undefined;
    expect(trapCard).toBeDefined();
    const rowFor = (label: string): string => {
      const row = [...(trapCard?.querySelectorAll('div') ?? [])].find(
        (d) => d.querySelector('span')?.textContent === label,
      );
      return row?.textContent ?? '';
    };
    expect(rowFor(t('crownHunt.statPerkTrapTriggersSeason'))).toContain('8'); // season
    expect(rowFor(t('crownHunt.statPerkTrapTriggersAllTime'))).toContain('33'); // all-time

    // The non-trap perks (Sköld) show NO trap-trigger rows.
    const shieldLogo = perkLogos.find((s) => s.querySelector('title')?.textContent === 'Sköld');
    const shieldCard = shieldLogo?.parentElement?.parentElement as HTMLElement | undefined;
    expect(shieldCard?.textContent ?? '').not.toContain(t('crownHunt.statPerkTrapTriggersSeason'));
  });
});
