'use client';

/**
 * Admin: Announcements (Meddelanden) page (Phase 13p).
 *
 * Create, edit, activate/retract and (with explicit confirm) delete community
 * announcements. All operations are direct rules-gated Firestore writes
 * (announcements: write = isAdmin()); the feature module validates content
 * before every write since firestore.rules has no field validation here.
 *
 * Retract = deactivate (active=false) is the primary way to pull an
 * announcement; hard delete is irreversible and gated behind a confirm.
 */

import { useCallback, useEffect, useState } from 'react';

import {
  adminCreateAnnouncement,
  adminDeleteAnnouncement,
  adminListAnnouncements,
  adminSetAnnouncementActive,
  adminUpdateAnnouncement,
  ANNOUNCEMENT_BODY_MAX_LENGTH,
  ANNOUNCEMENT_TITLE_MAX_LENGTH,
  ApiError,
  type AdminAnnouncement,
} from '@/features/announcements';
import { translate } from '@/i18n';
import { formatDate } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

/** Maps the module's stable validation codes to i18n keys. */
const VALIDATION_ERROR_KEYS: Record<string, string> = {
  'announcement/title-required': 'announcements.errors.titleRequired',
  'announcement/title-too-long': 'announcements.errors.titleTooLong',
  'announcement/body-required': 'announcements.errors.bodyRequired',
  'announcement/body-too-long': 'announcements.errors.bodyTooLong',
};

/**
 * Localizes an error for display. Only the module's own validation ApiErrors
 * (recognized by their stable `announcement/...` codes) map to specific i18n
 * messages; everything else — including raw Firebase SDK errors, which carry
 * untranslated English strings — falls back to the localized fallback key.
 */
function errorMessage(err: unknown, fallbackKey: string): string {
  if (err instanceof ApiError) {
    const key = VALIDATION_ERROR_KEYS[err.code];
    if (key) return t(key);
  }
  return t(fallbackKey);
}

function formatDateTime(iso: string | null): string {
  return formatDate(iso);
}

export default function AnnouncementsPage() {
  const [items, setItems] = useState<AdminAnnouncement[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Create form
  const [newTitle, setNewTitle] = useState('');
  const [newBody, setNewBody] = useState('');
  const [newActive, setNewActive] = useState(true);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Edit panel (one announcement at a time)
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editBody, setEditBody] = useState('');
  const [editActive, setEditActive] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  // Toggle/delete
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const anyPending = creating || saving || busyId !== null;

  const refresh = useCallback(async () => {
    try {
      setItems(await adminListAnnouncements());
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err, 'announcements.errors.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleCreate = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (anyPending) return;
      setCreating(true);
      setCreateError(null);
      try {
        await adminCreateAnnouncement({ title: newTitle, body: newBody, active: newActive });
        setNewTitle('');
        setNewBody('');
        setNewActive(true);
        await refresh();
      } catch (err) {
        setCreateError(errorMessage(err, 'announcements.errors.createFailed'));
      } finally {
        setCreating(false);
      }
    },
    [anyPending, newTitle, newBody, newActive, refresh],
  );

  const startEdit = useCallback((item: AdminAnnouncement) => {
    setEditingId(item.id);
    setEditTitle(item.title);
    setEditBody(item.body);
    setEditActive(item.active);
    setEditError(null);
  }, []);

  const cancelEdit = useCallback(() => {
    setEditingId(null);
    setEditError(null);
  }, []);

  const handleSaveEdit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!editingId || anyPending) return;
      setSaving(true);
      setEditError(null);
      try {
        await adminUpdateAnnouncement(editingId, {
          title: editTitle,
          body: editBody,
          active: editActive,
        });
        setEditingId(null);
        await refresh();
      } catch (err) {
        setEditError(errorMessage(err, 'announcements.errors.updateFailed'));
      } finally {
        setSaving(false);
      }
    },
    [editingId, anyPending, editTitle, editBody, editActive, refresh],
  );

  const handleToggleActive = useCallback(
    async (item: AdminAnnouncement) => {
      if (anyPending) return;
      setBusyId(item.id);
      setActionError(null);
      try {
        await adminSetAnnouncementActive(item.id, !item.active);
        await refresh();
      } catch (err) {
        setActionError(errorMessage(err, 'announcements.errors.toggleFailed'));
      } finally {
        setBusyId(null);
      }
    },
    [anyPending, refresh],
  );

  const handleDelete = useCallback(
    async (item: AdminAnnouncement) => {
      if (anyPending) return;
      // Hard delete is irreversible — deactivation is the primary retract
      // action, so deletion demands an explicit confirm.
      if (!window.confirm(`${t('announcements.deleteConfirm')}\n\n"${item.title}"`)) return;
      setBusyId(item.id);
      setActionError(null);
      try {
        await adminDeleteAnnouncement(item.id);
        if (editingId === item.id) setEditingId(null);
        await refresh();
      } catch (err) {
        setActionError(errorMessage(err, 'announcements.errors.deleteFailed'));
      } finally {
        setBusyId(null);
      }
    },
    [anyPending, editingId, refresh],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('announcements.title')}</h1>
        <p className={styles.subtitle}>{t('announcements.subtitle')}</p>
      </header>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>{t('announcements.create.heading')}</h2>
        <form className={styles.form} onSubmit={handleCreate}>
          <label className={styles.label} htmlFor="ann-new-title">
            {t('announcements.form.titleLabel')}
          </label>
          <input
            id="ann-new-title"
            className={styles.input}
            type="text"
            maxLength={ANNOUNCEMENT_TITLE_MAX_LENGTH}
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            placeholder={t('announcements.form.titlePlaceholder')}
          />
          <label className={styles.label} htmlFor="ann-new-body">
            {t('announcements.form.bodyLabel')}
          </label>
          <textarea
            id="ann-new-body"
            className={styles.textarea}
            rows={4}
            maxLength={ANNOUNCEMENT_BODY_MAX_LENGTH}
            value={newBody}
            onChange={(e) => setNewBody(e.target.value)}
            placeholder={t('announcements.form.bodyPlaceholder')}
          />
          <label className={styles.checkboxLabel}>
            <input
              type="checkbox"
              checked={newActive}
              onChange={(e) => setNewActive(e.target.checked)}
            />
            {t('announcements.form.activeLabel')}
          </label>
          <div className={styles.actions}>
            <button
              type="submit"
              className={styles.primaryButton}
              disabled={!newTitle.trim() || !newBody.trim() || anyPending}
            >
              {creating ? t('announcements.create.submitting') : t('announcements.create.submit')}
            </button>
          </div>
          {createError && (
            <p className={styles.error} role="alert">
              {createError}
            </p>
          )}
        </form>
      </section>

      <section className={styles.listSection}>
        <h2 className={styles.cardTitle}>{t('announcements.list.heading')}</h2>

        {loadError && (
          <p className={styles.error} role="alert">
            {loadError}
          </p>
        )}
        {actionError && (
          <p className={styles.error} role="alert">
            {actionError}
          </p>
        )}

        {loading ? (
          <p className={styles.muted}>{t('announcements.list.loading')}</p>
        ) : items.length === 0 && !loadError ? (
          <p className={styles.muted}>{t('announcements.list.empty')}</p>
        ) : (
          <ul className={styles.list}>
            {items.map((item) => (
              <li key={item.id} className={styles.item}>
                {editingId === item.id ? (
                  <form className={styles.form} onSubmit={handleSaveEdit}>
                    <label className={styles.label} htmlFor={`ann-edit-title-${item.id}`}>
                      {t('announcements.form.titleLabel')}
                    </label>
                    <input
                      id={`ann-edit-title-${item.id}`}
                      className={styles.input}
                      type="text"
                      maxLength={ANNOUNCEMENT_TITLE_MAX_LENGTH}
                      value={editTitle}
                      onChange={(e) => setEditTitle(e.target.value)}
                    />
                    <label className={styles.label} htmlFor={`ann-edit-body-${item.id}`}>
                      {t('announcements.form.bodyLabel')}
                    </label>
                    <textarea
                      id={`ann-edit-body-${item.id}`}
                      className={styles.textarea}
                      rows={4}
                      maxLength={ANNOUNCEMENT_BODY_MAX_LENGTH}
                      value={editBody}
                      onChange={(e) => setEditBody(e.target.value)}
                    />
                    <label className={styles.checkboxLabel}>
                      <input
                        type="checkbox"
                        checked={editActive}
                        onChange={(e) => setEditActive(e.target.checked)}
                      />
                      {t('announcements.form.activeLabel')}
                    </label>
                    <div className={styles.actions}>
                      <button
                        type="submit"
                        className={styles.primaryButton}
                        disabled={!editTitle.trim() || !editBody.trim() || anyPending}
                      >
                        {saving
                          ? t('announcements.actions.saving')
                          : t('announcements.actions.save')}
                      </button>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        onClick={cancelEdit}
                        disabled={saving}
                      >
                        {t('announcements.actions.cancel')}
                      </button>
                    </div>
                    {editError && (
                      <p className={styles.error} role="alert">
                        {editError}
                      </p>
                    )}
                  </form>
                ) : (
                  <>
                    <div className={styles.itemHeader}>
                      <h3 className={styles.itemTitle}>{item.title}</h3>
                      <span
                        className={`${styles.badge} ${item.active ? styles.badgeActive : styles.badgeInactive}`}
                      >
                        {item.active
                          ? t('announcements.badge.active')
                          : t('announcements.badge.inactive')}
                      </span>
                    </div>
                    <p className={styles.itemBody}>{item.body}</p>
                    <p className={styles.itemMeta}>
                      {t('announcements.createdLabel')}: {formatDateTime(item.createdAt)}
                      {' · '}
                      {t('announcements.updatedLabel')}: {formatDateTime(item.updatedAt)}
                    </p>
                    <div className={styles.actions}>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        onClick={() => startEdit(item)}
                        disabled={anyPending}
                      >
                        {t('announcements.actions.edit')}
                      </button>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        onClick={() => void handleToggleActive(item)}
                        disabled={anyPending}
                      >
                        {busyId === item.id
                          ? t('announcements.actions.working')
                          : item.active
                            ? t('announcements.actions.deactivate')
                            : t('announcements.actions.activate')}
                      </button>
                      <button
                        type="button"
                        className={styles.dangerButton}
                        onClick={() => void handleDelete(item)}
                        disabled={anyPending}
                      >
                        {t('announcements.actions.delete')}
                      </button>
                    </div>
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
