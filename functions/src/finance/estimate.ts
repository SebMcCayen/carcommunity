/**
 * finance.estimate — admin callable (contracts/functions/functions.json).
 *
 * Deployed via the `finance` export group as `finance-estimate`.
 *
 * ON-DEMAND, NOT SCHEDULED (a deliberate choice)
 * ----------------------------------------------
 * The board's numbers are a pure function of the price table, the assumptions,
 * the function inventory and the CURRENT member count. Nothing here needs
 * history, so there is no reason to pay for a scheduled write to snapshot it —
 * and a finance page that itself quietly added a daily Firestore write would be
 * a small irony. So this is computed on demand when an admin opens the page:
 * one admin-gated callable and two cheap reads — the latest metrics/{date}
 * snapshot for the live member count, and the `financeRecurringCosts`
 * collection for the operator-entered recurring costs. This callable runs on
 * the Admin SDK and is gated by `requireAdminActor` (Security Rules apply to
 * client SDKs, not to these server reads). After the reads the model runs in
 * memory. No scheduled write, so the board adds no recurring Firestore cost.
 *
 * NOTE: `financeRecurringCosts` is added by this PR. Its rules allow admin
 * client READS but DENY all client writes (`allow write: if false`); every
 * mutation goes through the audited CRUD callables, so the audit trail can't
 * be bypassed. The estimate merely reads the collection here.
 *
 * ⚠️ Everything MODELLED is a MODEL ESTIMATE, not the real bill (the
 * recurring costs are operator-entered actuals). The page shows the banner and
 * the link to the Google Cloud billing console.
 */

import { onCall } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import { METRICS_COLLECTION } from '../metrics/metrics-core';
import { estimateFinance, resolveMemberCount, type FinanceEstimate } from './model';
import {
  RECURRING_COSTS_COLLECTION,
  RECURRING_COST_AMOUNT_MAX,
  RECURRING_COST_CURRENCIES,
  RECURRING_COST_DESCRIPTION_MAX_LENGTH,
  RECURRING_COST_LABEL_MAX_LENGTH,
  RECURRING_COST_PERIODS,
  type RecurringCostCurrency,
  type RecurringCostEntry,
  type RecurringCostPeriod,
} from './recurringCosts-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/**
 * Reads the latest metrics snapshot's `totalUsers` (the live member count) and
 * its date. Returns nulls if no snapshot exists yet — the model then falls back
 * to a labelled default and the board says so.
 */
async function readLatestMemberCount(): Promise<{ totalUsers: number | null; date: string | null }> {
  // Ordered by the `date` field (== the YYYY-MM-DD doc id) descending, served
  // from the automatic single-field index — no composite index required.
  // Deliberately NOT orderBy('__name__','desc'): descending key scans are
  // rejected by Firestore ("does not support descending key scans"), so we sort
  // the mirrored `date` field instead, which every snapshot carries.
  const snap = await db
    .collection(METRICS_COLLECTION)
    .orderBy('date', 'desc')
    .limit(1)
    .get();
  if (snap.empty) {
    return { totalUsers: null, date: null };
  }
  const doc = snap.docs[0]!;
  const data = doc.data();
  const totalUsers = typeof data.totalUsers === 'number' ? data.totalUsers : null;
  const date = typeof data.date === 'string' ? data.date : doc.id;
  return { totalUsers, date };
}

/**
 * Reads every operator-entered recurring cost from `financeRecurringCosts`.
 * The collection is small (a handful of admin-entered rows), so an unbounded
 * read is fine.
 *
 * READ-SIDE DEFENCE (parity with the write-side zod). The callables are the
 * only sanctioned writer and firestore.rules deny all client writes, so a
 * well-formed row is the norm — but a row could still arrive via the console /
 * Admin SDK. So this mirrors the SAME bounds the write path enforces
 * (recurringCosts-core.ts) rather than trusting the stored shape:
 *  - currency/period must be valid enums, amount finite and > 0, label present
 *    — a row failing any of these is SKIPPED (never poisons the total);
 *  - amount ABOVE the sane upper bound is skipped too, so a fat-fingered
 *    console edit can't inflate the grand total;
 *  - label/description are CLAMPED to their caps (truncated, not rejected) so an
 *    over-long field can't bloat the payload.
 * Clamp/skip, never throw — one bad row must not break the whole board.
 */
async function readRecurringCosts(): Promise<RecurringCostEntry[]> {
  const snap = await db.collection(RECURRING_COSTS_COLLECTION).get();
  const entries: RecurringCostEntry[] = [];
  for (const doc of snap.docs) {
    const data = doc.data();
    const amount = typeof data.amount === 'number' ? data.amount : NaN;
    const currency = data.currency as RecurringCostCurrency;
    const period = data.period as RecurringCostPeriod;
    if (
      typeof data.label === 'string' &&
      data.label.length > 0 &&
      Number.isFinite(amount) &&
      amount > 0 &&
      amount <= RECURRING_COST_AMOUNT_MAX &&
      RECURRING_COST_CURRENCIES.includes(currency) &&
      RECURRING_COST_PERIODS.includes(period)
    ) {
      const description = typeof data.description === 'string' ? data.description : '';
      entries.push({
        id: doc.id,
        // Clamp to the write-side caps so a hand-edited over-long field is
        // truncated rather than passed through wholesale.
        label: data.label.slice(0, RECURRING_COST_LABEL_MAX_LENGTH),
        description: description.slice(0, RECURRING_COST_DESCRIPTION_MAX_LENGTH),
        amount,
        currency,
        period,
      });
    }
  }
  // Sort alphabetically by label for a stable, deterministic line order. The
  // model sums the entries regardless of order, so this only fixes how the
  // lines are listed (the admin UI applies its own display ordering).
  return entries.sort((a, b) => a.label.localeCompare(b.label));
}

export const estimate = onCall(CALLABLE_OPTS, async (request): Promise<FinanceEstimate> => {
  await requireAdminActor(request);

  const [{ totalUsers, date }, recurringCosts] = await Promise.all([
    readLatestMemberCount(),
    readRecurringCosts(),
  ]);
  const member = resolveMemberCount(totalUsers, date);

  return estimateFinance({
    memberCount: member.count,
    memberCountSource: member.source,
    memberCountAsOf: member.asOf,
    recurringCosts,
  });
});
