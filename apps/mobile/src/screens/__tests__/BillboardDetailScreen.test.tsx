/**
 * Tests for BillboardDetailScreen.
 */

import React from 'react';
import { Linking } from 'react-native';
import TestRenderer, { act } from 'react-test-renderer';

import { AppThemeProvider } from '../../hooks/useAppTheme';

jest.spyOn(console, 'warn').mockImplementation(() => undefined);
const openUrlSpy = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);

jest.mock('../../context/LiveLocationContext', () => ({
  useLiveLocation: jest.fn().mockReturnValue({ status: 'not_sharing', currentPosition: null }),
}));

jest.mock('../../hooks/useAuth', () => ({
  useAuth: jest.fn().mockReturnValue({
    currentUser: null,
    isAuthenticated: true,
    isLoading: false,
    withToken: jest.fn().mockImplementation(async (cb: (token: string) => Promise<void>) => {
      await cb('test-token');
    }),
  }),
  getPlatformAuthProvider: jest.fn().mockReturnValue(null),
}));

jest.mock('../../hooks/useI18n', () => ({
  useI18n: jest.fn().mockReturnValue({ t: (key: string) => key }),
}));

jest.mock('../../api/digital-billboards', () => ({
  fetchBillboardDetail: jest.fn().mockResolvedValue({
    ok: true,
    data: {
      billboardId: 'test-billboard-id',
      partnerId: 'test-partner-id',
      partnerCompanyName: 'Test Partner AB',
      headline: 'Test Headline',
      message: 'Test message without any html rendering',
      latitude: 57.5086,
      longitude: 12.0742,
      sponsorLabel: 'Sponsrad placering',
      availableFrom: null,
      availableUntil: null,
      callToActionType: 'website',
      callToActionValue: 'https://example.com',
      placementType: 'map_billboard',
    },
  }),
  fireAndForgetBillboardInteraction: jest.fn(),
}));

const mockNavigate = jest.fn();

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { BillboardDetailScreen } = require('../BillboardDetailScreen') as typeof import('../BillboardDetailScreen');

const fakeRoute = {
  params: { billboardId: 'test-billboard-id' },
} as unknown as Parameters<typeof BillboardDetailScreen>[0]['route'];
const fakeNav = {
  navigate: mockNavigate,
} as unknown as Parameters<typeof BillboardDetailScreen>[0]['navigation'];

function renderScreen() {
  return TestRenderer.create(
    <AppThemeProvider>
      <BillboardDetailScreen route={fakeRoute} navigation={fakeNav} />
    </AppThemeProvider>,
  );
}

afterAll(() => {
  jest.restoreAllMocks();
});

describe('BillboardDetailScreen', () => {
  it('renders without crashing', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    await act(async () => {
      renderer = renderScreen();
    });
    expect(renderer).not.toBeNull();
  });

  it('does not use dangerouslySetInnerHTML or render raw HTML', async () => {
    const { fetchBillboardDetail } = jest.requireMock('../../api/digital-billboards') as {
      fetchBillboardDetail: jest.Mock;
    };
    fetchBillboardDetail.mockResolvedValueOnce({
      ok: true,
      data: {
        billboardId: 'test-billboard-id',
        partnerId: 'test-partner-id',
        partnerCompanyName: 'Test Partner AB',
        headline: 'Test Headline',
        message: 'Test message without any <script>alert(1)</script> html',
        latitude: 57.5086,
        longitude: 12.0742,
        sponsorLabel: 'Sponsrad placering',
        availableFrom: null,
        availableUntil: null,
        callToActionType: 'website',
        callToActionValue: 'https://example.com',
        placementType: 'map_billboard',
      },
    });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    await act(async () => {
      renderer = renderScreen();
    });

    const json = JSON.stringify(renderer!.toJSON());
    expect(json).not.toContain('dangerouslySetInnerHTML');
    expect(json).not.toContain('<script>');
    expect(json).not.toContain('innerHTML');
  });

  it('disables CTA buttons when safe driving mode is active', async () => {
    const { useLiveLocation } = jest.requireMock('../../context/LiveLocationContext') as {
      useLiveLocation: jest.Mock;
    };
    useLiveLocation.mockReturnValue({ status: 'sharing', currentPosition: null });

    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    await act(async () => {
      renderer = renderScreen();
    });

    const json = JSON.stringify(renderer!.toJSON());
    expect(json).toContain('"disabled":true');

    useLiveLocation.mockReturnValue({ status: 'not_sharing', currentPosition: null });
  });

  it('shows the sponsored label and does not auto-open a URL', async () => {
    let renderer: ReturnType<typeof TestRenderer.create> | null = null;
    await act(async () => {
      renderer = renderScreen();
    });

    const json = JSON.stringify(renderer!.toJSON());
    expect(json).toContain('Sponsrad placering');
    expect(openUrlSpy).not.toHaveBeenCalled();
  });
});
