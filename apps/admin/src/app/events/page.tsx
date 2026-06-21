'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  cancelAdminEvent,
  loadAdminEvents,
  publishAdminEvent,
  type AdminEventSummary,
  type ApiError,
  type EventStatus,
} from '@/features/events';
import { EventStatusBadge } from '@/components/events/EventStatusBadge';
import { CancelDialog } from '@/components/events/EventDialogs';
import { PublishDialog } from '@/components/events/EventDialogs';
import { translate } from '@/i18n';
import { formatDate } from '@/lib';
import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

type FilterStatus = EventStatus | 'all';
type FilterUpcoming = 'all' | 'upcoming' | 'past';

const PAGE_SIZE = 20;

export default function EventsPage() {
  const [events, setEvents] = useState<AdminEventSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [filterStatus, setFilterStatus] = useState<FilterStatus>('all');
  const [filterUpcoming, setFilterUpcoming] = useState<FilterUpcoming>('all');
  const [filterOfficial, setFilterOfficial] = useState<'all' | 'official'>('all');

  // Publish dialog state
  const [publishTarget, setPublishTarget] = useState<AdminEventSummary | null>(null);
  const [publishLoading, setPublishLoading] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);

  // Cancel dialog state
  const [cancelTarget, setCancelTarget] = useState<AdminEventSummary | null>(null);
  const [cancelLoading, setCancelLoading] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  // Prevent duplicate in-flight requests
  const publishingRef = useRef(false);
  const cancellingRef = useRef(false);

  const fetchEvents = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await loadAdminEvents({
        page,
        pageSize: PAGE_SIZE,
        status: filterStatus !== 'all' ? filterStatus : undefined,
        upcoming: filterUpcoming === 'upcoming' ? true : filterUpcoming === 'past' ? false : undefined,
        isOfficial: filterOfficial === 'official' ? true : undefined,
      });
      setEvents(result.data.events);
      setTotal(result.meta.total);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message ?? t('events.error'));
    } finally {
      setLoading(false);
    }
  }, [page, filterStatus, filterUpcoming, filterOfficial]);

  useEffect(() => {
    void fetchEvents();
  }, [fetchEvents]);

  async function handlePublishConfirm() {
    if (!publishTarget || publishingRef.current) return;
    publishingRef.current = true;
    setPublishLoading(true);
    setPublishError(null);
    try {
      await publishAdminEvent(publishTarget.id);
      setPublishTarget(null);
      void fetchEvents();
    } catch (err) {
      const apiErr = err as ApiError;
      setPublishError(apiErr.message ?? t('events.publish.error'));
    } finally {
      setPublishLoading(false);
      publishingRef.current = false;
    }
  }

  async function handleCancelConfirm(reason: string) {
    if (!cancelTarget || cancellingRef.current) return;
    cancellingRef.current = true;
    setCancelLoading(true);
    setCancelError(null);
    try {
      await cancelAdminEvent(cancelTarget.id, { reason });
      setCancelTarget(null);
      void fetchEvents();
    } catch (err) {
      const apiErr = err as ApiError;
      setCancelError(apiErr.message ?? t('events.cancelEvent.error'));
    } finally {
      setCancelLoading(false);
      cancellingRef.current = false;
    }
  }

  const totalPages = Math.ceil(total / PAGE_SIZE);

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Event</h1>
        <div className={styles.headerActions}>
          <Link href="/events/new" className={styles.buttonPrimary}>
            <span aria-hidden="true">+</span>
            Skapa event
          </Link>
        </div>
      </div>

      <div className={styles.filterBar} role="search" aria-label="Filtrera event">
        <select
          className={styles.filterSelect}
          value={filterStatus}
          onChange={(e) => { setFilterStatus(e.target.value as FilterStatus); setPage(1); }}
          aria-label="Filtrera på status"
        >
          <option value="all">Alla statusar</option>
          <option value="draft">Utkast</option>
          <option value="published">Publicerat</option>
          <option value="cancelled">Inställt</option>
          <option value="completed">Genomfört</option>
        </select>

        <select
          className={styles.filterSelect}
          value={filterUpcoming}
          onChange={(e) => { setFilterUpcoming(e.target.value as FilterUpcoming); setPage(1); }}
          aria-label="Filtrera kommande/tidigare"
        >
          <option value="all">Alla event</option>
          <option value="upcoming">Kommande</option>
          <option value="past">Tidigare event</option>
        </select>

        <select
          className={styles.filterSelect}
          value={filterOfficial}
          onChange={(e) => { setFilterOfficial(e.target.value as 'all' | 'official'); setPage(1); }}
          aria-label="Filtrera officiella event"
        >
          <option value="all">Alla typer</option>
          <option value="official">Officiella KCC-event</option>
        </select>
      </div>

      {error && (
        <div className={styles.errorState} role="alert">
          {error}
          <button className={styles.retryButton} onClick={() => void fetchEvents()}>
            Försök igen
          </button>
        </div>
      )}

      {loading ? (
        <div className={styles.loadingState} aria-live="polite" aria-busy="true">
          Laddar event...
        </div>
      ) : events.length === 0 ? (
        <div className={styles.emptyState} aria-live="polite">
          Inga event hittades.
        </div>
      ) : (
        <section className={styles.section}>
          <div className={styles.tableWrapper}>
            <table className={styles.table} aria-label="Eventlista">
              <thead>
                <tr>
                  <th scope="col">Titel</th>
                  <th scope="col">Status</th>
                  <th scope="col">Starttid</th>
                  <th scope="col">Område</th>
                  <th scope="col">RSVP</th>
                  <th scope="col">Uppdaterad</th>
                  <th scope="col">Åtgärder</th>
                </tr>
              </thead>
              <tbody>
                {events.map((event) => (
                  <tr key={event.id}>
                    <td>
                      <div className={styles.eventTitle}>{event.title}</div>
                      {event.isOfficial && (
                        <div className={styles.officialBadge} aria-label="Officiellt KCC-event">
                          ♛ Officiellt
                        </div>
                      )}
                    </td>
                    <td>
                      <EventStatusBadge status={event.status} />
                    </td>
                    <td>
                      <span>{formatDate(event.startsAt)}</span>
                      {event.endsAt && (
                        <div className={styles.eventMeta}>→ {formatDate(event.endsAt)}</div>
                      )}
                    </td>
                    <td>{event.approximateArea}</td>
                    <td>
                      <div className={styles.rsvpCounts} aria-label="RSVP-sammanfattning">
                        <div className={styles.rsvpItem}>
                          <span className={styles.rsvpValue}>{event.rsvpCounts.going}</span>
                          <span className={styles.rsvpLabel}>Kommer</span>
                        </div>
                        <div className={styles.rsvpItem}>
                          <span className={styles.rsvpValue}>{event.rsvpCounts.maybe}</span>
                          <span className={styles.rsvpLabel}>Kanske</span>
                        </div>
                        <div className={styles.rsvpItem}>
                          <span className={styles.rsvpValue}>{event.rsvpCounts.not_going}</span>
                          <span className={styles.rsvpLabel}>Kan inte</span>
                        </div>
                      </div>
                    </td>
                    <td>
                      <span className={styles.eventMeta}>{formatDate(event.updatedAt)}</span>
                    </td>
                    <td>
                      <div className={styles.actionList}>
                        <Link
                          href={`/events/${event.id}`}
                          className={`${styles.actionButton} ${styles.actionButtonPrimary}`}
                        >
                          Redigera
                        </Link>
                        {event.status === 'draft' && (
                          <button
                            type="button"
                            className={`${styles.actionButton} ${styles.actionButtonPrimary}`}
                            onClick={() => {
                              setPublishError(null);
                              setPublishTarget(event);
                            }}
                          >
                            Publicera
                          </button>
                        )}
                        {(event.status === 'draft' || event.status === 'published') && (
                          <button
                            type="button"
                            className={`${styles.actionButton} ${styles.actionButtonDanger}`}
                            onClick={() => {
                              setCancelError(null);
                              setCancelTarget(event);
                            }}
                          >
                            Ställ in
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div className={styles.pagination} aria-label="Sidnavigering">
              <button
                className={styles.paginationButton}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                disabled={page <= 1}
                aria-label="Föregående sida"
              >
                ← Föregående
              </button>
              <span className={styles.paginationInfo}>
                Sida {page} av {totalPages} ({total} event)
              </span>
              <button
                className={styles.paginationButton}
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                disabled={page >= totalPages}
                aria-label="Nästa sida"
              >
                Nästa →
              </button>
            </div>
          )}
        </section>
      )}

      {publishTarget && (
        <PublishDialog
          event={publishTarget}
          onConfirm={handlePublishConfirm}
          onClose={() => setPublishTarget(null)}
          isSubmitting={publishLoading}
          error={publishError}
        />
      )}

      {cancelTarget && (
        <CancelDialog
          event={cancelTarget}
          onConfirm={handleCancelConfirm}
          onClose={() => setCancelTarget(null)}
          isSubmitting={cancelLoading}
          error={cancelError}
        />
      )}
    </div>
  );
}
