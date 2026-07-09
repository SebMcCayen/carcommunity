/**
 * Unit tests for the diagnostics pure logic (diagnostics-core.ts).
 * No emulators required. The sanitization matrix is the point — these are
 * the legacy privacy guarantees, ported verbatim.
 */

import { describe, expect, it } from 'vitest';
import {
  buildDiagnosticsReportDocument,
  diagnosticsRetentionCutoff,
  generateFingerprint,
  parseSubmitDiagnosticsReportInput,
  sanitizeMetadata,
} from '../diagnostics/diagnostics-core';

const validInput = {
  severity: 'error',
  platform: 'android',
  featureArea: 'map',
  safeMessage: 'Kartan kunde inte laddas',
};

describe('diagnostics-core sanitizeMetadata', () => {
  it('strips exact blocked keys, coordinates, and token-like substrings', () => {
    const result = sanitizeMetadata({
      accessToken: 'secret-value',
      authorization: 'Bearer x',
      cookie: 'session=1',
      latitude: 59.33,
      lng: 18.07,
      location: 'Stockholm',
      myAuthHeader: 'x',
      stackTrace: 'at foo()',
      backtrace: 'at bar()',
      userPassword: 'hunter2',
      screen: 'MapScreen',
      retryCount: 3,
      online: true,
    });
    expect(result).toEqual({ screen: 'MapScreen', retryCount: 3, online: true });
  });

  it('keeps only bounded scalars and drops nested structures', () => {
    const result = sanitizeMetadata({
      long: 'x'.repeat(600),
      nested: { secret: 'y' },
      list: [1, 2, 3],
      nothing: null,
    });
    // 'long' is a blocked coordinate key ('lon'? no — exact-match only; substring rules don't cover 'long').
    expect(result!.long).toHaveLength(500);
    expect(result!.nested).toBeUndefined();
    expect(result!.list).toBeUndefined();
    expect(result!.nothing).toBeNull();
  });

  it('caps at 20 keys and returns null when nothing safe remains', () => {
    const big = Object.fromEntries(Array.from({ length: 30 }, (_, i) => [`k${i}`, i]));
    expect(Object.keys(sanitizeMetadata(big)!)).toHaveLength(20);
    expect(sanitizeMetadata({ accessToken: 'x' })).toBeNull();
    expect(sanitizeMetadata(null)).toBeNull();
    expect(sanitizeMetadata([] as unknown as Record<string, unknown>)).toBeNull();
  });
});

describe('diagnostics-core fingerprint', () => {
  it('groups reports that differ only in numbers and UUIDs', () => {
    const base = {
      severity: 'error',
      platform: 'android',
      featureArea: 'network',
      errorCode: 'timeout',
    };
    const a = generateFingerprint({ ...base, safeMessage: 'Request 42 failed for 550e8400-e29b-41d4-a716-446655440000' });
    const b = generateFingerprint({ ...base, safeMessage: 'Request 7 failed for 123e4567-e89b-42d3-a456-426614174000' });
    expect(a).toBe(b);
    expect(a).toMatch(/^[a-f0-9]{64}$/);
    // Different stable attributes → different fingerprint.
    expect(generateFingerprint({ ...base, errorCode: 'dns', safeMessage: 'Request 42 failed' })).not.toBe(
      generateFingerprint({ ...base, errorCode: 'timeout', safeMessage: 'Request 42 failed' }),
    );
  });
});

describe('diagnostics-core input and builder', () => {
  it('validates enums and limits', () => {
    expect(parseSubmitDiagnosticsReportInput(validInput).ok).toBe(true);
    expect(parseSubmitDiagnosticsReportInput({ ...validInput, severity: 'fatal' }).ok).toBe(false);
    expect(parseSubmitDiagnosticsReportInput({ ...validInput, safeMessage: '' }).ok).toBe(false);
    expect(
      parseSubmitDiagnosticsReportInput({ ...validInput, safeMessage: 'x'.repeat(2001) }).ok,
    ).toBe(false);
    expect(parseSubmitDiagnosticsReportInput({ ...validInput, extra: 1 }).ok).toBe(false);
  });

  it('builds documents with sanitized metadata and anonymous userId', () => {
    const parsed = parseSubmitDiagnosticsReportInput({
      ...validInput,
      metadata: { idToken: 'x', screen: 'MapScreen' },
    });
    if (!parsed.ok) throw new Error('expected ok');
    const docData = buildDiagnosticsReportDocument(parsed.input, null, () => 'SERVER_TS');
    expect(docData.userId).toBeNull();
    expect(docData.metadata).toEqual({ screen: 'MapScreen' });
    expect(docData.fingerprint).toMatch(/^[a-f0-9]{64}$/);
    expect(JSON.stringify(docData)).not.toContain('idToken');
  });

  it('records the server-derived App Check presence flag (attested vs unattested)', () => {
    const parsed = parseSubmitDiagnosticsReportInput(validInput);
    if (!parsed.ok) throw new Error('expected ok');

    // Pre-auth telemetry is non-enforcing: a report WITHOUT a valid App Check
    // token is still stored, flagged so admins can distinguish it.
    const unattested = buildDiagnosticsReportDocument(
      parsed.input,
      null,
      () => 'SERVER_TS',
      { appCheckPresent: false },
    );
    expect(unattested.appCheckPresent).toBe(false);

    const attested = buildDiagnosticsReportDocument(parsed.input, null, () => 'SERVER_TS', {
      appCheckPresent: true,
    });
    expect(attested.appCheckPresent).toBe(true);

    // Backward-compatible: callers that omit the context store null (not undefined).
    const legacy = buildDiagnosticsReportDocument(parsed.input, null, () => 'SERVER_TS');
    expect(legacy.appCheckPresent).toBeNull();
  });

  it('computes the 90-day retention cutoff', () => {
    expect(diagnosticsRetentionCutoff(new Date('2026-07-05T12:00:00Z')).toISOString()).toBe(
      '2026-04-06T12:00:00.000Z',
    );
  });
});
