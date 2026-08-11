/**
 * Admin recurring-costs feature module (Finance & Cost board).
 *
 * READS are direct rules-gated Firestore reads (financeRecurringCosts:
 * read = isAdmin()); MUTATIONS go through the audited, field-validating
 * callables (finance-addRecurringCost / finance-updateRecurringCost /
 * finance-deleteRecurringCost) — the lib/firestore + lib/callables split.
 *
 * These are OPERATOR-ENTERED ACTUALS (Claude, tooling, domains …), each an
 * exact figure with a description, folded into the board's monthly grand total
 * by the finance-estimate callable. This module is also a client-side shape
 * gate: it validates before every callable so obvious mistakes are caught
 * without a round-trip (the callable re-validates authoritatively).
 *
 * The stored document shape mirrors functions/src/finance/recurringCosts-core.ts:
 *   { label, description, amount, currency, period, createdByUid,
 *     createdAt (server ts), updatedAt (server ts) }
 * Keep the field names in sync — the finance model reads them back by name.
 */

import {
  collection,
  getDocs,
  orderBy,
  query,
  type DocumentData,
} from 'firebase/firestore';

import { callAdmin } from '../../lib/callables';
import { ApiError } from '../../lib/errors';
import { getAdminFirestore } from '../../lib/firestore';

export { ApiError };

export const RECURRING_COST_LABEL_MAX_LENGTH = 80;
export const RECURRING_COST_DESCRIPTION_MAX_LENGTH = 500;
export const RECURRING_COST_AMOUNT_MAX = 10_000_000;

const COLLECTION = 'financeRecurringCosts';

export type RecurringCostCurrency = 'SEK' | 'USD';
export type RecurringCostPeriod = 'monthly' | 'yearly';

/** A stored recurring cost as read for the admin table. */
export interface RecurringCost {
  id: string;
  label: string;
  description: string;
  amount: number;
  currency: RecurringCostCurrency;
  period: RecurringCostPeriod;
}

/** The editable fields of the add/edit form. */
export interface RecurringCostInput {
  label: string;
  description: string;
  amount: number;
  currency: RecurringCostCurrency;
  period: RecurringCostPeriod;
}

/**
 * Normalises a stored document into a RecurringCost. Permissive on shape — a
 * hand-edited/partial doc should not break the whole listing — but only returns
 * rows with the load-bearing fields present and valid; a bad row is dropped.
 */
function toRecurringCost(id: string, data: DocumentData): RecurringCost | null {
  const amount = typeof data.amount === 'number' ? data.amount : NaN;
  const currency = data.currency;
  const period = data.period;
  if (
    typeof data.label !== 'string' ||
    data.label.length === 0 ||
    !Number.isFinite(amount) ||
    amount <= 0 ||
    (currency !== 'SEK' && currency !== 'USD') ||
    (period !== 'monthly' && period !== 'yearly')
  ) {
    return null;
  }
  return {
    id,
    label: data.label,
    description: typeof data.description === 'string' ? data.description : '',
    amount,
    currency,
    period,
  };
}

/** Lists the recurring costs (direct admin read), ordered by label. */
export async function adminListRecurringCosts(): Promise<RecurringCost[]> {
  const q = query(collection(getAdminFirestore(), COLLECTION), orderBy('label'));
  const snap = await getDocs(q);
  const rows: RecurringCost[] = [];
  snap.forEach((d) => {
    const row = toRecurringCost(d.id, d.data());
    if (row) rows.push(row);
  });
  return rows;
}

/**
 * Validates + normalises form input. Throws ApiError(400) with a stable code
 * the page maps to an i18n message. Mirrors the backend zod bounds.
 */
export function validateRecurringCostInput(input: RecurringCostInput): RecurringCostInput {
  const label = input.label.replace(/\s+/g, ' ').trim();
  if (!label) {
    throw new ApiError(400, 'recurringCost/label-required', 'Label is required.');
  }
  if (label.length > RECURRING_COST_LABEL_MAX_LENGTH) {
    throw new ApiError(
      400,
      'recurringCost/label-too-long',
      `Label must be at most ${RECURRING_COST_LABEL_MAX_LENGTH} characters.`,
    );
  }
  const description = input.description.trim();
  if (description.length > RECURRING_COST_DESCRIPTION_MAX_LENGTH) {
    throw new ApiError(
      400,
      'recurringCost/description-too-long',
      `Description must be at most ${RECURRING_COST_DESCRIPTION_MAX_LENGTH} characters.`,
    );
  }
  if (!Number.isFinite(input.amount) || input.amount <= 0) {
    throw new ApiError(400, 'recurringCost/amount-invalid', 'Amount must be a number greater than 0.');
  }
  if (input.amount > RECURRING_COST_AMOUNT_MAX) {
    throw new ApiError(400, 'recurringCost/amount-too-large', 'Amount is unrealistically large.');
  }
  if (input.currency !== 'SEK' && input.currency !== 'USD') {
    throw new ApiError(400, 'recurringCost/currency-invalid', 'Currency must be SEK or USD.');
  }
  if (input.period !== 'monthly' && input.period !== 'yearly') {
    throw new ApiError(400, 'recurringCost/period-invalid', 'Period must be monthly or yearly.');
  }
  return { label, description, amount: input.amount, currency: input.currency, period: input.period };
}

/** Adds a recurring cost (audited callable). */
export async function adminAddRecurringCost(input: RecurringCostInput): Promise<{ id: string }> {
  const fields = validateRecurringCostInput(input);
  return callAdmin<{ id: string }>('finance-addRecurringCost', fields);
}

/** Updates a recurring cost (audited callable). */
export async function adminUpdateRecurringCost(
  id: string,
  input: RecurringCostInput,
): Promise<{ id: string }> {
  const fields = validateRecurringCostInput(input);
  return callAdmin<{ id: string }>('finance-updateRecurringCost', { id, ...fields });
}

/** Deletes a recurring cost (audited callable). */
export async function adminDeleteRecurringCost(id: string): Promise<{ id: string; deleted: boolean }> {
  return callAdmin<{ id: string; deleted: boolean }>('finance-deleteRecurringCost', { id });
}
