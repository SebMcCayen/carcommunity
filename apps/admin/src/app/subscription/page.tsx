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

import { useCallback, useRef, useState } from 'react';

import {
  adminGetUserSubscription,
  adminGrantMembership,
  adminRevokeMembership,
  type AdminUserSubscriptionSummary,
  type ApiError,
} from '@/features/subscription';
import { translate } from '@/i18n';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const ENTITLEMENT_LABELS: Record<string, string> = {
  none: 'subscription.entitlement.none',
  member_monthly: 'subscription.entitlement.member_monthly',
};

const STATUS_LABELS: Record<string, string> = {
  inactive: 'subscription.status.inactive',
  active: 'subscription.status.active',
  grace_period: 'subscription.status.grace_period',
  expired: 'subscription.status.expired',
  revoked: 'subscription.status.revoked',
  cancelled: 'subscription.status.cancelled',
};

const PLATFORM_LABELS: Record<string, string> = {
  manual: 'subscription.platform.manual',
  apple: 'subscription.platform.apple',
  google: 'subscription.platform.google',
};

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString('sv-SE') : '–';
}

function entitlementLabel(entitlement: string): string {
  const key = ENTITLEMENT_LABELS[entitlement];
  return key ? t(key) : entitlement;
}

function statusLabel(status: string): string {
  const key = STATUS_LABELS[status];
  return key ? t(key) : status;
}

function platformLabel(platform: string): string {
  const key = PLATFORM_LABELS[platform];
  return key ? t(key) : platform;
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

  const actingRef = useRef(false);

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
      setError((err as ApiError)?.message ?? t('subscription.lookupError'));
    } finally {
      setLoading(false);
    }
  }, []);

  const handleLookup = (e: React.FormEvent) => {
    e.preventDefault();
    // Block a lookup while a grant/revoke is in flight — otherwise its refresh
    // (load after the action) can race/overwrite the manual lookup.
    if (actingRef.current) return;
    const uid = userId.trim();
    if (uid) void load(uid);
  };

  const handleAction = useCallback(
    async (action: 'grant' | 'revoke') => {
      if (!summary) return;
      const trimmedReason = reason.trim();
      if (!trimmedReason || actingRef.current) return;
      actingRef.current = true;
      setActing(true);
      setActionError(null);
      setSuccessMessage(null);
      try {
        if (action === 'grant') {
          await adminGrantMembership(summary.userId, trimmedReason);
          setSuccessMessage(t('subscription.grantSuccess'));
        } else {
          await adminRevokeMembership(summary.userId, trimmedReason);
          setSuccessMessage(t('subscription.revokeSuccess'));
        }
        setReason('');
        await load(summary.userId);
      } catch (err) {
        setActionError((err as ApiError)?.message ?? t('subscription.actionError'));
      } finally {
        setActing(false);
        actingRef.current = false;
      }
    },
    [summary, reason, load],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('subscription.title')}</h1>
        <p className={styles.subtitle}>{t('subscription.subtitle')}</p>
      </header>

      <form className={styles.lookupForm} onSubmit={handleLookup}>
        <label className={styles.label} htmlFor="sub-user-id">
          {t('subscription.userIdLabel')}
        </label>
        <div className={styles.lookupRow}>
          <input
            id="sub-user-id"
            className={styles.input}
            type="text"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            placeholder={t('subscription.userIdPlaceholder')}
          />
          <button
            type="submit"
            className={styles.primaryButton}
            disabled={!userId.trim() || loading || acting}
          >
            {loading ? t('subscription.lookupLoading') : t('subscription.lookup')}
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
          <h2 className={styles.cardTitle}>{t('subscription.currentStatusTitle')}</h2>
          {summary.isSuspendedWithActiveSubscription && (
            <p className={styles.warning} role="alert">
              {t('subscription.suspendedWarning')}
            </p>
          )}
          <dl className={styles.detailGrid}>
            <dt>{t('subscription.entitlementLabel')}</dt>
            <dd>{entitlementLabel(summary.entitlement)}</dd>
            <dt>{t('subscription.statusLabel')}</dt>
            <dd>
              {summary.subscription ? statusLabel(summary.subscription.status) : '–'}
            </dd>
            <dt>{t('subscription.platformLabel')}</dt>
            <dd>{summary.subscription ? platformLabel(summary.subscription.platform) : '–'}</dd>
            <dt>{t('subscription.expiresLabel')}</dt>
            <dd>{formatDate(summary.subscription?.expiresAt ?? null)}</dd>
          </dl>

          <h3 className={styles.cardTitle}>{t('subscription.actionTitle')}</h3>
          <label className={styles.label} htmlFor="sub-reason">
            {t('subscription.reasonLabel')} <span aria-hidden="true">*</span>
          </label>
          <input
            id="sub-reason"
            className={styles.input}
            type="text"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={t('subscription.reasonPlaceholder')}
          />
          <div className={styles.actions}>
            <button
              type="button"
              className={styles.primaryButton}
              onClick={() => void handleAction('grant')}
              disabled={!reason.trim() || acting}
            >
              {t('subscription.grant')}
            </button>
            <button
              type="button"
              className={styles.dangerButton}
              onClick={() => void handleAction('revoke')}
              disabled={!reason.trim() || acting}
            >
              {t('subscription.revoke')}
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
