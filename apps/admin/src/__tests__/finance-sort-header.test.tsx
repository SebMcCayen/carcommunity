import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/features/finance', () => ({
  GCP_BILLING_URL: 'https://example.test/billing',
  formatCount: (value: number) => String(value),
  formatSek: (value: number) => String(value),
  loadFinanceEstimate: vi.fn(),
}));

import { SortHeader } from '@/app/finance/page';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

function renderHeader(props: React.ComponentProps<typeof SortHeader>): {
  header: HTMLTableCellElement;
  button: HTMLButtonElement;
} {
  act(() => {
    root.render(
      <table>
        <thead>
          <tr>
            <SortHeader {...props} />
          </tr>
        </thead>
      </table>,
    );
  });
  const header = container.querySelector('th');
  const button = container.querySelector('button');
  if (!header || !button) throw new Error('sort header was not rendered');
  return { header, button };
}

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

describe('finance SortHeader', () => {
  it('uses a native button and reports the active ascending service sort', () => {
    const onClick = vi.fn();
    const { header, button } = renderHeader({
      label: 'Service / driver',
      active: true,
      direction: 'ascending',
      onClick,
    });

    expect(header.getAttribute('aria-sort')).toBe('ascending');
    expect(button.getAttribute('aria-label')).toBe('Sort by Service / driver');
    expect(button.querySelector('[aria-hidden="true"]')?.textContent).toContain('▴');
    button.click();
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('reports descending numeric sorts without exposing the decorative arrow', () => {
    const { header, button } = renderHeader({
      label: 'SEK / month',
      numeric: true,
      active: true,
      direction: 'descending',
      onClick: vi.fn(),
    });

    expect(header.getAttribute('aria-sort')).toBe('descending');
    expect(button.querySelector('[aria-hidden="true"]')?.textContent).toContain('▾');
    expect(button.textContent).toContain('SEK / month');
  });
});
