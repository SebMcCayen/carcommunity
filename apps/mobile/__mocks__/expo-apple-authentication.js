'use strict';
/* global jest */

/**
 * Jest mock for expo-apple-authentication.
 * Provides stub implementations so tests can verify Apple Sign-In
 * call patterns without a native module or real Apple credential.
 */

const AppleAuthenticationScope = {
  FULL_NAME: 0,
  EMAIL: 1,
};

const signInAsync = jest.fn(async () => ({
  user: 'mock-apple-user-id',
  fullName: { givenName: 'Test', familyName: 'User' },
  email: null,
  // identityToken is present by default in the happy-path mock.
  identityToken: 'mock-apple-identity-token',
  authorizationCode: 'mock-auth-code',
  realUserStatus: 0,
  state: null,
}));

const isAvailableAsync = jest.fn(async () => true);

module.exports = {
  AppleAuthenticationScope,
  signInAsync,
  isAvailableAsync,
};
