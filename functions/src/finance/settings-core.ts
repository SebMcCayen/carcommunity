import { z } from 'zod';
import { WORKLOADS } from './workloads';

export const SETTINGS_COLLECTION = 'financeSettings';
export const workloadOverridesSchema = z
  .array(
    z
      .object({
        id: z.string().refine((id) => WORKLOADS.some((w) => w.id === id)),
        eventsPerMemberPerDay: z.number().finite().min(0).max(1000),
      })
      .strict(),
  )
  .max(WORKLOADS.length)
  .refine((rows) => new Set(rows.map((r) => r.id)).size === rows.length);
export type WorkloadOverride = z.infer<typeof workloadOverridesSchema>[number];
