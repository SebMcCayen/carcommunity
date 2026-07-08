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

import { useCallback, useEffect, useMemo, useState } from 'react';

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

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

/** Human-readable label for an action code; raw value for unknown actions. */
function actionLabel(action: string): string {
  const key = auditActionLabelKey(action);
  return key ? t(key) : action;
}

function formatDateTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('sv-SE') : '–';
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

  const load = useCallback(async (targetId: string) => {
    setLoading(true);
    setError(null);
    try {
      const page = await listAdminAuditEvents({ targetId: targetId || undefined });
      setEvents(page.events);
      setCursor(page.cursor);
    } catch (err) {
      setEvents([]);
      setCursor(null);
      setError((err as ApiError)?.message ?? t('auditLog.loadError'));
    } finally {
      setLoading(false);
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
    setLoadingMore(true);
    setError(null);
    try {
      const page = await listAdminAuditEvents({
        targetId: activeTargetId || undefined,
        cursor,
      });
      setEvents((prev) => [...prev, ...page.events]);
      setCursor(page.cursor);
    } catch (err) {
      setError((err as ApiError)?.message ?? t('auditLog.loadError'));
    } finally {
      setLoadingMore(false);
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
        <p className={styles.statusText}>{t('auditLog.loading')}</p>
      ) : visibleEvents.length === 0 ? (
        <p className={styles.statusText}>{t('auditLog.empty')}</p>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>{t('auditLog.columnAction')}</th>
                <th>{t('auditLog.columnActor')}</th>
                <th>{t('auditLog.columnTarget')}</th>
                <th>{t('auditLog.columnReason')}</th>
                <th>{t('auditLog.columnTime')}</th>
              </tr>
            </thead>
            <tbody>
              {visibleEvents.map((event) => (
                <tr key={event.id}>
                  <td>
                    <span className={styles.actionLabel}>{actionLabel(event.action)}</span>
                    <span className={styles.actionCode}>{event.action}</span>
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
