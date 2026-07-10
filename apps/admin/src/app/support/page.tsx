'use client';

/**
 * Admin: Support (feedback inbox) page — Phase 13r.
 *
 * Lists in-app bug reports from `feedbackReports` (newest first, client-side
 * GitHub-status + platform filters, expandable per-report detail) and
 * cross-links each report to its public GitHub issue when one was filed.
 *
 * Security / privacy notes:
 *  - READ-ONLY: firestore.rules allows NO writes to `feedbackReports` at all
 *    (not from clients, not from admins) — every report is created exclusively
 *    by the feedback.reportIssue callable. There are deliberately no admin
 *    actions on this page.
 *  - The reporter's uid is PRIVATE to this admin record and is never attached
 *    to the world-readable GitHub issue (see feedback-core.ts). It is shown
 *    here for triage but truncated in the list (no full-id title attribute)
 *    so it can never leak into a hover tooltip; the detail view carries it.
 *  - The summary/description are free user-typed text and are rendered as
 *    plain text only.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  adminGetFeedbackReport,
  adminListFeedbackReports,
  FEEDBACK_GITHUB_STATUSES,
  FEEDBACK_PLATFORMS,
  type AdminFeedbackReportDetail,
  type AdminFeedbackReportSummary,
  type ApiError,
  type FeedbackGithubStatus,
  type FeedbackPlatform,
} from '@/features/support';
import { translate } from '@/i18n';
import { formatDate, truncate } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const statusLabel = (status: FeedbackGithubStatus) => t(`support.status.${status}`);
const platformLabel = (platform: FeedbackPlatform) => t(`support.platform.${platform}`);

function formatDateTime(iso: string | null): string {
  return formatDate(iso);
}

const shortId = (id: string) => (id.length > 8 ? `${id.slice(0, 8)}…` : id);

/** Longest summary/description preview shown inline in the list. */
const SUMMARY_PREVIEW_MAX = 120;

// ---------------------------------------------------------------------------
// GitHub-issue cross-link badge
// ---------------------------------------------------------------------------

interface IssueBadgeProps {
  status: FeedbackGithubStatus;
  number: number | null;
  url: string | null;
}

function IssueBadge({ status, number, url }: IssueBadgeProps) {
  // A live cross-link only when the issue was created AND a url is present.
  if (status === 'created' && url) {
    const label =
      number != null ? `${t('support.issue.created')} #${number}` : t('support.issue.created');
    return (
      <a
        className={styles.issueLink}
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={t('support.issue.openExternal')}
      >
        {label}
      </a>
    );
  }
  if (status === 'pending') {
    return <span className={styles.issuePending}>{t('support.issue.pending')}</span>;
  }
  // failed (and any created-without-url fallback) render distinctly, no link.
  return <span className={styles.issueFailed}>{t('support.issue.failed')}</span>;
}

// ---------------------------------------------------------------------------
// Expanded report detail
// ---------------------------------------------------------------------------

interface ReportDetailProps {
  reportId: string;
}

function ReportDetail({ reportId }: ReportDetailProps) {
  const [detail, setDetail] = useState<AdminFeedbackReportDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    adminGetFeedbackReport(reportId)
      .then((result) => {
        if (cancelled) return;
        if (result === null) {
          setError(t('support.detail.notFound'));
        } else {
          setDetail(result);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError((err as ApiError)?.message ?? t('support.detail.loadError'));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [reportId]);

  if (loading) {
    return (
      <p className={styles.statusText} aria-live="polite" aria-busy="true">
        {t('support.detail.loading')}
      </p>
    );
  }
  if (error) {
    return (
      <p className={styles.errorText} role="alert">
        {error}
      </p>
    );
  }
  if (!detail) return null;

  return (
    <div className={styles.detail}>
      {/* Free user-typed text — rendered as plain text only. */}
      <p className={styles.detailMessage}>{detail.description || '–'}</p>
      <dl className={styles.detailGrid}>
        <dt>{t('support.detail.reportId')}</dt>
        <dd className={styles.mono}>{detail.id}</dd>
        <dt>{t('support.detail.reporter')}</dt>
        <dd className={styles.mono}>{detail.uid ?? '–'}</dd>
        <dt>{t('support.detail.summary')}</dt>
        <dd>{detail.summary ?? t('support.detail.noSummary')}</dd>
        <dt>{t('support.detail.platform')}</dt>
        <dd>{platformLabel(detail.platform)}</dd>
        <dt>{t('support.detail.appVersion')}</dt>
        <dd>{detail.appVersion ?? '–'}</dd>
        <dt>{t('support.detail.osVersion')}</dt>
        <dd>{detail.osVersion ?? '–'}</dd>
        <dt>{t('support.detail.deviceModel')}</dt>
        <dd>{detail.deviceModel ?? '–'}</dd>
        <dt>{t('support.detail.githubStatus')}</dt>
        <dd>{statusLabel(detail.githubIssueStatus)}</dd>
        <dt>{t('support.detail.githubIssue')}</dt>
        <dd>
          <IssueBadge
            status={detail.githubIssueStatus}
            number={detail.githubIssueNumber}
            url={detail.githubIssueUrl}
          />
        </dd>
        <dt>{t('support.detail.createdAt')}</dt>
        <dd>{formatDateTime(detail.createdAt)}</dd>
      </dl>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function SupportPage() {
  const [reports, setReports] = useState<AdminFeedbackReportSummary[]>([]);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [statusFilter, setStatusFilter] = useState<FeedbackGithubStatus | ''>('');
  const [platformFilter, setPlatformFilter] = useState<FeedbackPlatform | ''>('');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // Monotonic request sequence: overlapping loads (toggling filters + refresh)
  // can resolve out of order, so only the most recently issued request is
  // allowed to write state — a slower earlier read can never overwrite it with
  // stale-filter results.
  const requestSeqRef = useRef(0);

  const fetchReports = useCallback(async () => {
    const seq = ++requestSeqRef.current;
    setLoading(true);
    setError(null);
    try {
      const page = await adminListFeedbackReports({
        ...(statusFilter ? { githubIssueStatus: statusFilter } : {}),
        ...(platformFilter ? { platform: platformFilter } : {}),
      });
      if (seq !== requestSeqRef.current) return;
      setReports(page.reports);
      setHasNext(page.hasNext);
    } catch (err) {
      if (seq !== requestSeqRef.current) return;
      setReports([]);
      setHasNext(false);
      setError((err as ApiError)?.message ?? t('support.loadError'));
    } finally {
      // Only the latest request clears the loading flag; a superseded request
      // must leave it set for the in-flight winner.
      if (seq === requestSeqRef.current) setLoading(false);
    }
  }, [statusFilter, platformFilter]);

  useEffect(() => {
    void fetchReports();
  }, [fetchReports]);

  const toggleStatus = (status: FeedbackGithubStatus) =>
    setStatusFilter((current) => (current === status ? '' : status));

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('support.title')}</h1>
        <p className={styles.subtitle}>{t('support.subtitle')}</p>
      </header>

      <div className={styles.filters}>
        <span className={styles.filterLabel} id="status-filter-label">
          {t('support.filter.statusLabel')}
        </span>
        <div className={styles.statusToggles} role="group" aria-labelledby="status-filter-label">
          {FEEDBACK_GITHUB_STATUSES.map((status) => (
            <button
              key={status}
              type="button"
              className={styles.toggle}
              aria-pressed={statusFilter === status}
              onClick={() => toggleStatus(status)}
            >
              {statusLabel(status)}
            </button>
          ))}
        </div>

        <label className={styles.filterLabel} htmlFor="platform-filter">
          {t('support.filter.platformLabel')}
        </label>
        <select
          id="platform-filter"
          className={styles.select}
          value={platformFilter}
          onChange={(e) => setPlatformFilter(e.target.value as FeedbackPlatform | '')}
        >
          <option value="">{t('support.filter.allPlatforms')}</option>
          {FEEDBACK_PLATFORMS.map((platform) => (
            <option key={platform} value={platform}>
              {platformLabel(platform)}
            </option>
          ))}
        </select>

        <button
          type="button"
          className={styles.refreshButton}
          onClick={() => void fetchReports()}
          disabled={loading}
        >
          {t('support.refresh')}
        </button>
      </div>

      <main className={styles.content}>
        {loading ? (
          <p className={styles.statusText} aria-live="polite" aria-busy="true">
            {t('support.loading')}
          </p>
        ) : error ? (
          <p className={styles.errorText} role="alert">
            {error}
          </p>
        ) : reports.length === 0 ? (
          <p className={styles.statusText}>{t('support.empty')}</p>
        ) : (
          <table className={styles.table} aria-label={t('support.title')}>
            <thead>
              <tr>
                <th scope="col">{t('support.columns.status')}</th>
                <th scope="col">{t('support.columns.platform')}</th>
                <th scope="col">{t('support.columns.summary')}</th>
                <th scope="col">{t('support.columns.reporter')}</th>
                <th scope="col">{t('support.columns.createdAt')}</th>
                <th scope="col">{t('support.columns.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((report) => {
                const expanded = expandedId === report.id;
                return (
                  <ReportRow
                    key={report.id}
                    report={report}
                    expanded={expanded}
                    onToggle={() => setExpandedId(expanded ? null : report.id)}
                  />
                );
              })}
            </tbody>
          </table>
        )}
        {!loading && !error && hasNext && (
          <p className={styles.statusText}>{t('support.moreAvailable')}</p>
        )}
      </main>
    </div>
  );
}

interface ReportRowProps {
  report: AdminFeedbackReportSummary;
  expanded: boolean;
  onToggle: () => void;
}

function ReportRow({ report, expanded, onToggle }: ReportRowProps) {
  // Prefer the short summary; fall back to a truncated slice of the
  // description so the row always shows something meaningful.
  const preview = report.summary ?? truncate(report.description, SUMMARY_PREVIEW_MAX);
  return (
    <>
      <tr>
        <td>
          <IssueBadge
            status={report.githubIssueStatus}
            number={report.githubIssueNumber}
            url={report.githubIssueUrl}
          />
        </td>
        <td>{platformLabel(report.platform)}</td>
        {/* Free user-typed text — plain text only. */}
        <td className={styles.summaryCell}>{preview || '–'}</td>
        {/* Truncated only — no title attr exposing the full uid; the detail view carries it. */}
        <td className={styles.mono}>{report.uid ? shortId(report.uid) : '–'}</td>
        <td>{formatDateTime(report.createdAt)}</td>
        <td>
          <button
            type="button"
            className={styles.actionButton}
            onClick={onToggle}
            aria-expanded={expanded}
          >
            {expanded ? t('support.actions.hide') : t('support.actions.show')}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className={styles.detailRow}>
          <td colSpan={6}>
            <ReportDetail reportId={report.id} />
          </td>
        </tr>
      )}
    </>
  );
}
