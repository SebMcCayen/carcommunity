/**
 * Tests for the Mapbox access token config helper.
 *
 * Uses jest.resetModules to control the value of EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN
 * at module load time, which is where the module-level MAPBOX_TOKEN constant
 * is evaluated.
 */

describe('mapbox config — getMapboxAccessToken', () => {
  const ORIGINAL_ENV = process.env;

  beforeEach(() => {
    jest.resetModules();
    process.env = { ...ORIGINAL_ENV };
  });

  afterAll(() => {
    process.env = ORIGINAL_ENV;
  });

  it('returns empty string when EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN is not set', () => {
    delete process.env['EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN'];

    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { getMapboxAccessToken } = require('../../config/mapbox') as typeof import('../../config/mapbox');

    expect(getMapboxAccessToken()).toBe('');

    warnSpy.mockRestore();
  });

  it('returns the token when EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN is set', () => {
    process.env['EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN'] = 'pk.test.example-token';

    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { getMapboxAccessToken } = require('../../config/mapbox') as typeof import('../../config/mapbox');

    expect(getMapboxAccessToken()).toBe('pk.test.example-token');
  });

  it('does not throw when the token is missing', () => {
    delete process.env['EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN'];

    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { getMapboxAccessToken } = require('../../config/mapbox') as typeof import('../../config/mapbox');

    expect(() => getMapboxAccessToken()).not.toThrow();

    warnSpy.mockRestore();
  });

  it('does not log the token value when it is present', () => {
    process.env['EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN'] = 'pk.test.secret-token';
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { getMapboxAccessToken } = require('../../config/mapbox') as typeof import('../../config/mapbox');

    getMapboxAccessToken();
    const warnCalls = warnSpy.mock.calls.map((args) => args.join(' '));
    const tokenLogged = warnCalls.some((msg) => msg.includes('pk.test.secret-token'));
    expect(tokenLogged).toBe(false);

    warnSpy.mockRestore();
  });
});

describe('mapbox config — isMapboxTokenConfigured', () => {
  const ORIGINAL_ENV = process.env;

  beforeEach(() => {
    jest.resetModules();
    process.env = { ...ORIGINAL_ENV };
  });

  afterAll(() => {
    process.env = ORIGINAL_ENV;
  });

  it('returns false when EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN is not set', () => {
    delete process.env['EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN'];

    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { isMapboxTokenConfigured } = require('../../config/mapbox') as typeof import('../../config/mapbox');

    expect(isMapboxTokenConfigured()).toBe(false);
  });

  it('returns true when EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN is set', () => {
    process.env['EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN'] = 'pk.test.example-token';

    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { isMapboxTokenConfigured } = require('../../config/mapbox') as typeof import('../../config/mapbox');

    expect(isMapboxTokenConfigured()).toBe(true);
  });
});
