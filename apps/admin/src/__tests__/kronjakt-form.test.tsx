/**
 * Component tests for the Crown Hunt (Kronjakt) Punkter create/edit form after
 * the "Crown = map collectable" redesign.
 *
 * A Crown is a Pokémon GO–style collectable, not a titled document, so the form
 * must:
 *   - render NO Title / Description fields (gone from the UI, not just optional);
 *   - choose the reward from a colour-coded RARITY TIER preset
 *     (Common/Rare/Epic/Legendary → 10/25/100/500 KP), default Common;
 *   - send no title/description and a fixed 75 m collect radius on submit; and
 *   - keep the safe-location confirmation gate on activation.
 *
 * The crown-hunt service layer is mocked so the tests assert the exact submit
 * payload without touching Firestore. Mapbox is unavailable under jsdom, so the
 * MapLocationPicker renders its manual lat/lng fallback inputs, which the tests
 * drive directly.
 *
 * Rendered with react-dom/client + React.act (no testing-library dependency,
 * matching the app's zero-extra-deps test setup).
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { translate } from '@/i18n';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Service-layer mock (crownHunt.* callables + direct reads)
// ---------------------------------------------------------------------------

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  update: vi.fn(),
  listPoints: vi.fn(),
  listClaims: vi.fn(),
  activate: vi.fn(),
  pause: vi.fn(),
  listSpawnCells: vi.fn(),
}));

vi.mock('@/features/crown-hunt', () => ({
  adminCreateCrownHuntPoint: mocks.create,
  adminUpdateCrownHuntPoint: mocks.update,
  adminListCrownHuntPoints: mocks.listPoints,
  adminListCrownHuntClaims: mocks.listClaims,
  adminActivateCrownHuntPoint: mocks.activate,
  adminPauseCrownHuntPoint: mocks.pause,
  adminListSpawnCells: mocks.listSpawnCells,
  adminApproveSpawnCell: vi.fn(),
  adminRevokeSpawnCell: vi.fn(),
  cellKeyForCoords: () => 'cell',
  formatCellCenter: () => '0, 0',
}));

import KronjaktPage from '@/app/kronjakt/page';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

function makePoint(overrides: Record<string, unknown> = {}) {
  const now = new Date('2026-08-01T00:00:00Z').toISOString();
  return {
    pointId: 'p1',
    title: '',
    description: null,
    latitude: 57.7,
    longitude: 11.97,
    geofenceRadiusMeters: 75,
    rewardPoints: 500,
    status: 'draft',
    repeatRule: 'once',
    availableFrom: null,
    availableUntil: null,
    approvedAt: null,
    approvedByUserId: null,
    createdByUserId: 'u1',
    createdAt: now,
    updatedAt: now,
    totalClaims: 0,
    ...overrides,
  };
}

function pointsResponse(points: Array<Record<string, unknown>>) {
  return {
    ok: true as const,
    data: { points },
    meta: { page: 1, pageSize: 20, total: points.length, hasNext: false },
  };
}

async function render() {
  await act(async () => {
    root.render(<KronjaktPage />);
  });
}

/** Sets a controlled input's value the way React's synthetic layer expects. */
function setNativeValue(input: HTMLInputElement | HTMLTextAreaElement, value: string) {
  const proto = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement : HTMLInputElement;
  const setter = Object.getOwnPropertyDescriptor(proto.prototype, 'value')!.set!;
  setter.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function buttonByText(label: string): HTMLButtonElement {
  const btn = [...container.querySelectorAll('button')].find(
    (b) => b.textContent?.trim() === label,
  );
  if (!btn) throw new Error(`No button with text "${label}"`);
  return btn as HTMLButtonElement;
}

async function openCreateForm() {
  await act(async () => {
    buttonByText(t('crownHunt.createPoint')).click();
  });
}

function numberInputs(): HTMLInputElement[] {
  return [...container.querySelectorAll('input[type="number"]')] as HTMLInputElement[];
}

/** The rarity tier toggle buttons (aria-pressed group inside the form). */
function tierButtons(): HTMLButtonElement[] {
  const form = container.querySelector('form')!;
  return [...form.querySelectorAll('button[aria-pressed]')] as HTMLButtonElement[];
}

async function fillCoordinates() {
  const [lat, lng] = numberInputs();
  await act(async () => {
    setNativeValue(lat!, '57.7');
    setNativeValue(lng!, '11.97');
  });
}

async function submitForm() {
  const form = container.querySelector('form')!;
  await act(async () => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
}

beforeEach(() => {
  Object.values(mocks).forEach((m) => m.mockReset());
  mocks.listPoints.mockResolvedValue(pointsResponse([]));
  mocks.listClaims.mockResolvedValue({
    ok: true,
    data: { claims: [] },
    meta: { page: 1, pageSize: 20, total: 0, hasNext: false },
  });
  mocks.listSpawnCells.mockResolvedValue([]);
  mocks.create.mockResolvedValue({ ok: true, data: makePoint() });
  mocks.update.mockResolvedValue({ ok: true, data: makePoint() });
  mocks.activate.mockResolvedValue({ ok: true, data: makePoint({ status: 'active' }) });

  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('Kronjakt point form — collectable redesign', () => {
  it('renders NO title/description fields and a four-tier rarity selector', async () => {
    await render();
    await openCreateForm();

    const form = container.querySelector('form')!;
    // No free-text fields at all. A reintroduced Title/Description would appear
    // as a <textarea> or a text/free-form <input>, so assert BOTH are absent —
    // the map picker's lat/lng are type="number" and don't count. (The only
    // text-like control left is the availability datepicker, which is not a
    // free-text field.)
    expect(form.querySelector('textarea')).toBeNull();
    expect(form.querySelector('input[type="text"]')).toBeNull();
    const freeTextInputs = [...form.querySelectorAll('input')].filter((el) => {
      const type = (el.getAttribute('type') ?? 'text').toLowerCase();
      return ['text', 'search', 'email', 'url', 'tel'].includes(type);
    });
    expect(freeTextInputs).toHaveLength(0);
    expect(form.textContent).not.toContain(t('crownHunt.formTitleLabel')); // 'Titel'
    expect(form.textContent).not.toContain(t('crownHunt.formDescriptionLabel')); // 'Beskrivning'

    // Rarity tier selector: four colour-coded toggle buttons, Common..Legendary.
    const tiers = tierButtons();
    expect(tiers).toHaveLength(4);
    const labels = tiers.map((r) => r.textContent);
    for (const key of ['tier_common', 'tier_rare', 'tier_epic', 'tier_legendary']) {
      expect(labels.some((l) => l?.includes(t(`crownHunt.${key}`)))).toBe(true);
    }
    // Default tier is Common (aria-pressed toggle group).
    expect(tiers.find((r) => r.getAttribute('aria-pressed') === 'true')?.textContent).toContain(
      t('crownHunt.tier_common'),
    );
  });

  it('defaults to Common (10 KP), sends a fixed 75 m radius and NO title/description', async () => {
    await render();
    await openCreateForm();
    await fillCoordinates();
    await submitForm();

    expect(mocks.create).toHaveBeenCalledTimes(1);
    const payload = mocks.create.mock.calls[0]![0] as Record<string, unknown>;
    expect(payload).not.toHaveProperty('title');
    expect(payload).not.toHaveProperty('description');
    expect(payload.rewardPoints).toBe(10);
    expect(payload.geofenceRadiusMeters).toBe(75);
    expect(payload.latitude).toBeCloseTo(57.7);
    expect(payload.longitude).toBeCloseTo(11.97);
  });

  it('resolves the Legendary tier to its 500 KP preset', async () => {
    await render();
    await openCreateForm();
    await fillCoordinates();

    const legendary = tierButtons().find((r) =>
      r.textContent?.includes(t('crownHunt.tier_legendary')),
    )!;
    await act(async () => {
      legendary.click();
    });
    await submitForm();

    const payload = mocks.create.mock.calls[0]![0] as Record<string, unknown>;
    expect(payload.rewardPoints).toBe(500);
    expect(payload).not.toHaveProperty('title');
  });

  it('reverse-maps an existing reward to its tier when editing (25 KP → Rare)', async () => {
    mocks.listPoints.mockResolvedValue(pointsResponse([makePoint({ rewardPoints: 25 })]));
    await render();

    await act(async () => {
      buttonByText(t('crownHunt.editPoint')).click();
    });

    const pressed = tierButtons().find((r) => r.getAttribute('aria-pressed') === 'true');
    expect(pressed?.textContent).toContain(t('crownHunt.tier_rare'));
  });

  it('PRESERVES a legacy custom reward (250 KP) when editing + saving without picking a tier', async () => {
    // Old UI allowed any 1–1000 KP reward. Editing such a point and saving
    // without touching the tier selector must NOT silently coerce it to 10 KP.
    mocks.listPoints.mockResolvedValue(pointsResponse([makePoint({ rewardPoints: 250 })]));
    await render();

    await act(async () => {
      buttonByText(t('crownHunt.editPoint')).click();
    });

    // No tier is pressed (it maps to none) and the real value is surfaced.
    const pressed = tierButtons().filter((r) => r.getAttribute('aria-pressed') === 'true');
    expect(pressed).toHaveLength(0);
    const form = container.querySelector('form')!;
    expect(form.textContent).toContain('250');

    await submitForm();

    expect(mocks.update).toHaveBeenCalledTimes(1);
    const payload = mocks.update.mock.calls[0]![1] as Record<string, unknown>;
    expect(payload.rewardPoints).toBe(250);
  });

  it('lets the admin convert a custom-reward point to a tier preset on edit', async () => {
    mocks.listPoints.mockResolvedValue(pointsResponse([makePoint({ rewardPoints: 250 })]));
    await render();

    await act(async () => {
      buttonByText(t('crownHunt.editPoint')).click();
    });
    const epic = tierButtons().find((r) => r.textContent?.includes(t('crownHunt.tier_epic')))!;
    await act(async () => {
      epic.click();
    });
    await submitForm();

    const payload = mocks.update.mock.calls[0]![1] as Record<string, unknown>;
    expect(payload.rewardPoints).toBe(100);
  });

  it('PRESERVES an existing geofence radius (50 m) on edit — no silent overwrite to 75', async () => {
    // The form has no geofence control; editing a legacy point must keep its
    // stored radius. Only NEW points default to the fixed 75 m.
    mocks.listPoints.mockResolvedValue(pointsResponse([makePoint({ geofenceRadiusMeters: 50 })]));
    await render();

    await act(async () => {
      buttonByText(t('crownHunt.editPoint')).click();
    });
    await submitForm();

    expect(mocks.update).toHaveBeenCalledTimes(1);
    const payload = mocks.update.mock.calls[0]![1] as Record<string, unknown>;
    expect(payload.geofenceRadiusMeters).toBe(50);
  });

  it('defaults a NEW point to the fixed 75 m collect radius', async () => {
    await render();
    await openCreateForm();
    await fillCoordinates();
    await submitForm();

    const payload = mocks.create.mock.calls[0]![0] as Record<string, unknown>;
    expect(payload.geofenceRadiusMeters).toBe(75);
  });

  it('keeps the safe-location confirmation REQUIRED to activate a draft', async () => {
    mocks.listPoints.mockResolvedValue(pointsResponse([makePoint({ status: 'draft' })]));
    await render();

    await act(async () => {
      buttonByText(t('crownHunt.activatePoint')).click();
    });

    const modal = container.querySelector('[role="dialog"]')!;
    const confirm = [...modal.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === t('crownHunt.confirm'),
    ) as HTMLButtonElement;
    // Gate closed until BOTH the checkbox and a note are provided.
    expect(confirm.disabled).toBe(true);

    const checkbox = modal.querySelector('input[type="checkbox"]') as HTMLInputElement;
    const note = modal.querySelector('textarea') as HTMLTextAreaElement;
    await act(async () => {
      checkbox.click();
    });
    await act(async () => {
      setNativeValue(note, 'Trygg parkeringsficka intill torget.');
    });

    expect(confirm.disabled).toBe(false);
    // Activation was NOT auto-fired by opening the modal.
    expect(mocks.activate).not.toHaveBeenCalled();
  });
});
