'use client';

/**
 * Admin: Token / credential renewal tracker (Token renewals) page.
 *
 * Lists managed credentials (tracked tokens, keystores, service accounts) and
 * flags which are expired / expiring soon so an operator can rotate them in
 * time. Supports full admin CRUD. All operations are direct rules-gated
 * Firestore writes (managedCredentials: read/write = isAdmin()); the feature
 * module validates every write since firestore.rules has no field validation
 * here.
 */

import { useCallback, useEffect, useState } from 'react';

import {
  adminCreateManagedCredential,
  adminDeleteManagedCredential,
  adminListManagedCredentials,
  adminUpdateManagedCredential,
  ApiError,
  computeCredentialStatus,
  CREDENTIAL_CATEGORIES,
  CREDENTIAL_DESCRIPTION_MAX_LENGTH,
  CREDENTIAL_NAME_MAX_LENGTH,
  CREDENTIAL_NOTES_MAX_LENGTH,
  type AdminManagedCredential,
  type CredentialCategory,
  type CredentialStatus,
  type ManagedCredentialInput,
} from '@/features/credentials';
import { translate } from '@/i18n';
import { formatDateOnly } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

/** Maps the module's stable validation codes to i18n keys. */
const VALIDATION_ERROR_KEYS: Record<string, string> = {
  'credential/name-required': 'credentials.errors.nameRequired',
  'credential/name-too-long': 'credentials.errors.nameTooLong',
  'credential/category-invalid': 'credentials.errors.categoryInvalid',
  'credential/description-too-long': 'credentials.errors.descriptionTooLong',
  'credential/notes-too-long': 'credentials.errors.notesTooLong',
  'credential/expires-invalid': 'credentials.errors.expiresInvalid',
  'credential/rotated-invalid': 'credentials.errors.rotatedInvalid',
};

/**
 * Localizes an error for display. Only the module's own validation ApiErrors
 * (recognized by their stable `credential/...` codes) map to specific i18n
 * messages; everything else — including raw Firebase SDK errors — falls back
 * to the localized fallback key.
 */
function errorMessage(err: unknown, fallbackKey: string): string {
  if (err instanceof ApiError) {
    const key = VALIDATION_ERROR_KEYS[err.code];
    if (key) return t(key);
  }
  return t(fallbackKey);
}

const STATUS_CLASS: Record<CredentialStatus, string> = {
  expired: styles.statusExpired ?? '',
  'expiring-soon': styles.statusExpiring ?? '',
  ok: styles.statusOk ?? '',
  'no-expiry': styles.statusNeutral ?? '',
  invalid: styles.statusInvalid ?? '',
};

function statusLabel(status: CredentialStatus): string {
  return t(`credentials.status.${status === 'expiring-soon' ? 'expiringSoon' : status}`);
}

function categoryLabel(category: CredentialCategory): string {
  return t(`credentials.category.${category}`);
}

/** Converts a stored ISO string to the `YYYY-MM-DD` value an <input type=date> expects. */
function isoToDateInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

interface FormState {
  name: string;
  description: string;
  category: CredentialCategory;
  expiresAt: string;
  lastRotatedAt: string;
  notes: string;
}

const EMPTY_FORM_STATE: FormState = {
  name: '',
  description: '',
  category: 'other',
  expiresAt: '',
  lastRotatedAt: '',
  notes: '',
};

function formStateToInput(form: FormState): ManagedCredentialInput {
  return {
    name: form.name,
    description: form.description,
    category: form.category,
    expiresAt: form.expiresAt.trim() === '' ? null : form.expiresAt,
    lastRotatedAt: form.lastRotatedAt.trim() === '' ? null : form.lastRotatedAt,
    notes: form.notes,
  };
}

function credentialToFormState(item: AdminManagedCredential): FormState {
  return {
    name: item.name,
    description: item.description,
    category: item.category,
    expiresAt: isoToDateInput(item.expiresAt),
    lastRotatedAt: isoToDateInput(item.lastRotatedAt),
    notes: item.notes,
  };
}

/** Renders the shared credential form fields (create + edit). */
function CredentialFields({
  idPrefix,
  form,
  onChange,
}: {
  idPrefix: string;
  form: FormState;
  onChange: (patch: Partial<FormState>) => void;
}) {
  return (
    <>
      <label className={styles.label} htmlFor={`${idPrefix}-name`}>
        {t('credentials.form.nameLabel')}
      </label>
      <input
        id={`${idPrefix}-name`}
        className={styles.input}
        type="text"
        maxLength={CREDENTIAL_NAME_MAX_LENGTH}
        value={form.name}
        onChange={(e) => onChange({ name: e.target.value })}
        placeholder={t('credentials.form.namePlaceholder')}
      />

      <label className={styles.label} htmlFor={`${idPrefix}-category`}>
        {t('credentials.form.categoryLabel')}
      </label>
      <select
        id={`${idPrefix}-category`}
        className={styles.input}
        value={form.category}
        onChange={(e) => onChange({ category: e.target.value as CredentialCategory })}
      >
        {CREDENTIAL_CATEGORIES.map((category) => (
          <option key={category} value={category}>
            {categoryLabel(category)}
          </option>
        ))}
      </select>

      <label className={styles.label} htmlFor={`${idPrefix}-description`}>
        {t('credentials.form.descriptionLabel')}
      </label>
      <input
        id={`${idPrefix}-description`}
        className={styles.input}
        type="text"
        maxLength={CREDENTIAL_DESCRIPTION_MAX_LENGTH}
        value={form.description}
        onChange={(e) => onChange({ description: e.target.value })}
        placeholder={t('credentials.form.descriptionPlaceholder')}
      />

      <div className={styles.dateRow}>
        <div className={styles.dateField}>
          <label className={styles.label} htmlFor={`${idPrefix}-expires`}>
            {t('credentials.form.expiresLabel')}
          </label>
          <input
            id={`${idPrefix}-expires`}
            className={styles.input}
            type="date"
            value={form.expiresAt}
            onChange={(e) => onChange({ expiresAt: e.target.value })}
          />
          <p className={styles.hint}>{t('credentials.form.expiresHint')}</p>
        </div>
        <div className={styles.dateField}>
          <label className={styles.label} htmlFor={`${idPrefix}-rotated`}>
            {t('credentials.form.rotatedLabel')}
          </label>
          <input
            id={`${idPrefix}-rotated`}
            className={styles.input}
            type="date"
            value={form.lastRotatedAt}
            onChange={(e) => onChange({ lastRotatedAt: e.target.value })}
          />
        </div>
      </div>

      <label className={styles.label} htmlFor={`${idPrefix}-notes`}>
        {t('credentials.form.notesLabel')}
      </label>
      <textarea
        id={`${idPrefix}-notes`}
        className={styles.textarea}
        rows={3}
        maxLength={CREDENTIAL_NOTES_MAX_LENGTH}
        value={form.notes}
        onChange={(e) => onChange({ notes: e.target.value })}
        placeholder={t('credentials.form.notesPlaceholder')}
      />
    </>
  );
}

export default function CredentialsPage() {
  const [items, setItems] = useState<AdminManagedCredential[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Create form
  const [newForm, setNewForm] = useState<FormState>(EMPTY_FORM_STATE);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Edit panel (one credential at a time)
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<FormState>(EMPTY_FORM_STATE);
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  // Delete
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const anyPending = creating || saving || busyId !== null;

  const refresh = useCallback(async () => {
    try {
      setItems(await adminListManagedCredentials());
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err, 'credentials.errors.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Status is derived once per render against a single "now" so every row and
  // the summary counts are evaluated against the same instant.
  const now = new Date();
  let expiredCount = 0;
  let expiringSoonCount = 0;
  for (const item of items) {
    const status = computeCredentialStatus(item.expiresAt, now);
    if (status === 'expired') expiredCount += 1;
    else if (status === 'expiring-soon') expiringSoonCount += 1;
  }
  const summary = { expired: expiredCount, expiringSoon: expiringSoonCount };

  const handleCreate = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (anyPending) return;
      setCreating(true);
      setCreateError(null);
      try {
        await adminCreateManagedCredential(formStateToInput(newForm));
        setNewForm(EMPTY_FORM_STATE);
        await refresh();
      } catch (err) {
        setCreateError(errorMessage(err, 'credentials.errors.createFailed'));
      } finally {
        setCreating(false);
      }
    },
    [anyPending, newForm, refresh],
  );

  const startEdit = useCallback((item: AdminManagedCredential) => {
    setEditingId(item.id);
    setEditForm(credentialToFormState(item));
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
        await adminUpdateManagedCredential(editingId, formStateToInput(editForm));
        setEditingId(null);
        await refresh();
      } catch (err) {
        setEditError(errorMessage(err, 'credentials.errors.updateFailed'));
      } finally {
        setSaving(false);
      }
    },
    [editingId, anyPending, editForm, refresh],
  );

  const handleDelete = useCallback(
    async (item: AdminManagedCredential) => {
      if (anyPending) return;
      if (!window.confirm(`${t('credentials.deleteConfirm')}\n\n"${item.name}"`)) return;
      setBusyId(item.id);
      setActionError(null);
      try {
        await adminDeleteManagedCredential(item.id);
        if (editingId === item.id) setEditingId(null);
        await refresh();
      } catch (err) {
        setActionError(errorMessage(err, 'credentials.errors.deleteFailed'));
      } finally {
        setBusyId(null);
      }
    },
    [anyPending, editingId, refresh],
  );

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.title}>{t('credentials.title')}</h1>
        <p className={styles.subtitle}>{t('credentials.subtitle')}</p>
      </header>

      <section className={styles.summary} aria-label={t('credentials.summary.label')}>
        <div className={`${styles.summaryTile} ${styles.summaryExpired}`}>
          <span className={styles.summaryCount}>{summary.expired}</span>
          <span className={styles.summaryLabel}>{t('credentials.summary.expired')}</span>
        </div>
        <div className={`${styles.summaryTile} ${styles.summaryExpiring}`}>
          <span className={styles.summaryCount}>{summary.expiringSoon}</span>
          <span className={styles.summaryLabel}>{t('credentials.summary.expiringSoon')}</span>
        </div>
      </section>

      <section className={styles.card}>
        <h2 className={styles.cardTitle}>{t('credentials.create.heading')}</h2>
        <form className={styles.form} onSubmit={handleCreate}>
          <CredentialFields
            idPrefix="cred-new"
            form={newForm}
            onChange={(patch) => setNewForm((prev) => ({ ...prev, ...patch }))}
          />
          <div className={styles.actions}>
            <button
              type="submit"
              className={styles.primaryButton}
              disabled={!newForm.name.trim() || anyPending}
            >
              {creating ? t('credentials.create.submitting') : t('credentials.create.submit')}
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
        <h2 className={styles.cardTitle}>{t('credentials.list.heading')}</h2>

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
          <p className={styles.muted}>{t('credentials.list.loading')}</p>
        ) : items.length === 0 && !loadError ? (
          <p className={styles.muted}>{t('credentials.list.empty')}</p>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th className={styles.th}>{t('credentials.columns.status')}</th>
                  <th className={styles.th}>{t('credentials.columns.name')}</th>
                  <th className={styles.th}>{t('credentials.columns.category')}</th>
                  <th className={styles.th}>{t('credentials.columns.expiresAt')}</th>
                  <th className={styles.th}>{t('credentials.columns.lastRotatedAt')}</th>
                  <th className={styles.th}>{t('credentials.columns.notes')}</th>
                  <th className={styles.th}>{t('credentials.columns.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => {
                  const status = computeCredentialStatus(item.expiresAt, now);
                  const isEditing = editingId === item.id;
                  return (
                    <tr key={item.id} className={styles.tr}>
                      {isEditing ? (
                        <td className={styles.td} colSpan={7}>
                          <form className={styles.form} onSubmit={handleSaveEdit}>
                            <CredentialFields
                              idPrefix={`cred-edit-${item.id}`}
                              form={editForm}
                              onChange={(patch) =>
                                setEditForm((prev) => ({ ...prev, ...patch }))
                              }
                            />
                            <div className={styles.actions}>
                              <button
                                type="submit"
                                className={styles.primaryButton}
                                disabled={!editForm.name.trim() || anyPending}
                              >
                                {saving
                                  ? t('credentials.actions.saving')
                                  : t('credentials.actions.save')}
                              </button>
                              <button
                                type="button"
                                className={styles.secondaryButton}
                                onClick={cancelEdit}
                                disabled={saving}
                              >
                                {t('credentials.actions.cancel')}
                              </button>
                            </div>
                            {editError && (
                              <p className={styles.error} role="alert">
                                {editError}
                              </p>
                            )}
                          </form>
                        </td>
                      ) : (
                        <>
                          <td className={styles.td}>
                            <span className={`${styles.statusBadge} ${STATUS_CLASS[status]}`}>
                              {statusLabel(status)}
                            </span>
                          </td>
                          <td className={styles.td}>
                            <span className={styles.name}>{item.name}</span>
                            {item.description && (
                              <span className={styles.description}>{item.description}</span>
                            )}
                          </td>
                          <td className={styles.td}>{categoryLabel(item.category)}</td>
                          <td className={styles.td}>
                            {item.expiresAt
                              ? formatDateOnly(item.expiresAt)
                              : t('credentials.noExpiry')}
                          </td>
                          <td className={styles.td}>
                            {item.lastRotatedAt ? formatDateOnly(item.lastRotatedAt) : '—'}
                          </td>
                          <td className={`${styles.td} ${styles.notesCell}`}>
                            {item.notes || '—'}
                          </td>
                          <td className={styles.td}>
                            <div className={styles.rowActions}>
                              <button
                                type="button"
                                className={styles.secondaryButton}
                                onClick={() => startEdit(item)}
                                disabled={anyPending}
                              >
                                {t('credentials.actions.edit')}
                              </button>
                              <button
                                type="button"
                                className={styles.dangerButton}
                                onClick={() => void handleDelete(item)}
                                disabled={anyPending}
                              >
                                {busyId === item.id
                                  ? t('credentials.actions.working')
                                  : t('credentials.actions.delete')}
                              </button>
                            </div>
                          </td>
                        </>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
