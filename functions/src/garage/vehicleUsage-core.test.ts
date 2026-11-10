import { describe, expect, it } from 'vitest';
import { rankVehicleUsage, VEHICLE_USAGE_WINDOW_MS } from './vehicleUsage-core';

describe('rankVehicleUsage', () => {
  const now = Date.UTC(2026, 10, 10);

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

  it('includes the 90-day boundary and excludes older, future, and invalid samples', () => {
    expect(
      rankVehicleUsage(
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
  });
});
