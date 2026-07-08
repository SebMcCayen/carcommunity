'use client';

/**
 * Event chat moderation page — admin portal (Phase 13h — Firebase migration).
 *
 * Reports-driven moderation queue backed by Firebase callables:
 *  - events-listChatReports lists reported messages newest-first.
 *  - events-resolveChatReport transitions a report (granska/lös/avvisa).
 *  - events-removeChatMessage soft-removes the offending message.
 *
 * The legacy cross-event "all messages" browser is intentionally not carried
 * over: chat messages are participant-only by design and the backend exposes no
 * admin message read. Every report carries its eventId + messageId, so an admin
 * moderates entirely from the report — the message body is never fetched.
 *
 * Security notes:
 * - The admin never receives message bodies (nothing is rendered as HTML).
 * - Backend verifies admin role for all operations.
 * - Reporter identities are surfaced to admins only.
 * - Removal preserves the message for audit purposes (soft-delete).
 */

import { useCallback, useEffect, useState } from 'react';
import styles from './page.module.css';
import { formatDateOnly } from '@/lib/format';

import {
  formatReportReason,
  formatReportStatus,
  loadAdminChatReports,
  removeAdminChatMessageFromReport,
  resolveAdminChatReport,
  OPEN_REPORT_STATUSES,
  type AdminEventChatReportRow,
  type ApiError,
  type ResolvableReportStatus,
} from '@/features/event-chat';

// ---------------------------------------------------------------------------
// Remove message dialog (acts on a report — no message body is available)
// ---------------------------------------------------------------------------

interface RemoveDialogProps {
  report: AdminEventChatReportRow;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  isSubmitting: boolean;
}

function RemoveDialog({ report, onConfirm, onCancel, isSubmitting }: RemoveDialogProps) {
  const [reason, setReason] = useState('');
  const reasonTrimmed = reason.trim();

  return (
    <div className={styles.dialogOverlay} role="dialog" aria-modal="true" aria-label="Ta bort meddelande">
      <div className={styles.dialog}>
        <h3 className={styles.dialogTitle}>Ta bort meddelande</h3>
        <p className={styles.dialogMeta}>
          Meddelande-ID: <strong>{report.messageId}</strong>
        </p>
        <p className={styles.dialogMeta}>Anledning: {formatReportReason(report.reason)}</p>
        <label className={styles.label} htmlFor="removal-reason">
          Orsak <span aria-hidden="true">*</span>
        </label>
        <textarea
          id="removal-reason"
          className={styles.textarea}
          rows={3}
          maxLength={2000}
          placeholder="Ange orsak till borttagning…"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={isSubmitting}
        />
        {!reasonTrimmed && (
          <p className={styles.errorHint} role="alert">
            Orsak krävs.
          </p>
        )}
        <div className={styles.dialogActions}>
          <button
            type="button"
            className={styles.cancelButton}
            onClick={onCancel}
            disabled={isSubmitting}
          >
            Avbryt
          </button>
          <button
            type="button"
            className={styles.removeButton}
            onClick={() => reasonTrimmed && onConfirm(reasonTrimmed)}
            disabled={!reasonTrimmed || isSubmitting}
            aria-busy={isSubmitting}
          >
            {isSubmitting ? 'Tar bort…' : 'Ta bort meddelande'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

const shortId = (id: string) => (id.length > 8 ? `${id.slice(0, 8)}…` : id);

export default function EventChatModerationPage() {
  const [reports, setReports] = useState<AdminEventChatReportRow[]>([]);
  const [reportsLoading, setReportsLoading] = useState(true);
  const [reportsError, setReportsError] = useState<string | null>(null);

  const [removeTarget, setRemoveTarget] = useState<AdminEventChatReportRow | null>(null);
  const [isRemoving, setIsRemoving] = useState(false);
  // Report id currently being transitioned, to disable its row buttons.
  const [pendingReportId, setPendingReportId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const fetchReports = useCallback(async () => {
    setReportsLoading(true);
    setReportsError(null);
    try {
      const res = await loadAdminChatReports();
      setReports(res.data.reports);
    } catch (err) {
      setReportsError((err as ApiError)?.message ?? 'Kunde inte ladda rapporter. Försök igen.');
    } finally {
      setReportsLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchReports();
  }, [fetchReports]);

  // Any moderation action in flight (resolve transition or message removal).
  // While true, every row's action buttons are disabled to prevent concurrent
  // actions racing against the queue refresh.
  const anyActionInFlight = pendingReportId !== null || isRemoving;

  const handleResolve = useCallback(
    async (report: AdminEventChatReportRow, status: ResolvableReportStatus) => {
      setPendingReportId(report.id);
      setActionError(null);
      try {
        await resolveAdminChatReport(report.eventId, report.id, status);
        await fetchReports();
      } catch (err) {
        setActionError((err as ApiError)?.message ?? 'Åtgärden misslyckades. Försök igen.');
      } finally {
        setPendingReportId(null);
      }
    },
    [fetchReports],
  );

  const handleRemoveConfirm = useCallback(
    async (reason: string) => {
      if (!removeTarget) return;
      setIsRemoving(true);
      setActionError(null);
      try {
        await removeAdminChatMessageFromReport(removeTarget.eventId, removeTarget.messageId, reason);
        setRemoveTarget(null);
        // Removal auto-resolves the message's open reports — refresh the queue.
        await fetchReports();
      } catch (err) {
        setActionError((err as ApiError)?.message ?? 'Kunde inte ta bort meddelandet. Försök igen.');
      } finally {
        setIsRemoving(false);
      }
    },
    [removeTarget, fetchReports],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>Eventchatt – moderering</h1>
        <p className={styles.subtitle}>Rapporterade meddelanden och moderationsåtgärder.</p>
      </header>

      {actionError && (
        <p className={styles.errorText} role="alert">
          {actionError}
        </p>
      )}

      <main className={styles.content}>
        {reportsLoading ? (
          <p className={styles.statusText}>Laddar rapporter…</p>
        ) : reportsError ? (
          <p className={styles.errorText}>{reportsError}</p>
        ) : reports.length === 0 ? (
          <p className={styles.statusText}>Inga rapporter hittades.</p>
        ) : (
          <table className={styles.table}>
            <thead>
              <tr>
                <th>Händelse</th>
                <th>Meddelande-ID</th>
                <th>Anledning</th>
                <th>Status</th>
                <th>Detaljer</th>
                <th>Datum</th>
                <th>Åtgärder</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((report) => {
                const isOpen = OPEN_REPORT_STATUSES.includes(report.status);
                // Disable this row's actions whenever ANY action is in flight,
                // not just when this specific row is transitioning — prevents
                // concurrent moderation actions across rows.
                const disableActions = anyActionInFlight;
                return (
                  <tr key={report.id}>
                    <td className={styles.idCell} title={report.eventId}>
                      {shortId(report.eventId)}
                    </td>
                    <td className={styles.idCell} title={report.messageId}>
                      {shortId(report.messageId)}
                    </td>
                    <td>{formatReportReason(report.reason)}</td>
                    <td>
                      <span className={styles.badge}>{formatReportStatus(report.status)}</span>
                    </td>
                    {/* Plain text only */}
                    <td>{report.details ?? '–'}</td>
                    <td>{formatDateOnly(report.createdAt)}</td>
                    <td>
                      {isOpen ? (
                        <div className={styles.rowActions}>
                          {report.status === 'new' && (
                            <button
                              type="button"
                              className={styles.actionButton}
                              onClick={() => void handleResolve(report, 'under_review')}
                              disabled={disableActions}
                            >
                              Granska
                            </button>
                          )}
                          <button
                            type="button"
                            className={styles.actionButton}
                            onClick={() => void handleResolve(report, 'resolved')}
                            disabled={disableActions}
                          >
                            Lös
                          </button>
                          <button
                            type="button"
                            className={styles.actionButton}
                            onClick={() => void handleResolve(report, 'dismissed')}
                            disabled={disableActions}
                          >
                            Avvisa
                          </button>
                          <button
                            type="button"
                            className={styles.removeButton}
                            onClick={() => setRemoveTarget(report)}
                            disabled={disableActions}
                          >
                            Ta bort meddelande
                          </button>
                        </div>
                      ) : (
                        <span className={styles.statusText}>
                          {report.reviewedAt
                            ? `Granskad ${formatDateOnly(report.reviewedAt)}`
                            : '–'}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </main>

      {removeTarget && (
        <RemoveDialog
          report={removeTarget}
          onConfirm={(reason) => void handleRemoveConfirm(reason)}
          onCancel={() => setRemoveTarget(null)}
          isSubmitting={isRemoving}
        />
      )}
    </div>
  );
}
