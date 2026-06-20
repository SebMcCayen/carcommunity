'use strict';
/* global jest */

/**
 * Jest mock for expo-task-manager.
 * Provides stub implementations so tests can verify task registration
 * and lifecycle calls without requiring a native device.
 */

const isTaskDefined = jest.fn().mockReturnValue(false);
const defineTask = jest.fn();
const isTaskRegisteredAsync = jest.fn().mockResolvedValue(false);
const unregisterTaskAsync = jest.fn().mockResolvedValue(undefined);

module.exports = {
  isTaskDefined,
  defineTask,
  isTaskRegisteredAsync,
  unregisterTaskAsync,
};
