'use client';

/**
 * Admin: Error reports (Felrapporter) page — Phase 13m.
 *
 * Lists crash/error telemetry from `diagnosticsReports` (newest first,
 * severity/platform filters, expandable per-report detail).
 *
 * Security notes:
 *  - READ-ONLY: firestore.rules allows no client writes to
 *    `diagnosticsReports` — reports are created exclusively by the
 *    diagnostics.submitReport callable so its privacy sanitization runs
 *    server-side on every document. There are deliberately no admin
 *    actions on this page.
 *  - Everything rendered is already privacy-sanitized at write time
 *    (no tokens, coordinates, stack traces, or raw headers are ever
 *    stored) and is rendered as plain text only.
 *  - userId is shown only as a truncated identifier; anonymous (pre-auth)
 *    reports render an explicit "anonymous" label.
 */

import { useCallback, useEffect, useState } from 'react';

import {
  adminGetErrorReport,
  adminListErrorReports,
  DIAGNOSTICS_PLATFORMS,
  DIAGNOSTICS_SEVERITIES,
  type AdminErrorReportDetail,
  type AdminErrorReportSummary,
  type ApiError,
  type DiagnosticsPlatform,
  type DiagnosticsSeverity,
} from '@/features/error-reports';
import { translate } from '@/i18n';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const severityLabel = (severity: DiagnosticsSeverity) => t(`errorReports.severity.${severity}`);
const platformLabel = (platform: DiagnosticsPlatform) => t(`errorReports.platform.${platform}`);
const featureAreaLabel = (featureArea: string) => t(`errorReports.featureArea.${featureArea}`);

// CSS-module lookups are typed `string | undefined`; className accepts both.
const SEVERITY_BADGE_CLASS: Record<DiagnosticsSeverity, string | undefined> = {
  info: styles.severityInfo,
  warning: styles.severityWarning,
  error: styles.severityError,
  critical: styles.severityCritical,
};

function formatDateTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('sv-SE') : '–';
}

const shortId = (id: string) => (id.length > 8 ? `${id.slice(0, 8)}…` : id);

// ---------------------------------------------------------------------------
// Expanded report detail
// ---------------------------------------------------------------------------

interface ReportDetailProps {
  reportId: string;
}

function ReportDetail({ reportId }: ReportDetailProps) {
  const [detail, setDetail] = useState<AdminErrorReportDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    adminGetErrorReport(reportId)
      .then((result) => {
        if (cancelled) return;
        if (result === null) {
          // Deleted between list load and expand (e.g. 90-day retention sweep).
          setError(t('errorReports.detail.notFound'));
        } else {
          setDetail(result);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError((err as ApiError)?.message ?? t('errorReports.detail.loadError'));
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
    return <p className={styles.statusText}>{t('errorReports.detail.loading')}</p>;
  }
  if (error) {
    return (
      <p className={styles.errorText} role="alert">
        {error}
      </p>
    );
  }
  if (!detail) return null;

  const metadataEntries = detail.metadata ? Object.entries(detail.metadata) : [];

  return (
    <div className={styles.detail}>
      {/* Plain text only — every field was privacy-sanitized at write time. */}
      <p className={styles.detailMessage}>{detail.safeMessage || '–'}</p>
      <dl className={styles.detailGrid}>
        <dt>{t('errorReports.detail.reportId')}</dt>
        <dd className={styles.mono}>{detail.id}</dd>
        <dt>{t('errorReports.detail.user')}</dt>
        <dd className={styles.mono}>
          {detail.userId ? detail.userId : t('errorReports.detail.anonymous')}
        </dd>
        <dt>{t('errorReports.detail.featureArea')}</dt>
        <dd>{featureAreaLabel(detail.featureArea)}</dd>
        <dt>{t('errorReports.detail.errorCode')}</dt>
        <dd className={styles.mono}>{detail.errorCode ?? '–'}</dd>
        <dt>{t('errorReports.detail.appVersion')}</dt>
        <dd>{detail.appVersion ?? '–'}</dd>
        <dt>{t('errorReports.detail.buildNumber')}</dt>
        <dd>{detail.buildNumber ?? '–'}</dd>
        <dt>{t('errorReports.detail.osVersion')}</dt>
        <dd>{detail.osVersion ?? '–'}</dd>
        <dt>{t('errorReports.detail.fingerprint')}</dt>
        <dd className={styles.mono}>{detail.fingerprint ?? '–'}</dd>
        <dt>{t('errorReports.detail.createdAt')}</dt>
        <dd>{formatDateTime(detail.createdAt)}</dd>
      </dl>

      <h3 className={styles.detailSubtitle}>{t('errorReports.detail.metadata')}</h3>
      {metadataEntries.length === 0 ? (
        <p className={styles.statusText}>{t('errorReports.detail.noMetadata')}</p>
      ) : (
        <dl className={styles.detailGrid}>
          {metadataEntries.map(([key, value]) => (
            <div className={styles.metadataRow} key={key}>
              <dt className={styles.mono}>{key}</dt>
              <dd className={styles.mono}>{value === null ? '–' : String(value)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function ErrorReportsPage() {
  const [reports, setReports] = useState<AdminErrorReportSummary[]>([]);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [severityFilter, setSeverityFilter] = useState<DiagnosticsSeverity | ''>('');
  const [platformFilter, setPlatformFilter] = useState<DiagnosticsPlatform | ''>('');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const fetchReports = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await adminListErrorReports({
        ...(severityFilter ? { severity: severityFilter } : {}),
        ...(platformFilter ? { platform: platformFilter } : {}),
      });
      setReports(page.reports);
      setHasNext(page.hasNext);
    } catch (err) {
      setReports([]);
      setHasNext(false);
      setError((err as ApiError)?.message ?? t('errorReports.loadError'));
    } finally {
      setLoading(false);
    }
  }, [severityFilter, platformFilter]);

  useEffect(() => {
    void fetchReports();
  }, [fetchReports]);

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('errorReports.title')}</h1>
        <p className={styles.subtitle}>{t('errorReports.subtitle')}</p>
      </header>

      <div className={styles.filters}>
        <label className={styles.filterLabel} htmlFor="severity-filter">
          {t('errorReports.filter.severityLabel')}
        </label>
        <select
          id="severity-filter"
          className={styles.select}
          value={severityFilter}
          onChange={(e) => setSeverityFilter(e.target.value as DiagnosticsSeverity | '')}
        >
          <option value="">{t('errorReports.filter.allSeverities')}</option>
          {DIAGNOSTICS_SEVERITIES.map((severity) => (
            <option key={severity} value={severity}>
              {severityLabel(severity)}
            </option>
          ))}
        </select>

        <label className={styles.filterLabel} htmlFor="platform-filter">
          {t('errorReports.filter.platformLabel')}
        </label>
        <select
          id="platform-filter"
          className={styles.select}
          value={platformFilter}
          onChange={(e) => setPlatformFilter(e.target.value as DiagnosticsPlatform | '')}
        >
          <option value="">{t('errorReports.filter.allPlatforms')}</option>
          {DIAGNOSTICS_PLATFORMS.map((platform) => (
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
          {t('errorReports.refresh')}
        </button>
      </div>

      <main className={styles.content}>
        {loading ? (
          <p className={styles.statusText}>{t('errorReports.loading')}</p>
        ) : error ? (
          <p className={styles.errorText} role="alert">
            {error}
          </p>
        ) : reports.length === 0 ? (
          <p className={styles.statusText}>{t('errorReports.empty')}</p>
        ) : (
          <table className={styles.table}>
            <thead>
              <tr>
                <th>{t('errorReports.columns.severity')}</th>
                <th>{t('errorReports.columns.platform')}</th>
                <th>{t('errorReports.columns.featureArea')}</th>
                <th>{t('errorReports.columns.message')}</th>
                <th>{t('errorReports.columns.errorCode')}</th>
                <th>{t('errorReports.columns.user')}</th>
                <th>{t('errorReports.columns.createdAt')}</th>
                <th>{t('errorReports.columns.actions')}</th>
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
          <p className={styles.statusText}>{t('errorReports.moreAvailable')}</p>
        )}
      </main>
    </div>
  );
}

interface ReportRowProps {
  report: AdminErrorReportSummary;
  expanded: boolean;
  onToggle: () => void;
}

function ReportRow({ report, expanded, onToggle }: ReportRowProps) {
  return (
    <>
      <tr>
        <td>
          <span className={SEVERITY_BADGE_CLASS[report.severity]}>
            {severityLabel(report.severity)}
          </span>
        </td>
        <td>{platformLabel(report.platform)}</td>
        <td>{featureAreaLabel(report.featureArea)}</td>
        {/* Plain text only — sanitized server-side at write time. */}
        <td className={styles.messageCell}>{report.safeMessage || '–'}</td>
        <td className={styles.mono}>{report.errorCode ?? '–'}</td>
        <td className={styles.mono} title={report.userId ?? undefined}>
          {report.userId ? shortId(report.userId) : t('errorReports.detail.anonymous')}
        </td>
        <td>{formatDateTime(report.createdAt)}</td>
        <td>
          <button
            type="button"
            className={styles.actionButton}
            onClick={onToggle}
            aria-expanded={expanded}
          >
            {expanded ? t('errorReports.actions.hide') : t('errorReports.actions.show')}
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className={styles.detailRow}>
          <td colSpan={8}>
            <ReportDetail reportId={report.id} />
          </td>
        </tr>
      )}
    </>
  );
}
