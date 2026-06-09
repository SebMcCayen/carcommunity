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
 * This shim adds the missing method so tests can run without upgrading or
 * ejecting jest-expo.
 *
 * TODO: Remove this file once jest-expo ships with jest@30-compatible internals
 *       (i.e. jest-watch-typeahead@3+) so the shim is no longer needed.
 */

const ReactNativeEnv = require('@react-native/jest-preset/jest/react-native-env.js');

class MobileTestEnvironment extends ReactNativeEnv {
  constructor(options, context) {
    super(options, context);

    // jest-mock@30 added clearMocksOnScope(scope) which jest-runtime@30 calls in
    // resetModules() (constructor) and teardown(). jest-mock@29 doesn't have it.
    // Some globals (e.g. expo's lazy `fetch` getter) throw when read outside test
    // code scope, so we guard each property access with try/catch.
    if (this.moduleMocker && typeof this.moduleMocker.clearMocksOnScope !== 'function') {
      this.moduleMocker.clearMocksOnScope = (scope) => {
        for (const key of Object.keys(scope)) {
          let value;
          try {
            value = scope[key];
          } catch {
            // Skip properties whose getters throw outside test execution scope.
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
