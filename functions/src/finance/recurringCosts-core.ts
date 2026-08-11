/**
 * Finance recurring costs — pure logic (input parsing, bounding, monthly
 * normalisation). No Firebase Admin SDK and no network imports, so every branch
 * is unit-testable without emulators (recurringCosts-core.test.ts).
 *
 * WHAT THIS IS
 * ------------
 * The finance board (functions/src/finance/*) estimates Google Cloud + Mapbox
 * spend from a model. Those are ESTIMATES. This module backs the OTHER half of
 * the grand total: the operator's REAL, arbitrary recurring costs (Claude, a
 * domain, a SaaS tool …), each entered by an admin with a description and
 * accounted for in the monthly total. Unlike the modelled sections these are
 * exact figures Seb types, stored in Firestore (`financeRecurringCosts/{id}`),
 * and surfaced as "operator-entered actuals" beside the modelled estimates.
 *
 * NORMALISATION
 * -------------
 * A cost has a `period` (`monthly` | `yearly`). The board works in SEK/month,
 * so a `yearly` cost contributes `amount / 12`. A `USD` amount is converted
 * through the SAME single dated FX constant the whole model runs on
 * (USD_TO_SEK, pricing.ts); `SEK` passes through. The conversion is done in
 * model.ts (which owns the FX helper) via [monthlyAmountInSourceCurrency] and
 * [annualAmountInSourceCurrency] here, keeping this module currency-agnostic
 * and pure.
 */

import { z } from 'zod';

// ---------------------------------------------------------------------------
// Collection + limits
// ---------------------------------------------------------------------------

/** Firestore collection of operator-entered recurring costs (admin-only). */
export const RECURRING_COSTS_COLLECTION = 'financeRecurringCosts';

/** Short display name, e.g. "Claude (Anthropic)". */
export const RECURRING_COST_LABEL_MAX_LENGTH = 80;
/** What the cost is for, e.g. "Max plan — the coding assistant that builds this app". */
export const RECURRING_COST_DESCRIPTION_MAX_LENGTH = 500;
/**
 * Upper bound on a single entry's amount, in its own currency's MAJOR units
 * (kronor / dollars, not öre / cents). Generous — a five-million-a-month line
 * is certainly a typo — and it exists mainly so a fat-fingered amount can never
 * balloon the grand total or overflow later arithmetic.
 */
export const RECURRING_COST_AMOUNT_MAX = 10_000_000;

/** Currencies an amount may be entered in (converted via USD_TO_SEK when USD). */
export const RECURRING_COST_CURRENCIES = ['SEK', 'USD'] as const;
export type RecurringCostCurrency = (typeof RECURRING_COST_CURRENCIES)[number];

/** Billing cadence. Yearly is normalised to /12 for the monthly board. */
export const RECURRING_COST_PERIODS = ['monthly', 'yearly'] as const;
export type RecurringCostPeriod = (typeof RECURRING_COST_PERIODS)[number];

// ---------------------------------------------------------------------------
// Stored shape + input
// ---------------------------------------------------------------------------

/**
 * The validated, normalised fields of one recurring cost. This is what a
 * callable persists (plus server-managed audit fields: createdByUid, createdAt,
 * updatedAt) and what the model reads back to fold into the monthly total.
 */
export interface RecurringCostFields {
  label: string;
  description: string;
  /** Amount in MAJOR units of `currency` (kronor / dollars). Finite, > 0. */
  amount: number;
  currency: RecurringCostCurrency;
  period: RecurringCostPeriod;
}

/** A stored entry (its document id plus the validated fields). */
export interface RecurringCostEntry extends RecurringCostFields {
  id: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

// ---------------------------------------------------------------------------
// Zod schemas
// ---------------------------------------------------------------------------

// zod 4: a bare z.number() already rejects NaN and ±Infinity, and .positive()
// rejects 0 and negatives, so an amount that reaches here is a real, finite,
// strictly-positive number bounded above by RECURRING_COST_AMOUNT_MAX.
const amountSchema = z.number().positive().max(RECURRING_COST_AMOUNT_MAX);

/** Fields shared by add + update. Trimming/emptiness is enforced after parse. */
const fieldsSchema = z.object({
  label: z.string().min(1).max(RECURRING_COST_LABEL_MAX_LENGTH),
  description: z.string().max(RECURRING_COST_DESCRIPTION_MAX_LENGTH),
  amount: amountSchema,
  currency: z.enum(RECURRING_COST_CURRENCIES),
  period: z.enum(RECURRING_COST_PERIODS),
});

const addInputSchema = fieldsSchema.strict();

const updateInputSchema = fieldsSchema.extend({ id: z.string().min(1).max(200) }).strict();

const deleteInputSchema = z.object({ id: z.string().min(1).max(200) }).strict();

// ---------------------------------------------------------------------------
// Bounding helpers
// ---------------------------------------------------------------------------

/** Collapses runs of whitespace and trims — a label/description is single-purpose text. */
function boundText(raw: string, max: number): string {
  return raw.replace(/\s+/g, ' ').trim().slice(0, max);
}

/** Multi-line-preserving bound for the description (keeps intentional newlines). */
function boundMultiline(raw: string, max: number): string {
  return raw
    .replace(/[^\S\n]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
    .slice(0, max);
}

/** Rounds a money amount to 2 decimals (öre/cents), avoiding float dust. */
export function roundAmount(amount: number): number {
  return Math.round((amount + Number.EPSILON) * 100) / 100;
}

// ---------------------------------------------------------------------------
// Parse functions (used by the callables; validation is the write-side gate)
// ---------------------------------------------------------------------------

function normaliseFields(data: z.infer<typeof fieldsSchema>): ParseResult<RecurringCostFields> {
  const label = boundText(data.label, RECURRING_COST_LABEL_MAX_LENGTH);
  if (label.length === 0) {
    return { ok: false, message: 'Label cannot be empty.' };
  }
  const description = boundMultiline(data.description, RECURRING_COST_DESCRIPTION_MAX_LENGTH);
  return {
    ok: true,
    input: {
      label,
      description,
      amount: roundAmount(data.amount),
      currency: data.currency,
      period: data.period,
    },
  };
}

/** Parse+bound the `finance.addRecurringCost` input. */
export function parseAddRecurringCostInput(data: unknown): ParseResult<RecurringCostFields> {
  const result = addInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        'Expected { label, description, amount > 0, currency: SEK|USD, period: monthly|yearly }.',
    };
  }
  return normaliseFields(result.data);
}

/** Parse+bound the `finance.updateRecurringCost` input (add fields + id). */
export function parseUpdateRecurringCostInput(
  data: unknown,
): ParseResult<RecurringCostFields & { id: string }> {
  const result = updateInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        'Expected { id, label, description, amount > 0, currency: SEK|USD, period: monthly|yearly }.',
    };
  }
  const fields = normaliseFields(result.data);
  if (!fields.ok) return fields;
  return { ok: true, input: { id: result.data.id, ...fields.input } };
}

/** Parse the `finance.deleteRecurringCost` input. */
export function parseDeleteRecurringCostInput(data: unknown): ParseResult<{ id: string }> {
  const result = deleteInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: 'Expected { id }.' };
  }
  return { ok: true, input: { id: result.data.id } };
}

// ---------------------------------------------------------------------------
// Normalisation to monthly / annual (currency-agnostic — model.ts applies FX)
// ---------------------------------------------------------------------------

/**
 * The amount this entry contributes PER MONTH, in its OWN currency. A yearly
 * cost is spread across 12 months; a monthly cost passes through. (model.ts
 * then converts USD → SEK via the single dated FX rate.)
 */
export function monthlyAmountInSourceCurrency(entry: RecurringCostFields): number {
  return entry.period === 'yearly' ? entry.amount / 12 : entry.amount;
}

/**
 * The amount this entry costs PER YEAR, in its OWN currency — for the line
 * detail (a monthly cost is ×12, a yearly cost is itself).
 */
export function annualAmountInSourceCurrency(entry: RecurringCostFields): number {
  return entry.period === 'yearly' ? entry.amount : entry.amount * 12;
}
