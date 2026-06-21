'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { EventForm } from '@/components/events/EventForm';
import { EventStatusBadge } from '@/components/events/EventStatusBadge';
import { CancelDialog, PublishDialog } from '@/components/events/EventDialogs';
import {
  cancelAdminEvent,
  loadAdminEvent,
  publishAdminEvent,
  updateAdminEvent,
  type AdminEventDetail,
  type ApiError,
  type UpdateEventRequest,
} from '@/features/events';
import { translate } from '@/i18n';
import { formatDate } from '@/lib';
import styles from '../new/page.module.css';

const t = (key: string) => translate('sv', key);

export default function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const router = useRouter();

  const [event, setEvent] = useState<AdminEventDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [isUpdating, setIsUpdating] = useState(false);

  const [showPublish, setShowPublish] = useState(false);
  const [publishLoading, setPublishLoading] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);

  const [showCancel, setShowCancel] = useState(false);
  const [cancelLoading, setCancelLoading] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const publishingRef = useRef(false);
  const cancellingRef = useRef(false);

  const fetchEvent = useCallback(async () => {
    if (!eventId) return;
    setLoading(true);
    setError(null);
    try {
      const result = await loadAdminEvent(eventId);
      setEvent(result.data.event);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message ?? t('events.loadError'));
    } finally {
      setLoading(false);
    }
  }, [eventId]);

  useEffect(() => {
    void fetchEvent();
  }, [fetchEvent]);

  async function handleUpdate(data: UpdateEventRequest) {
    if (!eventId || !event) return;
    setIsUpdating(true);
    setUpdateError(null);
    try {
      const result = await updateAdminEvent(eventId, data);
      setEvent(result.data.event);
    } catch (err) {
      const apiErr = err as ApiError;
      setUpdateError(apiErr.message ?? t('events.updateError'));
    } finally {
      setIsUpdating(false);
    }
  }

  async function handlePublishConfirm() {
    if (!event || publishingRef.current) return;
    publishingRef.current = true;
    setPublishLoading(true);
    setPublishError(null);
    try {
      const result = await publishAdminEvent(event.id);
      setEvent(result.data.event);
      setShowPublish(false);
    } catch (err) {
      const apiErr = err as ApiError;
      setPublishError(apiErr.message ?? t('events.publish.error'));
    } finally {
      setPublishLoading(false);
      publishingRef.current = false;
    }
  }

  async function handleCancelConfirm(reason: string) {
    if (!event || cancellingRef.current) return;
    cancellingRef.current = true;
    setCancelLoading(true);
    setCancelError(null);
    try {
      const result = await cancelAdminEvent(event.id, { reason });
      setEvent(result.data.event);
      setShowCancel(false);
    } catch (err) {
      const apiErr = err as ApiError;
      setCancelError(apiErr.message ?? t('events.cancelEvent.error'));
    } finally {
      setCancelLoading(false);
      cancellingRef.current = false;
    }
  }

  if (loading) {
    return (
      <div className={styles.page}>
        <Link href="/events" className={styles.backLink}>← {t('events.backToList')}</Link>
        <div className={styles.loadingState} aria-live="polite" aria-busy="true">{t('events.loading')}</div>
      </div>
    );
  }

  if (error || !event) {
    return (
      <div className={styles.page}>
        <Link href="/events" className={styles.backLink}>← {t('events.backToList')}</Link>
        <div className={styles.errorState} role="alert">
          {error ?? t('events.notFound')}
        </div>
      </div>
    );
  }

  const canEdit = event.status === 'draft' || event.status === 'published';
  const canPublish = event.status === 'draft';
  const canCancel = event.status === 'draft' || event.status === 'published';

  return (
    <div className={styles.page}>
      <Link href="/events" className={styles.backLink}>← {t('events.backToList')}</Link>

      <div className={styles.header}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)', flexWrap: 'wrap' }}>
          <h1 className={styles.title}>{event.title}</h1>
          <EventStatusBadge status={event.status} />
        </div>

        <div style={{ display: 'flex', gap: 'var(--space-3)', marginTop: 'var(--space-3)', flexWrap: 'wrap' }}>
          {canPublish && (
            <button
              type="button"
              style={{
                padding: 'var(--space-2) var(--space-5)',
                backgroundColor: 'var(--accent)',
                color: 'var(--color-ink)',
                border: 'none',
                borderRadius: 'var(--radius-sm)',
                fontSize: 'var(--text-sm)',
                fontWeight: 'var(--fw-semibold)',
                cursor: 'pointer',
              }}
              onClick={() => { setPublishError(null); setShowPublish(true); }}
            >
              {t('events.publish.button')}
            </button>
          )}
          {canCancel && (
            <button
              type="button"
              style={{
                padding: 'var(--space-2) var(--space-5)',
                backgroundColor: 'transparent',
                color: 'var(--color-error)',
                border: '1px solid rgba(200,50,50,0.4)',
                borderRadius: 'var(--radius-sm)',
                fontSize: 'var(--text-sm)',
                fontWeight: 'var(--fw-medium)',
                cursor: 'pointer',
              }}
              onClick={() => { setCancelError(null); setShowCancel(true); }}
            >
              {t('events.cancelEvent.title')}
            </button>
          )}
        </div>
      </div>

      {event.status === 'cancelled' && (
        <div className={styles.cancelledNotice} role="note">
          {t('events.cancelledNotice.intro')}{event.cancelledAt ? ` ${formatDate(event.cancelledAt)}` : ''}. {t('events.cancelledNotice.details')}
        </div>
      )}

      <div className={styles.metaSection} aria-label="Eventinformation">
        <div className={styles.metaRow}>
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>{t('events.meta.createdBy')}</span>
            <span className={styles.metaValue}>{event.createdByUserId ?? '—'}</span>
          </div>
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>{t('events.meta.createdAt')}</span>
            <span className={styles.metaValue}>{formatDate(event.createdAt)}</span>
          </div>
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>{t('events.meta.updatedAt')}</span>
            <span className={styles.metaValue}>{formatDate(event.updatedAt)}</span>
          </div>
        </div>
      </div>

      <h2 className={styles.sectionTitle}>{t('events.rsvpSection')}</h2>
      <div className={styles.rsvpGrid} aria-label="RSVP-sammanfattning">
        <div className={styles.rsvpCard}>
          <div className={styles.rsvpCount}>{event.rsvpCounts.going}</div>
          <div className={styles.rsvpLabel}>{t('events.rsvp.going')}</div>
        </div>
        <div className={styles.rsvpCard}>
          <div className={styles.rsvpCount}>{event.rsvpCounts.maybe}</div>
          <div className={styles.rsvpLabel}>{t('events.rsvp.maybe')}</div>
        </div>
        <div className={styles.rsvpCard}>
          <div className={styles.rsvpCount}>{event.rsvpCounts.not_going}</div>
          <div className={styles.rsvpLabel}>{t('events.rsvp.notGoing')}</div>
        </div>
      </div>

      {canEdit ? (
        <>
          <h2 className={styles.sectionTitle}>{t('events.editEvent')}</h2>
          <EventForm
            initialData={event}
            onSubmit={handleUpdate}
            onCancel={() => router.push('/events')}
            isSubmitting={isUpdating}
            submitError={updateError}
          />
        </>
      ) : (
        <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)', marginTop: 'var(--space-6)' }}>
          {t('events.noEdit')}
        </p>
      )}

      {showPublish && (
        <PublishDialog
          event={event}
          onConfirm={handlePublishConfirm}
          onClose={() => setShowPublish(false)}
          isSubmitting={publishLoading}
          error={publishError}
        />
      )}

      {showCancel && (
        <CancelDialog
          event={event}
          onConfirm={handleCancelConfirm}
          onClose={() => setShowCancel(false)}
          isSubmitting={cancelLoading}
          error={cancelError}
        />
      )}
    </div>
  );
}
