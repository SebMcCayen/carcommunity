'use client';

/**
 * Event chat moderation page — admin portal.
 *
 * Shows reported chat messages with moderation context.
 * Allows admins to remove messages with a required reason.
 *
 * Security notes:
 * - Backend verifies admin role for all operations.
 * - Message content is rendered as plain text only — never as raw HTML.
 * - Reporter identities are not exposed.
 * - Removal preserves the message for audit purposes.
 */

import { useCallback, useEffect, useState } from 'react';
import styles from './page.module.css';

import {
  formatModerationState,
  formatReportReason,
  formatReportStatus,
  loadAdminChatMessages,
  loadAdminChatReports,
  removeAdminChatMessage,
  type AdminEventChatMessageSummary,
  type AdminEventChatReportSummary,
  type ApiError,
} from '@/features/event-chat';

type Tab = 'messages' | 'reports';

// ---------------------------------------------------------------------------
// Remove message dialog
// ---------------------------------------------------------------------------

interface RemoveDialogProps {
  message: AdminEventChatMessageSummary;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  isSubmitting: boolean;
}

function RemoveDialog({ message, onConfirm, onCancel, isSubmitting }: RemoveDialogProps) {
  const [reason, setReason] = useState('');
  const reasonTrimmed = reason.trim();

  return (
    <div className={styles.dialogOverlay} role="dialog" aria-modal="true" aria-label="Ta bort meddelande">
      <div className={styles.dialog}>
        <h3 className={styles.dialogTitle}>Ta bort meddelande</h3>
        <p className={styles.dialogMeta}>
          Av: <strong>{message.author.displayName ?? 'Okänd'}</strong>
        </p>
        {/* Plain text only — never render as HTML */}
        <p className={styles.dialogExcerpt}>{message.message.slice(0, 200)}</p>
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
// Messages tab
// ---------------------------------------------------------------------------

interface MessagesTabProps {
  messages: AdminEventChatMessageSummary[];
  isLoading: boolean;
  error: string | null;
  onRemove: (msg: AdminEventChatMessageSummary) => void;
}

function MessagesTab({ messages, isLoading, error, onRemove }: MessagesTabProps) {
  if (isLoading) return <p className={styles.statusText}>Laddar meddelanden…</p>;
  if (error) return <p className={styles.errorText}>{error}</p>;
  if (messages.length === 0) return <p className={styles.statusText}>Inga meddelanden hittades.</p>;

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>Händelse</th>
          <th>Avsändare</th>
          <th>Meddelande</th>
          <th>Datum</th>
          <th>Rapporter</th>
          <th>Status</th>
          <th>Åtgärder</th>
        </tr>
      </thead>
      <tbody>
        {messages.map((msg) => (
          <tr key={msg.id} className={msg.moderationState === 'removed' ? styles.removedRow : undefined}>
            <td className={styles.idCell} title={msg.eventId}>{msg.eventId.slice(0, 8)}…</td>
            <td>{msg.author.displayName ?? 'Okänd'}</td>
            {/* Plain text only — content is NOT rendered as HTML */}
            <td className={styles.excerptCell}>{msg.message.slice(0, 80)}{msg.message.length > 80 ? '…' : ''}</td>
            <td>{new Date(msg.createdAt).toLocaleDateString('sv-SE')}</td>
            <td>{msg.reportCount}</td>
            <td>
              <span className={msg.moderationState === 'removed' ? styles.badgeRemoved : styles.badgeVisible}>
                {formatModerationState(msg.moderationState)}
              </span>
            </td>
            <td>
              {msg.moderationState === 'visible' && (
                <button
                  type="button"
                  className={styles.actionButton}
                  onClick={() => onRemove(msg)}
                >
                  Ta bort
                </button>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ---------------------------------------------------------------------------
// Reports tab
// ---------------------------------------------------------------------------

interface ReportsTabProps {
  reports: AdminEventChatReportSummary[];
  isLoading: boolean;
  error: string | null;
}

function ReportsTab({ reports, isLoading, error }: ReportsTabProps) {
  if (isLoading) return <p className={styles.statusText}>Laddar rapporter…</p>;
  if (error) return <p className={styles.errorText}>{error}</p>;
  if (reports.length === 0) return <p className={styles.statusText}>Inga rapporter hittades.</p>;

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>Meddelande-ID</th>
          <th>Anledning</th>
          <th>Status</th>
          <th>Detaljer</th>
          <th>Datum</th>
          <th>Granskad</th>
        </tr>
      </thead>
      <tbody>
        {reports.map((report) => (
          <tr key={report.id}>
            <td className={styles.idCell} title={report.messageId}>{report.messageId.slice(0, 8)}…</td>
            <td>{formatReportReason(report.reason)}</td>
            <td>
              <span className={styles.badge}>{formatReportStatus(report.status)}</span>
            </td>
            {/* Plain text only */}
            <td>{report.details ?? '–'}</td>
            <td>{new Date(report.createdAt).toLocaleDateString('sv-SE')}</td>
            <td>{report.reviewedAt ? new Date(report.reviewedAt).toLocaleDateString('sv-SE') : '–'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function EventChatModerationPage() {
  const [activeTab, setActiveTab] = useState<Tab>('messages');

  const [messages, setMessages] = useState<AdminEventChatMessageSummary[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [messagesError, setMessagesError] = useState<string | null>(null);

  const [reports, setReports] = useState<AdminEventChatReportSummary[]>([]);
  const [reportsLoading, setReportsLoading] = useState(false);
  const [reportsError, setReportsError] = useState<string | null>(null);

  const [removeTarget, setRemoveTarget] = useState<AdminEventChatMessageSummary | null>(null);
  const [isRemoving, setIsRemoving] = useState(false);

  const fetchMessages = useCallback(async () => {
    setMessagesLoading(true);
    setMessagesError(null);
    try {
      const res = await loadAdminChatMessages();
      setMessages(res.data.messages);
    } catch (err) {
      setMessagesError('Kunde inte ladda meddelanden. Försök igen.');
      console.error('Failed to load admin chat messages:', err instanceof Error ? err.message : String(err));
    } finally {
      setMessagesLoading(false);
    }
  }, []);

  const fetchReports = useCallback(async () => {
    setReportsLoading(true);
    setReportsError(null);
    try {
      const res = await loadAdminChatReports();
      setReports(res.data.reports);
    } catch (err) {
      setReportsError('Kunde inte ladda rapporter. Försök igen.');
      console.error('Failed to load admin chat reports:', err instanceof Error ? err.message : String(err));
    } finally {
      setReportsLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchMessages();
    void fetchReports();
  }, [fetchMessages, fetchReports]);

  const handleRemoveConfirm = useCallback(
    async (reason: string) => {
      if (!removeTarget) return;
      setIsRemoving(true);
      try {
        const res = await removeAdminChatMessage(removeTarget.id, reason);
        setMessages((prev) =>
          prev.map((m) => (m.id === removeTarget.id ? res.data.message : m)),
        );
        setRemoveTarget(null);
        // Refresh reports since removal resolves open reports
        void fetchReports();
      } catch (err) {
        console.error('Failed to remove chat message:', err instanceof Error ? err.message : String(err));
        alert('Kunde inte ta bort meddelandet. Försök igen.');
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

      <nav className={styles.tabs} aria-label="Chat moderation tabs">
        <button
          type="button"
          className={`${styles.tab} ${activeTab === 'messages' ? styles.tabActive : ''}`}
          onClick={() => setActiveTab('messages')}
          aria-current={activeTab === 'messages' ? 'page' : undefined}
        >
          Meddelanden
        </button>
        <button
          type="button"
          className={`${styles.tab} ${activeTab === 'reports' ? styles.tabActive : ''}`}
          onClick={() => setActiveTab('reports')}
          aria-current={activeTab === 'reports' ? 'page' : undefined}
        >
          Rapporterade meddelanden
        </button>
      </nav>

      <main className={styles.content}>
        {activeTab === 'messages' && (
          <MessagesTab
            messages={messages}
            isLoading={messagesLoading}
            error={messagesError}
            onRemove={setRemoveTarget}
          />
        )}
        {activeTab === 'reports' && (
          <ReportsTab
            reports={reports}
            isLoading={reportsLoading}
            error={reportsError}
          />
        )}
      </main>

      {removeTarget && (
        <RemoveDialog
          message={removeTarget}
          onConfirm={(reason) => void handleRemoveConfirm(reason)}
          onCancel={() => setRemoveTarget(null)}
          isSubmitting={isRemoving}
        />
      )}
    </div>
  );
}
