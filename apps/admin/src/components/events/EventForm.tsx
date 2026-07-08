'use client';

import { useEffect, useRef, useState } from 'react';
import type { AdminEventDetail, CreateEventRequest, UpdateEventRequest } from '@/features/events';
import { translate } from '@/i18n';
import styles from './EventForm.module.css';

const t = (key: string) => translate('sv', key);

/** Length of 'YYYY-MM-DDTHH:mm' — the format required by HTML datetime-local inputs. */
const DATETIME_LOCAL_LENGTH = 16;

/** Maximum allowed event duration: an end may be at most 3 days after the start. */
const MAX_EVENT_DURATION_MS = 3 * 24 * 60 * 60 * 1000;

/** Default time used when a date is picked without an accompanying time. */
const DEFAULT_TIME = '00:00';

function toLocalDateTimeValue(value: string | null | undefined): string {
  if (!value) {
    return '';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }

  const pad = (part: number) => String(part).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  ).slice(0, DATETIME_LOCAL_LENGTH);
}

/** Split a 'YYYY-MM-DDTHH:mm' value into its date ('YYYY-MM-DD') part. */
function datePartOf(value: string): string {
  return value ? value.slice(0, 10) : '';
}

/** Split a 'YYYY-MM-DDTHH:mm' value into its time ('HH:mm') part. */
function timePartOf(value: string): string {
  return value.length >= DATETIME_LOCAL_LENGTH ? value.slice(11, 16) : '';
}

/**
 * Combine separate date and time inputs back into a 'YYYY-MM-DDTHH:mm' string.
 * A date without a time defaults the time to 00:00; without a date there is no
 * value at all.
 */
function combineDateTime(date: string, time: string): string {
  if (!date) {
    return '';
  }
  return `${date}T${time || DEFAULT_TIME}`;
}

/**
 * Whether `endLocal` is more than 3 days (72h) after `startLocal`. Both are
 * 'YYYY-MM-DDTHH:mm' local-time strings. Returns false if either is unset or
 * unparseable (other validations cover those cases).
 */
export function exceedsMaxEventDuration(startLocal: string, endLocal: string): boolean {
  if (!startLocal || !endLocal) {
    return false;
  }
  const start = new Date(startLocal).getTime();
  const end = new Date(endLocal).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return false;
  }
  return end - start > MAX_EVENT_DURATION_MS;
}

interface EventFormData {
  title: string;
  summary: string;
  description: string;
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
  approximateArea: string;
  locationName: string;
  address: string;
  latitude: string;
  longitude: string;
  isOfficial: boolean;
}

interface EventFormProps {
  /** When set, the form is in edit mode. */
  initialData?: AdminEventDetail;
  onSubmit: (data: CreateEventRequest | UpdateEventRequest) => Promise<void>;
  onCancel?: () => void;
  isSubmitting?: boolean;
  submitError?: string | null;
}

function toFormData(event?: AdminEventDetail): EventFormData {
  if (!event) {
    return {
      title: '',
      summary: '',
      description: '',
      startDate: '',
      startTime: '',
      endDate: '',
      endTime: '',
      approximateArea: '',
      locationName: '',
      address: '',
      latitude: '',
      longitude: '',
      isOfficial: false,
    };
  }

  const startsAtLocal = toLocalDateTimeValue(event.startsAt);
  const endsAtLocal = toLocalDateTimeValue(event.endsAt);

  return {
    title: event.title,
    summary: event.summary ?? '',
    description: event.description ?? '',
    startDate: datePartOf(startsAtLocal),
    startTime: timePartOf(startsAtLocal),
    endDate: datePartOf(endsAtLocal),
    endTime: timePartOf(endsAtLocal),
    approximateArea: event.approximateArea,
    locationName: event.locationName ?? '',
    address: event.address ?? '',
    latitude: event.latitude != null ? String(event.latitude) : '',
    longitude: event.longitude != null ? String(event.longitude) : '',
    isOfficial: event.isOfficial,
  };
}

/**
 * Reusable form for creating and editing events.
 *
 * Client-side validation improves user experience, but the backend is the
 * authoritative validator. Backend errors are surfaced via submitError.
 */
export function EventForm({ initialData, onSubmit, onCancel, isSubmitting, submitError }: EventFormProps) {
  const isEdit = Boolean(initialData);
  const [form, setForm] = useState<EventFormData>(() => toFormData(initialData));
  const [clientErrors, setClientErrors] = useState<Partial<Record<keyof EventFormData, string>>>({});
  const isDirtyRef = useRef(false);

  useEffect(() => {
    setForm(toFormData(initialData));
    setClientErrors({});
    isDirtyRef.current = false;
  }, [initialData]);

  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (isDirtyRef.current) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, []);

  function handleChange(field: keyof EventFormData, value: string | boolean) {
    setForm((prev) => {
      if (prev[field] === value) {
        return prev;
      }
      isDirtyRef.current = true;
      return { ...prev, [field]: value };
    });
    setClientErrors((prev) => ({ ...prev, [field]: undefined }));
  }

  function validate(): boolean {
    const errors: Partial<Record<keyof EventFormData, string>> = {};

    if (!form.title.trim()) {
      errors.title = t('events.form.validation.titleRequired');
    } else if (form.title.length > 200) {
      errors.title = t('events.form.validation.titleTooLong');
    }

    const startsAt = combineDateTime(form.startDate, form.startTime);
    const endsAt = combineDateTime(form.endDate, form.endTime);

    if (!form.startDate) {
      errors.startDate = t('events.form.validation.startsAtRequired');
    }

    if (!form.approximateArea.trim()) {
      errors.approximateArea = t('events.form.validation.approximateAreaRequired');
    }

    if (endsAt && startsAt && new Date(endsAt) <= new Date(startsAt)) {
      errors.endDate = t('events.form.validation.endsAtAfterStartsAt');
    } else if (exceedsMaxEventDuration(startsAt, endsAt)) {
      errors.endDate = t('events.form.validation.endsAtTooFarFromStart');
    }

    const hasLat = form.latitude.trim() !== '';
    const hasLon = form.longitude.trim() !== '';
    if (hasLat !== hasLon) {
      errors.latitude = t('events.form.validation.coordinatesBothOrNone');
    }

    if (hasLat) {
      const lat = Number(form.latitude);
      if (isNaN(lat) || lat < -90 || lat > 90) {
        errors.latitude = t('events.form.validation.latitudeInvalid');
      }
    }

    if (hasLon) {
      const lon = Number(form.longitude);
      if (isNaN(lon) || lon < -180 || lon > 180) {
        errors.longitude = t('events.form.validation.longitudeInvalid');
      }
    }

    setClientErrors(errors);
    return Object.keys(errors).length === 0;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!validate()) return;

    const lat = form.latitude.trim() !== '' ? Number(form.latitude) : null;
    const lon = form.longitude.trim() !== '' ? Number(form.longitude) : null;

    const startsAtLocal = combineDateTime(form.startDate, form.startTime);
    const endsAtLocal = combineDateTime(form.endDate, form.endTime);

    if (isEdit) {
      const data: UpdateEventRequest = {
        title: form.title.trim() || undefined,
        summary: form.summary.trim() || null,
        description: form.description.trim() || null,
        startsAt: startsAtLocal ? new Date(startsAtLocal).toISOString() : undefined,
        endsAt: endsAtLocal ? new Date(endsAtLocal).toISOString() : null,
        approximateArea: form.approximateArea.trim() || undefined,
        locationName: form.locationName.trim() || null,
        address: form.address.trim() || null,
        latitude: lat,
        longitude: lon,
        isOfficial: form.isOfficial,
      };
      await onSubmit(data);
    } else {
      const data: CreateEventRequest = {
        title: form.title.trim(),
        summary: form.summary.trim() || null,
        description: form.description.trim() || null,
        startsAt: new Date(startsAtLocal).toISOString(),
        endsAt: endsAtLocal ? new Date(endsAtLocal).toISOString() : null,
        approximateArea: form.approximateArea.trim(),
        locationName: form.locationName.trim() || null,
        address: form.address.trim() || null,
        latitude: lat,
        longitude: lon,
        isOfficial: form.isOfficial,
      };
      await onSubmit(data);
    }

    isDirtyRef.current = false;
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} noValidate>
      {submitError && (
        <div className={styles.errorBanner} role="alert">
          {submitError}
        </div>
      )}

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-title">
          {t('events.form.titleLabel')}
          <span className={styles.required} aria-hidden="true">*</span>
        </label>
        <input
          id="ev-title"
          className={styles.input}
          type="text"
          maxLength={200}
          value={form.title}
          onChange={(e) => handleChange('title', e.target.value)}
          disabled={isSubmitting}
          aria-required="true"
          aria-describedby={clientErrors.title ? 'ev-title-error' : undefined}
        />
        {clientErrors.title && (
          <span id="ev-title-error" className={styles.fieldError} role="alert">{clientErrors.title}</span>
        )}
      </div>

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-summary">
          {t('events.form.summaryLabel')}
        </label>
        <textarea
          id="ev-summary"
          className={styles.textarea}
          maxLength={2000}
          rows={2}
          value={form.summary}
          onChange={(e) => handleChange('summary', e.target.value)}
          disabled={isSubmitting}
        />
      </div>

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-description">
          {t('events.form.descriptionLabel')}
        </label>
        <textarea
          id="ev-description"
          className={styles.textarea}
          maxLength={10000}
          rows={5}
          value={form.description}
          onChange={(e) => handleChange('description', e.target.value)}
          disabled={isSubmitting}
        />
      </div>

      <fieldset className={styles.dateTimeFieldset}>
        <legend className={styles.label}>
          {t('events.form.startsAtLabel')}
          <span className={styles.required} aria-hidden="true">*</span>
        </legend>
        <div className={styles.dateTimeRow}>
          <div className={styles.fieldGroup}>
            <label className={styles.subLabel} htmlFor="ev-start-date">
              {t('events.form.dateLabel')}
            </label>
            <input
              id="ev-start-date"
              className={styles.input}
              type="date"
              value={form.startDate}
              onChange={(e) => handleChange('startDate', e.target.value)}
              disabled={isSubmitting}
              aria-required="true"
              aria-describedby={clientErrors.startDate ? 'ev-start-error' : undefined}
            />
          </div>
          <div className={styles.fieldGroup}>
            <label className={styles.subLabel} htmlFor="ev-start-time">
              {t('events.form.timeLabel')}
            </label>
            <input
              id="ev-start-time"
              className={styles.input}
              type="time"
              value={form.startTime}
              onChange={(e) => handleChange('startTime', e.target.value)}
              disabled={isSubmitting}
              aria-describedby={clientErrors.startDate ? 'ev-start-error' : undefined}
            />
          </div>
        </div>
        {clientErrors.startDate && (
          <span id="ev-start-error" className={styles.fieldError} role="alert">{clientErrors.startDate}</span>
        )}
      </fieldset>

      <fieldset className={styles.dateTimeFieldset}>
        <legend className={styles.label}>
          {t('events.form.endsAtLabel')}
        </legend>
        <div className={styles.dateTimeRow}>
          <div className={styles.fieldGroup}>
            <label className={styles.subLabel} htmlFor="ev-end-date">
              {t('events.form.dateLabel')}
            </label>
            <input
              id="ev-end-date"
              className={styles.input}
              type="date"
              value={form.endDate}
              onChange={(e) => handleChange('endDate', e.target.value)}
              disabled={isSubmitting}
              aria-describedby={clientErrors.endDate ? 'ev-end-error' : undefined}
            />
          </div>
          <div className={styles.fieldGroup}>
            <label className={styles.subLabel} htmlFor="ev-end-time">
              {t('events.form.timeLabel')}
            </label>
            <input
              id="ev-end-time"
              className={styles.input}
              type="time"
              value={form.endTime}
              onChange={(e) => handleChange('endTime', e.target.value)}
              disabled={isSubmitting}
              aria-describedby={clientErrors.endDate ? 'ev-end-error' : undefined}
            />
          </div>
        </div>
        {clientErrors.endDate && (
          <span id="ev-end-error" className={styles.fieldError} role="alert">{clientErrors.endDate}</span>
        )}
      </fieldset>

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-area">
          {t('events.form.approximateAreaLabel')}
          <span className={styles.required} aria-hidden="true">*</span>
        </label>
        <input
          id="ev-area"
          className={styles.input}
          type="text"
          maxLength={200}
          value={form.approximateArea}
          onChange={(e) => handleChange('approximateArea', e.target.value)}
          disabled={isSubmitting}
          aria-required="true"
          aria-describedby={clientErrors.approximateArea ? 'ev-area-error' : undefined}
        />
        {clientErrors.approximateArea && (
          <span id="ev-area-error" className={styles.fieldError} role="alert">{clientErrors.approximateArea}</span>
        )}
      </div>

      <div className={styles.checkboxRow}>
        <input
          id="ev-official"
          type="checkbox"
          checked={form.isOfficial}
          onChange={(e) => handleChange('isOfficial', e.target.checked)}
          disabled={isSubmitting}
        />
        <label className={styles.checkboxLabel} htmlFor="ev-official">
          {t('events.form.isOfficialLabel')}
        </label>
      </div>

      <hr className={styles.sectionDivider} />

      <div className={styles.locationNotice} role="note">
        <span aria-hidden="true">🔒</span>
        <span>{t('events.form.locationNotice')}</span>
      </div>

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-location-name">
          {t('events.form.locationNameLabel')}
        </label>
        <input
          id="ev-location-name"
          className={styles.input}
          type="text"
          maxLength={200}
          value={form.locationName}
          onChange={(e) => handleChange('locationName', e.target.value)}
          disabled={isSubmitting}
        />
      </div>

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-address">
          {t('events.form.addressLabel')}
        </label>
        <input
          id="ev-address"
          className={styles.input}
          type="text"
          maxLength={400}
          value={form.address}
          onChange={(e) => handleChange('address', e.target.value)}
          disabled={isSubmitting}
        />
      </div>

      <div className={styles.coordinateRow}>
        <div className={styles.fieldGroup}>
          <label className={styles.label} htmlFor="ev-lat">
            {t('events.form.latitudeLabel')}
          </label>
          <input
            id="ev-lat"
            className={styles.input}
            type="number"
            step="any"
            min={-90}
            max={90}
            value={form.latitude}
            onChange={(e) => handleChange('latitude', e.target.value)}
            disabled={isSubmitting}
            aria-describedby={clientErrors.latitude ? 'ev-lat-error' : undefined}
          />
          {clientErrors.latitude && (
            <span id="ev-lat-error" className={styles.fieldError} role="alert">{clientErrors.latitude}</span>
          )}
        </div>

        <div className={styles.fieldGroup}>
          <label className={styles.label} htmlFor="ev-lon">
            {t('events.form.longitudeLabel')}
          </label>
          <input
            id="ev-lon"
            className={styles.input}
            type="number"
            step="any"
            min={-180}
            max={180}
            value={form.longitude}
            onChange={(e) => handleChange('longitude', e.target.value)}
            disabled={isSubmitting}
            aria-describedby={clientErrors.longitude ? 'ev-lon-error' : undefined}
          />
          {clientErrors.longitude && (
            <span id="ev-lon-error" className={styles.fieldError} role="alert">{clientErrors.longitude}</span>
          )}
        </div>
      </div>

      <div className={styles.formActions}>
        <button type="submit" className={styles.buttonPrimary} disabled={isSubmitting}>
          {isSubmitting
            ? isEdit ? t('events.form.saving') : t('events.form.creating')
            : isEdit ? t('events.form.save') : t('events.form.create')}
        </button>
        {onCancel && (
          <button type="button" className={styles.buttonSecondary} onClick={onCancel} disabled={isSubmitting}>
            {t('events.publish.cancel')}
          </button>
        )}
      </div>
    </form>
  );
}
