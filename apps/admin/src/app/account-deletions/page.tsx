'use client';

/**
 * Admin: Account deletion requests (Kontoraderingar) queue — Phase 13o.
 *
 * Lists accountDeletionRequests (pending oldest-first — queue semantics),
 * shows when each request falls due for the scheduled 30-day hard purge
 * (account-purgeDeleted), and lets an admin mark a manually handled request
 * as processed.
 *
 * Security/privacy notes:
 *  - Only the UID, optional user-supplied reason, timestamps, and status are
 *    shown — no personal data is read from the (soft-deleted) user document.
 *  - Marking processed is the rules-granted direct admin status update; the
 *    confirm dialog warns that it removes the request from the automatic
 *    purge queue (the sweep only processes status == 'pending').
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  adminListAccountDeletionRequests,
  daysUntilPurge,
  markAccountDeletionProcessed,
  type AccountDeletionStatusFilter,
  type AdminAccountDeletionRequest,
  type ApiError,
} from '@/features/account-deletions';
import { translate } from '@/i18n';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const FILTERS: readonly AccountDeletionStatusFilter[] = ['pending', 'processed', 'all'];

function formatDateTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('sv-SE') : '–';
}

/** "X dagar" until the purge window opens; due/overdue and unknown states. */
function purgeCountdownLabel(request: AdminAccountDeletionRequest): string {
  if (request.status === 'processed') return '–';
  const days = daysUntilPurge(request.createdAt);
  if (days == null) return '–';
  if (days <= 0) return t('accountDeletions.purgeDue');
  return `${days} ${t(days === 1 ? 'accountDeletions.day' : 'accountDeletions.days')}`;
}

export default function AccountDeletionsPage() {
  const [filter, setFilter] = useState<AccountDeletionStatusFilter>('pending');
  const [requests, setRequests] = useState<AdminAccountDeletionRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [actingUid, setActingUid] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const actingRef = useRef(false);
  // Monotonic request id: tab switches and post-action reloads can overlap, so
  // only the most recently started load is allowed to commit state — a slow
  // earlier request must not overwrite newer results or clear a fresh loading.
  const loadSeqRef = useRef(0);

  const load = useCallback(async (activeFilter: AccountDeletionStatusFilter) => {
    const seq = ++loadSeqRef.current;
    setLoading(true);
    setError(null);
    try {
      const rows = await adminListAccountDeletionRequests(activeFilter);
      if (seq !== loadSeqRef.current) return;
      setRequests(rows);
    } catch (err) {
      if (seq !== loadSeqRef.current) return;
      setRequests([]);
      setError((err as ApiError)?.message ?? t('accountDeletions.loadError'));
    } finally {
      // Only the latest load owns the loading flag; a superseded one leaves it
      // to the request that overtook it.
      if (seq === loadSeqRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(filter);
  }, [filter, load]);

  const handleMarkProcessed = useCallback(
    async (uid: string) => {
      if (actingRef.current) return;
      if (!window.confirm(t('accountDeletions.confirmMarkProcessed'))) return;
      actingRef.current = true;
      setActingUid(uid);
      setActionError(null);
      setSuccessMessage(null);
      try {
        const result = await markAccountDeletionProcessed(uid);
        await load(filter);
        setSuccessMessage(
          t(
            result.alreadyProcessed
              ? 'accountDeletions.alreadyProcessed'
              : 'accountDeletions.markProcessedSuccess',
          ),
        );
      } catch (err) {
        setActionError((err as ApiError)?.message ?? t('accountDeletions.markProcessedError'));
      } finally {
        setActingUid(null);
        actingRef.current = false;
      }
    },
    [filter, load],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('accountDeletions.title')}</h1>
        <p className={styles.subtitle}>{t('accountDeletions.subtitle')}</p>
      </header>

      <div className={styles.tabs} role="group" aria-label={t('accountDeletions.title')}>
        {FILTERS.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={filter === value}
            className={filter === value ? styles.tabActive : styles.tab}
            onClick={() => setFilter(value)}
            // Lock the filter while a mark-processed action is in flight: its
            // post-action load(filter) captured the filter at click time, so a
            // mid-action switch would repaint with wrong-filter rows plus an
            // unrelated success banner.
            disabled={(loading && filter === value) || actingUid !== null}
          >
            {t(`accountDeletions.tabs.${value}`)}
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
        <p className={styles.muted}>{t('accountDeletions.loading')}</p>
      ) : requests.length === 0 ? (
        <p className={styles.muted}>{t('accountDeletions.empty')}</p>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>{t('accountDeletions.table.userId')}</th>
                <th>{t('accountDeletions.table.reason')}</th>
                <th>{t('accountDeletions.table.requestedAt')}</th>
                <th>{t('accountDeletions.table.purgeIn')}</th>
                <th>{t('accountDeletions.table.status')}</th>
                <th>{t('accountDeletions.table.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((request) => (
                <tr key={request.userId}>
                  <td className={styles.uidCell}>{request.userId}</td>
                  <td className={styles.reasonCell}>
                    {request.reason ?? (
                      <span className={styles.muted}>{t('accountDeletions.noReason')}</span>
                    )}
                  </td>
                  <td>{formatDateTime(request.createdAt)}</td>
                  <td>{purgeCountdownLabel(request)}</td>
                  <td>
                    <span
                      className={
                        request.status === 'processed' ? styles.badgeProcessed : styles.badgePending
                      }
                    >
                      {t(`accountDeletions.status.${request.status}`)}
                    </span>
                  </td>
                  <td>
                    {request.status === 'pending' ? (
                      <button
                        type="button"
                        className={styles.actionButton}
                        onClick={() => void handleMarkProcessed(request.userId)}
                        disabled={actingUid !== null}
                      >
                        {actingUid === request.userId
                          ? t('accountDeletions.markProcessedPending')
                          : t('accountDeletions.markProcessed')}
                      </button>
                    ) : (
                      <span className={styles.muted}>{formatDateTime(request.processedAt)}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
