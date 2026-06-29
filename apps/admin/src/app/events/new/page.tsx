'use client';

import { Link } from 'react-router-dom';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { EventForm } from '@/components/events/EventForm';
import { createAdminEvent, type ApiError, type CreateEventRequest, type UpdateEventRequest } from '@/features/events';
import { translate } from '@/i18n';
import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

export default function NewEventPage() {
  const navigate = useNavigate();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  async function handleSubmit(data: CreateEventRequest | UpdateEventRequest) {
    setIsSubmitting(true);
    setSubmitError(null);
    try {
      const result = await createAdminEvent(data as CreateEventRequest);
      navigate(`/events/${result.data.event.id}`);
    } catch (err) {
      const apiErr = err as ApiError;
      setSubmitError(apiErr.message ?? t('events.createError'));
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <Link to="/events" className={styles.backLink}>
            ← {t('events.backToList')}
          </Link>
          <h1 className={styles.title}>{t('events.createEvent')}</h1>
          <p className={styles.subtitle}>{t('events.form.createSubtitle')}</p>
        </div>
      </div>

      <EventForm
        onSubmit={handleSubmit}
        onCancel={() => navigate('/events')}
        isSubmitting={isSubmitting}
        submitError={submitError}
      />
    </div>
  );
}
