'use client';

/**
 * Admin: Audit log (Granskningslogg) page — Phase 13n.
 *
 * Read-only table over the immutable `adminAuditEvents` collection, newest
 * first, with an exact targetId filter (served by the existing composite
 * index), a client-side action filter, and cursor-based load-more.
 *
 * Security notes:
 *  - Strictly read-only — audit records are backend-written and immutable
 *    (firestore.rules denies ALL client writes, even by admins).
 *  - Unknown action codes render as their raw value so new backend actions
 *    are never hidden or mislabeled.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import {
  auditActionLabelKey,
  filterEventsByAction,
  KNOWN_AUDIT_ACTIONS,
  listAdminAuditEvents,
  type AdminAuditEventRow,
  type ApiError,
  type AuditLogCursor,
} from '@/features/audit-log';
import { translate } from '@/i18n';
import { formatDate } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

/**
 * Human-readable label for an action code. Known actions use their i18n
 * label; a non-blank unknown action renders as its raw value (new backend
 * actions are never hidden); a blank/missing action shows a localized
 * "unknown action" placeholder rather than an empty label.
 */
function actionLabel(action: string): string {
  if (!action) return t('auditLog.unknownAction');
  const key = auditActionLabelKey(action);
  return key ? t(key) : action;
}

function formatDateTime(iso: string | null): string {
  return formatDate(iso);
}

export default function AuditLogPage() {
  const [events, setEvents] = useState<AdminAuditEventRow[]>([]);
  const [cursor, setCursor] = useState<AuditLogCursor | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The input field is draft state; activeTargetId is the applied filter the
  // query actually uses (so typing doesn't refetch on every keystroke).
  const [targetIdInput, setTargetIdInput] = useState('');
  const [activeTargetId, setActiveTargetId] = useState('');
  const [actionFilter, setActionFilter] = useState('');

  // Monotonic request id shared by load() and handleLoadMore(). Each call
  // claims the next id; a response only touches state while it is still the
  // latest request, so a filter change or load-more that starts during an
  // in-flight fetch can never let the stale response overwrite/append.
  const requestSeq = useRef(0);

  const load = useCallback(async (targetId: string) => {
    const seq = ++requestSeq.current;
    setLoading(true);
    // A fresh load supersedes any in-flight load-more: that stale response
    // will skip its finally (no longer the current seq), so clear the flag
    // here or the filter/load-more buttons could stay disabled forever.
    setLoadingMore(false);
    setError(null);
    try {
      const page = await listAdminAuditEvents({ targetId: targetId || undefined });
      if (seq !== requestSeq.current) return;
      setEvents(page.events);
      setCursor(page.cursor);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setEvents([]);
      setCursor(null);
      setError((err as ApiError)?.message ?? t('auditLog.loadError'));
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(activeTargetId);
  }, [load, activeTargetId]);

  const handleFilterSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setActiveTargetId(targetIdInput.trim());
  };

  const handleClearFilter = () => {
    setTargetIdInput('');
    setActiveTargetId('');
  };

  const handleLoadMore = useCallback(async () => {
    if (!cursor || loadingMore) return;
    const seq = ++requestSeq.current;
    setLoadingMore(true);
    setError(null);
    try {
      const page = await listAdminAuditEvents({
        targetId: activeTargetId || undefined,
        cursor,
      });
      if (seq !== requestSeq.current) return;
      setEvents((prev) => [...prev, ...page.events]);
      setCursor(page.cursor);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setError((err as ApiError)?.message ?? t('auditLog.loadError'));
    } finally {
      if (seq === requestSeq.current) setLoadingMore(false);
    }
  }, [cursor, loadingMore, activeTargetId]);

  const visibleEvents = useMemo(
    () => filterEventsByAction(events, actionFilter),
    [events, actionFilter],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('auditLog.title')}</h1>
        <p className={styles.subtitle}>{t('auditLog.subtitle')}</p>
      </header>

      <form className={styles.filters} onSubmit={handleFilterSubmit}>
        <div className={styles.filterGroup}>
          <label className={styles.label} htmlFor="audit-target-id">
            {t('auditLog.targetIdLabel')}
          </label>
          <div className={styles.filterRow}>
            <input
              id="audit-target-id"
              className={styles.input}
              type="text"
              value={targetIdInput}
              onChange={(e) => setTargetIdInput(e.target.value)}
              placeholder={t('auditLog.targetIdPlaceholder')}
            />
            <button
              type="submit"
              className={styles.primaryButton}
              disabled={loading || loadingMore}
            >
              {t('auditLog.applyFilter')}
            </button>
            {activeTargetId && (
              <button
                type="button"
                className={styles.secondaryButton}
                onClick={handleClearFilter}
                disabled={loading || loadingMore}
              >
                {t('auditLog.clearFilter')}
              </button>
            )}
          </div>
        </div>

        <div className={styles.filterGroup}>
          <label className={styles.label} htmlFor="audit-action-filter">
            {t('auditLog.actionFilterLabel')}
          </label>
          <select
            id="audit-action-filter"
            className={styles.select}
            value={actionFilter}
            onChange={(e) => setActionFilter(e.target.value)}
          >
            <option value="">{t('auditLog.allActions')}</option>
            {KNOWN_AUDIT_ACTIONS.map((action) => (
              <option key={action} value={action}>
                {actionLabel(action)}
              </option>
            ))}
          </select>
        </div>
      </form>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      {loading ? (
        <p className={styles.statusText} aria-live="polite" aria-busy="true">
          {t('auditLog.loading')}
        </p>
      ) : visibleEvents.length === 0 ? (
        // Suppress the empty state on error (the error banner above already
        // explains it); but when rows are already loaded, keep rendering the
        // table even if a later load-more failed.
        error ? null : (
          <p className={styles.statusText} aria-live="polite">
            {t('auditLog.empty')}
          </p>
        )
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table} aria-label={t('auditLog.tableLabel')}>
            <thead>
              <tr>
                <th scope="col">{t('auditLog.columnAction')}</th>
                <th scope="col">{t('auditLog.columnActor')}</th>
                <th scope="col">{t('auditLog.columnTarget')}</th>
                <th scope="col">{t('auditLog.columnReason')}</th>
                <th scope="col">{t('auditLog.columnTime')}</th>
              </tr>
            </thead>
            <tbody>
              {visibleEvents.map((event) => (
                <tr key={event.id}>
                  <td>
                    <span className={styles.actionLabel}>{actionLabel(event.action)}</span>
                    <span className={styles.actionCode}>{event.action || '–'}</span>
                  </td>
                  <td className={styles.monoCell} title={event.adminId}>
                    {event.adminId || '–'}
                  </td>
                  <td className={styles.monoCell} title={event.targetId}>
                    {event.targetType && <span className={styles.targetType}>{event.targetType}</span>}
                    {event.targetId || '–'}
                  </td>
                  <td className={styles.reasonCell}>{event.reason || '–'}</td>
                  <td className={styles.timeCell}>{formatDateTime(event.createdAt)}</td>
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
            {loadingMore ? t('auditLog.loadingMore') : t('auditLog.loadMore')}
          </button>
        </div>
      )}
    </div>
  );
}
