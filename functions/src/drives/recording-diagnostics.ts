import { z } from 'zod';
import {
  haversineDistanceMetres,
  MAX_PLAUSIBLE_SPEED_MPS,
  totalDistanceMetres,
  type TimedPoint,
} from './drive-calculations';

const count = z.number().int().min(0).max(10_000_000);

/** Bounded aggregates and recording-source context. Raw fixes and identifiers are forbidden. */
export const recordingDiagnosticsSchema = z
  .object({
    version: z.literal(1),
    algorithmVersion: z.literal('recording-quality-v1'),
    receivedPoints: count,
    acceptedPoints: count,
    rejectedInvalid: count,
    rejectedAccuracy: count,
    rejectedStale: count,
    rejectedTime: count,
    rejectedSpeed: count,
    droppedCapacity: count,
    droppedStationary: count,
    droppedThrottled: count,
    maxGapMs: z.number().int().min(0).max(604_800_000),
    gapCount: count,
    resumedCount: count,
    // Captured at recording start and retained through journal restore/retry.
    // Optional context is advisory even when the counters themselves are valid.
    platform: z.enum(['android', 'ios']).optional().catch(undefined),
    appVersion: z.string().min(1).max(50).optional().catch(undefined),
  })
  .strict()
  .transform(({ platform, appVersion, ...counters }) => ({
    ...counters,
    // Firestore rejects undefined values: omit malformed optional context entirely.
    ...(platform !== undefined ? { platform } : {}),
    ...(appVersion !== undefined ? { appVersion } : {}),
  }));

export type RecordingDiagnostics = z.infer<typeof recordingDiagnosticsSchema>;

export const calculationDiagnosticsSchema = z
  .object({
    algorithmVersion: z.literal('haversine-segment-v1'),
    totalSegments: z.number().int().min(0).max(20_000),
    rejectedTimeSegments: z.number().int().min(0).max(20_000),
    rejectedSpeedSegments: z.number().int().min(0).max(20_000),
    maxAcceptedGapMs: z.number().int().nonnegative(),
    gapCount: z.number().int().min(0).max(20_000),
    endpointDistanceMeters: z.number().min(0).max(21_000_000).nullable(),
    distanceBelowEndpoint: z.boolean(),
  })
  .strict();

export interface FeedbackDriveDiagnostics {
  recordingDiagnostics?: RecordingDiagnostics;
  calculationDiagnostics?: CalculationDiagnostics;
}

/** Read stored data through the same closed allowlists; never copy a ride wholesale. */
export function feedbackDriveDiagnostics(data: Record<string, unknown>): FeedbackDriveDiagnostics {
  const recording = recordingDiagnosticsSchema.safeParse(data.recordingDiagnostics);
  const calculation = calculationDiagnosticsSchema.safeParse(data.calculationDiagnostics);
  return {
    ...(recording.success ? { recordingDiagnostics: recording.data } : {}),
    ...(calculation.success ? { calculationDiagnostics: calculation.data } : {}),
  };
}

export interface CalculationDiagnostics {
  algorithmVersion: 'haversine-segment-v1';
  totalSegments: number;
  rejectedTimeSegments: number;
  rejectedSpeedSegments: number;
  /** Longest interval whose segment actually contributes to the stored distance. */
  maxAcceptedGapMs: number;
  /** Accepted segments spanning more than 15 seconds. No reconstruction is implied. */
  gapCount: number;
  endpointDistanceMeters: number | null;
  /** A diagnostic signal, never a replacement for the measured distance. */
  distanceBelowEndpoint: boolean;
}

/** Observes the existing calculation without changing any stored statistic. */
export function computeCalculationDiagnostics(
  points: readonly TimedPoint[] | undefined,
): CalculationDiagnostics {
  const result: CalculationDiagnostics = {
    algorithmVersion: 'haversine-segment-v1',
    totalSegments: Math.max(0, (points?.length ?? 0) - 1),
    rejectedTimeSegments: 0,
    rejectedSpeedSegments: 0,
    maxAcceptedGapMs: 0,
    gapCount: 0,
    endpointDistanceMeters: null,
    distanceBelowEndpoint: false,
  };
  if (!points || points.length < 2) return result;
  for (let i = 1; i < points.length; i++) {
    const previous = points[i - 1]!;
    const current = points[i]!;
    const dt = current.timestampMs - previous.timestampMs;
    if (dt <= 0) {
      result.rejectedTimeSegments++;
      continue;
    }
    const metres = haversineDistanceMetres(
      previous.latitude,
      previous.longitude,
      current.latitude,
      current.longitude,
    );
    if (metres / (dt / 1_000) > MAX_PLAUSIBLE_SPEED_MPS) {
      result.rejectedSpeedSegments++;
      continue;
    }
    result.maxAcceptedGapMs = Math.max(result.maxAcceptedGapMs, dt);
    if (dt > 15_000) result.gapCount++;
  }
  const first = points[0]!;
  const last = points[points.length - 1]!;
  result.endpointDistanceMeters = haversineDistanceMetres(
    first.latitude,
    first.longitude,
    last.latitude,
    last.longitude,
  );
  // Allow 100 m of endpoint uncertainty. Endpoints may themselves be poor;
  // this is evidence for investigation, not permission to rewrite a drive.
  result.distanceBelowEndpoint = totalDistanceMetres(points) + 100 < result.endpointDistanceMeters;
  return result;
}
