'use client';

/**
 * Admin Notifications management page.
 *
 * Allows admins to:
 *  - View notification send history
 *  - Create a new admin notification
 *  - Select a supported audience
 *  - Preview mobile appearance before sending
 *  - Enter required reason (audit-logged)
 *  - Confirm sending (required for all_users/free_users)
 *
 * Security and privacy rules:
 *  - Backend validates all recipient eligibility and access control.
 *  - No push tokens are exposed.
 *  - No delivery provider credentials are exposed.
 *  - No arbitrary HTML in body — plain text only.
 *  - Reason is mandatory and written to the audit log.
 *  - Idempotency key prevents duplicate sends.
 *  - all_users and free_users require explicit confirmation.
 *  - Individual recipient lists are never shown.
 *  - Audit metadata: category, audience type, recipient count, reason, batch ID.
 *  - Not included in audit: push tokens, full recipient lists, exact locations.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  AdminSendNotificationRequest,
  AdminNotificationBatchSummary,
  NotificationCategory,
  AdminNotificationAudience,
} from '@/features/notifications';
import {
  adminSendNotification,
  adminListNotifications,
  ACTIVE_NOTIFICATION_CATEGORIES,
  ADMIN_NOTIFICATION_AUDIENCES,
  ApiError,
} from '@/features/notifications';
import { translate } from '@/i18n';
import { brand } from '@/config/brand';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('sv-SE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function audienceLabel(audience: string): string {
  switch (audience) {
    case 'all_users': return t('notifications.audienceAllUsers');
    case 'free_users': return t('notifications.audienceFreeUsers');
    case 'members': return t('notifications.audienceMembers');
    case 'event_participants': return t('notifications.audienceEventParticipants');
    case 'specific_user': return t('notifications.audienceSpecificUser');
    case 'admins': return t('notifications.audienceAdmins');
    default: return audience;
  }
}

// ---------------------------------------------------------------------------
// Form state
// ---------------------------------------------------------------------------

interface SendFormState {
  category: NotificationCategory;
  audience: AdminNotificationAudience;
  title: string;
  previewText: string;
  body: string;
  eventId: string;
  targetUserId: string;
  reason: string;
  idempotencyKey: string;
}

const emptyForm = (): SendFormState => ({
  category: 'admin_message',
  audience: 'admins',
  title: '',
  previewText: '',
  body: '',
  eventId: '',
  targetUserId: '',
  reason: '',
  idempotencyKey: crypto.randomUUID(),
});

// ---------------------------------------------------------------------------
// Preview component
// ---------------------------------------------------------------------------

interface NotificationPreviewProps {
  title: string;
  previewText: string;
  category: string;
}

const NotificationPreview = ({ title, previewText, category }: NotificationPreviewProps) => (
  <div className={styles.notificationPreview}>
    <div className={styles.previewHeader}>
      <span className={styles.previewApp}>{brand.shortName}</span>
      <span className={styles.previewCategory}>{category}</span>
    </div>
    <div className={styles.previewTitle}>{title || t('notifications.titleLabel')}</div>
    <div className={styles.previewBody}>{previewText || t('notifications.previewLabel')}</div>
  </div>
);

// ---------------------------------------------------------------------------
// History row
// ---------------------------------------------------------------------------

interface BatchRowProps {
  batch: AdminNotificationBatchSummary;
}

const BatchRow = ({ batch }: BatchRowProps) => (
  <tr>
    <td className={styles.td}>{formatDate(batch.createdAt)}</td>
    <td className={styles.td}>{batch.category}</td>
    <td className={styles.td}>{audienceLabel(batch.audience)}</td>
    <td className={styles.td}>{batch.title}</td>
    <td className={styles.td}>{batch.recipientCount}</td>
    <td className={styles.td}>{batch.reason}</td>
  </tr>
);

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

export default function AdminNotificationsPage() {
  const [form, setForm] = useState<SendFormState>(emptyForm());
  const [batches, setBatches] = useState<AdminNotificationBatchSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSending, setIsSending] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [showPreview, setShowPreview] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const loadHistory = useCallback(async () => {
    try {
      const res = await adminListNotifications();
      if (!mountedRef.current) return;
      setBatches(res.data.batches);
    } catch {
      // Non-fatal — show empty history.
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);

  const handleFieldChange = (field: keyof SendFormState, value: string) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    setSuccessMessage(null);
    setErrorMessage(null);
  };

  const needsConfirmation =
    form.audience === 'all_users' || form.audience === 'free_users';

  const isValid =
    form.title.trim().length > 0 &&
    form.previewText.trim().length > 0 &&
    form.body.trim().length > 0 &&
    form.reason.trim().length > 0 &&
    form.idempotencyKey.trim().length > 0 &&
    (form.audience !== 'event_participants' || form.eventId.trim().length > 0) &&
    (form.audience !== 'specific_user' || form.targetUserId.trim().length > 0);

  const handlePreview = () => {
    setShowPreview(true);
    setShowConfirm(false);
  };

  const handlePreSend = () => {
    if (!isValid) return;
    if (needsConfirmation) {
      setShowConfirm(true);
    } else {
      void handleSend();
    }
  };

  const handleSend = async (confirmed = false) => {
    if (!isValid) return;
    if (isSending) return;

    const request: AdminSendNotificationRequest = {
      category: form.category,
      audience: form.audience,
      title: form.title,
      previewText: form.previewText,
      body: form.body,
      reason: form.reason,
      idempotencyKey: form.idempotencyKey,
      ...(form.eventId ? { eventId: form.eventId } : {}),
      ...(form.targetUserId ? { targetUserId: form.targetUserId } : {}),
      ...(confirmed ? { confirmed: true } : {}),
    };

    setIsSending(true);
    setErrorMessage(null);
    setShowConfirm(false);

    try {
      await adminSendNotification(request);
      if (!mountedRef.current) return;
      setSuccessMessage(t('notifications.sentSuccess'));
      // Reset form with a new idempotency key to prevent accidental resend.
      setForm(emptyForm());
      setShowPreview(false);
      await loadHistory();
    } catch (err) {
      if (!mountedRef.current) return;
      const message = err instanceof ApiError ? err.message : 'Okänt fel.';
      setErrorMessage(message);
    } finally {
      if (mountedRef.current) setIsSending(false);
    }
  };

  return (
    <main className={styles.page}>
      <h1 className={styles.heading}>{t('notifications.pageTitle')}</h1>

      {/* Send form */}
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>{t('notifications.createTitle')}</h2>

        <div className={styles.formGrid}>
          <label className={styles.label}>
            {t('notifications.categoryLabel')}
            <select
              className={styles.input}
              value={form.category}
              onChange={(e) => handleFieldChange('category', e.target.value)}
            >
              {ACTIVE_NOTIFICATION_CATEGORIES.map((cat) => (
                <option key={cat} value={cat}>{cat}</option>
              ))}
            </select>
          </label>

          <label className={styles.label}>
            {t('notifications.audienceLabel')}
            <select
              className={styles.input}
              value={form.audience}
              onChange={(e) => handleFieldChange('audience', e.target.value)}
            >
              {ADMIN_NOTIFICATION_AUDIENCES.map((aud) => (
                <option key={aud} value={aud}>{audienceLabel(aud)}</option>
              ))}
            </select>
          </label>

          {form.audience === 'event_participants' && (
            <label className={styles.label}>
              {t('notifications.eventIdLabel')}
              <input
                className={styles.input}
                type="text"
                value={form.eventId}
                onChange={(e) => handleFieldChange('eventId', e.target.value)}
                placeholder="UUID"
              />
            </label>
          )}

          {form.audience === 'specific_user' && (
            <label className={styles.label}>
              {t('notifications.targetUserIdLabel')}
              <input
                className={styles.input}
                type="text"
                value={form.targetUserId}
                onChange={(e) => handleFieldChange('targetUserId', e.target.value)}
                placeholder="UUID"
              />
            </label>
          )}

          <label className={styles.label}>
            {t('notifications.titleLabel')} (max 100)
            <input
              className={styles.input}
              type="text"
              maxLength={100}
              value={form.title}
              onChange={(e) => handleFieldChange('title', e.target.value)}
            />
          </label>

          <label className={styles.label}>
            {t('notifications.previewLabel')} (max 200)
            <input
              className={styles.input}
              type="text"
              maxLength={200}
              value={form.previewText}
              onChange={(e) => handleFieldChange('previewText', e.target.value)}
            />
          </label>

          <label className={`${styles.label} ${styles.fullWidth}`}>
            {t('notifications.bodyLabel')} (max 1000)
            <textarea
              className={styles.textarea}
              maxLength={1000}
              rows={4}
              value={form.body}
              onChange={(e) => handleFieldChange('body', e.target.value)}
            />
          </label>

          <label className={`${styles.label} ${styles.fullWidth}`}>
            {t('notifications.reasonLabel')}
            <input
              className={styles.input}
              type="text"
              maxLength={1000}
              value={form.reason}
              onChange={(e) => handleFieldChange('reason', e.target.value)}
              placeholder={t('notifications.reasonPlaceholder')}
            />
            <span className={styles.note}>{t('notifications.reasonNote')}</span>
          </label>

          {needsConfirmation && (
            <p className={`${styles.warning} ${styles.fullWidth}`}>
              {t('notifications.confirmationRequiredNote')}
            </p>
          )}
        </div>

        <div className={styles.actions}>
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={handlePreview}
            disabled={!isValid}
          >
            {t('notifications.previewTitle')}
          </button>
          <button
            type="button"
            className={styles.primaryButton}
            onClick={handlePreSend}
            disabled={!isValid || isSending}
          >
            {isSending ? t('notifications.saving') : t('notifications.sendButton')}
          </button>
        </div>

        {/* Preview panel */}
        {showPreview && (
          <div className={styles.previewPanel}>
            <h3 className={styles.previewPanelTitle}>{t('notifications.previewTitle')}</h3>
            <NotificationPreview
              title={form.title}
              previewText={form.previewText}
              category={form.category}
            />
          </div>
        )}

        {/* Confirmation dialog */}
        {showConfirm && (
          <div className={styles.confirmPanel} role="alertdialog" aria-modal="true">
            <h3 className={styles.confirmTitle}>{t('notifications.confirmTitle')}</h3>
            <p>
              {t('notifications.confirmBody')} <strong>{audienceLabel(form.audience)}</strong>?
            </p>
            <div className={styles.confirmActions}>
              <button
                type="button"
                className={styles.secondaryButton}
                onClick={() => setShowConfirm(false)}
              >
                {t('notifications.cancelButton')}
              </button>
              <button
                type="button"
                className={styles.primaryButton}
                onClick={() => void handleSend(true)}
                disabled={isSending}
              >
                {isSending ? t('notifications.saving') : t('notifications.confirmButton')}
              </button>
            </div>
          </div>
        )}

        {successMessage && (
          <p className={styles.success} role="status">{successMessage}</p>
        )}
        {errorMessage && (
          <p className={styles.error} role="alert">{errorMessage}</p>
        )}
      </section>

      {/* Send history */}
      <section className={styles.section}>
        <h2 className={styles.sectionHeading}>{t('notifications.historyTitle')}</h2>

        {isLoading ? (
          <p className={styles.loading}>{t('notifications.loading')}</p>
        ) : batches.length === 0 ? (
          <p className={styles.empty}>{t('notifications.noHistory')}</p>
        ) : (
          <div className={styles.tableWrapper}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th className={styles.th}>{t('notifications.createdAt')}</th>
                  <th className={styles.th}>{t('notifications.categoryLabel')}</th>
                  <th className={styles.th}>{t('notifications.audienceLabel')}</th>
                  <th className={styles.th}>{t('notifications.titleLabel')}</th>
                  <th className={styles.th}>{t('notifications.recipientCount')}</th>
                  <th className={styles.th}>{t('notifications.reasonLabel')}</th>
                </tr>
              </thead>
              <tbody>
                {batches.map((batch) => (
                  <BatchRow key={batch.batchId} batch={batch} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
