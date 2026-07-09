/**
 * Unit tests for the sign-in-failure → GitHub issue pure logic
 * (signInIssues-core.ts). No emulators. The public-body PII-exclusion
 * assertions are the point — the auto-filed GitHub issue is world-readable and
 * the source reports are UNAUTHENTICATED (there is no uid to leak, and there
 * must never be one).
 */

import { describe, expect, it } from 'vitest';
import {
  AUTO_GENERATED_LABEL,
  SIGN_IN_FEATURE_AREA,
  SIGN_IN_ISSUE_LABEL,
  buildNewSignInIssueLink,
  buildSignInIssueBody,
  buildSignInIssueLinkCreated,
  buildSignInIssueLinkIncrement,
  buildSignInIssuePayload,
  buildSignInIssueTitle,
  decideSignInIssueAction,
  extractSignInFailureReport,
  type SignInFailureReport,
  type SignInIssueLink,
} from '../diagnostics/signInIssues-core';

const ZWSP = '\u200b';

const fingerprint = 'a'.repeat(64);

const report: SignInFailureReport = {
  errorCode: 'GetCredentialException',
  safeMessage: 'Sign-in failed: GetCredentialException',
  appVersion: '1.4.0',
  buildNumber: '42',
  osVersion: 'Android 14 (API 34)',
  deviceModel: 'Google Pixel 8',
  fingerprint,
};

const meta = { firstSeenIso: '2026-07-09T12:00:00.000Z', count: 1 };

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

describe('extractSignInFailureReport', () => {
  const rawDoc = {
    userId: null,
    severity: 'error',
    platform: 'android',
    featureArea: SIGN_IN_FEATURE_AREA,
    safeMessage: 'Sign-in failed: GetCredentialException',
    errorCode: 'GetCredentialException',
    appVersion: '1.4.0',
    buildNumber: '42',
    osVersion: 'Android 14 (API 34)',
    metadata: { deviceModel: 'Google Pixel 8' },
    fingerprint,
  };

  it('extracts the sign-in view including deviceModel from metadata', () => {
    expect(extractSignInFailureReport(rawDoc)).toEqual(report);
  });

  it('returns null for a non-sign-in featureArea (cheap no-op)', () => {
    expect(extractSignInFailureReport({ ...rawDoc, featureArea: 'map' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, featureArea: 'auth' })).toBeNull();
  });

  it('returns null when fingerprint or safeMessage is missing', () => {
    expect(extractSignInFailureReport({ ...rawDoc, fingerprint: undefined })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, safeMessage: '' })).toBeNull();
    expect(extractSignInFailureReport(null)).toBeNull();
    expect(extractSignInFailureReport(undefined)).toBeNull();
  });

  it('tolerates absent optionals and non-object metadata', () => {
    const result = extractSignInFailureReport({
      featureArea: SIGN_IN_FEATURE_AREA,
      safeMessage: 'Sign-in failed',
      fingerprint,
    });
    expect(result).toEqual({
      errorCode: null,
      safeMessage: 'Sign-in failed',
      appVersion: null,
      buildNumber: null,
      osVersion: null,
      deviceModel: null,
      fingerprint,
    });
  });
});

// ---------------------------------------------------------------------------
// Dedup decision + link documents
// ---------------------------------------------------------------------------

describe('decideSignInIssueAction (dedup)', () => {
  it('creates when there is no existing link', () => {
    expect(decideSignInIssueAction(null)).toBe('create');
    expect(decideSignInIssueAction(undefined)).toBe('create');
  });

  it('increments when a link already exists (creating OR created)', () => {
    const creating: SignInIssueLink = {
      fingerprint,
      status: 'creating',
      issueNumber: null,
      issueUrl: null,
      count: 1,
    };
    const created: SignInIssueLink = {
      fingerprint,
      status: 'created',
      issueNumber: 7,
      issueUrl: 'https://github.com/SebMcCayen/carcommunity/issues/7',
      count: 3,
    };
    expect(decideSignInIssueAction(creating)).toBe('increment');
    expect(decideSignInIssueAction(created)).toBe('increment');
  });
});

describe('signInIssueLinks document builders', () => {
  it('builds a creating placeholder with count 1 and timestamps', () => {
    const doc = buildNewSignInIssueLink(report, () => 'TS');
    expect(doc).toEqual({
      fingerprint,
      status: 'creating',
      issueNumber: null,
      issueUrl: null,
      count: 1,
      firstSeenAt: 'TS',
      lastSeenAt: 'TS',
    });
  });

  it('patches the link to created with the issue number/url', () => {
    expect(buildSignInIssueLinkCreated({ number: 7, url: 'https://example/issues/7' })).toEqual({
      status: 'created',
      issueNumber: 7,
      issueUrl: 'https://example/issues/7',
    });
  });

  it('builds an increment patch that bumps count and lastSeenAt only', () => {
    const patch = buildSignInIssueLinkIncrement('INC', () => 'TS');
    expect(patch).toEqual({ count: 'INC', lastSeenAt: 'TS' });
    // Never re-writes firstSeenAt, issueNumber, or status.
    expect(Object.keys(patch).sort()).toEqual(['count', 'lastSeenAt']);
  });
});

// ---------------------------------------------------------------------------
// Public GitHub issue
// ---------------------------------------------------------------------------

describe('buildSignInIssueTitle', () => {
  it('tags with [Sign-in] and uses the error code', () => {
    expect(buildSignInIssueTitle(report)).toBe('[Sign-in] GetCredentialException');
  });

  it('falls back to the reason, then a generic label', () => {
    expect(buildSignInIssueTitle({ ...report, errorCode: null })).toBe(
      '[Sign-in] Sign-in failed: GetCredentialException',
    );
  });
});

describe('buildSignInIssueBody', () => {
  it('includes the sanitized fields, fingerprint, first-seen and count', () => {
    const body = buildSignInIssueBody(report, meta);
    expect(body).toContain('- Error code/type: GetCredentialException');
    expect(body).toContain('- Reason: Sign-in failed: GetCredentialException');
    expect(body).toContain('- App version: 1.4.0');
    expect(body).toContain('- Build number: 42');
    expect(body).toContain('- OS version: Android 14 (API 34)');
    expect(body).toContain('- Device model: Google Pixel 8');
    expect(body).toContain(`- Fingerprint: ${fingerprint}`);
    expect(body).toContain('- First seen: 2026-07-09T12:00:00.000Z');
    expect(body).toContain('- Occurrences: 1');
  });

  it('renders unknown for absent context', () => {
    const body = buildSignInIssueBody(
      { ...report, errorCode: null, appVersion: null, osVersion: null, deviceModel: null },
      meta,
    );
    expect(body).toContain('- Error code/type: unknown');
    expect(body).toContain('- App version: unknown');
    expect(body).toContain('- Device model: unknown');
  });
});

describe('public payload safety (world-readable repo)', () => {
  it('labels every issue sign-in-failure + auto-generated', () => {
    expect(buildSignInIssuePayload(report, meta).labels).toEqual([
      SIGN_IN_ISSUE_LABEL,
      AUTO_GENERATED_LABEL,
    ]);
    expect(SIGN_IN_ISSUE_LABEL).toBe('sign-in-failure');
    expect(AUTO_GENERATED_LABEL).toBe('auto-generated');
  });

  it('NEVER leaks a uid, email, token, or raw exception message', () => {
    // Even if a hostile UNAUTHENTICATED client stuffs PII-shaped strings into
    // the sanitized-reason/context fields, the payload must not surface a uid,
    // an email, or a token. (The extractor + the diagnostics callable strip
    // these upstream; this asserts the builder adds none of its own.)
    const serialized = JSON.stringify(buildSignInIssuePayload(report, meta)).toLowerCase();
    expect(serialized).not.toContain('uid');
    expect(serialized).not.toContain('@');
    expect(serialized).not.toContain('token');
    expect(serialized).not.toContain('password');
    expect(serialized).not.toContain('userid');
  });

  it('neutralizes @mention / #ref abuse in client-controlled fields', () => {
    // diagnostics.submitReport is unauthenticated, so errorCode/safeMessage/
    // context are attacker-controllable: a crafted @maintainer / #123 must not
    // become a live mention or cross-reference on the public issue.
    const hostile: SignInFailureReport = {
      ...report,
      errorCode: 'Boom @maintainer',
      safeMessage: 'cc @maintainer see #123',
      appVersion: '1.0 @ci',
    };
    const payload = buildSignInIssuePayload(hostile, meta);
    expect(payload.title).toBe(`[Sign-in] Boom @${ZWSP}maintainer`);
    expect(payload.body).toContain(`cc @${ZWSP}maintainer see #${ZWSP}123`);
    expect(payload.body).toContain(`1.0 @${ZWSP}ci`);
  });
});
