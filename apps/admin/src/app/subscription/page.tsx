'use client';

/**
 * Admin: Subscription (Prenumeration) page.
 *
 * Look up a user's subscription entitlement and apply a manual grant/revoke.
 *
 * Security notes:
 *  - All data comes from the backend; the admin client never writes
 *    subscriptions/{uid} (grant/revoke go through the callable).
 *  - Raw provider tokens are never shown.
 *  - A reason is mandatory and audit-logged server-side.
 *  - Store refunds/cancellations are provider-side and out of scope.
 */

import { useCallback, useState } from 'react';

import {
  adminGetUserSubscription,
  adminGrantMembership,
  adminRevokeMembership,
  type AdminUserSubscriptionSummary,
  type ApiError,
} from '@/features/subscription';

import styles from './page.module.css';

const ENTITLEMENT_LABELS: Record<string, string> = {
  none: 'Ingen',
  member_monthly: 'Medlem (månad)',
};

const STATUS_LABELS: Record<string, string> = {
  inactive: 'Inaktiv',
  active: 'Aktiv',
  grace_period: 'Respitperiod',
  expired: 'Utgången',
  revoked: 'Återkallad',
  cancelled: 'Avbruten',
};

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString('sv-SE') : '–';
}

export default function SubscriptionPage() {
  const [userId, setUserId] = useState('');
  const [summary, setSummary] = useState<AdminUserSubscriptionSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [reason, setReason] = useState('');
  const [acting, setActing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const load = useCallback(async (uid: string) => {
    setLoading(true);
    setError(null);
    setSuccessMessage(null);
    setActionError(null);
    try {
      const result = await adminGetUserSubscription(uid);
      setSummary(result);
    } catch (err) {
      setSummary(null);
      setError((err as ApiError)?.message ?? 'Kunde inte hämta prenumerationen.');
    } finally {
      setLoading(false);
    }
  }, []);

  const handleLookup = (e: React.FormEvent) => {
    e.preventDefault();
    const uid = userId.trim();
    if (uid) void load(uid);
  };

  const handleAction = useCallback(
    async (action: 'grant' | 'revoke') => {
      if (!summary) return;
      const trimmedReason = reason.trim();
      if (!trimmedReason || acting) return;
      setActing(true);
      setActionError(null);
      setSuccessMessage(null);
      try {
        if (action === 'grant') {
          await adminGrantMembership(summary.userId, trimmedReason);
          setSuccessMessage('Medlemskap beviljat.');
        } else {
          await adminRevokeMembership(summary.userId, trimmedReason);
          setSuccessMessage('Medlemskap återkallat.');
        }
        setReason('');
        await load(summary.userId);
      } catch (err) {
        setActionError((err as ApiError)?.message ?? 'Åtgärden misslyckades.');
      } finally {
        setActing(false);
      }
    },
    [summary, reason, acting, load],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>Prenumeration</h1>
        <p className={styles.subtitle}>
          Slå upp en användares medlemskap och bevilja eller återkalla det manuellt.
        </p>
      </header>

      <form className={styles.lookupForm} onSubmit={handleLookup}>
        <label className={styles.label} htmlFor="sub-user-id">
          Användar-ID
        </label>
        <div className={styles.lookupRow}>
          <input
            id="sub-user-id"
            className={styles.input}
            type="text"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            placeholder="uid"
          />
          <button type="submit" className={styles.primaryButton} disabled={!userId.trim() || loading}>
            {loading ? 'Hämtar…' : 'Slå upp'}
          </button>
        </div>
      </form>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      {summary && (
        <section className={styles.card}>
          <h2 className={styles.cardTitle}>Nuvarande status</h2>
          {summary.isSuspendedWithActiveSubscription && (
            <p className={styles.warning} role="alert">
              Varning: användaren är avstängd men har fortfarande en aktiv prenumeration.
            </p>
          )}
          <dl className={styles.detailGrid}>
            <dt>Berättigande</dt>
            <dd>{ENTITLEMENT_LABELS[summary.entitlement] ?? summary.entitlement}</dd>
            <dt>Status</dt>
            <dd>
              {summary.subscription
                ? (STATUS_LABELS[summary.subscription.status] ?? summary.subscription.status)
                : '–'}
            </dd>
            <dt>Plattform</dt>
            <dd>{summary.subscription?.platform ?? '–'}</dd>
            <dt>Utgår</dt>
            <dd>{formatDate(summary.subscription?.expiresAt ?? null)}</dd>
          </dl>

          <h3 className={styles.cardTitle}>Åtgärd</h3>
          <label className={styles.label} htmlFor="sub-reason">
            Orsak <span aria-hidden="true">*</span>
          </label>
          <input
            id="sub-reason"
            className={styles.input}
            type="text"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Ange orsak (loggas)…"
          />
          <div className={styles.actions}>
            <button
              type="button"
              className={styles.primaryButton}
              onClick={() => void handleAction('grant')}
              disabled={!reason.trim() || acting}
            >
              Bevilja medlemskap
            </button>
            <button
              type="button"
              className={styles.dangerButton}
              onClick={() => void handleAction('revoke')}
              disabled={!reason.trim() || acting}
            >
              Återkalla
            </button>
          </div>

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
        </section>
      )}
    </div>
  );
}
