'use client';

import { useEffect, useRef, useState } from 'react';
import { DateTimeField } from '@/components/ui/DateTimeField';
import { MapLocationPicker } from '@/components/map/MapLocationPicker';
import type { AdminEventDetail, CreateEventRequest, UpdateEventRequest } from '@/features/events';
import { translate } from '@/i18n';
import {
  completeDateTime,
  localToIso,
  parseLocalDateTime,
  toLocalDateTimeValue,
} from '@/lib/datetime';
import styles from './EventForm.module.css';

const t = (key: string) => translate('sv', key);

/** Maximum allowed event duration: an end may be at most 3 days after the start. */
const MAX_EVENT_DURATION_MS = 3 * 24 * 60 * 60 * 1000;

/**
 * Whether `endLocal` is more than 3 days (72h) after `startLocal`. Both are
 * 'YYYY-MM-DDTHH:mm' local-time strings. Returns false if either is unset or
 * unparseable (other validations cover those cases).
 */
export function exceedsMaxEventDuration(startLocal: string, endLocal: string): boolean {
  const start = parseLocalDateTime(startLocal);
  const end = parseLocalDateTime(endLocal);
  if (!start || !end) {
    return false;
  }
  return end.getTime() - start.getTime() > MAX_EVENT_DURATION_MS;
}

interface EventFormData {
  title: string;
  summary: string;
  description: string;
  /** Local `YYYY-MM-DD[THH:mm]` field value — see `lib/datetime`. */
  startsAt: string;
  /** Local `YYYY-MM-DD[THH:mm]` field value; empty means "no end". */
  endsAt: string;
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
      startsAt: '',
      endsAt: '',
      approximateArea: '',
      locationName: '',
      address: '',
      latitude: '',
      longitude: '',
      isOfficial: false,
    };
  }

  return {
    title: event.title,
    summary: event.summary ?? '',
    description: event.description ?? '',
    startsAt: toLocalDateTimeValue(event.startsAt),
    endsAt: toLocalDateTimeValue(event.endsAt),
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
    setClientErrors((prev) => {
      const next: Partial<Record<keyof EventFormData, string>> = { ...prev, [field]: undefined };
      // The cross-field end validation (end-after-start / max-duration) is keyed
      // under `endsAt`, but it depends on both the start and the end. Clear that
      // stale error when editing either of the participating fields.
      if (field === 'startsAt' || field === 'endsAt') {
        next.endsAt = undefined;
      }
      return next;
    });
  }

  function validate(): boolean {
    const errors: Partial<Record<keyof EventFormData, string>> = {};

    if (!form.title.trim()) {
      errors.title = t('events.form.validation.titleRequired');
    } else if (form.title.length > 200) {
      errors.title = t('events.form.validation.titleTooLong');
    }

    const startsAt = completeDateTime(form.startsAt);
    const endsAt = completeDateTime(form.endsAt);
    const startsAtDate = parseLocalDateTime(startsAt);
    const endsAtDate = parseLocalDateTime(endsAt);

    // An unparseable start counts as missing: it can come from a malformed
    // stored value loaded into edit mode, and it must not reach `toISOString`.
    if (!startsAtDate) {
      errors.startsAt = t('events.form.validation.startsAtRequired');
    }

    if (!form.approximateArea.trim()) {
      errors.approximateArea = t('events.form.validation.approximateAreaRequired');
    }

    if (endsAtDate && startsAtDate && endsAtDate.getTime() <= startsAtDate.getTime()) {
      errors.endsAt = t('events.form.validation.endsAtAfterStartsAt');
    } else if (exceedsMaxEventDuration(startsAt, endsAt)) {
      errors.endsAt = t('events.form.validation.endsAtTooFarFromStart');
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

    // `validate()` has already rejected an unparseable start, so a null here
    // would mean a logic error rather than operator input; bail rather than
    // send a request with a missing/shifted instant.
    const startsAtIso = localToIso(completeDateTime(form.startsAt));
    const endsAtIso = localToIso(completeDateTime(form.endsAt));
    if (!startsAtIso) return;

    if (isEdit) {
      const data: UpdateEventRequest = {
        title: form.title.trim() || undefined,
        summary: form.summary.trim() || null,
        description: form.description.trim() || null,
        startsAt: startsAtIso,
        endsAt: endsAtIso,
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
        startsAt: startsAtIso,
        endsAt: endsAtIso,
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

      <DateTimeField
        id="ev-start"
        label={
          <>
            {t('events.form.startsAtLabel')}
            <span className={styles.required} aria-hidden="true">*</span>
          </>
        }
        labelClassName={styles.label}
        inputClassName={styles.input}
        value={form.startsAt}
        onChange={(next) => handleChange('startsAt', next)}
        required
        disabled={isSubmitting}
        describedBy={clientErrors.startsAt ? 'ev-start-error' : undefined}
        hint={
          clientErrors.startsAt ? (
            <span id="ev-start-error" className={styles.fieldError} role="alert">{clientErrors.startsAt}</span>
          ) : null
        }
      />

      <DateTimeField
        id="ev-end"
        label={t('events.form.endsAtLabel')}
        labelClassName={styles.label}
        inputClassName={styles.input}
        value={form.endsAt}
        onChange={(next) => handleChange('endsAt', next)}
        disabled={isSubmitting}
        describedBy={clientErrors.endsAt ? 'ev-end-error' : undefined}
        hint={
          clientErrors.endsAt ? (
            <span id="ev-end-error" className={styles.fieldError} role="alert">{clientErrors.endsAt}</span>
          ) : null
        }
      />


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

      <MapLocationPicker
        latitude={form.latitude}
        longitude={form.longitude}
        onChange={(latitude, longitude) => {
          handleChange('latitude', latitude);
          handleChange('longitude', longitude);
        }}
        labelLat={t('events.form.latitudeLabel')}
        labelLng={t('events.form.longitudeLabel')}
        helpText={t('map.dragHint')}
        unavailableText={t('map.unavailable')}
        disabled={isSubmitting}
        error={clientErrors.latitude ?? clientErrors.longitude}
        labelClassName={styles.label}
        inputClassName={styles.input}
      />

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
