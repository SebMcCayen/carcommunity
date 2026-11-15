import { callAdmin } from '@/lib/callables';

export interface Resources {
  requests: number;
  reads: number;
  writes: number;
  deletes: number;
  cpuSeconds: number;
  gibSeconds: number;
  sentBytes: number;
}
export interface WorkloadLine {
  id: string;
  labelKey: string;
  source: 'assumption' | 'measured-usage';
  eventsPerMonth: number;
  assumedEventsPerMemberPerDay: number;
  readsPerEvent: number;
  writesPerEvent: number;
  deletesPerEvent: number;
  secondsPerEvent: number;
  bytesPerEvent: number;
  periodStart: string | null;
  periodEnd: string | null;
  capturedAtMs: number | null;
  resources: Resources;
  sekPerMonth: number;
}
export interface Evidence<T> {
  state: 'ready' | 'stale' | 'not-configured' | 'unavailable';
  data: T | null;
  lastAttemptFailed: boolean;
}
export interface UsageEvidence {
  capturedAtMs: number;
  startMs: number;
  endMs: number;
  functions: {
    id: string;
    requests: number | null;
    sentBytes: number | null;
    billableSeconds: number | null;
  }[];
}
export interface BillingEvidence {
  capturedAtMs: number;
  month: string;
  throughDate: string;
  latestExportAt: string;
  currency: 'SEK';
  services: { id: string; label: string; netCost: number }[];
}
export interface ReportedBilling extends Evidence<BillingEvidence> {
  totalSek: number | null;
  forecastSek: number | null;
  modelForPeriodSek: number | null;
  differenceSek: number | null;
  unallocatedToFunctionsSek: number | null;
}
/** Optional until the matching backend release has been deployed. */
export interface FinanceCoverage {
  workloads?: WorkloadLine[];
  assumptionsUpdatedAtMs?: number | null;
  usageEvidence?: Evidence<UsageEvidence>;
  reportedBilling?: ReportedBilling;
  otherModelledSekPerMonth?: number;
}
export function updateWorkloadAssumptions(
  overrides: { id: string; eventsPerMemberPerDay: number }[],
): Promise<{ ok: boolean }> {
  return callAdmin('finance-updateWorkloadAssumptions', { overrides });
}
