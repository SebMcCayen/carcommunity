/**
 * Unit tests for the feedback "Report a problem" pure logic
 * (feedback-core.ts). No emulators. The public-body PII-exclusion assertions
 * are the point — the GitHub issue is world-readable.
 */

import { describe, expect, it } from 'vitest';
import {
  FEEDBACK_ISSUE_LABEL,
  FEEDBACK_RATE_LIMIT_MAX,
  MAX_DESCRIPTION_LENGTH,
  MAX_SUMMARY_LENGTH,
  boundContext,
  boundText,
  buildFeedbackReportDocument,
  buildGitHubIssueBody,
  buildGitHubIssuePayload,
  buildGitHubIssueTitle,
  feedbackRateLimitWindowStart,
  isFeedbackRateLimited,
  parseReportIssueInput,
  type FeedbackReport,
} from '../feedback/feedback-core';

const report: FeedbackReport = {
  description: 'The map does not load after I open the app.',
  summary: 'Map fails to load',
  appVersion: '1.2.3',
  osVersion: 'Android 14',
  deviceModel: 'Pixel 8',
};

describe('feedback-core input parsing', () => {
  it('accepts a minimal valid report and bounds fields', () => {
    const result = parseReportIssueInput({ description: '  Something broke  ' });
    expect(result.ok).toBe(true);
    if (!result.ok) throw new Error('expected ok');
    expect(result.input.description).toBe('Something broke');
    expect(result.input.summary).toBeNull();
    expect(result.input.appVersion).toBeNull();
  });

  it('rejects empty/oversized description and unknown keys', () => {
    expect(parseReportIssueInput({ description: '' }).ok).toBe(false);
    expect(parseReportIssueInput({ description: '   ' }).ok).toBe(false);
    expect(parseReportIssueInput({ description: 'x'.repeat(MAX_DESCRIPTION_LENGTH + 1) }).ok).toBe(
      false,
    );
    expect(parseReportIssueInput({ description: 'ok', uid: 'sneaky' }).ok).toBe(false);
    expect(parseReportIssueInput({ description: 'ok', summary: 'x'.repeat(MAX_SUMMARY_LENGTH + 1) }).ok).toBe(
      false,
    );
  });

  it('strips control characters and collapses context whitespace', () => {
    // NUL/BEL control chars are stripped; tab/newline survive.
    expect(boundText('a\u0000b\u0007c', 100)).toBe('abc');
    expect(boundText('keeps\ttab and\nnewline', 100)).toBe('keeps\ttab and\nnewline');
    expect(boundContext('  Android   14 \n beta ', 100)).toBe('Android 14 beta');
    expect(boundContext('', 100)).toBeNull();
    expect(boundContext(undefined, 100)).toBeNull();
  });
});

describe('feedback-core rate limit', () => {
  it('flags once the per-user cap is reached', () => {
    expect(isFeedbackRateLimited(FEEDBACK_RATE_LIMIT_MAX - 1)).toBe(false);
    expect(isFeedbackRateLimited(FEEDBACK_RATE_LIMIT_MAX)).toBe(true);
    expect(isFeedbackRateLimited(FEEDBACK_RATE_LIMIT_MAX + 3)).toBe(true);
  });

  it('computes a one-hour rolling window start', () => {
    expect(feedbackRateLimitWindowStart(new Date('2026-07-09T12:00:00Z')).toISOString()).toBe(
      '2026-07-09T11:00:00.000Z',
    );
  });
});

describe('feedback-core public GitHub issue', () => {
  it('titles with the [Android] tag and bounds the summary', () => {
    expect(buildGitHubIssueTitle(report)).toBe('[Android] Map fails to load');
    // Falls back to the first description line when no summary is given.
    const noSummary: FeedbackReport = { ...report, summary: null };
    expect(buildGitHubIssueTitle(noSummary)).toBe(
      '[Android] The map does not load after I open the app.',
    );
    // Never empty.
    expect(buildGitHubIssueTitle({ ...report, summary: null, description: '   ' })).toBe(
      '[Android] Problem report',
    );
    // Bounded.
    const long = buildGitHubIssueTitle({ ...report, summary: 'x'.repeat(200) });
    expect(long.length).toBeLessThanOrEqual('[Android] '.length + MAX_SUMMARY_LENGTH);
  });

  it('builds a body with only description + context + id + timestamp', () => {
    const body = buildGitHubIssueBody(report, 'rep_123', '2026-07-09T12:00:00.000Z');
    expect(body).toContain('The map does not load after I open the app.');
    expect(body).toContain('- App version: 1.2.3');
    expect(body).toContain('- OS version: Android 14');
    expect(body).toContain('- Device model: Pixel 8');
    expect(body).toContain('- Report ID: rep_123');
    expect(body).toContain('- Submitted at: 2026-07-09T12:00:00.000Z');
    // The uid is never part of the body.
    expect(body).not.toContain('uid');
  });

  it('renders unknown for absent context fields', () => {
    const bare: FeedbackReport = {
      description: 'x',
      summary: null,
      appVersion: null,
      osVersion: null,
      deviceModel: null,
    };
    const body = buildGitHubIssueBody(bare, 'rep_1', '2026-07-09T12:00:00.000Z');
    expect(body).toContain('- App version: unknown');
    expect(body).toContain('- OS version: unknown');
    expect(body).toContain('- Device model: unknown');
  });

  it('NEVER leaks uid or PII into the public payload', () => {
    const uid = 'uid_secret_abc123';
    const payload = buildGitHubIssuePayload(report, 'rep_xyz', '2026-07-09T12:00:00.000Z');
    const serialized = JSON.stringify(payload);
    expect(serialized).not.toContain(uid);
    // The public payload references neither the private doc uid nor email/token
    // vocabulary — only what the user typed plus bounded context.
    expect(serialized.toLowerCase()).not.toContain('uid');
    expect(serialized.toLowerCase()).not.toContain('@');
    expect(serialized.toLowerCase()).not.toContain('token');
    expect(payload.labels).toEqual([FEEDBACK_ISSUE_LABEL]);
  });
});

describe('feedback-core private document', () => {
  it('stores the uid + context and starts pending', () => {
    const doc = buildFeedbackReportDocument(report, 'uid_secret_abc123', () => 'SERVER_TS');
    expect(doc.uid).toBe('uid_secret_abc123');
    expect(doc.platform).toBe('android');
    expect(doc.description).toBe(report.description);
    expect(doc.githubIssueStatus).toBe('pending');
    expect(doc.githubIssueNumber).toBeNull();
    expect(doc.githubIssueUrl).toBeNull();
    expect(doc.createdAt).toBe('SERVER_TS');
  });
});
