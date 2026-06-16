'use strict';

/**
 * Jest mock for expo-constants.
 * Provides stub implementations so tests can use Constants without a native module.
 */

const Constants = {
  expoConfig: {
    version: '0.1.0',
    name: 'KCC',
    slug: 'carcommunity-mobile',
    ios: {
      buildNumber: '1',
    },
    android: {
      versionCode: 1,
    },
  },
  executionEnvironment: 'storeClient',
  sessionId: 'mock-session-id',
};

module.exports = {
  default: Constants,
  ...Constants,
};
