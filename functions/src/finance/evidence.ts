import { z } from 'zod';

export const FINANCE_SNAPSHOTS_COLLECTION = 'financeSnapshots';
export const DAY_MS = 86_400_000;
export const EVIDENCE_MAX_AGE_MS = 3 * DAY_MS;

const amount = z.number().finite().nonnegative().max(1e15);
const timestamp = z.number().int().positive();
export const usageSchema = z
  .object({
    capturedAtMs: timestamp,
    startMs: timestamp,
    endMs: timestamp,
    functions: z
      .array(
        z.object({
          id: z.string().max(100),
          requests: amount.nullable(),
          sentBytes: amount.nullable(),
          billableSeconds: amount.nullable(),
        }),
      )
      .max(64),
  })
  .refine(
    (v) =>
      v.endMs > v.startMs &&
      v.endMs <= v.capturedAtMs &&
      v.endMs - v.startMs <= 31 * DAY_MS &&
      new Set(v.functions.map((f) => f.id)).size === v.functions.length,
  );

export const billingSchema = z
  .object({
    capturedAtMs: timestamp,
    month: z.string().regex(/^\d{4}-\d{2}$/),
    throughDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
    latestExportAt: z.string().datetime(),
    currency: z.literal('SEK'),
    services: z
      .array(
        z.object({
          id: z.string().min(1).max(100),
          label: z.string().min(1).max(160),
          // Credits and adjustments can make a service's net cost negative.
          netCost: z.number().finite().min(-1e12).max(1e12),
        }),
      )
      .min(1)
      .max(200),
  })
  .refine((v) => {
    const start = Date.parse(`${v.month}-01T00:00:00Z`);
    const end = Date.parse(`${v.throughDate}T00:00:00Z`);
    return (
      Number.isFinite(start) &&
      Number.isFinite(end) &&
      new Date(end).toISOString().slice(0, 10) === v.throughDate &&
      v.throughDate.startsWith(v.month) &&
      end >= start &&
      end + DAY_MS <= v.capturedAtMs &&
      Date.parse(v.latestExportAt) <= v.capturedAtMs + 300_000 &&
      new Set(v.services.map((s) => s.id)).size === v.services.length
    );
  });

export type UsageEvidence = z.infer<typeof usageSchema>;
export type BillingEvidence = z.infer<typeof billingSchema>;
export type EvidenceState = 'ready' | 'stale' | 'not-configured' | 'unavailable';
export interface Evidence<T> {
  state: EvidenceState;
  data: T | null;
  lastAttemptFailed: boolean;
}

/** Invalid, absent, old and failed data are never represented as zero spend. */
export function readEvidence<T>(schema: z.ZodType<T>, raw: unknown, nowMs: number): Evidence<T> {
  const envelope = z
    .object({
      state: z.enum(['ready', 'not-configured', 'unavailable']),
      lastAttemptFailed: z.boolean().optional(),
      data: z.unknown().optional(),
    })
    .safeParse(raw);
  if (!envelope.success) return { state: 'unavailable', data: null, lastAttemptFailed: false };
  const { state, lastAttemptFailed = false } = envelope.data;
  if (state !== 'ready') return { state, data: null, lastAttemptFailed };
  const parsed = schema.safeParse(envelope.data.data);
  if (!parsed.success) return { state: 'unavailable', data: null, lastAttemptFailed };
  const capturedAtMs = (parsed.data as { capturedAtMs: number }).capturedAtMs;
  if (capturedAtMs > nowMs + 300_000)
    return { state: 'unavailable', data: null, lastAttemptFailed };
  return {
    state: nowMs - capturedAtMs > EVIDENCE_MAX_AGE_MS ? 'stale' : 'ready',
    data: parsed.data,
    lastAttemptFailed,
  };
}
