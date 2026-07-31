'use client';

import type { ReactNode } from 'react';

import { translate } from '@/i18n';
import { datePartOf, timePartOf, withDatePart, withTimePart } from '@/lib/datetime';

import styles from './DateTimeField.module.css';

const t = (key: string) => translate('sv', key);

/**
 * Language tag applied to the native controls.
 *
 * The visible format of `type="date"` / `type="time"` comes from the locale,
 * not from CSS. Chromium-based browsers honour the element's `lang` when
 * laying out the sub-fields, so tagging them `sv-SE` asks for `YYYY-MM-DD` and
 * a 24-hour clock — matching the admin's required format — on a browser whose
 * UI language is something else. Firefox and Safari take the format from the
 * browser/OS locale and ignore `lang`; there is no web platform API that can
 * force it there. The *values* this component reads and writes are ISO in
 * every browser regardless, so only the picker chrome can differ.
 */
const INPUT_LANG = 'sv-SE';

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
 * Why a pair rather than the single native control: `datetime-local` renders
 * one opaque locale-formatted string that CSS cannot touch, so an operator on
 * an `en-GB` browser sees `dd/mm/yyyy, --:--`. Two separate controls are
 * individually labelled ("Datum" / "Tid"), individually clearable, and their
 * values are ISO in every browser.
 *
 * Behaviour worth knowing:
 *  - **Partial input.** A date with no time is a legitimate intermediate
 *    state and is preserved as such; only on submit does the caller collapse
 *    it (via `combineDateTime`/`localToIso`, which default the time to
 *    midnight). Nothing half-formed is ever handed to a `Date` constructor.
 *  - **Clearing.** Clearing the date clears the whole field to `''` — a time
 *    alone denotes no instant — so an optional field saves as null.
 *  - **Time is gated on the date.** The time input is disabled until a date
 *    exists, because a time typed first would be discarded anyway.
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
  const date = datePartOf(value);
  const time = timePartOf(value);

  const dateInput = (
    <input
      id={`${id}-date`}
      className={inputClassName}
      type="date"
      lang={INPUT_LANG}
      value={date}
      onChange={(event) => onChange(withDatePart(value, event.target.value))}
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
            type="time"
            lang={INPUT_LANG}
            value={time}
            onChange={(event) => onChange(withTimePart(value, event.target.value))}
            // A time with no date is discarded, so do not invite one.
            disabled={disabled || !date}
            aria-describedby={describedBy}
          />
        </div>
      </div>
      {hint}
    </fieldset>
  );
}
