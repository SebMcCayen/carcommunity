'use client';

import { useEffect, useRef, useState } from 'react';
import type { AdminEventDetail, CreateEventRequest, UpdateEventRequest } from '@/features/events';
import { translate } from '@/i18n';
import styles from './EventForm.module.css';

const t = (key: string) => translate('sv', key);

interface EventFormData {
  title: string;
  summary: string;
  description: string;
  startsAt: string;
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
    startsAt: event.startsAt ? event.startsAt.slice(0, 16) : '',
    endsAt: event.endsAt ? event.endsAt.slice(0, 16) : '',
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
    isDirtyRef.current = true;
  }, [form]);

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
    setForm((prev) => ({ ...prev, [field]: value }));
    setClientErrors((prev) => ({ ...prev, [field]: undefined }));
  }

  function validate(): boolean {
    const errors: Partial<Record<keyof EventFormData, string>> = {};

    if (!form.title.trim()) {
      errors.title = 'Titel krävs.';
    } else if (form.title.length > 200) {
      errors.title = 'Titel får inte vara längre än 200 tecken.';
    }

    if (!form.startsAt) {
      errors.startsAt = 'Starttid krävs.';
    }

    if (!form.approximateArea.trim()) {
      errors.approximateArea = 'Område krävs.';
    }

    if (form.endsAt && form.startsAt && new Date(form.endsAt) <= new Date(form.startsAt)) {
      errors.endsAt = 'Sluttid måste vara efter starttid.';
    }

    const hasLat = form.latitude.trim() !== '';
    const hasLon = form.longitude.trim() !== '';
    if (hasLat !== hasLon) {
      errors.latitude = 'Latitud och longitud måste anges båda eller ingen.';
    }

    if (hasLat) {
      const lat = Number(form.latitude);
      if (isNaN(lat) || lat < -90 || lat > 90) {
        errors.latitude = 'Latitud måste vara mellan -90 och 90.';
      }
    }

    if (hasLon) {
      const lon = Number(form.longitude);
      if (isNaN(lon) || lon < -180 || lon > 180) {
        errors.longitude = 'Longitud måste vara mellan -180 och 180.';
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

    if (isEdit) {
      const data: UpdateEventRequest = {
        title: form.title.trim() || undefined,
        summary: form.summary.trim() || null,
        description: form.description.trim() || null,
        startsAt: form.startsAt ? new Date(form.startsAt).toISOString() : undefined,
        endsAt: form.endsAt ? new Date(form.endsAt).toISOString() : null,
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
        startsAt: new Date(form.startsAt).toISOString(),
        endsAt: form.endsAt ? new Date(form.endsAt).toISOString() : null,
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

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-starts-at">
          {t('events.form.startsAtLabel')}
          <span className={styles.required} aria-hidden="true">*</span>
        </label>
        <input
          id="ev-starts-at"
          className={styles.input}
          type="datetime-local"
          value={form.startsAt}
          onChange={(e) => handleChange('startsAt', e.target.value)}
          disabled={isSubmitting}
          aria-required="true"
          aria-describedby={clientErrors.startsAt ? 'ev-starts-at-error' : undefined}
        />
        {clientErrors.startsAt && (
          <span id="ev-starts-at-error" className={styles.fieldError} role="alert">{clientErrors.startsAt}</span>
        )}
      </div>

      <div className={styles.fieldGroup}>
        <label className={styles.label} htmlFor="ev-ends-at">
          {t('events.form.endsAtLabel')}
        </label>
        <input
          id="ev-ends-at"
          className={styles.input}
          type="datetime-local"
          value={form.endsAt}
          onChange={(e) => handleChange('endsAt', e.target.value)}
          disabled={isSubmitting}
          aria-describedby={clientErrors.endsAt ? 'ev-ends-at-error' : undefined}
        />
        {clientErrors.endsAt && (
          <span id="ev-ends-at-error" className={styles.fieldError} role="alert">{clientErrors.endsAt}</span>
        )}
      </div>

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
