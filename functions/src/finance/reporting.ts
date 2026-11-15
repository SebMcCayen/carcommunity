import { DAY_MS, EVIDENCE_MAX_AGE_MS, type BillingEvidence, type Evidence } from './evidence';
import { DAYS_PER_MONTH } from './pricing';

export interface ReportedBilling extends Evidence<BillingEvidence> {
  totalSek: number | null;
  forecastSek: number | null;
  modelForPeriodSek: number | null;
  differenceSek: number | null;
  /** No precise feature attribution exists in the standard billing export. */
  unallocatedToFunctionsSek: number | null;
}

export function reportedBilling(
  input: Evidence<BillingEvidence> | undefined,
  now: Date,
  modelMonthly: number,
): ReportedBilling {
  const evidence = input ?? { state: 'not-configured', data: null, lastAttemptFailed: false };
  const data = evidence.data;
  const currentMonth = now.toISOString().slice(0, 7);
  const stale =
    data &&
    (data.month !== currentMonth ||
      now.getTime() - Date.parse(data.latestExportAt) > EVIDENCE_MAX_AGE_MS);
  const state = stale && evidence.state === 'ready' ? 'stale' : evidence.state;
  const total = data ? data.services.reduce((sum, service) => sum + service.netCost, 0) : null;
  const start = data ? Date.parse(`${data.month}-01T00:00:00Z`) : NaN;
  const end = data ? Date.parse(`${data.throughDate}T00:00:00Z`) + DAY_MS : NaN;
  const days = (end - start) / DAY_MS;
  const canCompare =
    state === 'ready' && data?.month === currentMonth && days > 0 && end <= now.getTime();
  const calendarDays = new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0),
  ).getUTCDate();
  const modelForPeriod = canCompare ? (modelMonthly / DAYS_PER_MONTH) * days : null;
  return {
    ...evidence,
    state,
    totalSek: total,
    forecastSek: canCompare && total !== null ? (total / days) * calendarDays : null,
    modelForPeriodSek: modelForPeriod,
    differenceSek: total !== null && modelForPeriod !== null ? total - modelForPeriod : null,
    unallocatedToFunctionsSek: total,
  };
}
