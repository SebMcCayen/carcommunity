'use strict';

/**
 * Custom Jest test environment for the mobile workspace.
 *
 * Extends the React Native jest environment with a compatibility shim for
 * jest-runtime@30. jest-expo@56 pulls in jest-watch-typeahead@2.2.1 which
 * depends on jest@29, installing jest-environment-node@29 into the project.
 * jest-runtime@30 calls `moduleMocker.clearMocksOnScope()`, a method that was
 * added in jest-mock@30 and is absent in jest-mock@29.
 *
 * This shim overrides the method (whether native or missing) to guard every
 * property access with try/catch. Expo's lazy `fetch` global is a getter that
 * calls require() internally; jest-runtime@30 forbids require() between tests,
 * so accessing it outside test scope throws a ReferenceError. The try/catch
 * ensures we skip those properties safely.
 *
 * TODO: Remove this file once jest-expo ships with jest@30-compatible internals
 *       (i.e. jest-watch-typeahead@3+) and Expo's lazy globals are safe to read
 *       between tests.
 */

const ReactNativeEnv = require('@react-native/jest-preset/jest/react-native-env.js');

class MobileTestEnvironment extends ReactNativeEnv {
  constructor(options, context) {
    super(options, context);

    // Override clearMocksOnScope unconditionally so the try/catch guard is
    // always active. jest-mock@30's native implementation lacks this guard,
    // causing Expo's lazy `fetch` getter (which calls require() on first access)
    // to throw a ReferenceError when invoked between tests by jest-runtime@30.
    if (this.moduleMocker) {
      this.moduleMocker.clearMocksOnScope = (scope) => {
        for (const key of Object.keys(scope)) {
          let value;
          try {
            value = scope[key];
          } catch {
            // Skip properties whose getters throw outside test execution scope
            // (e.g. Expo's lazy `fetch` polyfill).
            continue;
          }
          if (
            value != null &&
            (typeof value === 'object' || typeof value === 'function') &&
            '_isMockFunction' in value &&
            typeof value.mockClear === 'function'
          ) {
            value.mockClear();
          }
        }
      };
    }
  }
}

module.exports = MobileTestEnvironment;
