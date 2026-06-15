'use strict';
/* global jest */

/**
 * Jest mock for expo-secure-store.
 * Uses a simple in-memory map so tests can verify storage calls
 * without a native module or real device Keychain/Keystore.
 */

const store = new Map();

const getItemAsync = jest.fn(async (key) => store.get(key) ?? null);
const setItemAsync = jest.fn(async (key, value) => {
  store.set(key, value);
});
const deleteItemAsync = jest.fn(async (key) => {
  store.delete(key);
});

/** Reset the in-memory store and all mock call records between tests. */
const __reset = () => {
  store.clear();
  getItemAsync.mockClear();
  setItemAsync.mockClear();
  deleteItemAsync.mockClear();
};

module.exports = {
  getItemAsync,
  setItemAsync,
  deleteItemAsync,
  __reset,
};
