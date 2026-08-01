'use client';

/**
 * Admin: Users (Användare) list page.
 *
 * Lists the most recently created users (first page only — never all users at
 * once) with a client-side filter over the loaded page, and links each row to
 * the moderation detail view.
 *
 * Security notes:
 *  - All data is read directly from the rules-gated `users/{uid}` documents;
 *    only backend-managed, admin-safe fields are shown.
 *  - No moderation happens here — actions live on the detail page and go
 *    through the audited admin.* callables.
 */

import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import {
  adminListUsers,
  hasMemberSetNickname,
  type AdminUserSummary,
  type ApiError,
} from '@/features/users';
import { translate } from '@/i18n';
import { formatDateOnly } from '@/lib/format';

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

interface DeletedUserNav {
  uid: string;
  displayName: string;
}

export default function UsersPage() {
  const [users, setUsers] = useState<AdminUserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  // A one-shot success banner after a user was deleted on the detail page,
  // handed over via router navigation state. Captured on mount, then the
  // history entry's state is cleared so a refresh/back does not re-show it.
  const location = useLocation();
  const navigate = useNavigate();
  // Captured once from the initial navigation state and never updated, so the
  // setter is intentionally omitted.
  const [deletedNotice] = useState<DeletedUserNav | null>(
    (location.state as { deletedUser?: DeletedUserNav } | null)?.deletedUser ?? null,
  );
  useEffect(() => {
    if ((location.state as { deletedUser?: DeletedUserNav } | null)?.deletedUser) {
      navigate('.', { replace: true, state: null });
    }
    // Run once for the state this page was navigated in with.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await adminListUsers();
      setUsers(result);
    } catch (err) {
      setUsers([]);
      setError((err as ApiError)?.message ?? t('users.loadError'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const term = search.trim().toLowerCase();
  const filtered = term
    ? users.filter(
        (u) => u.displayName.toLowerCase().includes(term) || u.uid.toLowerCase().includes(term),
      )
    : users;

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('users.title')}</h1>
        <p className={styles.subtitle}>{t('users.subtitle')}</p>
      </header>

      {deletedNotice && (
        <p className={styles.successBanner} role="status">
          {t('users.detail.deleteSuccess').replace(
            '{name}',
            deletedNotice.displayName.trim() || deletedNotice.uid,
          )}
        </p>
      )}

      <div className={styles.searchRow}>
        <label className={styles.srOnly} htmlFor="users-search">
          {t('users.searchLabel')}
        </label>
        <input
          id="users-search"
          className={styles.input}
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t('users.searchPlaceholder')}
          autoComplete="off"
        />
      </div>

      {loading ? (
        <p className={styles.meta} aria-live="polite" aria-busy="true">
          {t('users.loading')}
        </p>
      ) : error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : filtered.length === 0 ? (
        <p className={styles.meta}>{term ? t('users.emptyFiltered') : t('users.empty')}</p>
      ) : (
        <div className={styles.tableWrapper}>
          <table className={styles.table} aria-label={t('users.title')}>
            <thead>
              <tr>
                <th scope="col">{t('users.columns.name')}</th>
                <th scope="col">{t('users.columns.role')}</th>
                <th scope="col">{t('users.columns.member')}</th>
                <th scope="col">{t('users.columns.status')}</th>
                <th scope="col">{t('users.columns.created')}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((u) => (
                <tr key={u.uid}>
                  <td>
                    <Link className={styles.rowLink} to={`/users/${u.uid}`}>
                      {hasMemberSetNickname(u) ? (
                        u.displayName
                      ) : (
                        <span className={styles.noNickname}>{t('users.noNickname')}</span>
                      )}
                    </Link>
                    <span className={styles.uid}>{u.uid}</span>
                  </td>
                  <td>{roleLabel(u.role)}</td>
                  <td>{u.activeMember ? t('users.member.yes') : t('users.member.no')}</td>
                  <td>
                    {u.deleted ? (
                      <span className={styles.badgeDeleted}>{t('users.status.deleted')}</span>
                    ) : u.suspended ? (
                      <span className={styles.badgeSuspended}>{t('users.status.suspended')}</span>
                    ) : (
                      <span className={styles.badgeActive}>{t('users.status.active')}</span>
                    )}
                  </td>
                  <td>{formatDate(u.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
