import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  load: vi.fn(),
  resolve: vi.fn(),
  allow: vi.fn(),
  remove: vi.fn(),
}));
vi.mock('@/features/event-chat', () => ({
  loadAdminChatReports: mocks.load,
  resolveAdminChatReport: mocks.resolve,
  allowAdminChatMessageFromReport: mocks.allow,
  removeAdminChatMessageFromReport: mocks.remove,
  OPEN_REPORT_STATUSES: ['new', 'under_review'],
  formatReportReason: (value: string) => value,
  formatReportStatus: (value: string) => value,
}));

import EventChatPage from '@/app/event-chat/page';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let container: HTMLDivElement;
let root: Root;
beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset());
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});
afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

describe('event chat report loading', () => {
  it('distinguishes a failed queue read from an empty queue and retries without moderating', async () => {
    mocks.load.mockRejectedValueOnce(new Error('INTERNAL [500]: sensitive backend details'));
    mocks.load.mockResolvedValueOnce({ data: { reports: [] } });
    await act(async () => root.render(<EventChatPage />));
    expect(container.querySelector('[role="alert"]')?.textContent).toContain(
      'Kunde inte ladda eventchattens anmälningar.',
    );
    expect(container.textContent).not.toContain('Inga rapporter hittades');
    expect(container.textContent).not.toContain('sensitive backend details');
    const retry = [...container.querySelectorAll('button')].find(
      (button) => button.textContent === 'Försök igen',
    )!;
    await act(async () => retry.click());
    expect(mocks.load).toHaveBeenCalledTimes(2);
    expect(container.querySelector('[role="alert"]')).toBeNull();
    expect(container.textContent).toContain('Inga rapporter hittades.');
    expect(mocks.resolve).not.toHaveBeenCalled();
    expect(mocks.allow).not.toHaveBeenCalled();
    expect(mocks.remove).not.toHaveBeenCalled();
  });
});
