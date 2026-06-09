/**
 * Tests for the live location marker → map view model mapping helper.
 */

import type { PublicLiveLocationMarker } from '@carcommunity/shared/live-location';
import {
  liveLocationMarkerToViewModel,
  liveLocationMarkersToViewModels,
} from '../markerMapping';

const makeMarker = (
  overrides: Partial<PublicLiveLocationMarker> = {},
): PublicLiveLocationMarker => ({
  userId: 'user-1',
  sessionId: 'session-abc',
  coordinate: {
    latitude: 57.5086,
    longitude: 12.0742,
    recordedAt: '2024-01-01T12:00:00.000Z',
  },
  status: 'active',
  ...overrides,
});

describe('liveLocationMarkerToViewModel', () => {
  it('uses the sessionId as the marker id', () => {
    const marker = makeMarker({ sessionId: 'session-xyz' });
    const vm = liveLocationMarkerToViewModel(marker);
    expect(vm.id).toBe('session-xyz');
  });

  it('maps latitude and longitude correctly', () => {
    const marker = makeMarker({
      coordinate: {
        latitude: 57.515,
        longitude: 12.085,
        recordedAt: '2024-01-01T12:00:00.000Z',
      },
    });
    const vm = liveLocationMarkerToViewModel(marker);
    expect(vm.coordinate.latitude).toBe(57.515);
    expect(vm.coordinate.longitude).toBe(12.085);
  });

  it('always sets type to "member"', () => {
    const vm = liveLocationMarkerToViewModel(makeMarker());
    expect(vm.type).toBe('member');
  });

  it('does not include heading, speed, or accuracy in the view model', () => {
    const marker = makeMarker({
      coordinate: {
        latitude: 57.5086,
        longitude: 12.0742,
        accuracyMeters: 5,
        headingDegrees: 180,
        speedMetersPerSecond: 10,
        recordedAt: '2024-01-01T12:00:00.000Z',
      },
    });
    const vm = liveLocationMarkerToViewModel(marker);
    expect(vm.coordinate).not.toHaveProperty('accuracyMeters');
    expect(vm.coordinate).not.toHaveProperty('headingDegrees');
    expect(vm.coordinate).not.toHaveProperty('speedMetersPerSecond');
  });
});

describe('liveLocationMarkersToViewModels', () => {
  it('returns an empty array for an empty input', () => {
    expect(liveLocationMarkersToViewModels([])).toEqual([]);
  });

  it('maps all markers in the array', () => {
    const markers = [
      makeMarker({ sessionId: 'session-1' }),
      makeMarker({ sessionId: 'session-2' }),
    ];
    const vms = liveLocationMarkersToViewModels(markers);
    expect(vms).toHaveLength(2);
    expect(vms[0]?.id).toBe('session-1');
    expect(vms[1]?.id).toBe('session-2');
  });
});
