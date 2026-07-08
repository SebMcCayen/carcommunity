'use client';

/**
 * Admin: User detail + moderation page (Phase 13l).
 *
 * Shows the admin-safe profile/status for a single user and exposes the
 * audited moderation actions: warn, suspend, restore access, and grant/revoke
 * the admin role.
 *
 * Security notes:
 *  - All fields come from the rules-gated `users/{uid}` document. The
 *    owner-only `userPrivate/{uid}` document is DELIBERATELY never read here.
 *  - Every action goes through an audited admin.* callable; the backend
 *    re-verifies the admin claim, re-guards the target (admins cannot moderate
 *    owners; no self-moderation/-elevation), and records the mandatory reason.
 *  - Destructive actions (suspend, admin-role changes) require an explicit
 *    confirmation step.
 *  - A re-entry guard (useRef) plus disabled buttons prevent double-submits.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';

import {
  adminGetUser,
  adminRestoreAccess,
  adminSetAdminRole,
  adminSuspendUser,
  adminWarnUser,
  type AdminUserDetail,
  type ApiError,
} from '@/features/users';
import { translate } from '@/i18n';
import { formatDateOnly } from '@/lib/format';

import { UserPointsSection } from './PointsSection';
import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const ROLE_LABELS: Record<string, string> = {
  user: 'users.role.user',
  admin: 'users.role.admin',
  owner: 'users.role.owner',
};

function roleLabel(role: string): string {
  const key = ROLE_LABELS[role];
  return key ? t(key) : role;
}

function formatDate(iso: string | null): string {
  return formatDateOnly(iso);
}

/** Actions that require an explicit confirmation step before firing. */
type ConfirmableAction = 'suspend' | 'grantAdmin' | 'revokeAdmin';

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();

  const [detail, setDetail] = useState<AdminUserDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [reason, setReason] = useState('');
  const [acting, setActing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<ConfirmableAction | null>(null);

  const actingRef = useRef(false);

  const load = useCallback(async (uid: string) => {
    setLoading(true);
    setError(null);
    setSuccessMessage(null);
    setActionError(null);
    try {
      const result = await adminGetUser(uid);
      setDetail(result);
    } catch (err) {
      setDetail(null);
      setError((err as ApiError)?.message ?? t('users.detail.loadError'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (id) void load(id);
  }, [id, load]);

  const runAction = useCallback(
    async (
      action: 'warn' | 'suspend' | 'restore' | 'grantAdmin' | 'revokeAdmin',
      successKey: string,
    ) => {
      if (!detail) return;
      const trimmedReason = reason.trim();
      if (!trimmedReason || actingRef.current) return;
      actingRef.current = true;
      setActing(true);
      setActionError(null);
      setSuccessMessage(null);
      setPendingConfirm(null);
      try {
        switch (action) {
          case 'warn':
            await adminWarnUser(detail.uid, trimmedReason);
            break;
          case 'suspend':
            await adminSuspendUser(detail.uid, trimmedReason);
            break;
          case 'restore':
            await adminRestoreAccess(detail.uid, trimmedReason);
            break;
          case 'grantAdmin':
            await adminSetAdminRole(detail.uid, true, trimmedReason);
            break;
          case 'revokeAdmin':
            await adminSetAdminRole(detail.uid, false, trimmedReason);
            break;
        }
        setReason('');
        // Refresh first — load() clears successMessage at its start — then set
        // the banner so it stays visible after the refresh completes.
        await load(detail.uid);
        setSuccessMessage(t(successKey));
      } catch (err) {
        setActionError((err as ApiError)?.message ?? t('users.detail.actionError'));
      } finally {
        setActing(false);
        actingRef.current = false;
      }
    },
    [detail, reason, load],
  );

  const confirmLabels: Record<ConfirmableAction, { message: string; run: () => void }> = {
    suspend: {
      message: t('users.detail.confirmSuspend'),
      run: () => void runAction('suspend', 'users.detail.suspendSuccess'),
    },
    grantAdmin: {
      message: t('users.detail.confirmGrantAdmin'),
      run: () => void runAction('grantAdmin', 'users.detail.grantAdminSuccess'),
    },
    revokeAdmin: {
      message: t('users.detail.confirmRevokeAdmin'),
      run: () => void runAction('revokeAdmin', 'users.detail.revokeAdminSuccess'),
    },
  };

  if (!id) {
    return <Navigate to="/users" replace />;
  }

  const hasReason = reason.trim().length > 0;
  const isAdminRole = detail?.role === 'admin';
  const isOwner = detail?.role === 'owner';

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <Link className={styles.backLink} to="/users">
          {t('users.detail.back')}
        </Link>
        <h1 className={styles.title}>
          {detail?.displayName || (loading ? id : t('users.unnamed'))}
        </h1>
        <p className={styles.uid}>{id}</p>
      </header>

      {loading ? (
        <p className={styles.meta} aria-live="polite" aria-busy="true">
          {t('users.detail.loading')}
        </p>
      ) : error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : !detail ? (
        <p className={styles.meta}>{t('users.detail.notFound')}</p>
      ) : (
        <>
          {detail.suspended && (
            <p className={styles.warning} role="alert">
              {t('users.detail.suspendedBanner')}
            </p>
          )}
          {detail.deleted && (
            <p className={styles.warning} role="alert">
              {t('users.detail.deletedBanner')}
            </p>
          )}

          <section className={styles.card}>
            <h2 className={styles.cardTitle}>{t('users.detail.profileTitle')}</h2>
            <dl className={styles.detailGrid}>
              <dt>{t('users.detail.roleLabel')}</dt>
              <dd>{roleLabel(detail.role)}</dd>
              <dt>{t('users.detail.memberLabel')}</dt>
              <dd>{detail.activeMember ? t('users.member.yes') : t('users.member.no')}</dd>
              <dt>{t('users.detail.statusLabel')}</dt>
              <dd>
                {detail.deleted
                  ? t('users.status.deleted')
                  : detail.suspended
                    ? t('users.status.suspended')
                    : t('users.status.active')}
              </dd>
              <dt>{t('users.detail.bioLabel')}</dt>
              <dd>{detail.bio || '–'}</dd>
              <dt>{t('users.detail.createdLabel')}</dt>
              <dd>{formatDate(detail.createdAt)}</dd>
              <dt>{t('users.detail.updatedLabel')}</dt>
              <dd>{formatDate(detail.updatedAt)}</dd>
            </dl>
            <p className={styles.meta}>
              <Link className={styles.link} to="/subscription">
                {t('users.detail.subscriptionLink')}
              </Link>
            </p>
          </section>

          <section className={styles.card}>
            <h2 className={styles.cardTitle}>{t('users.detail.actionTitle')}</h2>
            {isOwner && <p className={styles.meta}>{t('users.detail.ownerNote')}</p>}

            <label className={styles.label} htmlFor="user-reason">
              {t('users.detail.reasonLabel')} <span aria-hidden="true">*</span>
            </label>
            <input
              id="user-reason"
              className={styles.input}
              type="text"
              required
              autoComplete="off"
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t('users.detail.reasonPlaceholder')}
            />

            {pendingConfirm && (
              <div
                className={styles.confirm}
                role="alertdialog"
                aria-modal="true"
                aria-labelledby="user-confirm-title"
              >
                <p id="user-confirm-title" className={styles.confirmText}>
                  {confirmLabels[pendingConfirm].message}
                </p>
                <div className={styles.actions}>
                  <button
                    type="button"
                    className={styles.dangerButton}
                    onClick={confirmLabels[pendingConfirm].run}
                    disabled={acting}
                  >
                    {t('users.detail.confirm')}
                  </button>
                  <button
                    type="button"
                    className={styles.secondaryButton}
                    onClick={() => setPendingConfirm(null)}
                    disabled={acting}
                  >
                    {t('users.detail.cancel')}
                  </button>
                </div>
              </div>
            )}

            <div className={styles.actions}>
              <button
                type="button"
                className={styles.primaryButton}
                onClick={() => void runAction('warn', 'users.detail.warnSuccess')}
                disabled={!hasReason || acting}
              >
                {t('users.detail.warn')}
              </button>
              {detail.suspended ? (
                <button
                  type="button"
                  className={styles.primaryButton}
                  onClick={() => void runAction('restore', 'users.detail.restoreSuccess')}
                  disabled={!hasReason || acting}
                >
                  {t('users.detail.restore')}
                </button>
              ) : (
                <button
                  type="button"
                  className={styles.dangerButton}
                  onClick={() => setPendingConfirm('suspend')}
                  disabled={!hasReason || acting}
                >
                  {t('users.detail.suspend')}
                </button>
              )}
              {!isOwner &&
                (isAdminRole ? (
                  <button
                    type="button"
                    className={styles.dangerButton}
                    onClick={() => setPendingConfirm('revokeAdmin')}
                    disabled={!hasReason || acting}
                  >
                    {t('users.detail.revokeAdmin')}
                  </button>
                ) : (
                  <button
                    type="button"
                    className={styles.secondaryButton}
                    onClick={() => setPendingConfirm('grantAdmin')}
                    disabled={!hasReason || acting}
                  >
                    {t('users.detail.grantAdmin')}
                  </button>
                ))}
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

          <UserPointsSection userId={id} />
        </>
      )}
    </div>
  );
}
