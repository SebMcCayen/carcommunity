import { describe, expect, it } from 'vitest';
import { recentVehicleUsage, rankVehicleUsage, VEHICLE_USAGE_WINDOW_MS } from './vehicleUsage-core';

describe('rankVehicleUsage', () => {
  const now = Date.UTC(2026, 10, 10);

  it('orders distinct cars by latest use even when an older car was used more often', () => {
    expect(
      recentVehicleUsage(
        [
          { vehicleId: 'frequent', startedAtMs: now - 500 },
          { vehicleId: 'frequent', startedAtMs: now - 400 },
          { vehicleId: 'frequent', startedAtMs: now - 300 },
          { vehicleId: 'newest', startedAtMs: now },
          { vehicleId: 'second', startedAtMs: now - 100 },
          { vehicleId: 'first', startedAtMs: now - 100 },
          { vehicleId: 'newest', startedAtMs: now - 600 },
        ],
        now,
      ),
    ).toEqual(['newest', 'first', 'second', 'frequent']);
  });

  it('ranks by count, then last use, and keeps exact ties deterministic', () => {
    expect(
      rankVehicleUsage(
        [
          { vehicleId: 'more', startedAtMs: now - 300 },
          { vehicleId: 'more', startedAtMs: now - 400 },
          { vehicleId: 'recent', startedAtMs: now },
          { vehicleId: 'second', startedAtMs: now - 100 },
          { vehicleId: 'first', startedAtMs: now - 100 },
        ],
        now,
      ),
    ).toEqual(['more', 'recent', 'first', 'second']);
  });

  it.each([rankVehicleUsage, recentVehicleUsage])(
    'includes the 90-day boundary and excludes older, future, and invalid samples (%#)',
    (rank) => {
      expect(
        rank(
          [
            { vehicleId: 'old', startedAtMs: now - VEHICLE_USAGE_WINDOW_MS - 1 },
            { vehicleId: 'boundary', startedAtMs: now - VEHICLE_USAGE_WINDOW_MS },
            { vehicleId: 'future', startedAtMs: now + 1 },
            { vehicleId: 'invalid', startedAtMs: Number.NaN },
            { vehicleId: '', startedAtMs: now },
          ],
          now,
        ),
      ).toEqual(['boundary']);
    },
  );
});
