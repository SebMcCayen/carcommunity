/**
 * Tests for eventNavigation utility.
 *
 * Covers:
 *   - Apple Maps URL is correctly encoded for iOS (coordinates)
 *   - Apple Maps URL is correctly encoded for iOS (address fallback)
 *   - Android geo URI is correctly encoded (coordinates)
 *   - Android geo URI is correctly encoded (address fallback)
 *   - Throws when no navigation data is available
 *   - Handles canOpenURL returning false gracefully
 *   - Exact coordinates are not logged
 *
 * Platform and Linking are mocked via spies after module import so no
 * native TurboModule initialisation occurs.
 */

import { Linking, Platform } from 'react-native';
import { openExternalNavigation } from '../eventNavigation';

// ---------------------------------------------------------------------------
// Setup — spy on Linking so no real URLs are opened
// ---------------------------------------------------------------------------

const mockOpenURL = jest.fn<Promise<void>, [string]>().mockResolvedValue(undefined);
const mockCanOpenURL = jest.fn<Promise<boolean>, [string]>().mockResolvedValue(true);

beforeEach(() => {
  jest.clearAllMocks();
  mockOpenURL.mockResolvedValue(undefined);
  mockCanOpenURL.mockResolvedValue(true);
  jest.spyOn(Linking, 'openURL').mockImplementation(mockOpenURL);
  jest.spyOn(Linking, 'canOpenURL').mockImplementation(mockCanOpenURL);
  // Default to iOS; overridden per describe block
  Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });
});

afterEach(() => {
  jest.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// iOS — Apple Maps
// ---------------------------------------------------------------------------

describe('openExternalNavigation — iOS (Apple Maps)', () => {
  beforeEach(() => {
    Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });
  });

  it('opens Apple Maps with coordinates on iOS', async () => {
    await openExternalNavigation({
      latitude: 57.4875,
      longitude: 12.0762,
      address: null,
      locationName: 'Kungsbacka Torg',
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    // Must use Apple Maps domain
    expect(url).toContain('maps.apple.com');
    // Must encode coordinates
    expect(url).toContain('57.4875');
    expect(url).toContain('12.0762');
    // Must encode label
    expect(url).toContain(encodeURIComponent('Kungsbacka Torg'));
  });

  it('falls back to encoded address when coordinates are unavailable on iOS', async () => {
    await openExternalNavigation({
      latitude: null,
      longitude: null,
      address: 'Stortorget 1, Kungsbacka',
      locationName: null,
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    expect(url).toContain('maps.apple.com');
    expect(url).toContain(encodeURIComponent('Stortorget 1, Kungsbacka'));
    // Must NOT expose raw spaces or special characters
    expect(url).not.toContain(' ');
  });

  it('uses locationName as fallback query when address is also null on iOS', async () => {
    await openExternalNavigation({
      latitude: null,
      longitude: null,
      address: null,
      locationName: 'Kungsbacka Torg',
    });

    const url: string = mockOpenURL.mock.calls[0]![0];
    expect(url).toContain(encodeURIComponent('Kungsbacka Torg'));
  });

  it('throws when no navigation data is available', async () => {
    await expect(
      openExternalNavigation({ latitude: null, longitude: null, address: null, locationName: null }),
    ).rejects.toThrow();

    expect(mockOpenURL).not.toHaveBeenCalled();
  });

  it('throws when canOpenURL returns false', async () => {
    mockCanOpenURL.mockResolvedValue(false);

    await expect(
      openExternalNavigation({
        latitude: 57.4875,
        longitude: 12.0762,
        address: null,
        locationName: 'Kungsbacka Torg',
      }),
    ).rejects.toThrow();

    expect(mockOpenURL).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Android — geo URI
// ---------------------------------------------------------------------------

describe('openExternalNavigation — Android (geo URI)', () => {
  beforeEach(() => {
    Object.defineProperty(Platform, 'OS', { value: 'android', configurable: true });
  });

  it('opens a geo URI with coordinates on Android', async () => {
    await openExternalNavigation({
      latitude: 57.4875,
      longitude: 12.0762,
      address: null,
      locationName: 'Kungsbacka Torg',
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    // Must use geo URI scheme
    expect(url).toMatch(/^geo:/);
    // Must include coordinates
    expect(url).toContain('57.4875');
    expect(url).toContain('12.0762');
    // Must encode label
    expect(url).toContain(encodeURIComponent('Kungsbacka Torg'));
  });

  it('falls back to encoded address in geo URI on Android', async () => {
    await openExternalNavigation({
      latitude: null,
      longitude: null,
      address: 'Stortorget 1, Kungsbacka',
      locationName: null,
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    expect(url).toMatch(/^geo:/);
    expect(url).toContain(encodeURIComponent('Stortorget 1, Kungsbacka'));
    // Must NOT expose raw spaces in the query
    expect(url).not.toMatch(/q=[^&]*\s/);
  });

  it('throws when no navigation data is available', async () => {
    await expect(
      openExternalNavigation({ latitude: null, longitude: null, address: null, locationName: null }),
    ).rejects.toThrow();

    expect(mockOpenURL).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Coordinate privacy
// ---------------------------------------------------------------------------

describe('openExternalNavigation — coordinate privacy', () => {
  it('does not log exact coordinates', async () => {
    Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });

    const consoleSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    await openExternalNavigation({
      latitude: 57.4875,
      longitude: 12.0762,
      address: null,
      locationName: 'Test',
    });

    // No console call should contain raw coordinate values
    const allCalls = [
      ...consoleSpy.mock.calls,
      ...errorSpy.mock.calls,
      ...warnSpy.mock.calls,
    ].flat();

    const coordsLogged = allCalls.some(
      (call) => typeof call === 'string' && (call.includes('57.4875') || call.includes('12.0762')),
    );
    expect(coordsLogged).toBe(false);

    consoleSpy.mockRestore();
    errorSpy.mockRestore();
    warnSpy.mockRestore();
  });
});


// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

beforeEach(() => {
  jest.clearAllMocks();
  mockOpenURL.mockResolvedValue(undefined);
  mockCanOpenURL.mockResolvedValue(true);
});

// ---------------------------------------------------------------------------
// iOS — Apple Maps
// ---------------------------------------------------------------------------

describe('openExternalNavigation — iOS (Apple Maps)', () => {
  beforeEach(() => {
    Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });
  });

  it('opens Apple Maps with coordinates on iOS', async () => {
    await openExternalNavigation({
      latitude: 57.4875,
      longitude: 12.0762,
      address: null,
      locationName: 'Kungsbacka Torg',
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    // Must use Apple Maps domain
    expect(url).toContain('maps.apple.com');
    // Must encode coordinates
    expect(url).toContain('57.4875');
    expect(url).toContain('12.0762');
    // Must encode label
    expect(url).toContain(encodeURIComponent('Kungsbacka Torg'));
  });

  it('falls back to encoded address when coordinates are unavailable on iOS', async () => {
    await openExternalNavigation({
      latitude: null,
      longitude: null,
      address: 'Stortorget 1, Kungsbacka',
      locationName: null,
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    expect(url).toContain('maps.apple.com');
    expect(url).toContain(encodeURIComponent('Stortorget 1, Kungsbacka'));
    // Must NOT expose raw spaces or special characters
    expect(url).not.toContain(' ');
  });

  it('uses locationName as fallback query when address is also null on iOS', async () => {
    await openExternalNavigation({
      latitude: null,
      longitude: null,
      address: null,
      locationName: 'Kungsbacka Torg',
    });

    const url: string = mockOpenURL.mock.calls[0]![0];
    expect(url).toContain(encodeURIComponent('Kungsbacka Torg'));
  });

  it('throws when no navigation data is available', async () => {
    await expect(
      openExternalNavigation({ latitude: null, longitude: null, address: null, locationName: null }),
    ).rejects.toThrow();

    expect(mockOpenURL).not.toHaveBeenCalled();
  });

  it('throws when canOpenURL returns false', async () => {
    mockCanOpenURL.mockResolvedValue(false);

    await expect(
      openExternalNavigation({
        latitude: 57.4875,
        longitude: 12.0762,
        address: null,
        locationName: 'Kungsbacka Torg',
      }),
    ).rejects.toThrow();

    expect(mockOpenURL).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Android — geo URI
// ---------------------------------------------------------------------------

describe('openExternalNavigation — Android (geo URI)', () => {
  beforeEach(() => {
    Object.defineProperty(Platform, 'OS', { value: 'android', configurable: true });
  });

  it('opens a geo URI with coordinates on Android', async () => {
    await openExternalNavigation({
      latitude: 57.4875,
      longitude: 12.0762,
      address: null,
      locationName: 'Kungsbacka Torg',
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    // Must use geo URI scheme
    expect(url).toMatch(/^geo:/);
    // Must include coordinates
    expect(url).toContain('57.4875');
    expect(url).toContain('12.0762');
    // Must encode label
    expect(url).toContain(encodeURIComponent('Kungsbacka Torg'));
  });

  it('falls back to encoded address in geo URI on Android', async () => {
    await openExternalNavigation({
      latitude: null,
      longitude: null,
      address: 'Stortorget 1, Kungsbacka',
      locationName: null,
    });

    expect(mockOpenURL).toHaveBeenCalledTimes(1);
    const url: string = mockOpenURL.mock.calls[0]![0];
    expect(url).toMatch(/^geo:/);
    expect(url).toContain(encodeURIComponent('Stortorget 1, Kungsbacka'));
    // Must NOT expose raw spaces in the query
    expect(url).not.toMatch(/q=[^&]*\s/);
  });

  it('throws when no navigation data is available', async () => {
    await expect(
      openExternalNavigation({ latitude: null, longitude: null, address: null, locationName: null }),
    ).rejects.toThrow();

    expect(mockOpenURL).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Coordinate privacy
// ---------------------------------------------------------------------------

describe('openExternalNavigation — coordinate privacy', () => {
  it('does not log exact coordinates', async () => {
    const consoleSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    Object.defineProperty(Platform, 'OS', { value: 'ios', configurable: true });

    await openExternalNavigation({
      latitude: 57.4875,
      longitude: 12.0762,
      address: null,
      locationName: 'Test',
    });

    // No console call should contain raw coordinate values
    const allCalls = [
      ...consoleSpy.mock.calls,
      ...errorSpy.mock.calls,
      ...warnSpy.mock.calls,
    ].flat();

    const coordsLogged = allCalls.some(
      (call) => typeof call === 'string' && (call.includes('57.4875') || call.includes('12.0762')),
    );
    expect(coordsLogged).toBe(false);

    consoleSpy.mockRestore();
    errorSpy.mockRestore();
    warnSpy.mockRestore();
  });
});
