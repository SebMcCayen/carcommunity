/**
 * Component tests for the Kronjakt AREAS tab.
 *
 * Assert the area list renders from mocked reads and — the safety-critical part
 * — that ACTIVATING an area is gated on the safe-area confirmation: the confirm
 * button stays disabled until the checkbox is ticked, and the update callable is
 * only ever invoked with the literal `safeAreaConfirmed: true`.
 *
 * The pure helpers (validateAreaShape, buildActivateAreaRequest, …) are kept
 * real via importActual; only the network fns and the WebGL draw map are stubbed
 * (Mapbox is unavailable under jsdom).
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { translate } from '@/i18n';

const t = (key: string) => translate('sv', key);

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  del: vi.fn(),
}));

// Cut every path from the crown-hunt barrel to lib/firebase (which reads
// VITE_FIREBASE_* env at import), so importActual can load the REAL pure helpers.
vi.mock('@/lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('@/lib/firestore', () => ({
  getAdminFirestore: () => ({}),
  isFirestoreEmulatorEnabled: () => false,
}));

vi.mock('@/features/crown-hunt', async (importOriginal) => {
  const actual = (await importOriginal()) as Record<string, unknown>;
  return {
    ...actual,
    adminListSpawnAreas: mocks.list,
    adminCreateSpawnArea: mocks.create,
    adminUpdateSpawnArea: mocks.update,
    adminDeleteSpawnArea: mocks.del,
  };
});

// Stub the WebGL draw map: it exposes a button to simulate finishing a valid
// polygon, so the create flow is testable without Mapbox.
vi.mock('@/components/map/AreaDrawMap', () => ({
  AreaDrawMap: ({ onShapeDrawn }: { onShapeDrawn: (s: unknown) => void }) => {
    const draw = () =>
      onShapeDrawn({
        type: 'polygon',
        vertices: [
          { lat: 57.0, lon: 12.0 },
          { lat: 57.0, lon: 12.1 },
          { lat: 57.1, lon: 12.1 },
          { lat: 57.0, lon: 12.0 },
        ],
      });
    return (
      <div data-testid="draw-map-stub">
        <button type="button" onClick={draw}>
          stub-draw-polygon
        </button>
      </div>
    );
  },
}));

import { AreasTab } from '@/app/kronjakt/AreasTab';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

function makeArea(overrides: Record<string, unknown> = {}) {
  return {
    areaId: 'a1',
    name: 'Kungsbacka centrum',
    shape: { type: 'circle', center: { lat: 57.49, lon: 12.07 }, radiusMeters: 300 },
    active: false,
    safeAreaConfirmed: false,
    createdByUserId: 'admin1234',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    approvedByUserId: null,
    approvedAt: null,
    ...overrides,
  };
}

function buttonByText(label: string): HTMLButtonElement {
  const btn = [...container.querySelectorAll('button')].find((b) => b.textContent?.trim() === label);
  if (!btn) throw new Error(`No button "${label}"`);
  return btn as HTMLButtonElement;
}

function modal(): HTMLElement {
  const el = container.querySelector('[role="dialog"]');
  if (!el) throw new Error('No modal open');
  return el as HTMLElement;
}

async function render() {
  await act(async () => {
    root.render(<AreasTab onFlash={() => {}} />);
  });
  // flush the load effect
  await act(async () => {});
}

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  mocks.list.mockReset();
  mocks.create.mockReset();
  mocks.update.mockReset();
  mocks.del.mockReset();
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

describe('AreasTab — list', () => {
  it('renders a row per area with name, shape and status', async () => {
    mocks.list.mockResolvedValue([
      makeArea(),
      makeArea({
        areaId: 'a2',
        name: null,
        active: true,
        safeAreaConfirmed: true,
        shape: { type: 'rectangle', bounds: { north: 57.1, south: 57, east: 12.1, west: 12 } },
      }),
    ]);
    await render();
    const text = container.textContent ?? '';
    expect(text).toContain('Kungsbacka centrum');
    expect(text).toContain(t('crownHunt.areaUnnamed'));
    expect(text).toContain(t('crownHunt.areaStateActive'));
    expect(text).toContain(t('crownHunt.areaStateInactive'));
    // circle summary carries the radius
    expect(text).toContain('300');
  });

  it('shows the empty state when there are no areas', async () => {
    mocks.list.mockResolvedValue([]);
    await render();
    expect(container.textContent).toContain(t('crownHunt.areaNoAreas'));
  });
});

describe('AreasTab — activation safety gate', () => {
  it('will not activate an area until the safe-area confirmation is ticked', async () => {
    mocks.list.mockResolvedValue([makeArea()]);
    mocks.update.mockResolvedValue({ areaId: 'a1', active: true, safeAreaConfirmed: true, removedCrowns: 0 });
    await render();

    // Open the activate modal from the row.
    await act(async () => {
      buttonByText(t('crownHunt.areaActivate')).click();
    });

    // Confirm button (inside the modal) is disabled before confirmation.
    const confirmBtn = [...modal().querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === t('crownHunt.areaActivate'),
    ) as HTMLButtonElement;
    expect(confirmBtn.disabled).toBe(true);

    // Clicking it while disabled must not call the backend.
    await act(async () => confirmBtn.click());
    expect(mocks.update).not.toHaveBeenCalled();

    // Tick the confirmation → button enables.
    const checkbox = modal().querySelector('input[type="checkbox"]') as HTMLInputElement;
    await act(async () => checkbox.click());
    expect(confirmBtn.disabled).toBe(false);

    // Confirm → update called with the literal safeAreaConfirmed:true.
    await act(async () => confirmBtn.click());
    expect(mocks.update).toHaveBeenCalledWith({
      areaId: 'a1',
      active: true,
      safeAreaConfirmed: true,
    });
  });
});

describe('AreasTab — create gate', () => {
  it('creates a draft (inactive) area when "activate now" is not chosen', async () => {
    mocks.list.mockResolvedValue([]);
    mocks.create.mockResolvedValue({ areaId: 'new', active: false, safeAreaConfirmed: false, removedCrowns: 0 });
    await render();

    await act(async () => buttonByText(t('crownHunt.areaDrawButton')).click());
    // Simulate finishing a valid polygon via the stub map.
    await act(async () => buttonByText('stub-draw-polygon').click());
    // Save (create).
    await act(async () => buttonByText(t('crownHunt.areaSaveCreate')).click());

    expect(mocks.create).toHaveBeenCalledTimes(1);
    const request = mocks.create.mock.calls[0]![0] as { active?: boolean; safeAreaConfirmed?: boolean };
    expect(request.active).toBeUndefined();
    expect(request.safeAreaConfirmed).toBeUndefined();
  });
});
