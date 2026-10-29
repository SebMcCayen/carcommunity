import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, it, expect, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ load: vi.fn(), diagnostics: vi.fn(), uid: 'actor' }));
vi.mock('@/components/auth/FirebaseAuthProvider', () => ({
  useAdminAuth: () => ({ user: { uid: mocks.uid } }),
}));
vi.mock('@/features/crown-hunt/overview', () => ({
  loadCrownOverview: mocks.load,
  visibleCrowns: (rows: unknown[]) => rows,
}));
vi.mock('@/features/crown-hunt', () => ({
  adminSpawnDiagnostics: mocks.diagnostics,
  estimateAreaService: () => ({ serviceAtMs: Date.now() + 600000 }),
  areaPoiCount: () => 12,
}));
vi.mock('@/components/map/CrownOverviewMap', () => ({
  CrownOverviewMap: () => <div>Geografisk karta</div>,
}));
vi.mock('@/app/kronjakt/SpawnDiagnosticsPanel', () => ({ SpawnDiagnosticsPanel: () => null }));
import { CrownOverview } from '@/app/kronjakt/CrownOverview';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let root: Root, host: HTMLDivElement;
const snapshot = {
  areas: [
    {
      areaId: 'a',
      name: 'Första området',
      shape: { type: 'circle' },
      active: true,
      safeAreaConfirmed: true,
      poisRefreshedAt: null,
    },
    {
      areaId: 'b',
      name: 'Andra området',
      shape: { type: 'circle' },
      active: true,
      safeAreaConfirmed: true,
      poisRefreshedAt: null,
    },
  ],
  points: [],
  pointTotal: 0,
  crowns: [],
  crownsTruncated: false,
  areasPossiblyTruncated: false,
  spawnEnabled: false,
  readAt: '2026-10-28T10:00:00Z',
};
const diagnostics = (id: string, lastSpawnPassAt: string | null) => ({
  areaId: id,
  flagEnabled: false,
  active: true,
  safeAreaConfirmed: true,
  lastSpawnPassAt,
  blockers: [],
  serverTime: '2026-10-28T10:00:00Z',
});
beforeEach(() => {
  mocks.uid = 'actor';
  mocks.load.mockReset().mockResolvedValue(snapshot);
  mocks.diagnostics.mockReset();
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});
const render = () =>
  act(async () =>
    root.render(<CrownOverview onManageArea={() => undefined} onPoint={() => undefined} />),
  );
it('ignores late diagnostics from a previously selected area', async () => {
  let first: ((value: unknown) => void) | undefined;
  mocks.diagnostics
    .mockReturnValueOnce(
      new Promise((resolve) => {
        first = resolve;
      }),
    )
    .mockResolvedValueOnce(diagnostics('b', '2026-09-22T08:17:00Z'));
  await render();
  act(() => {
    const select = host.querySelector('select')!;
    select.value = 'b';
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await act(async () => {});
  expect(host.textContent).toContain('2026-09-22');
  await act(async () => first?.(diagnostics('a', '2026-09-11T09:23:00Z')));
  expect(host.textContent).not.toContain('2026-09-11');
  expect(host.textContent).toContain('2026-09-22');
});
it('shows denied overview reads as errors and retries instead of presenting empty counts', async () => {
  mocks.load.mockRejectedValueOnce(new Error('permission-denied')).mockResolvedValueOnce(snapshot);
  mocks.diagnostics.mockResolvedValue(diagnostics('a', null));
  await render();
  expect(host.querySelector('[role="alert"]')).not.toBeNull();
  expect(host.textContent).not.toContain('0 automatiska kronor');
  await act(async () => host.querySelector('button')!.click());
  expect(host.textContent).toContain('Platsdata saknas');
  expect(host.textContent).toContain('Historik saknas');
});
it('does not accept a late snapshot from a previous actor session', async () => {
  let old: ((value: unknown) => void) | undefined;
  mocks.load
    .mockReturnValueOnce(
      new Promise((resolve) => {
        old = resolve;
      }),
    )
    .mockResolvedValueOnce({ ...snapshot, areas: [] });
  await render();
  mocks.uid = 'new-actor';
  await render();
  await act(async () => old?.(snapshot));
  expect(host.textContent).not.toContain('Första området');
  expect(host.textContent).toContain('Inga områden är registrerade');
});
