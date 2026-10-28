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

import { AlertTriangle, Settings, Circle } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';

import {
  adminDeleteUser,
  adminGetUser,
  adminSetAdminRole,
  type AdminUserDetail,
  type ApiError,
} from '@/features/users';
import { translate } from '@/i18n';
import { formatDateOnly } from '@/lib/format';

import { ActionDialog } from '@/components/ui/ActionDialog';
import { MemberOverview } from './MemberOverview';
import { MemberHistory } from './MemberHistory';
import { AccessTools } from './AccessTools';
import { MemberMessages } from './MemberMessages';
import { MemberTools } from './MemberTools';
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
type ConfirmableAction = 'grantAdmin' | 'revokeAdmin';

function initials(name: string) {
  const words = name.trim().split(/\s+/).filter(Boolean);
  const capitals = name.match(/\p{Lu}/gu) ?? [];
  return (
    (words.length > 1
      ? words
          .slice(0, 2)
          .map((word) => Array.from(word)[0])
          .join('')
      : capitals.length > 1
        ? capitals.slice(0, 2).join('')
        : Array.from(name.trim()).slice(0, 2).join('')
    ).toLocaleUpperCase('sv-SE') || '?'
  );
}

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [detail, setDetail] = useState<AdminUserDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [reason, setReason] = useState('');
  const [acting, setActing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<ConfirmableAction | null>(null);

  const sections = ['overview', 'profile', 'warnings', 'points', 'messages', 'history'] as const;
  type Section = (typeof sections)[number];
  const [section, setSection] = useState<Section>(() => {
    const value = new URLSearchParams(window.location.search).get('section');
    return sections.includes(value as Section) ? (value as Section) : 'overview';
  });
  const [showAccess, setShowAccess] = useState(false);
  function selectSection(value: string) {
    if (!sections.includes(value as Section)) return;
    setSection(value as Section);
  }
  const sectionLabels: Record<Section, string> = {
    overview: 'Översikt',
    profile: 'Profil',
    warnings: 'Varningar',
    points: 'Poäng',
    history: 'Historik',
    messages: 'Meddelanden',
  };
  const requestSequence = useRef(0);
  const actingRef = useRef(false);

  // Danger zone: irreversible full deletion. Kept in its own state so it never
  // shares the moderation reason/confirm flow. The final Confirm is gated on the
  // admin re-typing the user's display name (or the word DELETE when the account
  // has no name) — a deliberate speed-bump for an unrecoverable action.
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [deleteReason, setDeleteReason] = useState('');
  const [deleteConfirmText, setDeleteConfirmText] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const deletingRef = useRef(false);

  const load = useCallback(async (uid: string) => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(null);
    setSuccessMessage(null);
    setActionError(null);
    try {
      const result = await adminGetUser(uid);
      if (sequence === requestSequence.current) setDetail(result);
    } catch (err) {
      if (sequence !== requestSequence.current) return;
      setDetail(null);
      setError((err as ApiError)?.message ?? t('users.detail.loadError'));
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (id) void load(id);
  }, [id, load]);

  const runAction = useCallback(
    async (action: 'grantAdmin' | 'revokeAdmin', successKey: string) => {
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

  const runDelete = useCallback(async () => {
    if (!detail || deletingRef.current) return;
    deletingRef.current = true;
    setDeleting(true);
    setDeleteError(null);
    try {
      await adminDeleteUser(detail.uid, deleteReason.trim());
      // The user (and this detail record) no longer exists — leave the page. The
      // list route shows a transient success banner via router state.
      navigate('/users', {
        replace: true,
        state: {
          deletedUser: {
            // Trim so a whitespace-only name falls back to the uid rather than
            // producing a blank success banner.
            uid: detail.uid,
            displayName: detail.displayName.trim() || detail.uid,
          },
        },
      });
    } catch (err) {
      setDeleteError((err as ApiError)?.message ?? t('users.detail.deleteError'));
      setDeleting(false);
      deletingRef.current = false;
    }
  }, [detail, deleteReason, navigate]);

  const confirmLabels: Record<ConfirmableAction, { message: string; run: () => void }> = {
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

  // The exact string the admin must re-type to arm the delete: the display name,
  // or the literal DELETE when the account has no name to echo back.
  const deleteConfirmPhrase = detail?.displayName.trim() || 'DELETE';
  const deleteReady =
    deleteReason.trim().length > 0 && deleteConfirmText.trim() === deleteConfirmPhrase;

  const openDeleteDialog = () => {
    setDeleteReason('');
    setDeleteConfirmText('');
    setDeleteError(null);
    setShowDeleteDialog(true);
  };
  const closeDeleteDialog = () => {
    if (deleting) return;
    setShowDeleteDialog(false);
  };

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <Link className={styles.backLink} to="/users">
          Medlemmar
        </Link>
        <span aria-hidden="true">/</span>
        <span>{detail?.uid === id ? detail.displayName || t('users.unnamed') : 'Medlemskort'}</span>
      </header>

      {detail && detail.uid === id && !loading && !error && (
        <>
          <div className={styles.memberHero}>
            <div className={styles.avatar} aria-hidden="true">
              {initials(detail.displayName)}
            </div>
            <div className={styles.memberIdentity}>
              <div className={styles.identityLine}>
                <h1>{detail.displayName || t('users.unnamed')}</h1>
                <span
                  role="status"
                  className={`${styles.statusBadge} ${detail.suspended || detail.deleted || detail.carCommunityAccess?.suspended || detail.carCommunityAccess?.blacklisted ? styles.restrictedBadge : ''}`}
                >
                  <Circle size={10} fill="currentColor" strokeWidth={0} aria-hidden="true" />
                  {detail.deleted
                    ? 'Borttaget konto'
                    : detail.suspended || detail.carCommunityAccess?.suspended
                      ? 'Avstängd'
                      : detail.carCommunityAccess?.blacklisted
                        ? 'Svartlistad'
                        : detail.carCommunityAccess?.ready && detail.carCommunityAccess?.initialized
                          ? 'Aktiv'
                          : 'Åtkomst ej verifierad'}
                </span>
              </div>
              <p>
                Avstängning:{' '}
                <strong>
                  {detail.carCommunityAccess?.suspended
                    ? 'Ja'
                    : detail.carCommunityAccess?.initialized
                      ? 'Nej'
                      : 'Ej verifierad'}
                </strong>{' '}
                · Svartlistning:{' '}
                <strong>
                  {detail.carCommunityAccess?.blacklisted
                    ? 'Ja'
                    : detail.carCommunityAccess?.initialized
                      ? 'Nej'
                      : 'Ej verifierad'}
                </strong>
              </p>
              <p className={styles.meta}>
                Två separata statusar som hanteras oberoende av varandra.
              </p>
            </div>
            <div className={styles.heroActions}>
              <button
                type="button"
                className={styles.primaryButton}
                disabled={!detail.permissions?.canWarn}
                onClick={() => selectSection('warnings')}
              >
                <AlertTriangle size={18} aria-hidden="true" /> Utfärda varning
              </button>
              <button
                type="button"
                className={styles.secondaryButton}
                onClick={() => setShowAccess(true)}
              >
                <Settings size={18} aria-hidden="true" /> Hantera åtkomst
              </button>
            </div>
          </div>
          <div className={styles.memberTabs} role="tablist" aria-label="Medlemskort">
            {sections.map((value, index) => (
              <button
                key={value}
                id={`member-tab-${value}`}
                type="button"
                role="tab"
                aria-selected={section === value}
                aria-controls="member-panel"
                tabIndex={section === value ? 0 : -1}
                onClick={() => selectSection(value)}
                onKeyDown={(event) => {
                  const next =
                    event.key === 'ArrowRight'
                      ? (index + 1) % sections.length
                      : event.key === 'ArrowLeft'
                        ? (index + sections.length - 1) % sections.length
                        : event.key === 'Home'
                          ? 0
                          : event.key === 'End'
                            ? sections.length - 1
                            : null;
                  if (next !== null) {
                    event.preventDefault();
                    selectSection(sections[next]!);
                    (
                      event.currentTarget.parentElement?.querySelectorAll('button')[next] as
                        HTMLButtonElement | undefined
                    )?.focus();
                  }
                }}
              >
                {sectionLabels[value]}
              </button>
            ))}
          </div>
        </>
      )}
      {loading || (detail && detail.uid !== id) ? (
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
        <div id="member-panel" role="tabpanel" aria-labelledby={`member-tab-${section}`}>
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

          {(section === 'profile' || section === 'warnings') && (
            <MemberTools
              key={`${detail.uid}:${section}:${detail.displayName}`}
              section={section}
              member={detail}
              onChanged={() => void load(detail.uid)}
            />
          )}
          {section === 'profile' && (
            <details className={styles.advancedDetails}>
              <summary>Kontoinformation och administratörsroll</summary>
              <section className={styles.card}>
                <h2 className={styles.cardTitle}>{t('users.detail.profileTitle')}</h2>
                <dl className={styles.detailGrid}>
                  <dt>Konto-ID</dt>
                  <dd className={styles.uid}>{detail.uid}</dd>
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
                  <dt>Senast registrerade inloggning</dt>
                  <dd>
                    {detail.lastLoginAt
                      ? new Date(detail.lastLoginAt).toLocaleString('sv-SE')
                      : 'Historik saknas'}
                  </dd>
                  <dt>Inloggningsfrekvens</dt>
                  <dd>Historik saknas</dd>
                  <dt>{t('users.detail.updatedLabel')}</dt>
                  <dd>{formatDate(detail.updatedAt)}</dd>
                </dl>
                <p className={styles.meta}>
                  <Link
                    className={styles.link}
                    to={`/subscription?uid=${encodeURIComponent(detail.uid)}`}
                  >
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
            </details>
          )}

          {section === 'overview' && (
            <MemberOverview
              key={detail.uid}
              member={detail}
              onSection={selectSection}
              onRefresh={() => void load(detail.uid)}
            />
          )}

          {section === 'points' && (
            <UserPointsSection
              key={detail.uid}
              userId={detail.uid}
              actorUid={detail.actorUid}
              canAdjust={detail.permissions?.canAdjustPoints}
            />
          )}
          {section === 'messages' && <MemberMessages key={detail.uid} uid={detail.uid} />}
          {section === 'history' && <MemberHistory key={detail.uid} uid={detail.uid} />}
          {showAccess && (
            <ActionDialog
              title="Hantera åtkomst till CarCommunity"
              onCancel={() => setShowAccess(false)}
            >
              <AccessTools
                key={detail.uid}
                uid={detail.uid}
                onChanged={() => void load(detail.uid)}
              />
            </ActionDialog>
          )}

          {section === 'profile' && (
            <>
              <section className={styles.dangerCard} aria-labelledby="user-danger-title">
                <h2 id="user-danger-title" className={styles.dangerCardTitle}>
                  {t('users.detail.dangerTitle')}
                </h2>
                <p className={styles.meta}>{t('users.detail.deleteDescription')}</p>
                <p className={styles.meta}>
                  Kontoborttagning gäller det gemensamma Firebase-kontot och kan påverka Driver.
                  Beslut för enbart CarCommunity finns under Hantera åtkomst.
                </p>

                {showDeleteDialog ? (
                  <div
                    className={styles.dangerConfirm}
                    role="alertdialog"
                    aria-modal="true"
                    aria-labelledby="user-delete-title"
                  >
                    <p id="user-delete-title" className={styles.confirmText}>
                      {t('users.detail.deleteConfirmBody')
                        .replace('{name}', detail.displayName.trim() || t('users.unnamed'))
                        .replace('{uid}', detail.uid)}
                    </p>

                    <label className={styles.label} htmlFor="user-delete-reason">
                      {t('users.detail.reasonLabel')} <span aria-hidden="true">*</span>
                    </label>
                    <input
                      id="user-delete-reason"
                      className={styles.input}
                      type="text"
                      required
                      autoComplete="off"
                      maxLength={500}
                      value={deleteReason}
                      onChange={(e) => setDeleteReason(e.target.value)}
                      placeholder={t('users.detail.reasonPlaceholder')}
                    />

                    <label className={styles.label} htmlFor="user-delete-confirm">
                      {t('users.detail.deleteTypeToConfirm').replace(
                        '{phrase}',
                        deleteConfirmPhrase,
                      )}
                    </label>
                    <input
                      id="user-delete-confirm"
                      className={styles.input}
                      type="text"
                      autoComplete="off"
                      autoCapitalize="none"
                      value={deleteConfirmText}
                      onChange={(e) => setDeleteConfirmText(e.target.value)}
                      placeholder={deleteConfirmPhrase}
                    />

                    <div className={styles.actions}>
                      <button
                        type="button"
                        className={styles.solidDangerButton}
                        onClick={() => void runDelete()}
                        disabled={!deleteReady || deleting}
                      >
                        {deleting ? t('users.detail.deleting') : t('users.detail.deleteConfirm')}
                      </button>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        onClick={closeDeleteDialog}
                        disabled={deleting}
                      >
                        {t('users.detail.cancel')}
                      </button>
                    </div>

                    {deleteError && (
                      <p className={styles.error} role="alert">
                        {deleteError}
                      </p>
                    )}
                  </div>
                ) : (
                  <div className={styles.actions}>
                    <button
                      type="button"
                      className={styles.dangerButton}
                      onClick={openDeleteDialog}
                    >
                      {t('users.detail.deleteUser')}
                    </button>
                  </div>
                )}
              </section>{' '}
            </>
          )}
        </div>
      )}
    </div>
  );
}
