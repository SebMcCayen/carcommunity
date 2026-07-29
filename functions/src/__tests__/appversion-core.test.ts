/**
 * Unit tests for the pure app-version config module: input validation for
 * admin.setAppVersion and the document body it writes.
 *
 * The property that matters most here is the one that cannot be recovered
 * from in production: a `minimumSupportedVersionCode` above the latest
 * published build would wall off every single user with no build they
 * could install to get back in. It must never be accepted.
 */

import { describe, expect, it } from 'vitest';
import {
  APP_VERSION_COLLECTION,
  APP_VERSION_DOC,
  buildAppVersionDocument,
  MAX_VERSION_CODE,
  MAX_VERSION_NAME_LENGTH,
  parseSetAppVersionInput,
} from '../shared/appVersion-core';

describe('appVersion document location', () => {
  it('sits beside the feature flags document in config/', () => {
    expect(APP_VERSION_COLLECTION).toBe('config');
    expect(APP_VERSION_DOC).toBe('appVersion');
  });
});

describe('parseSetAppVersionInput', () => {
  it('accepts a minimal payload', () => {
    const parsed = parseSetAppVersionInput({ latestVersionCode: 23 });
    expect(parsed.ok).toBe(true);
    expect(parsed.ok && parsed.input.latestVersionCode).toBe(23);
  });

  it('accepts the full payload', () => {
    const parsed = parseSetAppVersionInput({
      latestVersionCode: 23,
      latestVersionName: '0.8.12',
      minimumSupportedVersionCode: 20,
      reason: 'Release 0.8.12.',
    });
    expect(parsed.ok).toBe(true);
    expect(parsed.ok && parsed.input).toEqual({
      latestVersionCode: 23,
      latestVersionName: '0.8.12',
      minimumSupportedVersionCode: 20,
      reason: 'Release 0.8.12.',
    });
  });

  it('allows a minimum equal to the latest version', () => {
    expect(
      parseSetAppVersionInput({ latestVersionCode: 23, minimumSupportedVersionCode: 23 }).ok,
    ).toBe(true);
  });

  it('rejects a minimum above the latest version (would lock everyone out)', () => {
    const parsed = parseSetAppVersionInput({
      latestVersionCode: 23,
      minimumSupportedVersionCode: 24,
    });
    expect(parsed.ok).toBe(false);
  });

  it('rejects a missing, zero, fractional, negative or out-of-range latestVersionCode', () => {
    expect(parseSetAppVersionInput({}).ok).toBe(false);
    expect(parseSetAppVersionInput({ latestVersionCode: 0 }).ok).toBe(false);
    expect(parseSetAppVersionInput({ latestVersionCode: 23.5 }).ok).toBe(false);
    expect(parseSetAppVersionInput({ latestVersionCode: -1 }).ok).toBe(false);
    expect(parseSetAppVersionInput({ latestVersionCode: MAX_VERSION_CODE + 1 }).ok).toBe(false);
    expect(parseSetAppVersionInput({ latestVersionCode: MAX_VERSION_CODE }).ok).toBe(true);
  });

  it('rejects a versionCode that is not a real number', () => {
    expect(parseSetAppVersionInput({ latestVersionCode: '23' }).ok).toBe(false);
    expect(parseSetAppVersionInput({ latestVersionCode: Number.NaN }).ok).toBe(false);
    expect(parseSetAppVersionInput({ latestVersionCode: Number.POSITIVE_INFINITY }).ok).toBe(false);
  });

  it('rejects a blank or over-long versionName', () => {
    expect(parseSetAppVersionInput({ latestVersionCode: 23, latestVersionName: '   ' }).ok).toBe(
      false,
    );
    expect(
      parseSetAppVersionInput({
        latestVersionCode: 23,
        latestVersionName: 'v'.repeat(MAX_VERSION_NAME_LENGTH + 1),
      }).ok,
    ).toBe(false);
  });

  it('rejects unknown fields (strict payload)', () => {
    expect(parseSetAppVersionInput({ latestVersionCode: 23, force: true }).ok).toBe(false);
  });

  it('rejects null/undefined payloads without throwing', () => {
    expect(parseSetAppVersionInput(null).ok).toBe(false);
    expect(parseSetAppVersionInput(undefined).ok).toBe(false);
  });
});

describe('buildAppVersionDocument', () => {
  it('resolves omitted fields to their inert defaults', () => {
    expect(buildAppVersionDocument({ latestVersionCode: 23 })).toEqual({
      latestVersionCode: 23,
      latestVersionName: null,
      // 0 = no build is unsupported: the blocking path stays dark unless a
      // minimum is set deliberately.
      minimumSupportedVersionCode: 0,
    });
  });

  it('carries through the values it was given', () => {
    expect(
      buildAppVersionDocument({
        latestVersionCode: 23,
        latestVersionName: '0.8.12',
        minimumSupportedVersionCode: 20,
        reason: 'ignored — audit only',
      }),
    ).toEqual({
      latestVersionCode: 23,
      latestVersionName: '0.8.12',
      minimumSupportedVersionCode: 20,
    });
  });
});
