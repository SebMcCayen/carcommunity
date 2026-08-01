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
  NEVER_ONBOARDED_CONFIRM_TOKEN,
  adminListAccountDeletionRequests,
  daysUntilPurge,
  markAccountDeletionProcessed,
  previewNeverOnboardedPurge,
  runNeverOnboardedPurge,
  type AccountDeletionStatusFilter,
  type AdminAccountDeletionRequest,
  type ApiError,
  type NeverOnboardedPreview,
} from '@/features/account-deletions';
import { translate } from '@/i18n';
import { formatDate } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const FILTERS: readonly AccountDeletionStatusFilter[] = ['pending', 'processed', 'all'];

function formatDateTime(iso: string | null): string {
  return formatDate(iso);
}

/** "X dagar" until the purge window opens; due/overdue and unknown states. */
function purgeCountdownLabel(request: AdminAccountDeletionRequest): string {
  if (request.status === 'processed') return '–';
  const days = daysUntilPurge(request.createdAt);
  if (days == null) return '–';
  if (days <= 0) return t('accountDeletions.purgeDue');
  return `${days} ${t(days === 1 ? 'accountDeletions.day' : 'accountDeletions.days')}`;
}

const p = (key: string) => t(`neverOnboardedPurge.${key}`);

/**
 * One-off maintenance: preview → typed-confirm delete of never-onboarded
 * accounts (admin.purgeNeverOnboarded). The preview (dryRun) shows the count +
 * uids and how many admin/owner accounts were protected; the delete button is
 * enabled only after a preview has loaded a non-zero count AND the operator has
 * typed the exact confirm sentinel. The backend independently re-checks the
 * sentinel and re-selects the accounts, so the UI gate is convenience, not the
 * security boundary.
 */
function NeverOnboardedPurgeSection() {
  const [preview, setPreview] = useState<NeverOnboardedPreview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [confirmText, setConfirmText] = useState('');
  const [purging, setPurging] = useState(false);
  const [purgeError, setPurgeError] = useState<string | null>(null);
  const [purgeSuccess, setPurgeSuccess] = useState<string | null>(null);

  const busy = previewing || purging;

  const handlePreview = useCallback(async () => {
    if (busy) return;
    setPreviewing(true);
    setPreviewError(null);
    setPurgeError(null);
    setPurgeSuccess(null);
    try {
      setPreview(await previewNeverOnboardedPurge());
    } catch (err) {
      setPreview(null);
      setPreviewError((err as ApiError)?.message ?? p('previewError'));
    } finally {
      setPreviewing(false);
    }
  }, [busy]);

  const handlePurge = useCallback(async () => {
    if (busy || !preview || preview.candidateCount === 0) return;
    if (confirmText !== NEVER_ONBOARDED_CONFIRM_TOKEN) return;
    if (
      !window.confirm(
        `${p('confirmDialogPrefix')} ${preview.candidateCount} ${p('confirmDialogSuffix')}`,
      )
    ) {
      return;
    }
    setPurging(true);
    setPurgeError(null);
    setPurgeSuccess(null);
    try {
      const result = await runNeverOnboardedPurge(confirmText);
      setPurgeSuccess(
        `${p('purgeSuccessPrefix')} ${result.purgedCount} ${p('purgeSuccessSuffix')}`,
      );
      setConfirmText('');
      // Refresh the preview so the operator sees the remaining count (0, or the
      // next batch if the run was capped).
      setPreview(await previewNeverOnboardedPurge());
    } catch (err) {
      setPurgeError((err as ApiError)?.message ?? p('purgeError'));
    } finally {
      setPurging(false);
    }
  }, [busy, preview, confirmText]);

  const canDelete =
    !busy &&
    preview !== null &&
    preview.candidateCount > 0 &&
    confirmText === NEVER_ONBOARDED_CONFIRM_TOKEN;

  return (
    <section className={styles.maintenance} aria-label={p('title')}>
      <h2 className={styles.maintenanceTitle}>{p('title')}</h2>
      <p className={styles.subtitle}>{p('subtitle')}</p>

      <div className={styles.buttonRow}>
        <button
          type="button"
          className={styles.actionButton}
          onClick={() => void handlePreview()}
          disabled={busy}
        >
          {previewing ? p('previewPending') : p('previewButton')}
        </button>
      </div>

      {previewError && (
        <p className={styles.error} role="alert">
          {previewError}
        </p>
      )}
      {purgeError && (
        <p className={styles.error} role="alert">
          {purgeError}
        </p>
      )}
      {purgeSuccess && (
        <p className={styles.success} role="status">
          {purgeSuccess}
        </p>
      )}

      {preview && (
        <>
          <div className={styles.statRow}>
            <div className={styles.stat}>
              <span className={styles.statLabel}>{p('candidateCountLabel')}</span>
              <span className={styles.statValue}>{preview.candidateCount}</span>
            </div>
            <div className={styles.stat}>
              <span className={styles.statLabel}>{p('excludedLabel')}</span>
              <span className={styles.statValue}>{preview.excludedAdminOwnerCount}</span>
            </div>
          </div>

          {preview.capped && (
            <p className={styles.warning} role="alert">
              {p('cappedWarning')}
            </p>
          )}

          {preview.candidateCount === 0 ? (
            <p className={styles.muted}>{p('noCandidates')}</p>
          ) : (
            <>
              <div className={styles.tableWrap}>
                <table className={styles.table} aria-label={p('title')}>
                  <thead>
                    <tr>
                      <th scope="col">{p('table.uid')}</th>
                      <th scope="col">{p('table.createdAt')}</th>
                      <th scope="col">{p('table.hasUserPrivate')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.candidates.map((candidate) => (
                      <tr key={candidate.uid}>
                        <td className={styles.uidCell}>{candidate.uid}</td>
                        <td>{formatDateTime(candidate.createdAt)}</td>
                        <td>{candidate.hasUserPrivate ? p('yes') : p('no')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className={styles.buttonRow}>
                <label className={styles.statLabel} htmlFor="purge-confirm">
                  {p('confirmLabel')}
                </label>
                <input
                  id="purge-confirm"
                  className={styles.confirmInput}
                  type="text"
                  autoComplete="off"
                  placeholder={p('confirmPlaceholder')}
                  value={confirmText}
                  onChange={(event) => setConfirmText(event.target.value)}
                  disabled={busy}
                />
                <button
                  type="button"
                  className={styles.dangerButton}
                  onClick={() => void handlePurge()}
                  disabled={!canDelete}
                >
                  {purging
                    ? p('deletePending')
                    : `${p('deleteButton')} (${preview.candidateCount})`}
                </button>
              </div>
            </>
          )}
        </>
      )}
    </section>
  );
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
      // A superseded load neither mutates state nor rethrows — the request
      // that overtook it owns the outcome.
      if (seq !== loadSeqRef.current) return;
      setRequests([]);
      setError((err as ApiError)?.message ?? t('accountDeletions.loadError'));
      // Rethrow so a caller awaiting the reload (handleMarkProcessed) sees the
      // failure and skips its success banner. The error state is already set,
      // so callers that ignore the rejection still surface it in the UI.
      throw err;
    } finally {
      // Only the latest load owns the loading flag; a superseded one leaves it
      // to the request that overtook it.
      if (seq === loadSeqRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    // load() rethrows on failure; the error is already reflected in state, so
    // swallow the rejection here to avoid an unhandled promise rejection.
    void load(filter).catch(() => {});
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
        <p className={styles.muted} aria-live="polite" aria-busy="true">
          {t('accountDeletions.loading')}
        </p>
      ) : requests.length === 0 ? (
        <p className={styles.muted}>{t('accountDeletions.empty')}</p>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table} aria-label={t('accountDeletions.tableLabel')}>
            <thead>
              <tr>
                <th scope="col">{t('accountDeletions.table.userId')}</th>
                <th scope="col">{t('accountDeletions.table.reason')}</th>
                <th scope="col">{t('accountDeletions.table.requestedAt')}</th>
                <th scope="col">{t('accountDeletions.table.purgeIn')}</th>
                <th scope="col">{t('accountDeletions.table.status')}</th>
                <th scope="col">{t('accountDeletions.table.actions')}</th>
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

      <NeverOnboardedPurgeSection />
    </div>
  );
}
