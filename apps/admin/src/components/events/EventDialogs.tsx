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
      setReasonError('Anledning krävs.');
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
            <span id="cancel-reason-error" style={{ fontSize: 'var(--text-xs)', color: 'var(--color-error)' }} role="alert">
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
