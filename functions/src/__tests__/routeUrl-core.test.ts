import { describe, expect, it, vi } from 'vitest';
import {
  ROUTE_UPGRADE_MESSAGE,
  ROUTE_URL_EXPIRY_MS,
  decideRouteVisibility,
  parseRouteUrlInput,
  signRouteUrl,
} from '../drives/routeUrl-core';
import { DAY_MS, PLUS_DRIVE_HISTORY_DAYS } from '../drives/driveHistory-core';

const NOW = Date.parse('2026-09-01T12:00:00.000Z');

describe('parseRouteUrlInput', () => {
  it('accepts a Firestore-safe rideId', () => {
    const result = parseRouteUrlInput({ rideId: 'uid_session-123.abc' });
    expect(result).toEqual({ ok: true, input: { rideId: 'uid_session-123.abc' } });
  });

  it('rejects a missing, empty, over-long, reserved, or path-separated rideId', () => {
    expect(parseRouteUrlInput({}).ok).toBe(false);
    expect(parseRouteUrlInput({ rideId: '' }).ok).toBe(false);
    expect(parseRouteUrlInput({ rideId: 'a'.repeat(301) }).ok).toBe(false);
    expect(parseRouteUrlInput({ rideId: '.' }).ok).toBe(false);
    expect(parseRouteUrlInput({ rideId: '..' }).ok).toBe(false);
    expect(parseRouteUrlInput({ rideId: 'a/b' }).ok).toBe(false);
    // Extra keys are rejected (strict) so a caller cannot smuggle fields.
    expect(parseRouteUrlInput({ rideId: 'ok', extra: 1 }).ok).toBe(false);
  });
});

describe('decideRouteVisibility', () => {
  it('always allows Supporter regardless of age or newest-set membership', () => {
    expect(
      decideRouteVisibility({
        tier: 'supporter',
        rideCreatedAtMillis: NOW - 10 * 365 * DAY_MS,
        serverNowMillis: NOW,
        isAmongNewestForCommunity: false,
      }),
    ).toEqual({ visible: true });
  });

  it('allows Plus inside the rolling 90-day window and denies outside it', () => {
    const insideWindow = NOW - (PLUS_DRIVE_HISTORY_DAYS - 1) * DAY_MS;
    const outsideWindow = NOW - (PLUS_DRIVE_HISTORY_DAYS + 1) * DAY_MS;

    expect(
      decideRouteVisibility({
        tier: 'plus',
        rideCreatedAtMillis: insideWindow,
        serverNowMillis: NOW,
        isAmongNewestForCommunity: false,
      }),
    ).toEqual({ visible: true });

    expect(
      decideRouteVisibility({
        tier: 'plus',
        rideCreatedAtMillis: outsideWindow,
        serverNowMillis: NOW,
        isAmongNewestForCommunity: true,
      }),
    ).toEqual({ visible: false, message: ROUTE_UPGRADE_MESSAGE });
  });

  it('fails Plus closed when the ride createdAt is unknown', () => {
    expect(
      decideRouteVisibility({
        tier: 'plus',
        rideCreatedAtMillis: null,
        serverNowMillis: NOW,
        isAmongNewestForCommunity: false,
      }),
    ).toEqual({ visible: false, message: ROUTE_UPGRADE_MESSAGE });
  });

  it('allows Community only when the ride is among its newest set', () => {
    expect(
      decideRouteVisibility({
        tier: 'community',
        rideCreatedAtMillis: NOW,
        serverNowMillis: NOW,
        isAmongNewestForCommunity: true,
      }),
    ).toEqual({ visible: true });

    expect(
      decideRouteVisibility({
        tier: 'community',
        rideCreatedAtMillis: NOW,
        serverNowMillis: NOW,
        isAmongNewestForCommunity: false,
      }),
    ).toEqual({ visible: false, message: ROUTE_UPGRADE_MESSAGE });
  });
});

describe('signRouteUrl (fail-safe)', () => {
  it('returns the signed URL and a +5min expiry when signing succeeds', async () => {
    const signer = vi.fn(async (expiresAtMillis: number) => `https://signed/${expiresAtMillis}`);
    const result = await signRouteUrl(signer, NOW);
    expect(result).toEqual({
      ok: true,
      value: {
        url: `https://signed/${NOW + ROUTE_URL_EXPIRY_MS}`,
        expiresAtMillis: NOW + ROUTE_URL_EXPIRY_MS,
      },
    });
    expect(signer).toHaveBeenCalledWith(NOW + ROUTE_URL_EXPIRY_MS);
  });

  it('fails closed (never throws) when the signer throws — the missing-IAM path', async () => {
    const onError = vi.fn();
    const result = await signRouteUrl(
      async () => {
        // Mirrors the IAM Credentials signBlob PERMISSION_DENIED a runtime SA
        // without Service Account Token Creator raises.
        throw new Error('Permission iam.serviceAccounts.signBlob denied on runtime SA');
      },
      NOW,
      onError,
    );
    expect(result).toEqual({ ok: false });
    expect(onError).toHaveBeenCalledOnce();
  });

  it('fails closed when the signer yields an empty URL', async () => {
    const onError = vi.fn();
    const result = await signRouteUrl(async () => '', NOW, onError);
    expect(result).toEqual({ ok: false });
    expect(onError).toHaveBeenCalledOnce();
  });
});
