import { describe, expect, it } from 'vitest';
import {
  CLIENT_ERROR_ISSUE_LABEL,
  CLIENT_ERROR_RATE_LIMIT_MAX,
  buildClientErrorAuditDetails,
  buildClientErrorIssuePayload,
  buildClientErrorReportDocument,
  buildNewClientErrorIssueLink,
  clientErrorSignature,
  computeClientErrorFingerprint,
  decideClientErrorIssueAction,
  isClientErrorRateLimited,
  parseReportClientErrorInput,
  type ClientErrorIssueLink,
  type ClientErrorReport,
} from './clientErrors-core';

const FIXED_TS = 'TS';
const ts = () => FIXED_TS;

function sampleReport(overrides: Partial<ClientErrorReport> = {}): ClientErrorReport {
  return {
    feature: 'messages.conversationList',
    message: 'Conversation inbox listener failed',
    code: 'FAILED_PRECONDITION',
    appVersion: '1.2.3',
    osVersion: 'Android 14',
    deviceModel: 'Pixel 7',
    platform: 'android',
    fingerprint: computeClientErrorFingerprint(
      'messages.conversationList',
      'Conversation inbox listener failed',
      'FAILED_PRECONDITION',
    ),
    ...overrides,
  };
}

describe('parseReportClientErrorInput', () => {
  it('parses + bounds a valid submission and derives a fingerprint', () => {
    const result = parseReportClientErrorInput({
      feature: 'messages.conversationList',
      message: 'Conversation inbox listener failed',
      code: 'failed_precondition',
      appVersion: '1.2.3',
      platform: 'android',
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.input.feature).toBe('messages.conversationList');
    expect(result.input.code).toBe('failed_precondition');
    expect(result.input.platform).toBe('android');
    expect(result.input.fingerprint).toHaveLength(64);
  });

  it('defaults platform to android when omitted', () => {
    const result = parseReportClientErrorInput({ feature: 'x', message: 'y' });
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.input.platform).toBe('android');
  });

  it('rejects missing/empty/unknown fields', () => {
    expect(parseReportClientErrorInput(null).ok).toBe(false);
    expect(parseReportClientErrorInput({ feature: '', message: 'y' }).ok).toBe(false);
    expect(parseReportClientErrorInput({ feature: 'x', message: '' }).ok).toBe(false);
    expect(parseReportClientErrorInput({ feature: 'x', message: 'y', extra: 1 }).ok).toBe(false);
    expect(parseReportClientErrorInput({ feature: 'x', message: 'y', code: 'z'.repeat(200) }).ok).toBe(
      false,
    );
  });
});

describe('fingerprint + signature', () => {
  it('prefers the code, upper-cased, for the signature', () => {
    expect(clientErrorSignature('anything at all', 'failed_precondition')).toBe('FAILED_PRECONDITION');
  });

  it('normalizes volatile tokens in the message when no code is present', () => {
    // Differing digit runs collapse to the same signature/fingerprint.
    const a = clientErrorSignature('load failed after 3 retries', null);
    const b = clientErrorSignature('load failed after 17 retries', null);
    expect(a).toBe(b);
    expect(
      computeClientErrorFingerprint('f', 'load failed after 3 retries', null),
    ).toBe(computeClientErrorFingerprint('f', 'load failed after 17 retries', null));
  });

  it('distinguishes different features and different codes', () => {
    expect(computeClientErrorFingerprint('a', 'm', 'X')).not.toBe(
      computeClientErrorFingerprint('b', 'm', 'X'),
    );
    expect(computeClientErrorFingerprint('a', 'm', 'X')).not.toBe(
      computeClientErrorFingerprint('a', 'm', 'Y'),
    );
  });
});

describe('rate limit', () => {
  it('trips at the cap', () => {
    expect(isClientErrorRateLimited(CLIENT_ERROR_RATE_LIMIT_MAX - 1)).toBe(false);
    expect(isClientErrorRateLimited(CLIENT_ERROR_RATE_LIMIT_MAX)).toBe(true);
  });
});

describe('record + audit builders', () => {
  it('builds the private report doc with the uid and pending issue status', () => {
    const doc = buildClientErrorReportDocument(sampleReport(), 'uid-1', ts);
    expect(doc.uid).toBe('uid-1');
    expect(doc.githubIssueStatus).toBe('pending');
    expect(doc.githubIssueNumber).toBeNull();
    expect(doc.createdAt).toBe(FIXED_TS);
  });

  it('builds audit details WITHOUT a uid or secret (uid is the audit adminId)', () => {
    const details = buildClientErrorAuditDetails(sampleReport());
    expect(details).not.toHaveProperty('uid');
    expect(details.message).toBe('Conversation inbox listener failed');
    expect(details.code).toBe('FAILED_PRECONDITION');
    expect(details.fingerprint).toHaveLength(64);
  });
});

describe('dedup decision', () => {
  it('creates for a missing or failed link, increments otherwise', () => {
    expect(decideClientErrorIssueAction(null)).toBe('create');
    const link = (status: ClientErrorIssueLink['status']): ClientErrorIssueLink => ({
      fingerprint: 'f',
      feature: 'x',
      status,
      issueNumber: null,
      issueUrl: null,
      count: 1,
    });
    expect(decideClientErrorIssueAction(link('failed'))).toBe('create');
    expect(decideClientErrorIssueAction(link('creating'))).toBe('increment');
    expect(decideClientErrorIssueAction(link('created'))).toBe('increment');
  });

  it('seeds a new link at count 1 with a creating status', () => {
    const doc = buildNewClientErrorIssueLink(sampleReport(), ts);
    expect(doc.count).toBe(1);
    expect(doc.status).toBe('creating');
    expect(doc.issueNumber).toBeNull();
  });
});

describe('public issue payload', () => {
  it('labels auto-error and never leaks a uid into the body', () => {
    const payload = buildClientErrorIssuePayload(sampleReport(), {
      firstSeenIso: '2026-07-15T00:00:00.000Z',
      count: 4,
    });
    expect(payload.labels).toContain(CLIENT_ERROR_ISSUE_LABEL);
    expect(payload.title).toContain('messages.conversationList');
    expect(payload.body).toContain('Occurrences: 4');
    expect(payload.body).not.toMatch(/uid/i);
  });

  it('neutralizes @mentions / #refs in client-supplied scalars', () => {
    const payload = buildClientErrorIssuePayload(
      sampleReport({ message: 'ping @maintainer see #123' }),
      { firstSeenIso: '2026-07-15T00:00:00.000Z', count: 1 },
    );
    expect(payload.body).not.toContain('@maintainer');
    expect(payload.body).not.toContain('#123');
  });

  it('collapses newlines/tabs in the message so a bullet renders single-line', () => {
    const payload = buildClientErrorIssuePayload(
      sampleReport({ message: 'line one\n- injected: bullet\tafter\ttab\nline three' }),
      { firstSeenIso: '2026-07-15T00:00:00.000Z', count: 1 },
    );
    const messageLine = payload.body
      .split('\n')
      .find((line) => line.startsWith('- Message:'));
    expect(messageLine).toBeDefined();
    // The whole (whitespace-collapsed) message stays on the one bullet line.
    expect(messageLine).toBe('- Message: `line one - injected: bullet after tab line three`');
    // No raw newline/tab from the payload leaked a second bullet into the body.
    expect(payload.body).not.toContain('\t');
    // The crafted "- injected:" text never becomes its own bullet line; it
    // stays inside the single Message bullet's inline-code span.
    const injectedAsBullet = payload.body
      .split('\n')
      .some((line) => line.startsWith('- injected:'));
    expect(injectedAsBullet).toBe(false);
  });
});
