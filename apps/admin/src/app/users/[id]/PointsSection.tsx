'use client';

/**
 * UserPointsSection — admin Kronpoäng (KP) section for the user detail page.
 *
 * Shows the current balance, recent ledger entries, and an adjustment form.
 *
 * Security notes:
 *  - All operations are validated and authorised server-side.
 *  - The balance shown is fetched from the backend — never calculated locally.
 *  - Adjustment amounts must be positive integers (1–100 000).
 *  - A reason is required for every adjustment and is audited server-side.
 *  - Debits that would produce a negative balance are rejected by the backend.
 *  - The admin cannot set an absolute balance.
 *  - Existing ledger entries cannot be edited or deleted.
 *  - No transfer, purchase, withdrawal, or cash-value controls are exposed.
 *  - Owner accounts are protected by backend authorisation rules.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getAdminUserPointsBalance,
  getAdminUserPointsLedger,
  applyAdminPointsAdjustment,
  type PointsBalanceResponse,
  type PaginatedPointsLedgerResponse,
  type ApiError,
} from '@/features/points';
import { translate } from '@/i18n';
import { formatDateOnly } from '@/lib/format';
import styles from './PointsSection.module.css';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatDate(iso: string): string {
  return formatDateOnly(iso);
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

interface UserPointsSectionProps {
  userId: string;
}

export function UserPointsSection({ userId }: UserPointsSectionProps) {
  // ---------------------------------------------------------------------------
  // Balance state
  // ---------------------------------------------------------------------------
  const [balance, setBalance] = useState<PointsBalanceResponse | null>(null);
  const [balanceLoading, setBalanceLoading] = useState(true);
  const [balanceError, setBalanceError] = useState<string | null>(null);

  // ---------------------------------------------------------------------------
  // Ledger state
  // ---------------------------------------------------------------------------
  const [ledger, setLedger] = useState<PaginatedPointsLedgerResponse | null>(null);
  const [ledgerLoading, setLedgerLoading] = useState(true);
  const [ledgerError, setLedgerError] = useState<string | null>(null);

  // ---------------------------------------------------------------------------
  // Adjustment form state
  // ---------------------------------------------------------------------------
  const [adjustDirection, setAdjustDirection] = useState<'credit' | 'debit'>('credit');
  const [adjustAmount, setAdjustAmount] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [adjustLoading, setAdjustLoading] = useState(false);
  const [adjustError, setAdjustError] = useState<string | null>(null);
  const [adjustSuccess, setAdjustSuccess] = useState<string | null>(null);
  const [showConfirm, setShowConfirm] = useState(false);

  const adjustingRef = useRef(false);

  // ---------------------------------------------------------------------------
  // Fetch balance
  // ---------------------------------------------------------------------------
  const fetchBalance = useCallback(async () => {
    setBalanceLoading(true);
    setBalanceError(null);
    try {
      const result = await getAdminUserPointsBalance(userId);
      setBalance(result);
    } catch (err) {
      setBalanceError((err as ApiError).message ?? t('points.error'));
    } finally {
      setBalanceLoading(false);
    }
  }, [userId]);

  // ---------------------------------------------------------------------------
  // Fetch ledger
  // ---------------------------------------------------------------------------
  const fetchLedger = useCallback(async () => {
    setLedgerLoading(true);
    setLedgerError(null);
    try {
      const result = await getAdminUserPointsLedger(userId);
      setLedger(result);
    } catch (err) {
      setLedgerError((err as ApiError).message ?? t('points.error'));
    } finally {
      setLedgerLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    void fetchBalance();
    void fetchLedger();
  }, [fetchBalance, fetchLedger]);

  // ---------------------------------------------------------------------------
  // Derived expected balance for UI guidance only
  // ---------------------------------------------------------------------------
  const currentBalance = balance?.data.balance ?? null;
  const parsedAmount = parseInt(adjustAmount, 10);
  const validAmount = Number.isInteger(parsedAmount) && parsedAmount > 0 && parsedAmount <= 100_000;
  const expectedBalance =
    currentBalance !== null && validAmount
      ? adjustDirection === 'credit'
        ? currentBalance + parsedAmount
        : currentBalance - parsedAmount
      : null;

  // ---------------------------------------------------------------------------
  // Submit adjustment
  // ---------------------------------------------------------------------------
  async function handleAdjustConfirm() {
    if (adjustingRef.current) return;
    if (!validAmount || !adjustReason.trim()) return;

    adjustingRef.current = true;
    setAdjustLoading(true);
    setAdjustError(null);
    setAdjustSuccess(null);
    setShowConfirm(false);

    try {
      await applyAdminPointsAdjustment(userId, {
        type: adjustDirection === 'credit' ? 'adjustment_credit' : 'adjustment_debit',
        amount: parsedAmount,
        reason: adjustReason.trim(),
      });
      setAdjustSuccess(t('points.adjust.success'));
      setAdjustAmount('');
      setAdjustReason('');
      await fetchBalance();
      await fetchLedger();
    } catch (err) {
      const apiErr = err as ApiError;
      if (apiErr.statusCode === 400) {
        setAdjustError(t('points.adjust.insufficientBalance'));
      } else {
        setAdjustError(apiErr.message ?? t('points.adjust.error'));
      }
    } finally {
      setAdjustLoading(false);
      adjustingRef.current = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------
  return (
    <section className={styles.section} aria-labelledby="points-heading">
      <h2 id="points-heading" className={styles.sectionTitle}>
        {t('points.pageTitle')}
      </h2>

      {/* Balance */}
      <div className={styles.balanceCard}>
        {balanceLoading ? (
          <span className={styles.meta}>{t('points.loading')}</span>
        ) : balanceError ? (
          <div className={styles.errorState} role="alert">
            {balanceError}
            <button className={styles.retryButton} onClick={() => void fetchBalance()}>
              {t('points.retry')}
            </button>
          </div>
        ) : (
          <span className={styles.balanceValue}>
            {balance?.data.balance ?? 0} {t('points.shortForm')}
          </span>
        )}
        <span className={styles.balanceLabel}>{t('points.balanceLabel')}</span>
      </div>

      {/* Ledger */}
      <div className={styles.subsection}>
        <h3 className={styles.subsectionTitle}>{t('points.recentTransactions')}</h3>
        {ledgerLoading ? (
          <div className={styles.loadingState} aria-live="polite" aria-busy="true">
            {t('points.loading')}
          </div>
        ) : ledgerError ? (
          <div className={styles.errorState} role="alert">
            {ledgerError}
            <button className={styles.retryButton} onClick={() => void fetchLedger()}>
              {t('points.retry')}
            </button>
          </div>
        ) : (
          <div className={styles.tableWrapper}>
            <table className={styles.table} aria-label={t('points.recentTransactions')}>
              <thead>
                <tr>
                  <th scope="col">{t('points.columns.date')}</th>
                  <th scope="col">{t('points.columns.type')}</th>
                  <th scope="col">{t('points.columns.source')}</th>
                  <th scope="col">{t('points.columns.amount')}</th>
                  <th scope="col">{t('points.columns.balanceAfter')}</th>
                  <th scope="col">{t('points.columns.description')}</th>
                </tr>
              </thead>
              <tbody>
                {ledger?.data.transactions.length === 0 ? (
                  <tr>
                    <td colSpan={6} className={styles.emptyRow}>
                      {t('points.empty')}
                    </td>
                  </tr>
                ) : (
                  ledger?.data.transactions.map((tx) => (
                    <tr key={tx.transactionId}>
                      <td>{formatDate(tx.createdAt)}</td>
                      <td>
                        <code className={styles.code}>{tx.transactionType}</code>
                      </td>
                      <td>
                        <code className={styles.code}>{tx.source}</code>
                      </td>
                      <td
                        className={tx.amount >= 0 ? styles.amountCredit : styles.amountDebit}
                      >
                        {tx.amount >= 0 ? `+${tx.amount}` : String(tx.amount)}
                      </td>
                      <td>{tx.balanceAfter}</td>
                      <td>{tx.description}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Adjustment form */}
      <div className={styles.subsection}>
        <h3 className={styles.subsectionTitle}>{t('points.adjust.title')}</h3>
        <p className={styles.notice}>{t('points.adjust.notice')}</p>

        {adjustSuccess && (
          <div className={styles.successState} role="status" aria-live="polite">
            {adjustSuccess}
          </div>
        )}
        {adjustError && (
          <div className={styles.errorState} role="alert">
            {adjustError}
          </div>
        )}

        {/* Confirmation dialog */}
        {showConfirm && (
          <div className={styles.confirmDialog} role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">
            <p id="confirm-title" className={styles.confirmTitle}>
              {t('points.adjust.confirmTitle')}
            </p>
            <p className={styles.meta}>{t('points.adjust.confirmDescription')}</p>
            <div className={styles.confirmActions}>
              <button
                className={styles.submitButton}
                onClick={() => void handleAdjustConfirm()}
                disabled={adjustLoading}
                aria-busy={adjustLoading}
              >
                {adjustLoading ? t('points.adjust.submitting') : t('points.adjust.confirm')}
              </button>
              <button
                className={styles.cancelButton}
                onClick={() => setShowConfirm(false)}
                disabled={adjustLoading}
              >
                {t('points.adjust.cancel')}
              </button>
            </div>
          </div>
        )}

        <form
          className={styles.form}
          onSubmit={(e) => {
            e.preventDefault();
            if (!validAmount || !adjustReason.trim()) return;
            setShowConfirm(true);
          }}
          noValidate
        >
          {/* Direction */}
          <div className={styles.formGroup}>
            <fieldset className={styles.fieldset}>
              <legend className={styles.label}>{t('points.adjust.title')}</legend>
              <label className={styles.radioLabel}>
                <input
                  type="radio"
                  name="direction"
                  value="credit"
                  checked={adjustDirection === 'credit'}
                  onChange={() => setAdjustDirection('credit')}
                  disabled={adjustLoading}
                />
                {t('points.adjust.creditLabel')}
              </label>
              <label className={styles.radioLabel}>
                <input
                  type="radio"
                  name="direction"
                  value="debit"
                  checked={adjustDirection === 'debit'}
                  onChange={() => setAdjustDirection('debit')}
                  disabled={adjustLoading}
                />
                {t('points.adjust.debitLabel')}
              </label>
            </fieldset>
          </div>

          {/* Amount */}
          <div className={styles.formGroup}>
            <label htmlFor="adjust-amount" className={styles.label}>
              {t('points.adjust.amountLabel')} <span aria-hidden="true">*</span>
            </label>
            <input
              id="adjust-amount"
              className={styles.input}
              type="number"
              min={1}
              max={100000}
              step={1}
              value={adjustAmount}
              onChange={(e) => setAdjustAmount(e.target.value)}
              placeholder={t('points.adjust.amountPlaceholder')}
              required
              disabled={adjustLoading}
            />
            {expectedBalance !== null && (
              <span className={styles.hint}>
                {t('points.adjust.expectedBalance')}: {expectedBalance} {t('points.shortForm')}
              </span>
            )}
          </div>

          {/* Reason */}
          <div className={styles.formGroup}>
            <label htmlFor="adjust-reason" className={styles.label}>
              {t('points.adjust.reasonLabel')} <span aria-hidden="true">*</span>
            </label>
            <textarea
              id="adjust-reason"
              className={styles.textarea}
              value={adjustReason}
              onChange={(e) => setAdjustReason(e.target.value)}
              placeholder={t('points.adjust.reasonPlaceholder')}
              required
              disabled={adjustLoading}
            />
          </div>

          <button
            type="submit"
            className={styles.submitButton}
            disabled={adjustLoading || !validAmount || !adjustReason.trim() || showConfirm}
            aria-busy={adjustLoading}
          >
            {t('points.adjust.submit')}
          </button>
        </form>
      </div>
    </section>
  );
}
