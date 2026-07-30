/**
 * Component tests for the feature-flags page: the DOM half of the
 * contract-sync guarantee. `feature-flags-contract-sync.test.ts` proves the
 * data layer offers every backend flag; this proves the page actually paints
 * a row (and a working toggle) for each one, including `crownHuntSpawn`,
 * which was previously absent from the rendered table.
 *
 * Rendered with react-dom/client + React.act (no testing-library dependency,
 * matching the app's zero-extra-deps test setup).
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const setFlagMock = vi.fn();

// Firestore is not reachable in tests: the page falls back to the contract
// defaults it renders on first paint, which is exactly the view under test.
vi.mock('firebase/firestore', () => ({
  doc: vi.fn(),
  getDoc: vi.fn(async () => {
    throw new Error('offline');
  }),
}));
vi.mock('@/lib/firestore', () => ({ getAdminFirestore: vi.fn() }));
vi.mock('@/lib/callables', () => ({
  callAdmin: (...args: unknown[]) => setFlagMock(...args),
}));

import FeatureFlagsPage from '@/app/feature-flags/page';
import { FEATURE_FLAG_DEFINITIONS } from '@/features/feature-flags';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

async function render() {
  await act(async () => {
    root.render(<FeatureFlagsPage />);
  });
}

function rowFor(key: string): HTMLTableRowElement {
  const cell = [...container.querySelectorAll('tbody tr')].find((tr) =>
    [...tr.querySelectorAll('span')].some((span) => span.textContent === key),
  );
  if (!cell) throw new Error(`No table row rendered for flag "${key}".`);
  return cell as HTMLTableRowElement;
}

beforeEach(() => {
  setFlagMock.mockReset();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('feature-flags page', () => {
  it('renders a row for every flag in the contract', async () => {
    await render();
    expect(container.querySelectorAll('tbody tr')).toHaveLength(FEATURE_FLAG_DEFINITIONS.length);
    for (const definition of FEATURE_FLAG_DEFINITIONS) {
      const row = rowFor(definition.key);
      expect(row.textContent).toContain(definition.label);
      expect(row.textContent).toContain(definition.description);
    }
  });

  it('renders crownHuntSpawn as a reachable, currently-disabled switch', async () => {
    await render();
    const row = rowFor('crownHuntSpawn');
    expect(row.textContent).toContain('Disabled');
    const button = row.querySelector('button');
    expect(button?.textContent).toBe('Aktivera');
    expect(button?.disabled).toBe(false);
  });

  it('turns a safety flag OFF with no extra confirmation beyond the audit reason', async () => {
    // The emergency direction must stay one click plus the logged reason.
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt').mockReturnValue('Incident 42');
    setFlagMock.mockResolvedValue({ key: 'crownHunt', enabled: false });
    await render();
    await act(async () => {
      rowFor('crownHunt').querySelector('button')?.click();
    });
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(setFlagMock).toHaveBeenCalledWith('admin-setFeatureFlag', {
      key: 'crownHunt',
      enabled: false,
      reason: 'Incident 42',
    });
  });

  it('requires an explicit confirmation before ENABLING a safety flag', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('because');
    await render();
    await act(async () => {
      rowFor('crownHuntSpawn').querySelector('button')?.click();
    });
    expect(confirmSpy).toHaveBeenCalledOnce();
    // Declining the confirmation stops before the reason prompt and the write.
    expect(promptSpy).not.toHaveBeenCalled();
    expect(setFlagMock).not.toHaveBeenCalled();
  });

  it('enables a safety flag once confirmed, through the audited callable', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt').mockReturnValue('Approved cells in place');
    setFlagMock.mockResolvedValue({ key: 'crownHuntSpawn', enabled: true });
    await render();
    await act(async () => {
      rowFor('crownHuntSpawn').querySelector('button')?.click();
    });
    expect(setFlagMock).toHaveBeenCalledWith('admin-setFeatureFlag', {
      key: 'crownHuntSpawn',
      enabled: true,
      reason: 'Approved cells in place',
    });
  });
});
