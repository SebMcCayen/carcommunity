/**
 * Component tests for the shared `DateTimeField`.
 *
 * These cover the parts that only exist once the pure helpers are wired to
 * real DOM controls: that the pair really is two locale-independent text
 * inputs formatting `YYYY-MM-DD` / `HH:mm` themselves — never a native
 * `type="date"`/`type="time"`/`datetime-local`, whose visible format the
 * browser locale dictates and no API can override — that the visible text is
 * masked into shape as it is typed and only committed once complete, that the
 * time control is gated on a date, and that clearing propagates an empty value.
 *
 * Rendered with react-dom/client + React.act (no testing-library dependency,
 * matching the app's zero-extra-deps test setup).
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DateTimeField } from '@/components/ui/DateTimeField';

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

const dateInput = () => container.querySelector<HTMLInputElement>('#f-date')!;
const timeInput = () => container.querySelector<HTMLInputElement>('#f-time');

/** Drive a controlled input the way React's synthetic onChange expects. */
function type(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype,
    'value',
  )?.set;
  act(() => {
    setter?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

describe('DateTimeField', () => {
  it('renders locale-independent text inputs, never a native date/time control', () => {
    render(<DateTimeField id="f" label="Från" value="2026-07-08T23:12" onChange={vi.fn()} />);

    // No native control whose visible format the browser locale would dictate.
    expect(container.querySelector('input[type="datetime-local"]')).toBeNull();
    expect(container.querySelector('input[type="date"]')).toBeNull();
    expect(container.querySelector('input[type="time"]')).toBeNull();

    // Plain text inputs, so the format is ours to control on every browser.
    expect(dateInput().type).toBe('text');
    expect(timeInput()?.type).toBe('text');
    // Numeric keypad on mobile, and a placeholder that shows the required shape.
    expect(dateInput().inputMode).toBe('numeric');
    expect(dateInput().getAttribute('placeholder')).toMatch(/-mm-dd$/);
  });

  it('shows the value as YYYY-MM-DD / HH:mm text, independent of browser locale', () => {
    render(<DateTimeField id="f" label="Från" value="2026-07-08T23:12" onChange={vi.fn()} />);

    // The literal text in the box is ISO — not dd/mm/yyyy, not a 12h clock —
    // because it is a plain text input we format ourselves.
    expect(dateInput().value).toBe('2026-07-08');
    expect(timeInput()?.value).toBe('23:12');
  });

  it('masks typed digits into YYYY-MM-DD, committing only once complete', () => {
    const onChange = vi.fn();
    render(<DateTimeField id="f" label="Från" value="" onChange={onChange} />);

    // Partial input: the box is shaped with a dash, but nothing is committed
    // (an incomplete date denotes no instant).
    type(dateInput(), '202607');
    expect(dateInput().value).toBe('2026-07');
    expect(onChange).not.toHaveBeenCalled();

    // Completing the date commits the canonical value.
    type(dateInput(), '20260731');
    expect(dateInput().value).toBe('2026-07-31');
    expect(onChange).toHaveBeenCalledWith('2026-07-31');
  });

  it('holds an impossible calendar date without committing it', () => {
    const onChange = vi.fn();
    render(<DateTimeField id="f" label="Från" value="" onChange={onChange} />);

    // 31 February is masked into shape but is not a real date, so it is never
    // emitted (and never silently rolled over to 3 March).
    type(dateInput(), '20260231');
    expect(dateInput().value).toBe('2026-02-31');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('labels each control separately and associates the labels', () => {
    render(<DateTimeField id="f" label="Från" value="" onChange={vi.fn()} />);

    const labels = [...container.querySelectorAll('label')].map((l) => [l.htmlFor, l.textContent]);
    expect(labels).toEqual([
      ['f-date', 'Datum'],
      ['f-time', 'Tid'],
    ]);
    expect(container.querySelector('legend')?.textContent).toBe('Från');
  });

  it('gates the time control on a date being present', () => {
    const onChange = vi.fn();
    render(<DateTimeField id="f" label="Från" value="" onChange={onChange} />);
    expect(timeInput()?.disabled).toBe(true);

    render(<DateTimeField id="f" label="Från" value="2026-07-08" onChange={onChange} />);
    expect(timeInput()?.disabled).toBe(false);
  });

  it('emits a date-only value while the time is still empty', () => {
    const onChange = vi.fn();
    render(<DateTimeField id="f" label="Från" value="" onChange={onChange} />);

    type(dateInput(), '2026-07-08');
    expect(onChange).toHaveBeenCalledWith('2026-07-08');
  });

  it('emits the combined value once a time is entered', () => {
    const onChange = vi.fn();
    render(<DateTimeField id="f" label="Från" value="2026-07-08" onChange={onChange} />);

    type(timeInput()!, '14:30');
    expect(onChange).toHaveBeenCalledWith('2026-07-08T14:30');
  });

  it('emits an empty value when the date is cleared', () => {
    const onChange = vi.fn();
    render(<DateTimeField id="f" label="Från" value="2026-07-08T14:30" onChange={onChange} />);

    type(dateInput(), '');
    expect(onChange).toHaveBeenCalledWith('');
  });

  it('empties the time box too when the date is cleared', () => {
    render(<DateTimeField id="f" label="Från" value="2026-07-08T14:30" onChange={vi.fn()} />);
    expect(timeInput()?.value).toBe('14:30');

    // Clearing the date clears the whole field; the (now disabled) time box must
    // not keep showing a stale value the committed value no longer holds.
    type(dateInput(), '');
    expect(dateInput().value).toBe('');
    expect(timeInput()?.value).toBe('');
    expect(timeInput()?.disabled).toBe(true);
  });

  it('keeps the date when only the time is cleared', () => {
    const onChange = vi.fn();
    render(<DateTimeField id="f" label="Från" value="2026-07-08T14:30" onChange={onChange} />);

    type(timeInput()!, '');
    expect(onChange).toHaveBeenCalledWith('2026-07-08');
  });

  it('disables both controls when the form is submitting', () => {
    render(<DateTimeField id="f" label="Från" value="2026-07-08T14:30" onChange={vi.fn()} disabled />);

    expect(dateInput().disabled).toBe(true);
    expect(timeInput()?.disabled).toBe(true);
  });

  it('shows an empty field for a malformed value rather than throwing', () => {
    render(<DateTimeField id="f" label="Från" value="not-a-date" onChange={vi.fn()} />);

    expect(dateInput().value).toBe('');
    expect(timeInput()?.value).toBe('');
  });

  describe('date-only mode', () => {
    it('renders a single labelled date control with no time input', () => {
      render(<DateTimeField id="f" mode="date" label="Utgår" value="2026-07-08" onChange={vi.fn()} />);

      expect(timeInput()).toBeNull();
      expect(dateInput().value).toBe('2026-07-08');
      const label = container.querySelector('label');
      expect(label?.htmlFor).toBe('f-date');
      expect(label?.textContent).toBe('Utgår');
    });

    it('emits the plain `YYYY-MM-DD` value the caller stores', () => {
      const onChange = vi.fn();
      render(<DateTimeField id="f" mode="date" label="Utgår" value="" onChange={onChange} />);

      type(dateInput(), '2026-07-08');
      expect(onChange).toHaveBeenCalledWith('2026-07-08');
    });

    it('emits an empty value when cleared', () => {
      const onChange = vi.fn();
      render(<DateTimeField id="f" mode="date" label="Utgår" value="2026-07-08" onChange={onChange} />);

      type(dateInput(), '');
      expect(onChange).toHaveBeenCalledWith('');
    });

    it('does not carry a hidden pre-existing time through a date edit', () => {
      // A full datetime arrives (e.g. editing a legacy record). The time input
      // is not rendered in date-only mode, so 14:30 is invisible and
      // uneditable. Editing the date must NOT smuggle it back out: the emitted
      // value is a plain date. Fails (emits `2026-07-09T14:30`) if the date
      // input keeps the time via `withDatePart`.
      const onChange = vi.fn();
      render(<DateTimeField id="f" mode="date" label="Utgår" value="2026-07-08T14:30" onChange={onChange} />);

      expect(timeInput()).toBeNull();
      type(dateInput(), '2026-07-09');
      expect(onChange).toHaveBeenCalledWith('2026-07-09');
    });
  });
});
