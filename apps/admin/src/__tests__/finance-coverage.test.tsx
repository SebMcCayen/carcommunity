import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FinanceEstimate, WorkloadLine } from '@/features/finance';

const save = vi.hoisted(() => vi.fn());
vi.mock('@/features/finance', () => ({
  formatCount: (value: number) => String(Math.round(value)),
  formatSek: (value: number) => `${value} kr`,
  updateWorkloadAssumptions: save,
}));
import { CostCoverage } from '@/app/finance/CostCoverage';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let container: HTMLDivElement;
let root: Root;
const row: WorkloadLine = {
  id: 'roadCameras.photo',
  labelKey: 'photos',
  source: 'assumption',
  eventsPerMonth: 100,
  assumedEventsPerMemberPerDay: 2,
  readsPerEvent: 8,
  writesPerEvent: 2,
  deletesPerEvent: 0,
  secondsPerEvent: 0.8,
  bytesPerEvent: 102400,
  periodStart: null,
  periodEnd: null,
  capturedAtMs: null,
  resources: {
    requests: 100,
    reads: 800,
    writes: 200,
    deletes: 0,
    cpuSeconds: 80,
    gibSeconds: 20,
    sentBytes: 10240000,
  },
  sekPerMonth: 3,
};
const base = {
  generatedAtMs: Date.parse('2026-11-15T12:00:00Z'),
  workloads: [row],
  otherModelledSekPerMonth: 20,
  usageEvidence: { state: 'not-configured', data: null, lastAttemptFailed: false },
} as FinanceEstimate;

beforeEach(() => {
  save.mockReset();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});
afterEach(() => {
  act(() => root.unmount());
  container.remove();
});
const render = (est = base, onSaved = vi.fn()) => {
  act(() => root.render(<CostCoverage est={est} onSaved={onSaved} />));
  return onSaved;
};

describe('finance coverage UI', () => {
  it('shows missing evidence as missing, while a calculated zero remains a real zero', () => {
    render({ ...base, workloads: [{ ...row, sekPerMonth: 0 }] });
    expect(container.textContent).toContain('Underlag saknas');
    expect(container.textContent).toContain('Anslutningen är inte aktiverad');
    expect(container.textContent).toContain('0 kr');
    expect(container.textContent).toContain('Antagande');
  });
  it('supports an older backend without hiding the pending upgrade', () => {
    render({ generatedAtMs: 1 } as FinanceEstimate);
    expect(container.textContent).toContain('väntar på den uppdaterade');
  });
  it('keeps reported costs, stale status and failed refreshes distinct from estimated function costs', () => {
    render({
      ...base,
      reportedBilling: {
        state: 'stale',
        lastAttemptFailed: true,
        data: {
          capturedAtMs: base.generatedAtMs,
          latestExportAt: '2026-11-01T00:00:00Z',
          month: '2026-11',
          throughDate: '2026-11-14',
          currency: 'SEK',
          services: [{ id: 'build', label: 'Cloud Build', netCost: 12 }],
        },
        totalSek: 12,
        forecastSek: null,
        modelForPeriodSek: null,
        differenceSek: null,
        unallocatedToFunctionsSek: 12,
      },
    });
    expect(container.textContent).toContain('Underlaget är gammalt');
    expect(container.textContent).toContain('Senaste uppdateringen misslyckades');
    expect(container.textContent).toContain('Cloud Build');
    expect(container.textContent).toContain('12 kr');
    expect(container.textContent).toContain('3 kr');
  });
  it('saves fallback rates and refreshes only after a successful backend write', async () => {
    save.mockResolvedValue({ ok: true });
    const onSaved = render();
    const input = container.querySelector('input')!;
    const nativeSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    act(() => {
      nativeSetter.call(input, '4');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => {
      container
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    expect(save).toHaveBeenCalledWith([{ id: 'roadCameras.photo', eventsPerMemberPerDay: 4 }]);
    expect(onSaved).toHaveBeenCalledOnce();
  });
  it('resets with an empty override list and reports failures without claiming success', async () => {
    save.mockRejectedValue(new Error('failed'));
    const onSaved = render();
    await act(async () => {
      container.querySelector<HTMLButtonElement>('button[type="button"]')!.click();
    });
    expect(save).toHaveBeenCalledWith([]);
    expect(onSaved).not.toHaveBeenCalled();
    expect(container.textContent).toContain('Kunde inte spara');
  });
});
