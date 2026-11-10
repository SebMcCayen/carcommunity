export const VEHICLE_USAGE_WINDOW_MS = 90 * 24 * 60 * 60 * 1000;

export interface VehicleUse {
  vehicleId: string;
  startedAtMs: number;
}

export function rankVehicleUsage(uses: readonly VehicleUse[], nowMs: number): string[] {
  const cutoffMs = nowMs - VEHICLE_USAGE_WINDOW_MS;
  const totals = new Map<string, { count: number; lastUsedAtMs: number }>();
  for (const { vehicleId, startedAtMs } of uses) {
    if (
      !vehicleId ||
      !Number.isFinite(startedAtMs) ||
      startedAtMs < cutoffMs ||
      startedAtMs > nowMs
    ) {
      continue;
    }
    const total = totals.get(vehicleId);
    totals.set(vehicleId, {
      count: (total?.count ?? 0) + 1,
      lastUsedAtMs: Math.max(total?.lastUsedAtMs ?? 0, startedAtMs),
    });
  }
  return [...totals.entries()]
    .sort(
      ([idA, a], [idB, b]) =>
        b.count - a.count || b.lastUsedAtMs - a.lastUsedAtMs || idA.localeCompare(idB, 'en'),
    )
    .map(([id]) => id);
}
