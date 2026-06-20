'use strict';
/* global jest */

/**
 * Jest mock for expo-location.
 * Provides stub implementations so tests can control location permission
 * and position callbacks without requiring a native device.
 */

const PermissionStatus = {
  GRANTED: 'granted',
  DENIED: 'denied',
  UNDETERMINED: 'undetermined',
};

const Accuracy = {
  Lowest: 1,
  Low: 2,
  Balanced: 3,
  High: 4,
  Highest: 5,
  BestForNavigation: 6,
};

const requestForegroundPermissionsAsync = jest.fn().mockResolvedValue({
  status: PermissionStatus.GRANTED,
  granted: true,
  expires: 'never',
  canAskAgain: true,
});

const requestBackgroundPermissionsAsync = jest.fn().mockResolvedValue({
  status: PermissionStatus.GRANTED,
  granted: true,
  expires: 'never',
  canAskAgain: true,
});

const getBackgroundPermissionsAsync = jest.fn().mockResolvedValue({
  status: PermissionStatus.DENIED,
  granted: false,
  expires: 'never',
  canAskAgain: true,
});

const watchPositionAsync = jest.fn().mockResolvedValue({
  remove: jest.fn(),
});

const getCurrentPositionAsync = jest.fn().mockResolvedValue({
  coords: {
    latitude: 57.5086,
    longitude: 12.0742,
    accuracy: 10,
    heading: null,
    speed: null,
    altitude: null,
    altitudeAccuracy: null,
  },
  timestamp: Date.now(),
});

const startLocationUpdatesAsync = jest.fn().mockResolvedValue(undefined);
const stopLocationUpdatesAsync = jest.fn().mockResolvedValue(undefined);
const hasStartedLocationUpdatesAsync = jest.fn().mockResolvedValue(false);

module.exports = {
  PermissionStatus,
  Accuracy,
  requestForegroundPermissionsAsync,
  requestBackgroundPermissionsAsync,
  getBackgroundPermissionsAsync,
  watchPositionAsync,
  getCurrentPositionAsync,
  startLocationUpdatesAsync,
  stopLocationUpdatesAsync,
  hasStartedLocationUpdatesAsync,
};
