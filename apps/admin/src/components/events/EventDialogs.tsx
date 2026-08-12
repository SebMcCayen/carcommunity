'use client';

import { useState } from 'react';
import type { AdminEventSummary } from '@/features/events';
import { translate } from '@/i18n';
import { formatDate } from '@/lib';
import styles from './EventDialogs.module.css';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Publish dialog
// ---------------------------------------------------------------------------

interface PublishDialogProps {
  event: Pick<AdminEventSummary, 'id' | 'title' | 'startsAt' | 'approximateArea'>;
  onConfirm: () => Promise<void>;
  onClose: () => void;
  isSubmitting: boolean;
  error?: string | null;
}

/**
 * Confirmation dialog for publishing a draft event.
 * Does not optimistically update state — backend confirmation is required.
 * Disables confirm button while request is in flight to prevent duplicate submissions.
 */
export function PublishDialog({ event, onConfirm, onClose, isSubmitting, error }: PublishDialogProps) {
  return (
    <div className={styles.overlay} role="dialog" aria-modal="true" aria-labelledby="publish-dialog-title">
      <div className={styles.dialog}>
        <h2 id="publish-dialog-title" className={styles.title}>
          {t('events.publish.title')}
        </h2>

        <p className={styles.description}>{t('events.publish.description')}</p>

        <div className={styles.eventSummary} aria-label="Eventinformation">
          <div className={styles.eventTitle}>{event.title}</div>
          <div className={styles.eventMeta}>
            {formatDate(event.startsAt)} · {event.approximateArea}
          </div>
        </div>

        <p className={styles.description}>{t('events.publish.confirm')}</p>

        {error && (
          <div className={styles.errorBanner} role="alert">
            {error}
          </div>
        )}

        <div className={styles.actions}>
          <button
            type="button"
            className={styles.buttonSecondary}
            onClick={onClose}
            disabled={isSubmitting}
          >
            {t('events.publish.cancel')}
          </button>
          <button
            type="button"
            className={styles.buttonPrimary}
            onClick={onConfirm}
            disabled={isSubmitting}
            aria-busy={isSubmitting}
          >
            {isSubmitting ? '...' : t('events.publish.button')}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Public-site dialog (enable/disable the homepage listing + public page)
// ---------------------------------------------------------------------------

interface PublicSiteDialogProps {
  event: Pick<AdminEventSummary, 'id' | 'title' | 'startsAt'>;
  /** True = the dialog confirms ENABLING; false = confirms DISABLING. */
  enable: boolean;
  onConfirm: () => Promise<void>;
  onClose: () => void;
  isSubmitting: boolean;
  error?: string | null;
}

/**
 * Confirmation dialog for toggling an event's public page + homepage listing
 * (events.setPublicSite). Publication is normally the CREATOR's decision made
 * in the app; this admin control is the moderation safety valve (disable) and
 * the after-the-fact assist (enable on the creator's request). No optimistic
 * update — backend confirmation is required, and the confirm button is
 * disabled while in flight to prevent duplicate submissions.
 */
export function PublicSiteDialog({
  event,
  enable,
  onConfirm,
  onClose,
  isSubmitting,
  error,
}: PublicSiteDialogProps) {
  const keyBase = enable ? 'events.publicSite.enable' : 'events.publicSite.disable';
  return (
    <div className={styles.overlay} role="dialog" aria-modal="true" aria-labelledby="public-site-dialog-title">
      <div className={styles.dialog}>
        <h2 id="public-site-dialog-title" className={styles.title}>
          {t(`${keyBase}.title`)}
        </h2>

        <p className={styles.description}>{t(`${keyBase}.description`)}</p>

        {/* Labelled by its own visible event title (aria-labelledby) rather
            than a hardcoded-locale aria-label, so screen readers announce the
            same text sighted users see. */}
        <div className={styles.eventSummary} role="group" aria-labelledby="public-site-dialog-event-title">
          <div id="public-site-dialog-event-title" className={styles.eventTitle}>{event.title}</div>
          <div className={styles.eventMeta}>{formatDate(event.startsAt)}</div>
        </div>

        <p className={styles.description}>{t(`${keyBase}.confirm`)}</p>

        {error && (
          <div className={styles.errorBanner} role="alert">
            {error}
          </div>
        )}

        <div className={styles.actions}>
          <button
            type="button"
            className={styles.buttonSecondary}
            onClick={onClose}
            disabled={isSubmitting}
          >
            {t('events.publicSite.cancel')}
          </button>
          <button
            type="button"
            className={enable ? styles.buttonPrimary : styles.buttonDanger}
            onClick={onConfirm}
            disabled={isSubmitting}
            aria-busy={isSubmitting}
          >
            {isSubmitting ? '...' : t(`${keyBase}.button`)}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Cancel dialog
// ---------------------------------------------------------------------------

interface CancelDialogProps {
  event: Pick<AdminEventSummary, 'id' | 'title' | 'startsAt' | 'approximateArea'>;
  onConfirm: (reason: string) => Promise<void>;
  onClose: () => void;
  isSubmitting: boolean;
  error?: string | null;
}

/**
 * Confirmation dialog for cancelling an event.
 * Requires a non-empty reason. Event is preserved — never hard-deleted.
 * Disables confirm button while request is in flight to prevent duplicate submissions.
 */
export function CancelDialog({ event, onConfirm, onClose, isSubmitting, error }: CancelDialogProps) {
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | null>(null);

  async function handleConfirm() {
    if (!reason.trim()) {
      setReasonError(t('events.cancelEvent.reasonRequired'));
      return;
    }
    setReasonError(null);
    await onConfirm(reason.trim());
  }

  return (
    <div className={styles.overlay} role="dialog" aria-modal="true" aria-labelledby="cancel-dialog-title">
      <div className={styles.dialog}>
        <h2 id="cancel-dialog-title" className={styles.title}>
          {t('events.cancelEvent.title')}
        </h2>

        <div className={styles.eventSummary} aria-label="Eventinformation">
          <div className={styles.eventTitle}>{event.title}</div>
          <div className={styles.eventMeta}>{formatDate(event.startsAt)}</div>
        </div>

        <div className={styles.fieldGroup}>
          <label className={styles.label} htmlFor="cancel-reason">
            {t('events.cancelEvent.reasonLabel')}
          </label>
          <textarea
            id="cancel-reason"
            className={styles.textarea}
            maxLength={2000}
            rows={3}
            placeholder={t('events.cancelEvent.reasonPlaceholder')}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              setReasonError(null);
            }}
            disabled={isSubmitting}
            aria-required="true"
            aria-describedby={reasonError ? 'cancel-reason-error' : undefined}
          />
          {reasonError && (
            // `--color-error` (#d9534f) measures 4.32:1 on the dark card surface,
            // just below AA. `--status-error` is theme-aware: 6.79:1 there.
            <span id="cancel-reason-error" style={{ fontSize: 'var(--text-xs)', color: 'var(--status-error)' }} role="alert">
              {reasonError}
            </span>
          )}
        </div>

        <div className={styles.notice} role="note">
          {t('events.cancelEvent.notice')}
        </div>

        {error && (
          <div className={styles.errorBanner} role="alert">
            {error}
          </div>
        )}

        <div className={styles.actions}>
          <button
            type="button"
            className={styles.buttonSecondary}
            onClick={onClose}
            disabled={isSubmitting}
          >
            {t('events.cancelEvent.cancel')}
          </button>
          <button
            type="button"
            className={styles.buttonDanger}
            onClick={handleConfirm}
            disabled={isSubmitting || !reason.trim()}
            aria-busy={isSubmitting}
          >
            {isSubmitting ? '...' : t('events.cancelEvent.button')}
          </button>
        </div>
      </div>
    </div>
  );
}
