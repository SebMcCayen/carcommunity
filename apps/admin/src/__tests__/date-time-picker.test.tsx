/**
 * Component tests for the `DateTimePicker` (the click-to-pick calendar + time
 * control used for the Kronjakt availability window).
 *
 * These cover the behaviour that only exists once the calendar math and popover
 * state are wired to real DOM: that it is NOT a native `type="date"`/`"time"`
 * (whose visible format the browser locale dictates), that picking a day / a
 * time emits the same `''` | `YYYY-MM-DD` | `YYYY-MM-DDTHH:mm` local-wall-clock
 * value contract as `DateTimeField`, that clearing emits `''`, that the time
 * controls are gated on a date, and that arrow-key navigation moves the roving
 * focus. Also guards the accessibility wiring (label association + per-day
 * accessible names) that the review flagged.
 *
 * Rendered with react-dom/client + React.act (no testing-library dependency,
 * matching the app's zero-extra-deps test setup).
 */

import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DateTimePicker } from '@/components/ui/DateTimePicker';

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

function render(node: React.ReactElement) {
  act(() => root.render(node));
}

const trigger = () => container.querySelector<HTMLButtonElement>('#dtp')!;
const dialog = () => container.querySelector<HTMLElement>('[role="dialog"]');
const day = (iso: string) => container.querySelector<HTMLButtonElement>(`[data-date="${iso}"]`);
const selects = () => [...container.querySelectorAll<HTMLSelectElement>('select')];

function click(el: Element) {
  act(() => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

function open() {
  click(trigger());
}

/** Drive a controlled <select> the way React's synthetic onChange expects. */
function setSelect(select: HTMLSelectElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value')?.set;
  act(() => {
    setter?.call(select, value);
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

/** Controlled wrapper so `value` actually updates between interactions. */
function Controlled({ initial, onEmit }: { initial: string; onEmit: (v: string) => void }) {
  const [v, setV] = useState(initial);
  return (
    <DateTimePicker
      id="dtp"
      label="Från"
      value={v}
      onChange={(next) => {
        setV(next);
        onEmit(next);
      }}
    />
  );
}

describe('DateTimePicker', () => {
  it('renders a trigger button, never a native date/time control', () => {
    render(<DateTimePicker id="dtp" label="Från" value="" onChange={vi.fn()} />);

    expect(trigger().tagName).toBe('BUTTON');
    expect(container.querySelector('input[type="date"]')).toBeNull();
    expect(container.querySelector('input[type="time"]')).toBeNull();
    expect(container.querySelector('input[type="datetime-local"]')).toBeNull();
    // Closed by default — the calendar is a popover, not always-on.
    expect(dialog()).toBeNull();
  });

  it('shows the placeholder when empty and the ISO value when set', () => {
    render(<DateTimePicker id="dtp" label="Från" value="" onChange={vi.fn()} />);
    expect(trigger().textContent).toContain('Välj datum');

    render(<DateTimePicker id="dtp" label="Från" value="2026-07-20T09:45" onChange={vi.fn()} />);
    expect(trigger().textContent).toContain('2026-07-20 09:45');
  });

  it('associates the label with the trigger and gives each day a full-date name', () => {
    render(<DateTimePicker id="dtp" label="Från" value="2026-07-20" onChange={vi.fn()} />);

    const label = container.querySelector('label');
    expect(label?.htmlFor).toBe('dtp');

    open();
    // Day cells announce the whole date, not just the number.
    expect(day('2026-07-20')?.getAttribute('aria-label')).toMatch(/2026/);
    expect(day('2026-07-20')?.getAttribute('aria-label')).not.toBe('20');
  });

  it('opens the calendar popover on trigger click', () => {
    render(<DateTimePicker id="dtp" label="Från" value="2026-07-20" onChange={vi.fn()} />);
    expect(dialog()).toBeNull();
    open();
    expect(dialog()).not.toBeNull();
    // The month containing the value is shown, so its days are present.
    expect(day('2026-07-20')).not.toBeNull();
  });

  it('emits a bare YYYY-MM-DD when a day is picked and no time is set', () => {
    const onChange = vi.fn();
    render(<DateTimePicker id="dtp" label="Från" value="2026-07-15" onChange={onChange} />);

    open();
    click(day('2026-07-20')!);
    expect(onChange).toHaveBeenCalledWith('2026-07-20');
  });

  it('emits the combined YYYY-MM-DDTHH:mm once a time is chosen', () => {
    const onEmit = vi.fn();
    render(<Controlled initial="2026-07-20" onEmit={onEmit} />);

    open();
    const [hour, minute] = selects();
    expect(hour).toBeTruthy();
    setSelect(hour!, '09');
    setSelect(minute!, '45');
    expect(onEmit).toHaveBeenLastCalledWith('2026-07-20T09:45');
  });

  it('disables the time selects until a date exists', () => {
    render(<DateTimePicker id="dtp" label="Från" value="" onChange={vi.fn()} />);
    open();
    expect(selects().length).toBeGreaterThan(0);
    for (const s of selects()) expect(s.disabled).toBe(true);

    // Same open popover, now with a date — the selects become enabled. (The
    // component instance keeps `open`, so do not toggle the trigger again.)
    render(<DateTimePicker id="dtp" label="Från" value="2026-07-20" onChange={vi.fn()} />);
    for (const s of selects()) expect(s.disabled).toBe(false);
  });

  it('clears to an empty value via the clear button', () => {
    const onChange = vi.fn();
    render(<DateTimePicker id="dtp" label="Från" value="2026-07-20T09:45" onChange={onChange} />);

    open();
    const clearBtn = [...container.querySelectorAll('button')].find(
      (b) => b.textContent === 'Rensa',
    )!;
    click(clearBtn);
    expect(onChange).toHaveBeenCalledWith('');
  });

  it('moves the roving focus with the arrow keys', () => {
    render(<DateTimePicker id="dtp" label="Från" value="2026-07-20" onChange={vi.fn()} />);
    open();

    // The selected day starts focusable.
    expect(day('2026-07-20')!.tabIndex).toBe(0);

    const grid = container.querySelector<HTMLElement>('[role="grid"]')!;
    act(() => {
      grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });

    // Focus rolls to the next day.
    expect(day('2026-07-21')!.tabIndex).toBe(0);
    expect(day('2026-07-20')!.tabIndex).toBe(-1);
  });

  describe('date-only mode', () => {
    it('has no time selects and emits a bare date', () => {
      const onChange = vi.fn();
      render(
        <DateTimePicker id="dtp" mode="date" label="Från" value="2026-07-15" onChange={onChange} />,
      );

      open();
      expect(selects()).toHaveLength(0);
      click(day('2026-07-20')!);
      expect(onChange).toHaveBeenCalledWith('2026-07-20');
    });
  });
});
