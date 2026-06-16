/**
 * Tests for useNativeLogin hook.
 *
 * Verifies that the correct native provider is invoked per platform,
 * that identity tokens are returned correctly, and that they are not
 * stored or logged.
 *
 * expo-apple-authentication and @react-native-google-signin/google-signin
 * are mocked via __mocks__ / moduleNameMapper so no real native modules
 * are loaded.
 */

import { Platform } from 'react-native';
import * as AppleAuthentication from 'expo-apple-authentication';
import { GoogleSignin, isErrorWithCode, statusCodes } from '@react-native-google-signin/google-signin';

import { useNativeLogin, NativeLoginCancelledError } from '../useNativeLogin';

const mockAppleSignIn = AppleAuthentication.signInAsync as jest.MockedFunction<
  typeof AppleAuthentication.signInAsync
>;
const mockGoogleSignIn = GoogleSignin.signIn as jest.MockedFunction<typeof GoogleSignin.signIn>;
const mockGoogleConfigure = GoogleSignin.configure as jest.MockedFunction<
  typeof GoogleSignin.configure
>;

beforeEach(() => {
  jest.clearAllMocks();

  // Default: Apple returns a valid credential with an identity token.
  mockAppleSignIn.mockResolvedValue({
    user: 'apple-user-id',
    fullName: { givenName: 'Test', familyName: 'User', nickname: null, middleName: null, namePrefix: null, nameSuffix: null },
    email: null,
    identityToken: 'apple-id-token-from-sdk',
    authorizationCode: 'auth-code',
    realUserStatus: 0,
    state: null,
  });

  // Default: Google returns a user info object with an idToken.
  mockGoogleSignIn.mockResolvedValue({
    user: {
      id: 'google-user-id',
      name: 'Test User',
      email: 'test@example.com',
      photo: null,
      familyName: null,
      givenName: null,
    },
    idToken: 'google-id-token-from-sdk',
    serverAuthCode: null,
    scopes: [],
  } as unknown as Awaited<ReturnType<typeof GoogleSignin.signIn>>);
});

describe('useNativeLogin — iOS (Apple)', () => {
  beforeEach(() => {
    Platform.OS = 'ios';
  });

  it('calls AppleAuthentication.signInAsync on iOS', async () => {
    const { signIn } = useNativeLogin();
    await signIn();
    expect(mockAppleSignIn).toHaveBeenCalledTimes(1);
  });

  it('returns identityToken and provider apple on iOS', async () => {
    const { signIn } = useNativeLogin();
    const result = await signIn();
    expect(result).not.toBeNull();
    expect(result!.provider).toBe('apple');
    expect(result!.identityToken).toBe('apple-id-token-from-sdk');
  });

  it('requests only FULL_NAME scope (email not requested)', async () => {
    const { signIn } = useNativeLogin();
    await signIn();
    expect(mockAppleSignIn).toHaveBeenCalledWith(
      expect.objectContaining({
        requestedScopes: [AppleAuthentication.AppleAuthenticationScope.FULL_NAME],
      }),
    );
    // EMAIL scope must NOT be in requestedScopes
    const call = mockAppleSignIn.mock.calls[0];
    if (call) {
      expect(call[0]?.requestedScopes).not.toContain(AppleAuthentication.AppleAuthenticationScope.EMAIL);
    }
  });

  it('throws when Apple Sign-In returns no identity token', async () => {
    mockAppleSignIn.mockResolvedValue({
      user: 'apple-user-id',
      fullName: null,
      email: null,
      identityToken: null,
      authorizationCode: null,
      realUserStatus: 0,
      state: null,
    });

    const { signIn } = useNativeLogin();
    await expect(signIn()).rejects.toThrow('identity token');
  });

  it('does not call Google Sign-In on iOS', async () => {
    const { signIn } = useNativeLogin();
    await signIn();
    expect(mockGoogleSignIn).not.toHaveBeenCalled();
  });

  it('does not log the identity token', async () => {
    const consoleSpy = jest.spyOn(console, 'log');
    const consoleWarnSpy = jest.spyOn(console, 'warn');
    const consoleErrorSpy = jest.spyOn(console, 'error');

    const { signIn } = useNativeLogin();
    await signIn();

    const allLogged = [
      ...consoleSpy.mock.calls.flat(),
      ...consoleWarnSpy.mock.calls.flat(),
      ...consoleErrorSpy.mock.calls.flat(),
    ].map((a) => (typeof a === 'string' ? a : JSON.stringify(a)));

    for (const entry of allLogged) {
      expect(entry).not.toContain('apple-id-token-from-sdk');
    }

    consoleSpy.mockRestore();
    consoleWarnSpy.mockRestore();
    consoleErrorSpy.mockRestore();
  });
});

describe('useNativeLogin — Android (Google)', () => {
  beforeEach(() => {
    Platform.OS = 'android';
  });

  it('calls GoogleSignin.configure before sign-in on Android', async () => {
    const { signIn } = useNativeLogin();
    await signIn();
    expect(mockGoogleConfigure).toHaveBeenCalledTimes(1);
  });

  it('calls GoogleSignin.signIn on Android', async () => {
    const { signIn } = useNativeLogin();
    await signIn();
    expect(mockGoogleSignIn).toHaveBeenCalledTimes(1);
  });

  it('returns identityToken and provider google on Android', async () => {
    const { signIn } = useNativeLogin();
    const result = await signIn();
    expect(result).not.toBeNull();
    expect(result!.provider).toBe('google');
    expect(result!.identityToken).toBe('google-id-token-from-sdk');
  });

  it('does not call Apple Sign-In on Android', async () => {
    const { signIn } = useNativeLogin();
    await signIn();
    expect(mockAppleSignIn).not.toHaveBeenCalled();
  });

  it('throws NativeLoginCancelledError when user cancels Google Sign-In', async () => {
    const cancelError = Object.assign(new Error('Cancelled'), {
      code: statusCodes.SIGN_IN_CANCELLED,
    });
    mockGoogleSignIn.mockRejectedValue(cancelError);
    // isErrorWithCode is a type guard in the mock; cast to unknown first to override.
    (isErrorWithCode as unknown as jest.Mock).mockReturnValue(true);

    const { signIn } = useNativeLogin();
    await expect(signIn()).rejects.toBeInstanceOf(NativeLoginCancelledError);
  });

  it('throws NativeLoginCancelledError when Google Sign-In is already in progress', async () => {
    const inProgressError = Object.assign(new Error('In progress'), {
      code: statusCodes.IN_PROGRESS,
    });
    mockGoogleSignIn.mockRejectedValue(inProgressError);
    (isErrorWithCode as unknown as jest.Mock).mockReturnValue(true);

    const { signIn } = useNativeLogin();
    await expect(signIn()).rejects.toBeInstanceOf(NativeLoginCancelledError);
  });

  it('throws when Google Sign-In returns no idToken', async () => {
    mockGoogleSignIn.mockResolvedValue({
      user: {
        id: 'google-user-id',
        name: null,
        email: 'test@example.com',
        photo: null,
        familyName: null,
        givenName: null,
      },
      idToken: null,
      serverAuthCode: null,
      scopes: [],
    } as unknown as Awaited<ReturnType<typeof GoogleSignin.signIn>>);

    const { signIn } = useNativeLogin();
    await expect(signIn()).rejects.toThrow('ID token');
  });

  it('does not log the identity token', async () => {
    const consoleSpy = jest.spyOn(console, 'log');
    const consoleWarnSpy = jest.spyOn(console, 'warn');
    const consoleErrorSpy = jest.spyOn(console, 'error');

    const { signIn } = useNativeLogin();
    await signIn();

    const allLogged = [
      ...consoleSpy.mock.calls.flat(),
      ...consoleWarnSpy.mock.calls.flat(),
      ...consoleErrorSpy.mock.calls.flat(),
    ].map((a) => (typeof a === 'string' ? a : JSON.stringify(a)));

    for (const entry of allLogged) {
      expect(entry).not.toContain('google-id-token-from-sdk');
    }

    consoleSpy.mockRestore();
    consoleWarnSpy.mockRestore();
    consoleErrorSpy.mockRestore();
  });
});

describe('useNativeLogin — unsupported platform', () => {
  it('returns null on unsupported platform (web)', async () => {
    Platform.OS = 'web' as typeof Platform.OS;
    const { signIn } = useNativeLogin();
    const result = await signIn();
    expect(result).toBeNull();
  });

  it('does not call any provider SDK on unsupported platform', async () => {
    Platform.OS = 'web' as typeof Platform.OS;
    const { signIn } = useNativeLogin();
    await signIn();
    expect(mockAppleSignIn).not.toHaveBeenCalled();
    expect(mockGoogleSignIn).not.toHaveBeenCalled();
  });
});

describe('useNativeLogin — identity token is not persisted', () => {
  it('returns identityToken without storing it (Apple)', async () => {
    Platform.OS = 'ios';
    // Importing SecureStore to verify it is not called during signIn.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const SecureStore = require('expo-secure-store') as { setItemAsync: jest.Mock };
    SecureStore.setItemAsync.mockClear();

    const { signIn } = useNativeLogin();
    const result = await signIn();

    // The identity token must be returned for immediate use — not stored.
    expect(result!.identityToken).toBe('apple-id-token-from-sdk');
    expect(SecureStore.setItemAsync).not.toHaveBeenCalled();
  });

  it('returns identityToken without storing it (Google)', async () => {
    Platform.OS = 'android';
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const SecureStore = require('expo-secure-store') as { setItemAsync: jest.Mock };
    SecureStore.setItemAsync.mockClear();

    const { signIn } = useNativeLogin();
    const result = await signIn();

    expect(result!.identityToken).toBe('google-id-token-from-sdk');
    expect(SecureStore.setItemAsync).not.toHaveBeenCalled();
  });
});
