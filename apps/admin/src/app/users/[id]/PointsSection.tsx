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
import { useCursorHistory } from '@/hooks/useCursorHistory';
import { HistoryPager } from '@/components/ui/HistoryPager';
import {
  getAdminUserPointsLedger,
  applyAdminPointsAdjustment,
  type PointsBalanceResponse,
  type ApiError,
} from '@/features/points';
import { pendingOperation } from '@/lib/pendingOperation';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { translate } from '@/i18n';
import styles from './PointsSection.module.css';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString('sv-SE');
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

interface UserPointsSectionProps {
  userId: string;
  actorUid?: string;
  canAdjust?: boolean;
}

export function UserPointsSection({ userId, actorUid, canAdjust = true }: UserPointsSectionProps) {
  // ---------------------------------------------------------------------------
  // Balance state
  // ---------------------------------------------------------------------------
  const [balance, setBalance] = useState<PointsBalanceResponse | null>(null);
  const [balanceLoading, setBalanceLoading] = useState(true);
  const [balanceError, setBalanceError] = useState<string | null>(null);
  const balanceRequest = useRef(0);

  // ---------------------------------------------------------------------------
  // Ledger state
  // ---------------------------------------------------------------------------
  const readLedger = useCallback(
    async (cursor: string | null) => {
      const sequence = cursor === null ? ++balanceRequest.current : balanceRequest.current;
      if (cursor === null) {
        setBalanceLoading(true);
        setBalanceError(null);
      }
      try {
        const result = await getAdminUserPointsLedger(userId, undefined, undefined, cursor);
        if (cursor === null && sequence === balanceRequest.current) {
          setBalance({
            ok: true,
            data: { balance: result.data.balance, displayName: 'Kronpoäng', shortForm: 'KP' },
          });
          setBalanceLoading(false);
        }
        return { items: result.data.transactions, meta: result.meta };
      } catch (error) {
        if (cursor === null && sequence === balanceRequest.current) {
          setBalanceError(t('points.error'));
          setBalanceLoading(false);
        }
        throw error;
      }
    },
    [userId],
  );
  const ledgerHistory = useCursorHistory(readLedger, 'transactionId');
  const ledgerLoading = ledgerHistory.loading && ledgerHistory.items.length === 0;
  const ledgerError = ledgerHistory.items.length === 0 ? ledgerHistory.error : null;
  const fetchLedger = ledgerHistory.refresh;

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

  const fetchBalance = fetchLedger;
  useEffect(
    () => () => {
      balanceRequest.current++;
    },
    [userId],
  );

  // ---------------------------------------------------------------------------
  // Derived expected balance for UI guidance only
  // ---------------------------------------------------------------------------
  const currentBalance = balance?.data.balance ?? null;
  const parsedAmount = Number(adjustAmount);
  const validAmount =
    Number.isSafeInteger(parsedAmount) && parsedAmount > 0 && parsedAmount <= 100_000;
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
      if (!actorUid)
        throw new Error('Läs medlemskortet igen för att bekräfta administratörens session.');
      const payload = {
        type:
          adjustDirection === 'credit'
            ? ('adjustment_credit' as const)
            : ('adjustment_debit' as const),
        amount: parsedAmount,
        reason: adjustReason.trim(),
        expectedActorUid: actorUid,
      };
      const receipt = pendingOperation(`${actorUid}:${userId}:points-adjust`, payload);
      const result = await applyAdminPointsAdjustment(userId, {
        type: adjustDirection === 'credit' ? 'adjustment_credit' : 'adjustment_debit',
        amount: parsedAmount,
        reason: adjustReason.trim(),
        expectedActorUid: actorUid,
        idempotencyKey: receipt.key,
      });
      receipt.clear();
      balanceRequest.current++;
      setBalance({
        ok: true,
        data: { balance: result.data.balanceAfter, displayName: 'Kronpoäng', shortForm: 'KP' },
      });
      setBalanceError(null);
      setBalanceLoading(false);
      setAdjustSuccess(t('points.adjust.success'));
      setAdjustAmount('');
      setAdjustReason('');
      await fetchLedger();
    } catch (err) {
      const apiErr = err as ApiError;
      setAdjustError(apiErr.message ?? t('points.adjust.error'));
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
        Kronpoäng (KP) / XP
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
        <span className={styles.balanceLabel}>Gemensamt saldo för XP och Kronpoäng</span>
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
                {ledgerHistory.items.length === 0 ? (
                  <tr>
                    <td colSpan={6} className={styles.emptyRow}>
                      {t('points.empty')}
                    </td>
                  </tr>
                ) : (
                  ledgerHistory.items.map((tx) => (
                    <tr key={tx.transactionId}>
                      <td>{formatDate(tx.createdAt)}</td>
                      <td>
                        <code className={styles.code}>{tx.transactionType}</code>
                      </td>
                      <td>
                        <code className={styles.code}>{tx.source}</code>
                      </td>
                      <td className={tx.amount >= 0 ? styles.amountCredit : styles.amountDebit}>
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

      {((!ledgerHistory.loading && !ledgerHistory.error) || ledgerHistory.items.length > 0) && (
        <HistoryPager
          shown={ledgerHistory.items.length}
          total={ledgerHistory.total}
          hasNext={!!ledgerHistory.nextCursor}
          loading={ledgerHistory.loading}
          error={ledgerHistory.error}
          onMore={() => void ledgerHistory.loadMore()}
          onRefresh={() => void fetchLedger()}
        />
      )}

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
          <ActionDialog
            title={t('points.adjust.confirmTitle')}
            cancelDisabled={adjustLoading}
            onCancel={() => setShowConfirm(false)}
          >
            <p>
              {userId}: {currentBalance} → {expectedBalance} KP
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
            </div>
          </ActionDialog>
        )}

        <form
          className={styles.form}
          onSubmit={(e) => {
            e.preventDefault();
            if (
              !validAmount ||
              !adjustReason.trim() ||
              balanceLoading ||
              balanceError ||
              currentBalance === null
            )
              return;
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
              maxLength={500}
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
            disabled={
              !canAdjust ||
              adjustLoading ||
              !validAmount ||
              !adjustReason.trim() ||
              showConfirm ||
              balanceLoading ||
              !!balanceError ||
              currentBalance === null
            }
            aria-busy={adjustLoading}
          >
            {t('points.adjust.submit')}
          </button>
        </form>
      </div>
    </section>
  );
}
