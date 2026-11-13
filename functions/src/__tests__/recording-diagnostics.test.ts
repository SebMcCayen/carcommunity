import { describe, expect, it } from 'vitest';
import { buildRideDocument, computeDriveStats, parseSaveDriveInput } from '../drives/drives-core';
import {
  computeCalculationDiagnostics,
  feedbackDriveDiagnostics,
  recordingDiagnosticsSchema,
} from '../drives/recording-diagnostics';
import { totalDistanceMetres } from '../drives/drive-calculations';
import {
  buildFeedbackReportDocument,
  buildGitHubIssuePayload,
  parseReportIssueInput,
} from '../feedback/feedback-core';

const recording = {
  version: 1,
  algorithmVersion: 'recording-quality-v1',
  receivedPoints: 120,
  acceptedPoints: 100,
  rejectedInvalid: 0,
  rejectedAccuracy: 10,
  rejectedStale: 0,
  rejectedTime: 0,
  rejectedSpeed: 10,
  droppedCapacity: 0,
  droppedStationary: 0,
  droppedThrottled: 0,
  maxGapMs: 30_000,
  gapCount: 1,
  resumedCount: 1,
} as const;
const times = { startedAt: '2026-11-12T10:00:00.000Z', endedAt: '2026-11-12T11:00:00.000Z' };

describe('bounded recording diagnostics', () => {
  it('keeps old saves valid and adds diagnostics without changing statistics', () => {
    const legacy = parseSaveDriveInput(times);
    const modern = parseSaveDriveInput({ ...times, recordingDiagnostics: recording });
    expect(legacy.ok).toBe(true);
    expect(modern.ok).toBe(true);
    if (!legacy.ok || !modern.ok) throw new Error('expected valid saves');
    expect(computeDriveStats(modern.input)).toEqual(computeDriveStats(legacy.input));
    const document = buildRideDocument(
      modern.input,
      {
        userId: 'owner',
        rideId: 'drive',
        stats: computeDriveStats(modern.input),
        routeThumbnail: null,
      },
      () => 'SERVER_TS',
    );
    expect(document.recordingDiagnostics).toEqual(recording);
    expect(document.calculationDiagnostics).toEqual(computeCalculationDiagnostics(undefined));
  });

  it.each([
    { receivedPoints: -1 },
    { acceptedPoints: 1.5 },
    { rejectedSpeed: 10_000_001 },
    { maxGapMs: 604_800_001 },
    { version: 2 },
    { algorithmVersion: 'arbitrary' },
    { latitude: 57.1 },
    { message: 'free text' },
    { uid: 'private-id' },
  ])('drops malformed or non-allowlisted diagnostics without losing the drive %j', (change) => {
    const bad = { ...recording, ...change };
    expect(recordingDiagnosticsSchema.safeParse(bad).success).toBe(false);
    const parsed = parseSaveDriveInput({ ...times, recordingDiagnostics: bad });
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) throw new Error('valid drive must survive malformed telemetry');
    expect(parsed.input.recordingDiagnostics).toBeUndefined();
    const document = buildRideDocument(
      parsed.input,
      {
        userId: 'owner',
        rideId: 'drive',
        stats: computeDriveStats(parsed.input),
        routeThumbnail: null,
      },
      () => 'TS',
    );
    expect(document).not.toHaveProperty('recordingDiagnostics');
  });

  it('accepts bounded counters only and rejects missing fields', () => {
    expect(recordingDiagnosticsSchema.safeParse(recording).success).toBe(true);
    const missing: Record<string, unknown> = { ...recording };
    delete missing.resumedCount;
    expect(recordingDiagnosticsSchema.safeParse(missing).success).toBe(false);
    const parsed = parseSaveDriveInput({ ...times, recordingDiagnostics: missing });
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) throw new Error('valid drive must survive incomplete telemetry');
    expect(parsed.input.recordingDiagnostics).toBeUndefined();
    expect(parseSaveDriveInput({ ...times, unexpected: true }).ok).toBe(false);
  });

  it.each([
    { platform: 'web' },
    { platform: 123 },
    { platform: null },
    { appVersion: '' },
    { appVersion: 'x'.repeat(51) },
    { appVersion: { latitude: 57 } },
    { platform: false, appVersion: null },
  ])('removes malformed optional context while preserving counters %j', (context) => {
    const parsed = parseSaveDriveInput({
      ...times,
      recordingDiagnostics: { ...recording, ...context },
    });
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) throw new Error('valid counters must survive malformed context');
    expect(parsed.input.recordingDiagnostics).toEqual(recording);
    expect(parsed.input.recordingDiagnostics).not.toHaveProperty('platform');
    expect(parsed.input.recordingDiagnostics).not.toHaveProperty('appVersion');
    expect(
      feedbackDriveDiagnostics({ recordingDiagnostics: { ...recording, ...context } }),
    ).toEqual({ recordingDiagnostics: recording });
  });

  it('retains valid context independently of malformed companion fields', () => {
    const parsed = recordingDiagnosticsSchema.parse({
      ...recording,
      platform: 'android',
      appVersion: 'x'.repeat(51),
    });
    expect(parsed).toEqual({ ...recording, platform: 'android' });
    expect(
      recordingDiagnosticsSchema.parse({ ...recording, platform: 'web', appVersion: '1.1.13' }),
    ).toEqual({ ...recording, appVersion: '1.1.13' });
  });
});

describe('server calculation diagnostics', () => {
  it('identifies rejected jumps and endpoint contradiction without correcting the total', () => {
    const points = [
      { latitude: 57, longitude: 12, timestampMs: 1_000 },
      { latitude: 57, longitude: 12.1, timestampMs: 6_000 },
      { latitude: 57.01, longitude: 12, timestampMs: 11_000 },
      { latitude: 57.011, longitude: 12, timestampMs: 16_000 },
    ];
    const distance = totalDistanceMetres(points);
    const result = computeCalculationDiagnostics(points);
    expect(result.totalSegments).toBe(3);
    expect(result.rejectedSpeedSegments).toBe(2);
    expect(result.distanceBelowEndpoint).toBe(true);
    expect(result.endpointDistanceMeters).toBeGreaterThan(1_000);
    expect(totalDistanceMetres(points)).toBe(distance);
  });

  it('distinguishes duplicate time, accepted gaps and harmless stationary/short drives', () => {
    const point = { latitude: 57, longitude: 12 };
    const result = computeCalculationDiagnostics([
      { ...point, timestampMs: 1_000 },
      { ...point, timestampMs: 1_000 },
      { ...point, timestampMs: 31_000 },
    ]);
    expect(result.rejectedTimeSegments).toBe(1);
    expect(result.gapCount).toBe(1);
    expect(result.maxAcceptedGapMs).toBe(30_000);
    expect(result.distanceBelowEndpoint).toBe(false);
    expect(computeCalculationDiagnostics([]).endpointDistanceMeters).toBeNull();
  });
});

describe('explicit drive feedback privacy', () => {
  it('distinguishes the recording source from a different reporting platform and version', () => {
    const diagnostics = feedbackDriveDiagnostics({
      recordingDiagnostics: { ...recording, platform: 'android', appVersion: '1.1.13' },
    });
    const parsed = parseReportIssueInput({
      description: 'Distance is wrong',
      platform: 'ios',
      appVersion: '2.4.6',
      rideId: 'private-ride',
    });
    if (!parsed.ok) throw new Error('expected valid feedback');
    const report = { ...parsed.input, driveDiagnostics: diagnostics };
    const payload = buildGitHubIssuePayload(report, 'report', 'submitted');
    expect(payload.title).toMatch(/^\[iOS\]/);
    expect(payload.body).toContain('- Reporter platform: ios');
    expect(payload.body).toContain('- Reporter app version: 2.4.6');
    expect(payload.body).toContain(
      '- Recording source platform: android; recording app version: 1.1.13',
    );
    expect(payload.body).not.toContain('private-ride');
    const privateDoc = buildFeedbackReportDocument(report, 'owner', () => 'TS');
    expect(privateDoc.platform).toBe('ios');
    expect(privateDoc.appVersion).toBe('2.4.6');
    expect(privateDoc.driveDiagnostics).toEqual(diagnostics);
  });

  it.each([
    'user@example.com',
    'branch-private-id',
    '1.2.3\n@owner',
    '1.2',
    '1.2.3.4',
    '12345.2.3',
  ])('retains bounded nonstandard versions privately without publishing %s', (appVersion) => {
    const diagnostics = feedbackDriveDiagnostics({
      recordingDiagnostics: { ...recording, platform: 'ios', appVersion },
    });
    expect(diagnostics.recordingDiagnostics?.appVersion).toBe(appVersion);
    const parsed = parseReportIssueInput({ description: 'Distance is wrong' });
    if (!parsed.ok) throw new Error('expected valid feedback');
    const payload = buildGitHubIssuePayload(
      { ...parsed.input, driveDiagnostics: diagnostics },
      'report',
      'submitted',
    );
    expect(payload.body).toContain(
      '- Recording source platform: ios; recording app version: unknown/private',
    );
    expect(payload.body).not.toContain(appVersion);
  });

  it('does not infer legacy recording context from the reporting device', () => {
    const parsed = parseReportIssueInput({
      description: 'Distance is wrong',
      platform: 'ios',
      appVersion: '2.4.6',
    });
    if (!parsed.ok) throw new Error('expected valid feedback');
    const payload = buildGitHubIssuePayload(
      { ...parsed.input, driveDiagnostics: { recordingDiagnostics: recording } },
      'report',
      'submitted',
    );
    expect(payload.body).toContain(
      '- Recording source platform: unknown; recording app version: unknown/private',
    );
  });

  it('publishes allowlisted counts but keeps ride, endpoints and measurement times private', () => {
    const calculation = computeCalculationDiagnostics([
      { latitude: 57, longitude: 12, timestampMs: 123_456 },
      { latitude: 57.1, longitude: 12, timestampMs: 128_456 },
    ]);
    const diagnostics = feedbackDriveDiagnostics({
      recordingDiagnostics: recording,
      calculationDiagnostics: calculation,
      routeThumbnail: 'secret-route',
      sourceSessionId: 'secret-session',
    });
    const parsed = parseReportIssueInput({
      description: 'Distance is wrong',
      rideId: 'secret-ride',
    });
    if (!parsed.ok) throw new Error('expected valid feedback');
    const report = { ...parsed.input, driveDiagnostics: diagnostics };
    const publicText = JSON.stringify(buildGitHubIssuePayload(report, 'report', 'submitted'));
    expect(publicText).toContain('accepted 100/120 points');
    expect(publicText).toContain('endpoint consistency incomplete');
    for (const value of [
      'secret-ride',
      'secret-route',
      'secret-session',
      '123456',
      '128456',
      '57.1',
      String(calculation.endpointDistanceMeters),
    ]) {
      expect(publicText).not.toContain(value);
    }
    const privateDoc = buildFeedbackReportDocument(report, 'owner', () => 'TS');
    expect(privateDoc.rideId).toBe('secret-ride');
    expect(privateDoc.driveDiagnostics).toEqual(diagnostics);
  });

  it('does not accept client-supplied diagnostic text or illegal drive paths', () => {
    expect(parseReportIssueInput({ description: 'x', driveDiagnostics: recording }).ok).toBe(false);
    for (const rideId of ['../secret', '.', '..', 'owner/ride', 'x'.repeat(301)]) {
      expect(parseReportIssueInput({ description: 'x', rideId }).ok).toBe(false);
    }
    expect(
      feedbackDriveDiagnostics({ recordingDiagnostics: { ...recording, latitude: 57 } }),
    ).toEqual({});
  });
});
