'use client';

import { useEffect, useId, useRef, useState, type ReactNode } from 'react';

import { translate } from '@/i18n';
import {
  datePartOf,
  parseLocalDateTime,
  timePartOf,
  toLocalDateValue,
  withDatePart,
  withTimePart,
} from '@/lib/datetime';

import styles from './DateTimePicker.module.css';

const t = (key: string) => translate('sv', key);

/**
 * Display locale for the calendar chrome (month name, weekday headers). The
 * admin is Swedish (`translate('sv', …)`), so the calendar labels match. This
 * only styles the *labels* — the emitted value is always the locale-independent
 * `YYYY-MM-DD` / `HH:mm` contract, so unlike a native `<input type="date">`
 * there is no locale-format hazard here.
 */
const DISPLAY_LOCALE = 'sv-SE';

// 2024-01-01 was a Monday — a fixed anchor for Monday-first weekday headers.
const WEEKDAY_LABELS = Array.from({ length: 7 }, (_, i) =>
  new Intl.DateTimeFormat(DISPLAY_LOCALE, { weekday: 'short' }).format(new Date(2024, 0, 1 + i)),
);

const monthLabel = (year: number, month: number) =>
  new Intl.DateTimeFormat(DISPLAY_LOCALE, { month: 'long', year: 'numeric' }).format(
    new Date(year, month, 1),
  );

// Full localized date (e.g. "onsdag 20 augusti 2026") for a day cell's
// accessible name, so a screen reader announces the whole date, not just "20".
const FULL_DATE_FORMAT = new Intl.DateTimeFormat(DISPLAY_LOCALE, { dateStyle: 'full' });

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
const MINUTES = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, '0'));

/** Local `YYYY-MM-DD` for a Date. */
const isoDate = (date: Date) => toLocalDateValue(date);

/** Today as a local `YYYY-MM-DD`. */
const todayIso = () => isoDate(new Date());

/** Shift a `YYYY-MM-DD` by whole days, staying in local time. */
function addDays(dateIso: string, delta: number): string {
  const base = parseLocalDateTime(dateIso) ?? new Date();
  base.setDate(base.getDate() + delta);
  return isoDate(base);
}

/** First-of-month `{ year, month }` (0-based month) for a `YYYY-MM-DD`. */
function monthOf(dateIso: string): { year: number; month: number } {
  const d = parseLocalDateTime(dateIso) ?? new Date();
  return { year: d.getFullYear(), month: d.getMonth() };
}

export interface DateTimePickerProps {
  /** Base id; the trigger button gets it and the popover derives from it. */
  id: string;
  /** Field label. Pass the caller's own required-marker node if any. */
  label: ReactNode;
  /**
   * The field value: `''`, `'YYYY-MM-DD'`, or `'YYYY-MM-DDTHH:mm'` — always
   * local wall-clock, identical to the `DateTimeField` contract.
   */
  value: string;
  onChange: (next: string) => void;
  /** `'date'` hides the time controls; the value then stays `'YYYY-MM-DD'`. */
  mode?: 'datetime' | 'date';
  required?: boolean;
  disabled?: boolean;
  describedBy?: string;
  hint?: ReactNode;
  inputClassName?: string;
  labelClassName?: string;
  className?: string;
}

/**
 * A click-to-pick date (+ time) control: a trigger button that opens a calendar
 * popover you select a day from, plus 24-hour hour/minute dropdowns — no typing
 * required.
 *
 * ## Why a bespoke calendar instead of `<input type="date">`
 *
 * A native `type="date"`/`type="time"` renders its visible format from the
 * browser/OS locale (`dd/mm/yyyy`, a 12-hour clock, …) and no attribute can
 * force `YYYY-MM-DD` + 24h there — the same reason `DateTimeField` drives its
 * own text inputs. This control keeps that guarantee while giving back the
 * clickable calendar chrome: the calendar labels are painted from a fixed
 * `sv-SE` locale, but the *emitted value* is built from the picked calendar
 * cell, so it is always the locale-independent `YYYY-MM-DD` / `HH:mm` the admin
 * requires. It is a self-contained React component (no date-picker dependency,
 * no external script), so it adds nothing the admin CSP could reject.
 *
 * The value contract, and all date maths, go through `lib/datetime`
 * (`parseLocalDateTime` etc.), so the anchored local-wall-clock ↔ stored-UTC
 * invariants documented there hold unchanged.
 */
export function DateTimePicker({
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
}: DateTimePickerProps) {
  const [open, setOpen] = useState(false);
  const reactId = useId();
  const labelId = `${id}-label-${reactId}`;
  const dialogId = `${id}-dialog-${reactId}`;

  const selectedDate = datePartOf(value); // '' | 'YYYY-MM-DD'
  const selectedTime = timePartOf(value); // '' | 'HH:mm'
  const hasDate = selectedDate !== '';

  // The month the grid is showing, and the day that holds roving focus.
  const [view, setView] = useState(() => monthOf(selectedDate || todayIso()));
  const [focusDate, setFocusDate] = useState(() => selectedDate || todayIso());

  const wrapperRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const gridRef = useRef<HTMLDivElement>(null);

  // On open, snap the grid + roving focus to the selected day (or today).
  useEffect(() => {
    if (!open) return;
    const anchor = selectedDate || todayIso();
    setView(monthOf(anchor));
    setFocusDate(anchor);
  }, [open, selectedDate]);

  // Move DOM focus to the roving day whenever it changes while open.
  useEffect(() => {
    if (!open) return;
    const el = gridRef.current?.querySelector<HTMLButtonElement>(`[data-date="${focusDate}"]`);
    el?.focus();
  }, [focusDate, open]);

  // Close on outside click / Escape.
  useEffect(() => {
    if (!open) return;
    const onDocPointer = (e: MouseEvent) => {
      if (!wrapperRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onDocPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const emit = (next: string) => onChange(next);

  const pickDay = (dateIso: string) => {
    emit(mode === 'date' ? dateIso : withDatePart(value, dateIso));
    setFocusDate(dateIso);
    setView(monthOf(dateIso));
    if (mode === 'date') {
      setOpen(false);
      triggerRef.current?.focus();
    }
  };

  const setTime = (hh: string, mm: string) => {
    // A time needs a date to attach to; the selects are disabled without one.
    if (!hasDate) return;
    emit(withTimePart(value, `${hh}:${mm}`));
  };

  const clearValue = () => {
    emit('');
    setOpen(false);
    triggerRef.current?.focus();
  };

  const shiftView = (deltaMonths: number) => {
    const base = new Date(view.year, view.month + deltaMonths, 1);
    const next = { year: base.getFullYear(), month: base.getMonth() };
    setView(next);
    setFocusDate(isoDate(new Date(next.year, next.month, 1)));
  };

  const onGridKeyDown = (e: React.KeyboardEvent) => {
    // Monday-first weekday index (Mon = 0 … Sun = 6) of the roving focus day.
    const weekdayIndex = ((parseLocalDateTime(focusDate)?.getDay() ?? 1) + 6) % 7;
    let delta: number;
    switch (e.key) {
      case 'ArrowLeft':
        delta = -1;
        break;
      case 'ArrowRight':
        delta = 1;
        break;
      case 'ArrowUp':
        delta = -7;
        break;
      case 'ArrowDown':
        delta = 7;
        break;
      case 'Home':
        delta = -weekdayIndex;
        break;
      case 'End':
        delta = 6 - weekdayIndex;
        break;
      case 'PageUp':
        shiftView(-1);
        e.preventDefault();
        return;
      case 'PageDown':
        shiftView(1);
        e.preventDefault();
        return;
      default:
        return;
    }
    e.preventDefault();
    const next = addDays(focusDate, delta);
    setFocusDate(next);
    setView(monthOf(next));
  };

  // Build the 6-week (42-cell) grid, Monday-first.
  const firstOfMonth = new Date(view.year, view.month, 1);
  const leadingBlanks = (firstOfMonth.getDay() + 6) % 7; // Mon = 0
  const gridStart = new Date(view.year, view.month, 1 - leadingBlanks);
  const cells = Array.from({ length: 42 }, (_, i) => {
    const d = new Date(gridStart);
    d.setDate(gridStart.getDate() + i);
    return d;
  });

  const today = todayIso();

  const displayText =
    value === ''
      ? t('common.datePickerPlaceholder')
      : mode === 'date'
        ? selectedDate
        : `${selectedDate}${selectedTime ? ` ${selectedTime}` : ''}`;

  const timeParts = selectedTime ? selectedTime.split(':') : [];
  const selHour = timeParts[0] ?? '00';
  const selMinute = timeParts[1] ?? '00';

  return (
    <div className={[styles.wrapper, className].filter(Boolean).join(' ')} ref={wrapperRef}>
      {/* A real <label> for the trigger button (a labelable element), so the
          label is associated with the control and clickable to activate it. */}
      <label className={labelClassName} id={labelId} htmlFor={id}>
        {label}
      </label>
      <button
        type="button"
        ref={triggerRef}
        id={id}
        className={[inputClassName, styles.trigger].filter(Boolean).join(' ')}
        onClick={() => !disabled && setOpen((v) => !v)}
        disabled={disabled}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-labelledby={labelId}
        aria-required={required || undefined}
        aria-describedby={describedBy}
      >
        <span className={value === '' ? styles.placeholder : undefined}>{displayText}</span>
        <span aria-hidden="true" className={styles.calendarIcon}>
          📅
        </span>
      </button>

      {open && (
        <div
          className={styles.popover}
          role="dialog"
          aria-modal="false"
          aria-label={t('common.calendarLabel')}
          id={dialogId}
        >
          <div className={styles.calHeader}>
            <button
              type="button"
              className={styles.navBtn}
              onClick={() => shiftView(-1)}
              aria-label={t('common.previousMonth')}
            >
              ‹
            </button>
            <span className={styles.monthLabel} aria-live="polite">
              {monthLabel(view.year, view.month)}
            </span>
            <button
              type="button"
              className={styles.navBtn}
              onClick={() => shiftView(1)}
              aria-label={t('common.nextMonth')}
            >
              ›
            </button>
          </div>

          <div className={styles.weekRow} aria-hidden="true">
            {WEEKDAY_LABELS.map((w) => (
              <span key={w} className={styles.weekday}>
                {w}
              </span>
            ))}
          </div>

          <div className={styles.grid} role="grid" ref={gridRef} onKeyDown={onGridKeyDown}>
            {cells.map((d) => {
              const dIso = isoDate(d);
              const inMonth = d.getMonth() === view.month;
              const isSelected = dIso === selectedDate;
              const isToday = dIso === today;
              const isFocus = dIso === focusDate;
              return (
                <button
                  key={dIso}
                  type="button"
                  role="gridcell"
                  data-date={dIso}
                  tabIndex={isFocus ? 0 : -1}
                  aria-label={FULL_DATE_FORMAT.format(d)}
                  aria-selected={isSelected}
                  aria-current={isToday ? 'date' : undefined}
                  className={[
                    styles.day,
                    inMonth ? '' : styles.dayOutside,
                    isSelected ? styles.daySelected : '',
                    isToday && !isSelected ? styles.dayToday : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  onClick={() => pickDay(dIso)}
                >
                  {d.getDate()}
                </button>
              );
            })}
          </div>

          {mode !== 'date' && (
            <div className={styles.timeRow}>
              <label className={styles.timeLabel}>
                {t('common.hourLabel')}
                <select
                  className={styles.timeSelect}
                  value={selHour}
                  disabled={!hasDate}
                  onChange={(e) => setTime(e.target.value, selMinute)}
                >
                  {HOURS.map((h) => (
                    <option key={h} value={h}>
                      {h}
                    </option>
                  ))}
                </select>
              </label>
              <span aria-hidden="true" className={styles.timeColon}>
                :
              </span>
              <label className={styles.timeLabel}>
                {t('common.minuteLabel')}
                <select
                  className={styles.timeSelect}
                  value={selMinute}
                  disabled={!hasDate}
                  onChange={(e) => setTime(selHour, e.target.value)}
                >
                  {MINUTES.map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          )}

          <div className={styles.footer}>
            <button type="button" className={styles.footerBtn} onClick={clearValue}>
              {t('common.clear')}
            </button>
            <button
              type="button"
              className={styles.footerBtnPrimary}
              onClick={() => {
                setOpen(false);
                triggerRef.current?.focus();
              }}
            >
              {t('common.done')}
            </button>
          </div>
        </div>
      )}
      {hint}
    </div>
  );
}
