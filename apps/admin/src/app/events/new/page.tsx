'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { EventForm } from '@/components/events/EventForm';
import { createAdminEvent, type ApiError, type CreateEventRequest, type UpdateEventRequest } from '@/features/events';
import styles from './page.module.css';

export default function NewEventPage() {
  const router = useRouter();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  async function handleSubmit(data: CreateEventRequest | UpdateEventRequest) {
    setIsSubmitting(true);
    setSubmitError(null);
    try {
      const result = await createAdminEvent(data as CreateEventRequest);
      router.push(`/events/${result.data.event.id}`);
    } catch (err) {
      const apiErr = err as ApiError;
      setSubmitError(apiErr.message ?? 'Kunde inte skapa eventet. Försök igen.');
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <Link href="/events" className={styles.backLink}>
            ← Tillbaka till eventlistan
          </Link>
          <h1 className={styles.title}>Skapa event</h1>
          <p className={styles.subtitle}>Eventet sparas som utkast. Publicera det manuellt när det är klart.</p>
        </div>
      </div>

      <EventForm
        onSubmit={handleSubmit}
        onCancel={() => router.push('/events')}
        isSubmitting={isSubmitting}
        submitError={submitError}
      />
    </div>
  );
}
