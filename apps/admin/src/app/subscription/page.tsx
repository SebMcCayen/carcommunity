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

import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import {
  adminGetUserSubscription,
  adminGrantMembership,
  adminRevokeMembership,
  type AdminUserSubscriptionSummary,
  type ApiError,
} from '@/features/subscription';
import { translate } from '@/i18n';
import { formatDateOnly } from '@/lib/format';

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
  return formatDateOnly(iso);
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
  const [searchParams] = useSearchParams();
  // When the admin arrives from a specific user's profile
  // (/subscription?uid=…) the target UID is already known, so it is pre-filled
  // and locked read-only — a mistyped UID on a destructive grant/revoke is
  // dangerous. Without the param the page is a standalone lookup (reachable
  // from the sidebar) and the UID stays manually editable.
  const presetUid = (searchParams.get('uid') ?? '').trim();
  const fromProfile = presetUid.length > 0;

  const [userId, setUserId] = useState(presetUid);
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
    // Clear the previous user's summary immediately so grant/revoke (which key
    // off summary.userId) can't act on the prior user while this lookup is in
    // flight — the read-only UID field already shows the new target by now.
    setSummary(null);
    // Drop any half-typed reason too: a reason entered for the previous user
    // must never carry over to a new lookup and get applied to a different UID
    // with one click on a destructive grant/revoke.
    setReason('');
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

  // Keep the input in sync with the query param. The initial useState(presetUid)
  // only runs on first mount; the field must track presetUid on every change so
  // it never shows a stale UID. Whenever presetUid changes — whether it arrives,
  // clears, OR switches from one non-empty UID to another (navigating between two
  // profile-scoped /subscription?uid=… routes while still mounted) — we fully
  // re-scope: mirror the new UID and drop the previous user's summary, reason and
  // messages. Resetting on *every* change (not just empty↔non-empty) closes the
  // window where a stale summary/reason could target the previous UID before the
  // auto-lookup effect below reloads for the new one.
  useEffect(() => {
    setUserId(presetUid);
    setSummary(null);
    setError(null);
    setSuccessMessage(null);
    setActionError(null);
    setReason('');
  }, [presetUid]);

  // Auto-look-up when a UID arrives via the profile link, so pressing
  // "Hantera prenumeration" opens the flow already scoped to that user.
  useEffect(() => {
    if (presetUid) void load(presetUid);
  }, [presetUid, load]);

  const handleLookup = (e: React.FormEvent) => {
    e.preventDefault();
    // Block a lookup while a grant/revoke is in flight — otherwise its refresh
    // (load after the action) can race/overwrite the manual lookup.
    if (actingRef.current) return;
    // When arriving from a profile the query param is the source of truth for the
    // target UID; the field is read-only and userId mirrors presetUid, but that
    // mirror can lag by a render if the param just changed. Read presetUid
    // directly in that case so a lookup can never target a stale/divergent UID.
    const uid = (fromProfile ? presetUid : userId).trim();
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
        } else {
          await adminRevokeMembership(summary.userId, trimmedReason);
        }
        setReason('');
        // Refresh first — load() clears successMessage at its start — then set
        // the banner so it stays visible after the refresh completes.
        await load(summary.userId);
        setSuccessMessage(
          t(action === 'grant' ? 'subscription.grantSuccess' : 'subscription.revokeSuccess'),
        );
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
            readOnly={fromProfile}
            aria-readonly={fromProfile}
          />
          <button
            type="submit"
            className={styles.primaryButton}
            disabled={!userId.trim() || loading || acting}
          >
            {loading ? t('subscription.lookupLoading') : t('subscription.lookup')}
          </button>
        </div>
        {fromProfile && <p className={styles.hint}>{t('subscription.fromProfileHint')}</p>}
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
            required
            autoComplete="off"
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
