import type { WorkloadOverride } from './settings-core';
import { DAY_MS, type UsageEvidence } from './evidence';
import { DAYS_PER_MONTH } from './pricing';

export interface Resources {
  requests: number;
  reads: number;
  writes: number;
  deletes: number;
  cpuSeconds: number;
  gibSeconds: number;
  sentBytes: number;
}
export interface WorkloadDefinition {
  id: string;
  labelKey: string;
  eventsPerMemberPerDay: number;
  readsPerEvent: number;
  writesPerEvent: number;
  deletesPerEvent: number;
  secondsPerEvent: number;
  bytesPerEvent: number;
  memoryGiB?: number;
  // Queue mutations are in addition to already costed scheduled invocations.
  scheduled?: boolean;
}

/**
 * Initial planning assumptions, 2026-11-15, NOT measured counts or upper bounds.
 * Calls include authorization/feature checks, quota work and expected retries.
 * Feed/reaction reads vary with page size, joins and transaction retries; these
 * round budgets must be calibrated. Photos include base64/JSON overhead. Queue
 * work assumes one task per 300 member-days; reaction cleanup one per 50 days.
 */
export const WORKLOADS: readonly WorkloadDefinition[] = [
  {
    id: 'roadCameras.photo',
    labelKey: 'photos',
    eventsPerMemberPerDay: 2,
    readsPerEvent: 8,
    writesPerEvent: 2,
    deletesPerEvent: 0,
    secondsPerEvent: 0.8,
    bytesPerEvent: 100 * 1024,
  },
  {
    id: 'roadCameras.catalog',
    labelKey: 'catalog',
    eventsPerMemberPerDay: 1,
    readsPerEvent: 25,
    writesPerEvent: 2,
    deletesPerEvent: 0,
    secondsPerEvent: 0.5,
    bytesPerEvent: 600 * 1024,
  },
  {
    id: 'driverFeed.list',
    labelKey: 'feed',
    eventsPerMemberPerDay: 4,
    readsPerEvent: 85,
    writesPerEvent: 1,
    deletesPerEvent: 0,
    secondsPerEvent: 0.5,
    bytesPerEvent: 30 * 1024,
  },
  {
    id: 'driverFeed.detail',
    labelKey: 'feedDetail',
    eventsPerMemberPerDay: 2,
    readsPerEvent: 85,
    writesPerEvent: 0,
    deletesPerEvent: 0,
    secondsPerEvent: 0.5,
    bytesPerEvent: 30 * 1024,
  },
  {
    id: 'chatReactions.set',
    labelKey: 'reactions',
    eventsPerMemberPerDay: 2,
    readsPerEvent: 15,
    writesPerEvent: 5,
    deletesPerEvent: 1,
    secondsPerEvent: 0.4,
    bytesPerEvent: 1024,
  },
  {
    id: 'chatReactions.getMine',
    labelKey: 'reactionReads',
    eventsPerMemberPerDay: 5,
    readsPerEvent: 30,
    writesPerEvent: 0,
    deletesPerEvent: 0,
    secondsPerEvent: 0.3,
    bytesPerEvent: 4 * 1024,
  },
  {
    id: 'account.getAccess',
    labelKey: 'access',
    eventsPerMemberPerDay: 4,
    readsPerEvent: 5,
    writesPerEvent: 0,
    deletesPerEvent: 0,
    secondsPerEvent: 0.3,
    bytesPerEvent: 2 * 1024,
  },
  {
    id: 'account.listWarnings',
    labelKey: 'warnings',
    eventsPerMemberPerDay: 1,
    readsPerEvent: 25,
    writesPerEvent: 0,
    deletesPerEvent: 0,
    secondsPerEvent: 0.3,
    bytesPerEvent: 4 * 1024,
  },
  {
    id: 'partners.portal',
    labelKey: 'partnerPortal',
    // Unmeasured pilot mix of browsing, reservations, QR and staff operations.
    // Includes authorization, private quotas, retries and rare logo processing;
    // the separately listed schedules own expiry and aggregate-publication work.
    eventsPerMemberPerDay: 0.5,
    readsPerEvent: 40,
    writesPerEvent: 8,
    deletesPerEvent: 1,
    secondsPerEvent: 0.75,
    bytesPerEvent: 64 * 1024,
    memoryGiB: 0.5,
  },
  {
    id: 'chatReactionLifecycle.activeWork',
    labelKey: 'reactionCleanup',
    eventsPerMemberPerDay: 0.02,
    readsPerEvent: 50,
    writesPerEvent: 5,
    deletesPerEvent: 50,
    secondsPerEvent: 1,
    bytesPerEvent: 0,
  },
  {
    id: 'driverQueue.activeWork',
    labelKey: 'driverQueue',
    eventsPerMemberPerDay: 1 / 300,
    readsPerEvent: 500,
    writesPerEvent: 100,
    deletesPerEvent: 500,
    secondsPerEvent: 0,
    bytesPerEvent: 0,
    scheduled: true,
  },
];

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

export const emptyResources = (): Resources => ({
  requests: 0,
  reads: 0,
  writes: 0,
  deletes: 0,
  cpuSeconds: 0,
  gibSeconds: 0,
  sentBytes: 0,
});

export function buildWorkloads(
  members: number,
  usage: UsageEvidence | null,
  overrides: WorkloadOverride[] = [],
): WorkloadLine[] {
  return WORKLOADS.map((definition) => {
    const rate =
      overrides.find((row) => row.id === definition.id)?.eventsPerMemberPerDay ??
      definition.eventsPerMemberPerDay;
    const observation = usage?.functions.find((f) => f.id === definition.id);
    const measured = observation?.requests != null;
    const scale = usage ? DAYS_PER_MONTH / ((usage.endMs - usage.startMs) / DAY_MS) : 1;
    const events = measured ? observation.requests! * scale : members * rate * DAYS_PER_MONTH;
    const seconds =
      measured && observation.billableSeconds != null
        ? observation.billableSeconds * scale
        : events * definition.secondsPerEvent;
    const bytes =
      measured && observation.sentBytes != null
        ? observation.sentBytes * scale
        : events * definition.bytesPerEvent;
    return {
      id: definition.id,
      labelKey: definition.labelKey,
      source: measured ? 'measured-usage' : 'assumption',
      eventsPerMonth: events,
      assumedEventsPerMemberPerDay: rate,
      readsPerEvent: definition.readsPerEvent,
      writesPerEvent: definition.writesPerEvent,
      deletesPerEvent: definition.deletesPerEvent,
      secondsPerEvent: definition.secondsPerEvent,
      bytesPerEvent: definition.bytesPerEvent,
      periodStart: measured ? new Date(usage!.startMs).toISOString().slice(0, 10) : null,
      periodEnd: measured ? new Date(usage!.endMs).toISOString().slice(0, 10) : null,
      capturedAtMs: measured ? usage!.capturedAtMs : null,
      resources: {
        requests: definition.scheduled ? 0 : events,
        reads: events * definition.readsPerEvent,
        writes: events * definition.writesPerEvent,
        deletes: events * definition.deletesPerEvent,
        cpuSeconds: seconds,
        gibSeconds: seconds * (definition.memoryGiB ?? 0.25),
        sentBytes: bytes,
      },
      sekPerMonth: 0,
    };
  });
}

export function sumWorkloads(lines: WorkloadLine[]): Resources {
  const total = emptyResources();
  for (const line of lines) {
    for (const key of Object.keys(total) as (keyof Resources)[]) total[key] += line.resources[key];
  }
  return total;
}
