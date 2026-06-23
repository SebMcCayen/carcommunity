'use client';

/**
 * Admin: Badges (Utmärkelser) page.
 *
 * Shows aggregate badge statistics (award count per badge key) and provides
 * a form to manually award the `helpful_member` badge.
 *
 * Security notes:
 *  - All operations are validated by the backend. Client-side role checks are
 *    for UX only and are NOT security boundaries.
 *  - Only `helpful_member` may be awarded manually. The backend rejects any
 *    other badge key.
 *  - A reason is required and audited server-side.
 *  - No individual user lists are exposed — only aggregate counts.
 *  - No rankings, leaderboards, or user comparisons are shown.
 *  - Manual award is idempotent: a second award returns the existing record.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  loadAdminBadgeSummary,
  awardHelpfulMemberBadge,
  type AdminBadgeSummaryResponse,
  type ApiError,
} from '@/features/badges';
import { translate } from '@/i18n';
import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

export default function BadgesPage() {
  // ---------------------------------------------------------------------------
  // Summary state
  // ---------------------------------------------------------------------------
  const [summary, setSummary] = useState<AdminBadgeSummaryResponse | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryError, setSummaryError] = useState<string | null>(null);

  // ---------------------------------------------------------------------------
  // Award form state (helpful_member only)
  // ---------------------------------------------------------------------------
  const [userId, setUserId] = useState('');
  const [reason, setReason] = useState('');
  const [awardLoading, setAwardLoading] = useState(false);
  const [awardError, setAwardError] = useState<string | null>(null);
  const [awardSuccess, setAwardSuccess] = useState<string | null>(null);
  const [alreadyAwarded, setAlreadyAwarded] = useState(false);

  const awardingRef = useRef(false);

  // ---------------------------------------------------------------------------
  // Load summary
  // ---------------------------------------------------------------------------
  const fetchSummary = useCallback(async () => {
    setSummaryLoading(true);
    setSummaryError(null);
    try {
      const result = await loadAdminBadgeSummary();
      setSummary(result);
    } catch (err) {
      const apiErr = err as ApiError;
      setSummaryError(apiErr.message ?? t('badges.summary.error'));
    } finally {
      setSummaryLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchSummary();
  }, [fetchSummary]);

  // ---------------------------------------------------------------------------
  // Award helpful_member
  // ---------------------------------------------------------------------------
  async function handleAwardSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (awardingRef.current) return;

    const trimmedUserId = userId.trim();
    const trimmedReason = reason.trim();

    if (!trimmedUserId || !trimmedReason) return;

    awardingRef.current = true;
    setAwardLoading(true);
    setAwardError(null);
    setAwardSuccess(null);
    setAlreadyAwarded(false);

    try {
      const result = await awardHelpfulMemberBadge(trimmedUserId, { reason: trimmedReason });
      if (result.data.alreadyAwarded) {
        setAlreadyAwarded(true);
        setAwardSuccess(null);
      } else {
        setAwardSuccess(
          `${t('badges.award.success')} ${new Date(result.data.badge.awardedAt).toLocaleDateString('sv-SE', { year: 'numeric', month: 'long', day: 'numeric' })}.`,
        );
        setUserId('');
        setReason('');
        // Refresh summary to reflect the new award count.
        void fetchSummary();
      }
    } catch (err) {
      const apiErr = err as ApiError;
      setAwardError(apiErr.message ?? t('badges.award.error'));
    } finally {
      setAwardLoading(false);
      awardingRef.current = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------
  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{t('badges.pageTitle')}</h1>
      </div>

      {/* Summary table */}
      <section className={styles.section} aria-labelledby="summary-heading">
        <h2 id="summary-heading" className={styles.sectionTitle}>
          {t('badges.summary.title')}
        </h2>

        {summaryLoading ? (
          <div className={styles.loadingState} aria-live="polite" aria-busy="true">
            {t('badges.summary.loading')}
          </div>
        ) : summaryError ? (
          <div className={styles.errorState} role="alert">
            {summaryError}
            <button className={styles.retryButton} onClick={() => void fetchSummary()}>
              {t('badges.summary.retry')}
            </button>
          </div>
        ) : (
          <div className={styles.tableWrapper}>
            <table className={styles.table} aria-label={t('badges.summary.tableLabel')}>
              <thead>
                <tr>
                  <th scope="col">{t('badges.columns.badge')}</th>
                  <th scope="col">{t('badges.columns.totalAwards')}</th>
                  <th scope="col">{t('badges.columns.recentAwards')}</th>
                  <th scope="col">{t('badges.columns.lastAwardedAt')}</th>
                </tr>
              </thead>
              <tbody>
                {summary?.data.summary.map((item) => (
                  <tr key={item.key}>
                    <td>
                      <div className={styles.badgeName}>{item.name}</div>
                      <div className={styles.badgeMeta}>{item.key}</div>
                    </td>
                    <td>
                      <span className={styles.awardCount}>{item.totalCount}</span>
                    </td>
                    <td>
                      <span>{item.recentCount}</span>
                    </td>
                    <td>
                      <span className={styles.badgeMeta}>—</span>
                    </td>
                  </tr>
                ))}
                {summary?.data.summary.length === 0 && (
                  <tr>
                    <td colSpan={4} className={styles.badgeMeta} style={{ textAlign: 'center', padding: 'var(--space-6)' }}>
                      {t('badges.summary.empty')}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Award helpful_member form */}
      <section className={styles.section} aria-labelledby="award-heading">
        <h2 id="award-heading" className={styles.sectionTitle}>
          {t('badges.award.title')}
        </h2>
        <p className={styles.badgeMeta} style={{ marginBottom: 'var(--space-4)' }}>
          {t('badges.award.description')}
        </p>

        {awardSuccess && (
          <div className={styles.successState} role="status" aria-live="polite">
            {awardSuccess}
          </div>
        )}

        {alreadyAwarded && (
          <div className={styles.alreadyAwardedNote} role="status" aria-live="polite">
            {t('badges.award.alreadyAwarded')}
          </div>
        )}

        {awardError && (
          <div className={styles.errorState} role="alert">
            {awardError}
          </div>
        )}

        <form className={styles.form} onSubmit={(e) => void handleAwardSubmit(e)} noValidate>
          <div className={styles.formGroup}>
            <label htmlFor="userId" className={styles.label}>
              {t('badges.award.userIdLabel')} <span aria-hidden="true">*</span>
            </label>
            <input
              id="userId"
              className={styles.input}
              type="text"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              placeholder={t('badges.award.userIdPlaceholder')}
              required
              disabled={awardLoading}
              autoComplete="off"
            />
            <span className={styles.hint}>{t('badges.award.userIdHint')}</span>
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="reason" className={styles.label}>
              {t('badges.award.reasonLabel')} <span aria-hidden="true">*</span>
            </label>
            <textarea
              id="reason"
              className={styles.textarea}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t('badges.award.reasonPlaceholder')}
              required
              disabled={awardLoading}
            />
            <span className={styles.hint}>{t('badges.award.reasonHint')}</span>
          </div>

          <button
            type="submit"
            className={styles.submitButton}
            disabled={awardLoading || !userId.trim() || !reason.trim()}
            aria-busy={awardLoading}
          >
            {awardLoading ? t('badges.award.submitting') : t('badges.award.submit')}
          </button>
        </form>
      </section>
    </div>
  );
}
