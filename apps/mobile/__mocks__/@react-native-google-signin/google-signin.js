'use strict';
/* global jest */

/**
 * Jest mock for @react-native-google-signin/google-signin.
 * Provides stub implementations so tests can verify Google Sign-In
 * call patterns without a native module or real Google credential.
 */

const configure = jest.fn();

const signIn = jest.fn(async () => ({
  user: {
    id: 'mock-google-user-id',
    name: 'Test User',
    email: 'test@example.com',
    photo: null,
    familyName: null,
    givenName: null,
  },
  scopes: [],
  idToken: 'mock-google-id-token',
  serverAuthCode: null,
}));

const signOut = jest.fn(async () => undefined);

const isSignedIn = jest.fn(async () => false);

const GoogleSignin = {
  configure,
  signIn,
  signOut,
  isSignedIn,
};

module.exports = {
  GoogleSignin,
  statusCodes: {
    SIGN_IN_CANCELLED: 'SIGN_IN_CANCELLED',
    IN_PROGRESS: 'IN_PROGRESS',
    PLAY_SERVICES_NOT_AVAILABLE: 'PLAY_SERVICES_NOT_AVAILABLE',
    SIGN_IN_REQUIRED: 'SIGN_IN_REQUIRED',
  },
  isErrorWithCode: jest.fn((error) => {
    return error != null && typeof error === 'object' && 'code' in error;
  }),
};
