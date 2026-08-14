'use client';

/**
 * Admin: Users (Användare) list page.
 *
 * Loads the full user list (the user base is small — see `adminListUsers`) and
 * filters/sorts it entirely in memory: a case-insensitive substring search
 * across name / uid / email, role / status / member facets, and a sort that
 * includes least-recent activity for spotting dormant accounts. Each row links
 * to the moderation detail view.
 *
 * Security notes:
 *  - All data is read directly from the rules-gated `users/{uid}` and
 *    `userLifecycle/{uid}` documents; only backend-managed, admin-safe fields
 *    are shown. The owner-only `userPrivate/{uid}` is never read.
 *  - No moderation happens here — actions live on the detail page and go
 *    through the audited admin.* callables.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import {
  adminListUsers,
  DEFAULT_USER_SORT,
  filterAndSortUsers,
  hasMemberSetNickname,
  USER_ROLES,
  type AdminUserSummary,
  type ApiError,
  type UserListFilters,
  type UserSortKey,
} from '@/features/users';
import { translate } from '@/i18n';
import { formatDateOnly, formatDateTimeStockholm } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

const ROLE_LABELS: Record<string, string> = {
  user: 'users.role.user',
  admin: 'users.role.admin',
  owner: 'users.role.owner',
};

const SORT_KEYS: UserSortKey[] = [
  'lastActivityDesc',
  'lastActivityAsc',
  'createdDesc',
  'createdAsc',
  'nameAsc',
];

const STATUS_KEYS: Array<'active' | 'suspended' | 'deleted'> = ['active', 'suspended', 'deleted'];

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
  const [truncated, setTruncated] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<UserListFilters['role']>('');
  const [statusFilter, setStatusFilter] = useState<UserListFilters['status']>('');
  const [memberFilter, setMemberFilter] = useState<UserListFilters['member']>('');
  const [sort, setSort] = useState<UserSortKey>(DEFAULT_USER_SORT);

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
      setUsers(result.users);
      setTruncated(result.truncated);
    } catch (err) {
      setUsers([]);
      setTruncated(false);
      setError((err as ApiError)?.message ?? t('users.loadError'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const anyFilterActive =
    search.trim() !== '' || roleFilter !== '' || statusFilter !== '' || memberFilter !== '';
  const filtered = useMemo(
    () =>
      filterAndSortUsers(
        users,
        { search, role: roleFilter, status: statusFilter, member: memberFilter },
        sort,
      ),
    [users, search, roleFilter, statusFilter, memberFilter, sort],
  );

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

      <div className={styles.filters}>
        <div className={styles.filterGroup}>
          <label className={styles.label} htmlFor="users-search">
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

        <div className={styles.filterGroup}>
          <label className={styles.label} htmlFor="users-role-filter">
            {t('users.filters.roleLabel')}
          </label>
          <select
            id="users-role-filter"
            className={styles.select}
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value as UserListFilters['role'])}
          >
            <option value="">{t('users.filters.allRoles')}</option>
            {USER_ROLES.map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </select>
        </div>

        <div className={styles.filterGroup}>
          <label className={styles.label} htmlFor="users-status-filter">
            {t('users.filters.statusLabel')}
          </label>
          <select
            id="users-status-filter"
            className={styles.select}
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as UserListFilters['status'])}
          >
            <option value="">{t('users.filters.allStatuses')}</option>
            {STATUS_KEYS.map((status) => (
              <option key={status} value={status}>
                {t(`users.status.${status}`)}
              </option>
            ))}
          </select>
        </div>

        <div className={styles.filterGroup}>
          <label className={styles.label} htmlFor="users-member-filter">
            {t('users.filters.memberLabel')}
          </label>
          <select
            id="users-member-filter"
            className={styles.select}
            value={memberFilter}
            onChange={(e) => setMemberFilter(e.target.value as UserListFilters['member'])}
          >
            <option value="">{t('users.filters.allMembers')}</option>
            <option value="yes">{t('users.member.yes')}</option>
            <option value="no">{t('users.member.no')}</option>
          </select>
        </div>

        <div className={styles.filterGroup}>
          <label className={styles.label} htmlFor="users-sort">
            {t('users.filters.sortLabel')}
          </label>
          <select
            id="users-sort"
            className={styles.select}
            value={sort}
            onChange={(e) => setSort(e.target.value as UserSortKey)}
          >
            {SORT_KEYS.map((key) => (
              <option key={key} value={key}>
                {t(`users.sort.${key}`)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {truncated && (
        <p className={styles.capNotice} role="status">
          {t('users.capNotice')}
        </p>
      )}

      {loading ? (
        <p className={styles.meta} aria-live="polite" aria-busy="true">
          {t('users.loading')}
        </p>
      ) : error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : filtered.length === 0 ? (
        <p className={styles.meta}>
          {anyFilterActive ? t('users.emptyFiltered') : t('users.empty')}
        </p>
      ) : (
        <>
          <p className={styles.meta} aria-live="polite">
            {t('users.countSummary')
              .replace('{shown}', String(filtered.length))
              .replace('{total}', String(users.length))}
          </p>
          <div className={styles.tableWrapper}>
            <table className={styles.table} aria-label={t('users.title')}>
              <thead>
                <tr>
                  <th scope="col">{t('users.columns.name')}</th>
                  <th scope="col">{t('users.columns.role')}</th>
                  <th scope="col">{t('users.columns.member')}</th>
                  <th scope="col">{t('users.columns.status')}</th>
                  <th scope="col">{t('users.columns.lastActivity')}</th>
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
                    <td>{formatDateTimeStockholm(u.lastLoginAt)}</td>
                    <td>{formatDate(u.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
