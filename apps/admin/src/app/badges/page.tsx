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
  grantEarlyTesterBadge,
  type AdminBadgeSummaryResponse,
  type ApiError,
} from '@/features/badges';
import { translate } from '@/i18n';
import { formatDateOnly } from '@/lib/format';
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
  // Grant early_tester (Grundare) form state — a hand-picked UID list
  // ---------------------------------------------------------------------------
  const [founderUids, setFounderUids] = useState('');
  const [founderReason, setFounderReason] = useState('');
  const [founderLoading, setFounderLoading] = useState(false);
  const [founderError, setFounderError] = useState<string | null>(null);
  const [founderSuccess, setFounderSuccess] = useState<string | null>(null);

  const grantingRef = useRef(false);

  /** Splits the textarea into a clean, de-duplicated UID list (one per line). */
  const parseFounderUids = (raw: string): string[] =>
    Array.from(
      new Set(
        raw
          .split(/[\r\n,]+/)
          .map((line) => line.trim())
          .filter((line) => line.length > 0),
      ),
    );

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
          `${t('badges.award.success')} ${formatDateOnly(result.data.badge.awardedAt)}.`,
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
  // Grant early_tester (Grundare) to a hand-picked UID list
  // ---------------------------------------------------------------------------
  async function handleGrantFounderSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (grantingRef.current) return;

    const uids = parseFounderUids(founderUids);
    if (uids.length === 0) {
      setFounderError(t('badges.grantFounder.noUids'));
      setFounderSuccess(null);
      return;
    }

    grantingRef.current = true;
    setFounderLoading(true);
    setFounderError(null);
    setFounderSuccess(null);

    try {
      const trimmedReason = founderReason.trim();
      const result = await grantEarlyTesterBadge({
        uids,
        ...(trimmedReason ? { reason: trimmedReason } : {}),
      });
      const { grantedCount, alreadyGrantedCount, skippedCount } = result.data;
      setFounderSuccess(
        t('badges.grantFounder.success')
          .replace('%1$d', String(grantedCount))
          .replace('%2$d', String(alreadyGrantedCount))
          .replace('%3$d', String(skippedCount)),
      );
      if (grantedCount > 0) {
        // Refresh summary to reflect the new award counts.
        void fetchSummary();
      }
    } catch (err) {
      const apiErr = err as ApiError;
      setFounderError(apiErr.message ?? t('badges.grantFounder.error'));
    } finally {
      setFounderLoading(false);
      grantingRef.current = false;
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
                  </tr>
                ))}
                {summary?.data.summary.length === 0 && (
                  <tr>
                    <td colSpan={3} className={styles.badgeMeta} style={{ textAlign: 'center', padding: 'var(--space-6)' }}>
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

      {/* Grant early_tester (Grundare) to a hand-picked UID list */}
      <section className={styles.section} aria-labelledby="grant-founder-heading">
        <h2 id="grant-founder-heading" className={styles.sectionTitle}>
          {t('badges.grantFounder.title')}
        </h2>
        <p className={styles.badgeMeta} style={{ marginBottom: 'var(--space-4)' }}>
          {t('badges.grantFounder.description')}
        </p>

        {founderSuccess && (
          <div className={styles.successState} role="status" aria-live="polite">
            {founderSuccess}
          </div>
        )}

        {founderError && (
          <div className={styles.errorState} role="alert">
            {founderError}
          </div>
        )}

        <form className={styles.form} onSubmit={(e) => void handleGrantFounderSubmit(e)} noValidate>
          <div className={styles.formGroup}>
            <label htmlFor="founderUids" className={styles.label}>
              {t('badges.grantFounder.uidsLabel')} <span aria-hidden="true">*</span>
            </label>
            <textarea
              id="founderUids"
              className={styles.textarea}
              value={founderUids}
              onChange={(e) => setFounderUids(e.target.value)}
              placeholder={t('badges.grantFounder.uidsPlaceholder')}
              required
              disabled={founderLoading}
            />
            <span className={styles.hint}>{t('badges.grantFounder.uidsHint')}</span>
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="founderReason" className={styles.label}>
              {t('badges.grantFounder.reasonLabel')}
            </label>
            <input
              id="founderReason"
              className={styles.input}
              type="text"
              value={founderReason}
              onChange={(e) => setFounderReason(e.target.value)}
              placeholder={t('badges.grantFounder.reasonPlaceholder')}
              disabled={founderLoading}
              autoComplete="off"
            />
            <span className={styles.hint}>{t('badges.grantFounder.reasonHint')}</span>
          </div>

          <button
            type="submit"
            className={styles.submitButton}
            disabled={founderLoading || parseFounderUids(founderUids).length === 0}
            aria-busy={founderLoading}
          >
            {founderLoading
              ? t('badges.grantFounder.submitting')
              : t('badges.grantFounder.submit')}
          </button>
        </form>
      </section>
    </div>
  );
}
