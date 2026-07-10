'use client';

/**
 * Admin: Moderation reports (Moderationsanmälningar) queue — Phase 13q.
 *
 * Lists moderationReports newest-first, filterable by status (pending /
 * reviewed / dismissed / all), and lets an admin resolve a report by moving
 * its status to reviewed or dismissed (or reopening it to pending). Status is
 * the ONLY field the rules let an admin write — reporter, target, reason, and
 * details are immutable.
 *
 * Security/privacy notes:
 *  - Only the report metadata (reporter uid, target type/id, reason, detail
 *    text, timestamp, status) is shown — resolving is the rules-granted direct
 *    admin status update, and the confirm dialog spells out the action.
 *  - The resolve write is NOT audited (a direct update writes no
 *    adminAuditEvents record) — a possible follow-up if audit parity is wanted.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  adminListModerationReports,
  resolveModerationReport,
  type AdminModerationReport,
  type ApiError,
  type ModerationReportCursor,
  type ModerationReportStatusFilter,
  type ResolvableModerationReportStatus,
} from '@/features/moderation-reports';
import { translate } from '@/i18n';
import { formatDate } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const FILTERS: readonly ModerationReportStatusFilter[] = [
  'pending',
  'reviewed',
  'dismissed',
  'all',
];

/** Detail text longer than this is truncated with an inline expand toggle. */
const DETAILS_PREVIEW_LENGTH = 140;

function formatDateTime(iso: string | null): string {
  return formatDate(iso);
}

/** Badge class for a target type; unknown types get the neutral fallback. */
function targetTypeBadgeClass(targetType: string): string | undefined {
  switch (targetType) {
    case 'user':
      return styles.badgeUser;
    case 'message':
      return styles.badgeMessage;
    case 'event':
      return styles.badgeEvent;
    default:
      return styles.badgeNeutral;
  }
}

/** Badge class for a review status. */
function statusBadgeClass(status: AdminModerationReport['status']): string | undefined {
  switch (status) {
    case 'reviewed':
      return styles.badgeReviewed;
    case 'dismissed':
      return styles.badgeDismissed;
    default:
      return styles.badgePending;
  }
}

function DetailsCell({ details }: { details: string | null }) {
  const [expanded, setExpanded] = useState(false);
  if (!details) {
    return <span className={styles.muted}>{t('moderationReports.noDetails')}</span>;
  }
  const isLong = details.length > DETAILS_PREVIEW_LENGTH;
  if (!isLong) return <span>{details}</span>;
  return (
    <span>
      {expanded ? details : `${details.slice(0, DETAILS_PREVIEW_LENGTH).trimEnd()}…`}{' '}
      <button
        type="button"
        className={styles.linkButton}
        aria-expanded={expanded}
        onClick={() => setExpanded((prev) => !prev)}
      >
        {t(expanded ? 'moderationReports.showLess' : 'moderationReports.showMore')}
      </button>
    </span>
  );
}

export default function ModerationReportsPage() {
  const [filter, setFilter] = useState<ModerationReportStatusFilter>('pending');
  const [reports, setReports] = useState<AdminModerationReport[]>([]);
  const [cursor, setCursor] = useState<ModerationReportCursor | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [actingId, setActingId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const actingRef = useRef(false);
  // Monotonic request id shared by load() and handleLoadMore(): tab switches,
  // load-more, and post-action reloads can overlap, so a response only touches
  // state while it is still the latest request — a slow earlier request must
  // never overwrite/append newer results or clear a fresh loading flag.
  const requestSeq = useRef(0);

  const load = useCallback(async (activeFilter: ModerationReportStatusFilter) => {
    const seq = ++requestSeq.current;
    setLoading(true);
    // A fresh load supersedes any in-flight load-more; that stale response will
    // skip its finally (no longer the current seq), so clear the flag here or
    // the load-more button could stay disabled forever.
    setLoadingMore(false);
    setError(null);
    try {
      const page = await adminListModerationReports({ filter: activeFilter });
      if (seq !== requestSeq.current) return;
      setReports(page.reports);
      setCursor(page.cursor);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setReports([]);
      setCursor(null);
      setError((err as ApiError)?.message ?? t('moderationReports.loadError'));
      // Rethrow so a caller awaiting the reload (handleResolve) sees the
      // failure and skips its success banner; the error state is already set.
      throw err;
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    // load() rethrows on failure; the error is already reflected in state, so
    // swallow the rejection here to avoid an unhandled promise rejection.
    void load(filter).catch(() => {});
  }, [filter, load]);

  const handleLoadMore = useCallback(async () => {
    if (!cursor || loadingMore) return;
    const seq = ++requestSeq.current;
    setLoadingMore(true);
    setError(null);
    try {
      const page = await adminListModerationReports({ filter, cursor });
      if (seq !== requestSeq.current) return;
      setReports((prev) => [...prev, ...page.reports]);
      setCursor(page.cursor);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setError((err as ApiError)?.message ?? t('moderationReports.loadError'));
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false);
    }
  }, [cursor, loadingMore, filter]);

  const handleResolve = useCallback(
    async (reportId: string, status: ResolvableModerationReportStatus) => {
      if (actingRef.current) return;
      const confirmKey =
        status === 'reviewed'
          ? 'moderationReports.confirmReviewed'
          : status === 'dismissed'
            ? 'moderationReports.confirmDismissed'
            : 'moderationReports.confirmReopen';
      if (!window.confirm(t(confirmKey))) return;
      actingRef.current = true;
      setActingId(reportId);
      setActionError(null);
      setSuccessMessage(null);
      try {
        const result = await resolveModerationReport(reportId, status);
        await load(filter);
        setSuccessMessage(
          t(
            result.alreadyResolved
              ? 'moderationReports.alreadyResolved'
              : `moderationReports.resolveSuccess.${status}`,
          ),
        );
      } catch (err) {
        setActionError((err as ApiError)?.message ?? t('moderationReports.resolveError'));
      } finally {
        setActingId(null);
        actingRef.current = false;
      }
    },
    [filter, load],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('moderationReports.title')}</h1>
        <p className={styles.subtitle}>{t('moderationReports.subtitle')}</p>
      </header>

      <div className={styles.tabs} role="group" aria-label={t('moderationReports.title')}>
        {FILTERS.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={filter === value}
            className={filter === value ? styles.tabActive : styles.tab}
            onClick={() => setFilter(value)}
            // Lock the filter while a resolve action is in flight: its
            // post-action load(filter) captured the filter at click time, so a
            // mid-action switch would repaint with wrong-filter rows plus an
            // unrelated success banner.
            disabled={(loading && filter === value) || actingId !== null}
          >
            {t(`moderationReports.tabs.${value}`)}
          </button>
        ))}
      </div>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      {actionError && (
        <p className={styles.error} role="alert">
          {actionError}
        </p>
      )}
      {successMessage && (
        <p className={styles.success} role="status">
          {successMessage}
        </p>
      )}

      {loading ? (
        <p className={styles.muted} aria-live="polite" aria-busy="true">
          {t('moderationReports.loading')}
        </p>
      ) : reports.length === 0 ? (
        // Suppress the empty state on error (the banner above already explains
        // it); when rows are already loaded, keep the table even if a later
        // load-more failed.
        error ? null : (
          <p className={styles.muted}>{t('moderationReports.empty')}</p>
        )
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table} aria-label={t('moderationReports.tableLabel')}>
            <thead>
              <tr>
                <th scope="col">{t('moderationReports.table.reportedBy')}</th>
                <th scope="col">{t('moderationReports.table.target')}</th>
                <th scope="col">{t('moderationReports.table.reason')}</th>
                <th scope="col">{t('moderationReports.table.details')}</th>
                <th scope="col">{t('moderationReports.table.createdAt')}</th>
                <th scope="col">{t('moderationReports.table.status')}</th>
                <th scope="col">{t('moderationReports.table.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((report) => (
                <tr key={report.id}>
                  <td className={styles.monoCell} title={report.reportedBy}>
                    {report.reportedBy || '–'}
                  </td>
                  <td>
                    <span className={targetTypeBadgeClass(report.targetType)}>
                      {report.targetType
                        ? t(`moderationReports.targetType.${report.targetType}`)
                        : t('moderationReports.targetType.unknown')}
                    </span>
                    <span className={styles.monoSub} title={report.targetId}>
                      {report.targetId || '–'}
                    </span>
                  </td>
                  <td className={styles.reasonCell}>{report.reason || '–'}</td>
                  <td className={styles.detailsCell}>
                    <DetailsCell details={report.details} />
                  </td>
                  <td className={styles.timeCell}>{formatDateTime(report.createdAt)}</td>
                  <td>
                    <span className={statusBadgeClass(report.status)}>
                      {t(`moderationReports.status.${report.status}`)}
                    </span>
                  </td>
                  <td>
                    <div className={styles.actions}>
                      {report.status !== 'reviewed' && (
                        <button
                          type="button"
                          className={styles.actionButton}
                          onClick={() => void handleResolve(report.id, 'reviewed')}
                          disabled={actingId !== null}
                        >
                          {actingId === report.id
                            ? t('moderationReports.resolvePending')
                            : t('moderationReports.markReviewed')}
                        </button>
                      )}
                      {report.status !== 'dismissed' && (
                        <button
                          type="button"
                          className={styles.actionButton}
                          onClick={() => void handleResolve(report.id, 'dismissed')}
                          disabled={actingId !== null}
                        >
                          {actingId === report.id
                            ? t('moderationReports.resolvePending')
                            : t('moderationReports.dismiss')}
                        </button>
                      )}
                      {report.status !== 'pending' && (
                        <button
                          type="button"
                          className={styles.actionButton}
                          onClick={() => void handleResolve(report.id, 'pending')}
                          disabled={actingId !== null}
                        >
                          {actingId === report.id
                            ? t('moderationReports.resolvePending')
                            : t('moderationReports.reopen')}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!loading && cursor && (
        <div className={styles.loadMoreRow}>
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={() => void handleLoadMore()}
            disabled={loadingMore}
            aria-busy={loadingMore}
          >
            {loadingMore ? t('moderationReports.loadingMore') : t('moderationReports.loadMore')}
          </button>
        </div>
      )}
    </div>
  );
}
