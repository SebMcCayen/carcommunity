'use client';

/**
 * Finance & Cost board — "Recurring costs" section.
 *
 * Admin CRUD over OPERATOR-ENTERED recurring costs (Claude, tooling, domains …),
 * each an exact figure with a description, folded into the board's monthly grand
 * total by the finance-estimate callable. Reads the list directly (rules-gated)
 * and mutates through the audited callables (features/finance/recurringCosts).
 *
 * Every mutating button carries a SYNCHRONOUS in-flight guard (a useRef, not
 * just async state) so a fast double-click cannot fire the mutation twice —
 * delete especially must never double-fire. Delete is additionally behind an
 * explicit confirm.
 *
 * After any successful mutation it calls `onChanged` so the parent page reloads
 * the estimate and the grand total / composition reflect the new list.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  ApiError,
  RECURRING_COST_AMOUNT_MAX,
  RECURRING_COST_DESCRIPTION_MAX_LENGTH,
  RECURRING_COST_LABEL_MAX_LENGTH,
  adminAddRecurringCost,
  adminDeleteRecurringCost,
  adminListRecurringCosts,
  adminUpdateRecurringCost,
  formatSek,
  type RecurringCost,
  type RecurringCostCurrency,
  type RecurringCostInput,
  type RecurringCostPeriod,
} from '@/features/finance';
import { translate } from '@/i18n';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

/** Maps stable validation codes from the feature module to i18n keys. */
const VALIDATION_ERROR_KEYS: Record<string, string> = {
  'recurringCost/label-required': 'finance.recurringCosts.errors.labelRequired',
  'recurringCost/label-too-long': 'finance.recurringCosts.errors.labelTooLong',
  'recurringCost/description-too-long': 'finance.recurringCosts.errors.descriptionTooLong',
  'recurringCost/amount-invalid': 'finance.recurringCosts.errors.amountInvalid',
  'recurringCost/amount-too-large': 'finance.recurringCosts.errors.amountTooLarge',
  'recurringCost/currency-invalid': 'finance.recurringCosts.errors.currencyInvalid',
  'recurringCost/period-invalid': 'finance.recurringCosts.errors.periodInvalid',
};

function errorMessage(err: unknown, fallbackKey: string): string {
  if (err instanceof ApiError) {
    const key = VALIDATION_ERROR_KEYS[err.code];
    if (key) return t(key);
    if (err.message) return err.message;
  }
  return t(fallbackKey);
}

/** Monthly-SEK equivalent shown per row (mirrors the backend normalisation). */
function monthlySek(cost: RecurringCost, usdToSek: number): number {
  const monthlyAmount = cost.period === 'yearly' ? cost.amount / 12 : cost.amount;
  return cost.currency === 'USD' ? monthlyAmount * usdToSek : monthlyAmount;
}

interface FormFields {
  label: string;
  description: string;
  amount: string;
  currency: RecurringCostCurrency;
  period: RecurringCostPeriod;
}

const EMPTY_FIELDS: FormFields = {
  label: '',
  description: '',
  amount: '',
  currency: 'SEK',
  period: 'monthly',
};

function toInput(fields: FormFields): RecurringCostInput {
  return {
    label: fields.label,
    description: fields.description,
    amount: Number.parseFloat(fields.amount),
    currency: fields.currency,
    period: fields.period,
  };
}

export function RecurringCostsSection({
  usdToSek,
  onChanged,
}: {
  usdToSek: number;
  onChanged: () => void;
}) {
  const [items, setItems] = useState<RecurringCost[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Create form
  const [form, setForm] = useState<FormFields>(EMPTY_FIELDS);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const creatingRef = useRef(false);

  // Edit (one row at a time)
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<FormFields>(EMPTY_FIELDS);
  const [saving, setSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);
  const savingRef = useRef(false);

  // Delete / row action
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const deletingRef = useRef(false);

  const anyPending = creating || saving || busyId !== null;

  const refresh = useCallback(async () => {
    try {
      setItems(await adminListRecurringCosts());
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err, 'finance.recurringCosts.errors.loadFailed'));
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
      // Synchronous guard: block a second submit before React re-renders.
      if (creatingRef.current || anyPending) return;
      creatingRef.current = true;
      setCreating(true);
      setCreateError(null);
      try {
        await adminAddRecurringCost(toInput(form));
        setForm(EMPTY_FIELDS);
        await refresh();
        onChanged();
      } catch (err) {
        setCreateError(errorMessage(err, 'finance.recurringCosts.errors.createFailed'));
      } finally {
        setCreating(false);
        creatingRef.current = false;
      }
    },
    [anyPending, form, refresh, onChanged],
  );

  const startEdit = useCallback((item: RecurringCost) => {
    setEditingId(item.id);
    setEditForm({
      label: item.label,
      description: item.description,
      amount: String(item.amount),
      currency: item.currency,
      period: item.period,
    });
    setEditError(null);
  }, []);

  const cancelEdit = useCallback(() => {
    setEditingId(null);
    setEditError(null);
  }, []);

  const handleSaveEdit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!editingId || savingRef.current || anyPending) return;
      savingRef.current = true;
      setSaving(true);
      setEditError(null);
      try {
        await adminUpdateRecurringCost(editingId, toInput(editForm));
        setEditingId(null);
        await refresh();
        onChanged();
      } catch (err) {
        setEditError(errorMessage(err, 'finance.recurringCosts.errors.updateFailed'));
      } finally {
        setSaving(false);
        savingRef.current = false;
      }
    },
    [editingId, anyPending, editForm, refresh, onChanged],
  );

  const handleDelete = useCallback(
    async (item: RecurringCost) => {
      if (deletingRef.current || anyPending) return;
      if (!window.confirm(`${t('finance.recurringCosts.deleteConfirm')}\n\n"${item.label}"`)) return;
      deletingRef.current = true;
      setBusyId(item.id);
      setActionError(null);
      try {
        await adminDeleteRecurringCost(item.id);
        if (editingId === item.id) setEditingId(null);
        await refresh();
        onChanged();
      } catch (err) {
        setActionError(errorMessage(err, 'finance.recurringCosts.errors.deleteFailed'));
      } finally {
        setBusyId(null);
        deletingRef.current = false;
      }
    },
    [anyPending, editingId, refresh, onChanged],
  );

  const renderFormFields = (
    fields: FormFields,
    setFields: (next: FormFields) => void,
    idPrefix: string,
  ) => (
    <>
      <label className={styles.formLabel} htmlFor={`${idPrefix}-label`}>
        {t('finance.recurringCosts.form.label')}
      </label>
      <input
        id={`${idPrefix}-label`}
        className={styles.formInput}
        type="text"
        maxLength={RECURRING_COST_LABEL_MAX_LENGTH}
        value={fields.label}
        onChange={(e) => setFields({ ...fields, label: e.target.value })}
        placeholder={t('finance.recurringCosts.form.labelPlaceholder')}
      />

      <label className={styles.formLabel} htmlFor={`${idPrefix}-description`}>
        {t('finance.recurringCosts.form.description')}
      </label>
      <textarea
        id={`${idPrefix}-description`}
        className={styles.formTextarea}
        rows={2}
        maxLength={RECURRING_COST_DESCRIPTION_MAX_LENGTH}
        value={fields.description}
        onChange={(e) => setFields({ ...fields, description: e.target.value })}
        placeholder={t('finance.recurringCosts.form.descriptionPlaceholder')}
      />

      <div className={styles.formRow}>
        <div className={styles.formCol}>
          <label className={styles.formLabel} htmlFor={`${idPrefix}-amount`}>
            {t('finance.recurringCosts.form.amount')}
          </label>
          <input
            id={`${idPrefix}-amount`}
            className={styles.formInput}
            type="number"
            min="0.01"
            step="0.01"
            max={RECURRING_COST_AMOUNT_MAX}
            value={fields.amount}
            onChange={(e) => setFields({ ...fields, amount: e.target.value })}
            placeholder="0"
          />
        </div>
        <div className={styles.formCol}>
          <label className={styles.formLabel} htmlFor={`${idPrefix}-currency`}>
            {t('finance.recurringCosts.form.currency')}
          </label>
          <select
            id={`${idPrefix}-currency`}
            className={styles.formInput}
            value={fields.currency}
            onChange={(e) => setFields({ ...fields, currency: e.target.value as RecurringCostCurrency })}
          >
            <option value="SEK">SEK</option>
            <option value="USD">USD</option>
          </select>
        </div>
        <div className={styles.formCol}>
          <label className={styles.formLabel} htmlFor={`${idPrefix}-period`}>
            {t('finance.recurringCosts.form.period')}
          </label>
          <select
            id={`${idPrefix}-period`}
            className={styles.formInput}
            value={fields.period}
            onChange={(e) => setFields({ ...fields, period: e.target.value as RecurringCostPeriod })}
          >
            <option value="monthly">{t('finance.recurringCosts.period.monthly')}</option>
            <option value="yearly">{t('finance.recurringCosts.period.yearly')}</option>
          </select>
        </div>
      </div>
    </>
  );

  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>{t('finance.recurringCosts.title')}</h2>
      <p className={styles.sectionNote}>{t('finance.recurringCosts.subtitle')}</p>

      {/* Add form */}
      <div className={styles.card}>
        <h3 className={styles.formHeading}>{t('finance.recurringCosts.addHeading')}</h3>
        <form className={styles.form} onSubmit={handleCreate}>
          {renderFormFields(form, setForm, 'rc-new')}
          <div className={styles.formActions}>
            <button
              type="submit"
              className={styles.primaryButton}
              disabled={!form.label.trim() || !form.amount.trim() || anyPending}
            >
              {creating ? t('finance.recurringCosts.adding') : t('finance.recurringCosts.add')}
            </button>
          </div>
          {createError && (
            <p className={styles.formError} role="alert">
              {createError}
            </p>
          )}
        </form>
      </div>

      {/* Table */}
      {loadError && (
        <p className={styles.formError} role="alert">
          {loadError}
        </p>
      )}
      {actionError && (
        <p className={styles.formError} role="alert">
          {actionError}
        </p>
      )}

      {loading ? (
        <p className={styles.sectionNote}>{t('finance.recurringCosts.loading')}</p>
      ) : items.length === 0 && !loadError ? (
        <div className={styles.emptyState}>
          <span className={styles.emptyMark} aria-hidden="true">
            ∅
          </span>
          {t('finance.recurringCosts.empty')}
        </div>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>{t('finance.recurringCosts.col.label')}</th>
                <th className={styles.numeric}>{t('finance.recurringCosts.col.amount')}</th>
                <th>{t('finance.recurringCosts.col.period')}</th>
                <th className={styles.numeric}>{t('finance.recurringCosts.col.monthlySek')}</th>
                <th className={styles.numeric}>{t('finance.recurringCosts.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) =>
                editingId === item.id ? (
                  <tr key={item.id}>
                    <td colSpan={5}>
                      <form className={styles.form} onSubmit={handleSaveEdit}>
                        {renderFormFields(editForm, setEditForm, `rc-edit-${item.id}`)}
                        <div className={styles.formActions}>
                          <button
                            type="submit"
                            className={styles.primaryButton}
                            disabled={!editForm.label.trim() || !editForm.amount.trim() || anyPending}
                          >
                            {saving
                              ? t('finance.recurringCosts.saving')
                              : t('finance.recurringCosts.save')}
                          </button>
                          <button
                            type="button"
                            className={styles.secondaryButton}
                            onClick={cancelEdit}
                            disabled={saving}
                          >
                            {t('finance.recurringCosts.cancel')}
                          </button>
                        </div>
                        {editError && (
                          <p className={styles.formError} role="alert">
                            {editError}
                          </p>
                        )}
                      </form>
                    </td>
                  </tr>
                ) : (
                  <tr key={item.id}>
                    <td>
                      <div>{item.label}</div>
                      {item.description && (
                        <div className={styles.rowDriver}>{item.description}</div>
                      )}
                    </td>
                    <td className={styles.numeric}>
                      {item.amount} {item.currency}
                    </td>
                    <td>
                      {item.period === 'yearly'
                        ? t('finance.recurringCosts.period.yearly')
                        : t('finance.recurringCosts.period.monthly')}
                    </td>
                    <td className={`${styles.numeric} ${styles.cost}`}>
                      {formatSek(monthlySek(item, usdToSek))}
                    </td>
                    <td className={styles.numeric}>
                      <div className={styles.rowActions}>
                        <button
                          type="button"
                          className={styles.secondaryButton}
                          onClick={() => startEdit(item)}
                          disabled={anyPending}
                        >
                          {t('finance.recurringCosts.edit')}
                        </button>
                        <button
                          type="button"
                          className={styles.dangerButton}
                          onClick={() => void handleDelete(item)}
                          disabled={anyPending}
                        >
                          {busyId === item.id
                            ? t('finance.recurringCosts.deleting')
                            : t('finance.recurringCosts.delete')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
