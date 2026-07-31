/**
 * Component tests for the shared `DateTimeField`.
 *
 * These cover the parts that only exist once the pure helpers are wired to
 * real DOM controls: that the pair really is two ISO-valued native inputs
 * rather than one locale-formatted `datetime-local`, that the time control is
 * gated on a date, and that clearing propagates an empty value.
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
  it('renders a date + time pair, never a datetime-local control', () => {
    render(<DateTimeField id="f" label="Från" value="2026-07-08T23:12" onChange={vi.fn()} />);

    expect(container.querySelector('input[type="datetime-local"]')).toBeNull();
    expect(dateInput().type).toBe('date');
    expect(timeInput()?.type).toBe('time');
  });

  it('shows the value as ISO parts, whatever the browser paints around them', () => {
    render(<DateTimeField id="f" label="Från" value="2026-07-08T23:12" onChange={vi.fn()} />);

    expect(dateInput().value).toBe('2026-07-08');
    expect(timeInput()?.value).toBe('23:12');
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
