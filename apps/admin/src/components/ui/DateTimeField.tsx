'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';

import { translate } from '@/i18n';
import {
  datePartOf,
  maskDateInput,
  maskTimeInput,
  normalizeDateInput,
  normalizeTimeInput,
  timePartOf,
  withDatePart,
  withTimePart,
} from '@/lib/datetime';

import styles from './DateTimeField.module.css';

const t = (key: string) => translate('sv', key);

export interface DateTimeFieldProps {
  /** Base id. The inputs get `${id}-date` and `${id}-time`. */
  id: string;
  /** Legend content for the pair. Pass the caller's own required-marker node. */
  label: ReactNode;
  /**
   * The field value: `''`, `'YYYY-MM-DD'`, or `'YYYY-MM-DDTHH:mm'` — always
   * local wall-clock. See `lib/datetime` for the timezone contract.
   */
  value: string;
  onChange: (next: string) => void;
  /** `'date'` drops the time input; the value then stays `'YYYY-MM-DD'`. */
  mode?: 'datetime' | 'date';
  required?: boolean;
  disabled?: boolean;
  /** Forwarded to `aria-describedby` on both inputs (e.g. an error node id). */
  describedBy?: string;
  /** Optional helper/hint node rendered under the inputs. */
  hint?: ReactNode;
  /** The call site's own input class, so the field inherits that page's styling. */
  inputClassName?: string;
  /** The call site's own label class, applied to the legend. */
  labelClassName?: string;
  /** Extra class for the wrapping fieldset. */
  className?: string;
}

/**
 * A date (+ time) input pair, replacing `<input type="datetime-local">`.
 *
 * ## Why plain text inputs, not `type="date"` / `type="time"`
 *
 * A native `datetime-local` — and equally a native `type="date"` or
 * `type="time"` — renders its visible format from the **browser/OS locale**,
 * which is why an operator on an `en-GB` machine sees `dd/mm/yyyy, --:--`
 * instead of the `YYYY-MM-DD` + 24h `HH:mm` this admin requires. No attribute,
 * `lang` tag or CSS rule can override that: Chromium honours `lang` only
 * unreliably, and Firefox and Safari ignore it entirely. The one way to
 * *guarantee* the format on every browser is to drive our own
 * `<input type="text">` controls and format the text ourselves. The controls
 * are keyboard-typable, individually labelled ("Datum" / "Tid"), individually
 * clearable, and — being pure text — display exactly `YYYY-MM-DD` / `HH:mm`
 * regardless of locale. The trade-off is the loss of the native calendar/clock
 * pop-up chrome; guaranteeing the format is the requirement it is paid for.
 *
 * The stored value contract is unchanged from the native version: `value` and
 * `onChange` still speak `''` | `'YYYY-MM-DD'` | `'YYYY-MM-DDTHH:mm'` local
 * wall-clock, and all parsing/formatting still goes through `lib/datetime`.
 *
 * Behaviour worth knowing:
 *  - **Partial input.** As the operator types, the visible text is masked into
 *    shape (`20260731` → `2026-07-31`) but is not committed until it is a
 *    complete, real calendar value. An incomplete or impossible date (e.g.
 *    `2026-02-31`) stays in the box without being emitted, so nothing
 *    half-formed reaches the caller or a `Date` constructor. Emptying the box
 *    commits `''`.
 *  - **Clearing.** Clearing the date clears the whole field to `''` — a time
 *    alone denotes no instant — so an optional field saves as null.
 *  - **Time is gated on the date.** The time input is disabled until a complete
 *    date exists, because a time typed first would be discarded anyway.
 */
export function DateTimeField({
  id,
  label,
  value,
  onChange,
  mode = 'datetime',
  required = false,
  disabled = false,
  describedBy,
  hint,
  inputClassName,
  labelClassName,
  className,
}: DateTimeFieldProps) {
  // Visible text buffers. They hold the masked, possibly-still-partial text the
  // operator is typing; the canonical value is only ever derived from them once
  // complete. Seeded from the incoming value's canonical parts.
  const [dateText, setDateText] = useState(() => datePartOf(value));
  const [timeText, setTimeText] = useState(() => timePartOf(value));

  // Reconcile the buffers when `value` changes from *outside* the component
  // (form reset, loading a record) without wiping in-progress typing. We only
  // adopt the incoming value when it differs from what we last emitted: our own
  // emits round-trip back as `value`, and re-seeding on those would clobber a
  // valid partial entry mid-keystroke.
  const lastEmitted = useRef(value);
  useEffect(() => {
    if (value !== lastEmitted.current) {
      lastEmitted.current = value;
      setDateText(datePartOf(value));
      setTimeText(timePartOf(value));
    }
  }, [value]);

  const emit = (next: string) => {
    lastEmitted.current = next;
    onChange(next);
  };

  const hasDate = normalizeDateInput(dateText) !== '';

  const handleDateInput = (raw: string) => {
    const masked = maskDateInput(raw);
    setDateText(masked);

    // Emptying the box explicitly clears the whole field (a time on its own
    // denotes no instant). A complete, valid date is committed — in date-only
    // mode as the bare `YYYY-MM-DD` the caller stores, otherwise merged onto any
    // existing time via `withDatePart`. A partial/invalid date is held in the
    // buffer only, leaving the last committed value (and its time) untouched.
    if (masked === '') {
      // Clearing the date clears the whole field. Drop any buffered time too,
      // so a stale value can't linger in the (now disabled) time box — the
      // committed value is empty, and the two must agree.
      setTimeText('');
      emit(mode === 'date' ? '' : withDatePart(value, ''));
      return;
    }
    const canonical = normalizeDateInput(masked);
    if (canonical) {
      emit(mode === 'date' ? canonical : withDatePart(value, canonical));
    }
  };

  const handleTimeInput = (raw: string) => {
    const masked = maskTimeInput(raw);
    setTimeText(masked);

    // Clearing the time keeps the date; a complete valid time is merged onto it.
    // A partial time is held in the buffer without being committed.
    if (masked === '') {
      emit(withTimePart(value, ''));
      return;
    }
    const canonical = normalizeTimeInput(masked);
    if (canonical) {
      emit(withTimePart(value, canonical));
    }
  };

  const dateInput = (
    <input
      id={`${id}-date`}
      className={inputClassName}
      type="text"
      inputMode="numeric"
      autoComplete="off"
      placeholder={t('common.dateFormatPlaceholder')}
      maxLength={10}
      value={dateText}
      onChange={(event) => handleDateInput(event.target.value)}
      disabled={disabled}
      aria-required={required || undefined}
      aria-describedby={describedBy}
    />
  );

  // Date-only: one control, labelled directly. A fieldset/legend here would
  // wrap a single input and force a redundant second "Datum" sub-label.
  if (mode === 'date') {
    return (
      <div className={[styles.dateOnly, className].filter(Boolean).join(' ')}>
        <label className={labelClassName} htmlFor={`${id}-date`}>
          {label}
        </label>
        {dateInput}
        {hint}
      </div>
    );
  }

  return (
    <fieldset className={[styles.fieldset, className].filter(Boolean).join(' ')}>
      <legend className={labelClassName}>{label}</legend>
      <div className={styles.row}>
        <div className={styles.dateField}>
          <label className={styles.subLabel} htmlFor={`${id}-date`}>
            {t('common.dateLabel')}
          </label>
          {dateInput}
        </div>
        <div className={styles.timeField}>
          <label className={styles.subLabel} htmlFor={`${id}-time`}>
            {t('common.timeLabel')}
          </label>
          <input
            id={`${id}-time`}
            className={inputClassName}
            type="text"
            inputMode="numeric"
            autoComplete="off"
            placeholder={t('common.timeFormatPlaceholder')}
            maxLength={5}
            value={timeText}
            onChange={(event) => handleTimeInput(event.target.value)}
            // A time with no date is discarded, so do not invite one.
            disabled={disabled || !hasDate}
            aria-describedby={describedBy}
          />
        </div>
      </div>
      {hint}
    </fieldset>
  );
}
