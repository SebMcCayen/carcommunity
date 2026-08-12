'use client';

import { Link } from 'react-router-dom';
import { useParams, useNavigate } from 'react-router-dom';
import { useCallback, useEffect, useRef, useState } from 'react';
import { EventForm } from '@/components/events/EventForm';
import { EventStatusBadge } from '@/components/events/EventStatusBadge';
import { CancelDialog, PublicSiteDialog, PublishDialog } from '@/components/events/EventDialogs';
import {
  cancelAdminEvent,
  loadAdminEvent,
  publishAdminEvent,
  resolveEventCreatorName,
  setEventPublicSite,
  updateAdminEvent,
  type AdminEventDetail,
  type ApiError,
  type UpdateEventRequest,
} from '@/features/events';
import {
  loadAdminGroupDriveSummary,
  type AdminGroupDriveSummary,
} from '@/features/group-drive';
import { translate } from '@/i18n';
import { formatDate } from '@/lib';
import styles from '../new/page.module.css';

const t = (key: string) => translate('sv', key);

export default function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const navigate = useNavigate();

  const [event, setEvent] = useState<AdminEventDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [isUpdating, setIsUpdating] = useState(false);

  const [groupDriveSummary, setGroupDriveSummary] = useState<AdminGroupDriveSummary | null>(null);

  // Resolved display name for the creator, tagged with the uid it was resolved
  // for. Keeping the uid alongside the name lets the render discard a result
  // that belongs to a previously viewed event (see the derivation below).
  const [resolvedCreator, setResolvedCreator] = useState<{ uid: string; name: string } | null>(null);

  const [showPublish, setShowPublish] = useState(false);
  const [publishLoading, setPublishLoading] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);

  const [showCancel, setShowCancel] = useState(false);
  const [cancelLoading, setCancelLoading] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  // Public-site toggle (homepage listing + public event page). `showPublicSite`
  // holds the direction the dialog confirms: true = enable, false = disable.
  const [showPublicSite, setShowPublicSite] = useState<boolean | null>(null);
  const [publicSiteLoading, setPublicSiteLoading] = useState(false);
  const [publicSiteError, setPublicSiteError] = useState<string | null>(null);

  const publishingRef = useRef(false);
  const cancellingRef = useRef(false);
  const publicSiteRef = useRef(false);

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

  // Load group drive aggregate counts separately — best-effort, non-blocking.
  useEffect(() => {
    if (!eventId) return;
    void loadAdminGroupDriveSummary(eventId).then(setGroupDriveSummary).catch(() => {
      // Non-critical: group drive summary may be unavailable for some events.
    });
  }, [eventId]);

  // Resolve the creator uid to a display name — best-effort, non-blocking.
  // The page renders regardless; on any failure the uid is shown as a fallback.
  // The result is tagged with its uid so the render can ignore a stale name
  // resolved for a different event (see the derivation below).
  useEffect(() => {
    const uid = event?.createdByUserId;
    if (!uid) return;
    let active = true;
    void resolveEventCreatorName(uid).then((name) => {
      if (active) setResolvedCreator({ uid, name });
    });
    return () => {
      active = false;
    };
  }, [event?.createdByUserId]);

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

  async function handlePublicSiteConfirm() {
    if (!event || showPublicSite === null || publicSiteRef.current) return;
    publicSiteRef.current = true;
    setPublicSiteLoading(true);
    setPublicSiteError(null);
    try {
      const result = await setEventPublicSite(event.id, showPublicSite);
      setEvent(result.data.event);
      setShowPublicSite(null);
    } catch (err) {
      const apiErr = err as ApiError;
      setPublicSiteError(apiErr.message ?? t('events.publicSite.error'));
    } finally {
      setPublicSiteLoading(false);
      publicSiteRef.current = false;
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
        <Link to="/events" className={styles.backLink}>← {t('events.backToList')}</Link>
        <div className={styles.loadingState} aria-live="polite" aria-busy="true">{t('events.loading')}</div>
      </div>
    );
  }

  if (error || !event) {
    return (
      <div className={styles.page}>
        <Link to="/events" className={styles.backLink}>← {t('events.backToList')}</Link>
        <div className={styles.errorState} role="alert">
          {error ?? t('events.notFound')}
        </div>
      </div>
    );
  }

  const canEdit = event.status === 'draft' || event.status === 'published';
  const canPublish = event.status === 'draft';
  const canCancel = event.status === 'draft' || event.status === 'published';
  // Mirrors the backend guard (guardPublicSiteTogglable): enabling is only
  // meaningful for draft/published events; disabling is always allowed so an
  // event can never be stuck publicly listed. UX-only — the callable enforces.
  const canEnablePublicSite = !event.publicSiteEnabled && canEdit;
  const canDisablePublicSite = event.publicSiteEnabled;

  // Only use the resolved name when it was resolved for THIS event's creator
  // uid. On navigating between events the resolved state briefly belongs to the
  // previous event, so we fall back to the current uid during render — no
  // post-paint flash of the wrong creator is possible.
  const creatorDisplay =
    resolvedCreator && resolvedCreator.uid === event.createdByUserId
      ? resolvedCreator.name
      : event.createdByUserId;

  return (
    <div className={styles.page}>
      <Link to="/events" className={styles.backLink}>← {t('events.backToList')}</Link>

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
          {canEnablePublicSite && (
            <button
              type="button"
              style={{
                padding: 'var(--space-2) var(--space-5)',
                backgroundColor: 'transparent',
                color: 'var(--text-primary)',
                border: '1px solid var(--border-strong, var(--text-secondary))',
                borderRadius: 'var(--radius-sm)',
                fontSize: 'var(--text-sm)',
                fontWeight: 'var(--fw-medium)',
                cursor: 'pointer',
              }}
              onClick={() => { setPublicSiteError(null); setShowPublicSite(true); }}
            >
              {t('events.publicSite.enable.button')}
            </button>
          )}
          {canDisablePublicSite && (
            <button
              type="button"
              style={{
                padding: 'var(--space-2) var(--space-5)',
                backgroundColor: 'transparent',
                color: 'var(--text-primary)',
                border: '1px solid var(--border-strong, var(--text-secondary))',
                borderRadius: 'var(--radius-sm)',
                fontSize: 'var(--text-sm)',
                fontWeight: 'var(--fw-medium)',
                cursor: 'pointer',
              }}
              onClick={() => { setPublicSiteError(null); setShowPublicSite(false); }}
            >
              {t('events.publicSite.disable.button')}
            </button>
          )}
          {canCancel && (
            <button
              type="button"
              style={{
                padding: 'var(--space-2) var(--space-5)',
                backgroundColor: 'transparent',
                // `--color-error` is 4.32:1 on the dark surface; `--status-error`
                // is theme-aware and measures 6.79:1. The hardcoded rgba border
                // was 1.66:1 against the page — below the 3:1 needed to identify
                // a control boundary.
                color: 'var(--status-error)',
                border: '1px solid var(--status-error)',
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
            <span className={styles.metaValue} title={event.createdByUserId ?? undefined}>
              {event.createdByUserId ? creatorDisplay : '—'}
            </span>
          </div>
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>{t('events.meta.createdAt')}</span>
            <span className={styles.metaValue}>{formatDate(event.createdAt)}</span>
          </div>
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>{t('events.meta.updatedAt')}</span>
            <span className={styles.metaValue}>{formatDate(event.updatedAt)}</span>
          </div>
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>{t('events.publicSite.stateLabel')}</span>
            <span className={styles.metaValue}>
              {event.publicSiteEnabled
                ? t('events.publicSite.stateOn')
                : t('events.publicSite.stateOff')}
            </span>
          </div>
        </div>
        <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-xs)', marginTop: 'var(--space-2)' }}>
          {t('events.publicSite.creatorNote')}
        </p>
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

      {/* ── Group drive aggregate status — aggregate counts only, no positions ── */}
      <h2 className={styles.sectionTitle}>{t('groupDrive.adminSection')}</h2>
      {groupDriveSummary !== null ? (
        <div className={styles.rsvpGrid} aria-label="Gruppkörningsstatus">
          <div className={styles.rsvpCard}>
            <div className={styles.rsvpCount}>{groupDriveSummary.totalActive}</div>
            <div className={styles.rsvpLabel}>{t('groupDrive.adminTotalActive')}</div>
          </div>
          <div className={styles.rsvpCard}>
            <div className={styles.rsvpCount}>{groupDriveSummary.joinedCount}</div>
            <div className={styles.rsvpLabel}>{t('groupDrive.adminJoined')}</div>
          </div>
          <div className={styles.rsvpCard}>
            <div className={styles.rsvpCount}>{groupDriveSummary.onTheWayCount}</div>
            <div className={styles.rsvpLabel}>{t('groupDrive.adminOnTheWay')}</div>
          </div>
          <div className={styles.rsvpCard}>
            <div className={styles.rsvpCount}>{groupDriveSummary.arrivedCount}</div>
            <div className={styles.rsvpLabel}>{t('groupDrive.adminArrived')}</div>
          </div>
        </div>
      ) : (
        <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
          {t('groupDrive.adminNoData')}
        </p>
      )}

      {canEdit ? (
        <>
          <h2 className={styles.sectionTitle}>{t('events.editEvent')}</h2>
          <EventForm
            initialData={event}
            onSubmit={handleUpdate}
            onCancel={() => navigate('/events')}
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

      {showPublicSite !== null && (
        <PublicSiteDialog
          event={event}
          enable={showPublicSite}
          onConfirm={handlePublicSiteConfirm}
          onClose={() => setShowPublicSite(null)}
          isSubmitting={publicSiteLoading}
          error={publicSiteError}
        />
      )}
    </div>
  );
}
