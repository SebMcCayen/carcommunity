/**
 * Unit tests for the sign-in-failure → GitHub issue pure logic
 * (signInIssues-core.ts). No emulators. The public-body PII-exclusion
 * assertions are the point — the auto-filed GitHub issue is world-readable and
 * the source reports are UNAUTHENTICATED (there is no uid to leak, and there
 * must never be one). Because the endpoint is unauthenticated, EVERY report
 * field is attacker-controllable, so the server must whitelist the exception
 * type and never echo client free-text (safeMessage) into the public issue.
 */

import { describe, expect, it } from 'vitest';
import {
  AUTO_GENERATED_LABEL,
  KNOWN_SIGN_IN_EXCEPTION_TYPES,
  SIGN_IN_FEATURE_AREA,
  SIGN_IN_ISSUE_LABEL,
  SIGN_IN_ISSUE_STALE_CREATING_MS,
  SIGN_IN_PUBLIC_REASON,
  UNKNOWN_ERROR_TYPE,
  bucketExceptionType,
  buildNewSignInIssueLink,
  buildSignInIssueBody,
  buildSignInIssueLinkCreated,
  buildSignInIssueLinkFailed,
  buildSignInIssueLinkIncrement,
  buildSignInIssueLinkRetry,
  buildSignInIssuePayload,
  buildSignInIssueTitle,
  computeSignInFingerprint,
  decideSignInIssueAction,
  extractSignInFailureReport,
  validateExceptionType,
  type SignInFailureReport,
  type SignInIssueLink,
} from '../diagnostics/signInIssues-core';

const ZWSP = '​';

const fingerprint = computeSignInFingerprint('GetCredentialException');

const report: SignInFailureReport = {
  errorType: 'GetCredentialException',
  appVersion: '1.4.0',
  buildNumber: '42',
  osVersion: 'Android 14 (API 34)',
  deviceModel: 'Google Pixel 8',
  fingerprint,
};

const meta = { firstSeenIso: '2026-07-09T12:00:00.000Z', count: 1 };

// ---------------------------------------------------------------------------
// Exception-type whitelist (the server-side PII control)
// ---------------------------------------------------------------------------

describe('validateExceptionType', () => {
  it('accepts a simple or fully-qualified class-name token', () => {
    expect(validateExceptionType('GetCredentialException')).toBe('GetCredentialException');
    expect(validateExceptionType('SignInException')).toBe('SignInException');
    expect(validateExceptionType('com.google.FirebaseAuthException')).toBe(
      'com.google.FirebaseAuthException',
    );
    expect(validateExceptionType('  Trimmed_Exception$Inner  ')).toBe('Trimmed_Exception$Inner');
  });

  it('collapses anything that is not a class-name token to Unknown', () => {
    expect(validateExceptionType(null)).toBe(UNKNOWN_ERROR_TYPE);
    expect(validateExceptionType(undefined)).toBe(UNKNOWN_ERROR_TYPE);
    expect(validateExceptionType('')).toBe(UNKNOWN_ERROR_TYPE);
    expect(validateExceptionType(42 as unknown)).toBe(UNKNOWN_ERROR_TYPE);
    // Free text / PII must never survive.
    expect(validateExceptionType('attacker@evil.com')).toBe(UNKNOWN_ERROR_TYPE);
    expect(validateExceptionType('cc @maintainer see #123')).toBe(UNKNOWN_ERROR_TYPE);
    expect(validateExceptionType('Sign-in failed: something')).toBe(UNKNOWN_ERROR_TYPE);
    expect(validateExceptionType('has spaces')).toBe(UNKNOWN_ERROR_TYPE);
    // Over-length rejected (bounded).
    expect(validateExceptionType('A'.repeat(101))).toBe(UNKNOWN_ERROR_TYPE);
  });
});

describe('computeSignInFingerprint (dedup from validated type only)', () => {
  it('is stable for the same validated type regardless of other client context', () => {
    // Two valid reports with the SAME exception type but DIFFERENT client context
    // (appVersion/buildNumber/deviceModel) must dedup to the SAME issue — the
    // fingerprint is derived from the validated type only, so it cannot be
    // polluted by any other client-controlled field.
    const a = extractSignInFailureReport({
      featureArea: SIGN_IN_FEATURE_AREA,
      platform: 'android',
      severity: 'error',
      safeMessage: 'Sign-in failed: GetCredentialException',
      errorCode: 'GetCredentialException',
      appVersion: '1.4.0',
      buildNumber: '42',
    });
    const b = extractSignInFailureReport({
      featureArea: SIGN_IN_FEATURE_AREA,
      platform: 'android',
      severity: 'error',
      safeMessage: 'Sign-in failed: GetCredentialException',
      errorCode: 'GetCredentialException',
      appVersion: '9.9.9',
      buildNumber: '1',
      metadata: { deviceModel: 'Other Device' },
    });
    expect(a?.fingerprint).toBe(b?.fingerprint);
    expect(a?.fingerprint).toBe(computeSignInFingerprint('GetCredentialException'));
  });

  it('differs for different validated types', () => {
    expect(computeSignInFingerprint('GetCredentialException')).not.toBe(
      computeSignInFingerprint('SignInException'),
    );
  });
});

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
    // A client-provided fingerprint is deliberately IGNORED (recomputed).
    fingerprint: 'client-provided-value-that-must-be-ignored',
  };

  it('extracts the validated view including deviceModel, recomputing the fingerprint', () => {
    expect(extractSignInFailureReport(rawDoc)).toEqual(report);
  });

  it('returns null for a non-sign-in featureArea (cheap no-op)', () => {
    expect(extractSignInFailureReport({ ...rawDoc, featureArea: 'map' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, featureArea: 'auth' })).toBeNull();
  });

  it('rejects a non-android platform (only the Android reporter may file issues)', () => {
    expect(extractSignInFailureReport({ ...rawDoc, platform: 'ios' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, platform: 'web' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, platform: undefined })).toBeNull();
  });

  it('rejects a non-error severity', () => {
    expect(extractSignInFailureReport({ ...rawDoc, severity: 'info' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, severity: 'critical' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, severity: undefined })).toBeNull();
  });

  it('rejects an off-format errorCode instead of filing an Unknown issue', () => {
    // A hostile UNAUTHENTICATED caller stuffs an email into errorCode + PII into
    // safeMessage. The strict gate REJECTS the whole report (no issue filed at
    // all — safer than an `Unknown` bucket), and nothing hostile is surfaced.
    const result = extractSignInFailureReport({
      ...rawDoc,
      safeMessage: 'my email is victim@example.com and phone 0700000000',
      errorCode: 'attacker@evil.com',
    });
    expect(result).toBeNull();
    // Other off-format codes are rejected too.
    expect(extractSignInFailureReport({ ...rawDoc, errorCode: 'has spaces' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, errorCode: '' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, errorCode: undefined })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, errorCode: 'A'.repeat(101) })).toBeNull();
  });

  it('rejects a safeMessage that is not exactly `Sign-in failed: <errorCode>`', () => {
    // safeMessage must match the real reporter's format byte-for-byte given the
    // errorCode; a mismatch (extra text, wrong code, empty, missing) is rejected.
    expect(
      extractSignInFailureReport({
        ...rawDoc,
        safeMessage: 'Sign-in failed: GetCredentialException (attempt 1)',
      }),
    ).toBeNull();
    expect(
      extractSignInFailureReport({ ...rawDoc, safeMessage: 'Sign-in failed: OtherException' }),
    ).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, safeMessage: 'totally unrelated' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, safeMessage: '' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, safeMessage: '   ' })).toBeNull();
    expect(extractSignInFailureReport({ ...rawDoc, safeMessage: undefined })).toBeNull();
  });

  it('ignores the client fingerprint (recomputed server-side)', () => {
    // fingerprint is no longer trusted from the client — recomputed server-side.
    expect(extractSignInFailureReport({ ...rawDoc, fingerprint: undefined })).toEqual(report);
    expect(extractSignInFailureReport(null)).toBeNull();
    expect(extractSignInFailureReport(undefined)).toBeNull();
  });

  it('tolerates absent optionals and non-object metadata on an otherwise valid report', () => {
    // Minimal valid report: required gate fields + a valid ALLOWLISTED errorCode
    // with the matching safeMessage, but no appVersion/buildNumber/osVersion/metadata.
    const result = extractSignInFailureReport({
      featureArea: SIGN_IN_FEATURE_AREA,
      platform: 'android',
      severity: 'error',
      errorCode: 'SignInFailedException',
      safeMessage: 'Sign-in failed: SignInFailedException',
    });
    expect(result).toEqual({
      errorType: 'SignInFailedException',
      appVersion: null,
      buildNumber: null,
      osVersion: null,
      deviceModel: null,
      fingerprint: computeSignInFingerprint('SignInFailedException'),
    });
  });

  it('keeps an ALLOWLISTED exception type as its own bucket/fingerprint', () => {
    const result = extractSignInFailureReport({
      featureArea: SIGN_IN_FEATURE_AREA,
      platform: 'android',
      severity: 'error',
      errorCode: 'FirebaseAuthInvalidCredentialsException',
      safeMessage: 'Sign-in failed: FirebaseAuthInvalidCredentialsException',
    });
    expect(result?.errorType).toBe('FirebaseAuthInvalidCredentialsException');
    expect(result?.fingerprint).toBe(
      computeSignInFingerprint('FirebaseAuthInvalidCredentialsException'),
    );
  });

  it('collapses a valid-but-NON-allowlisted token to the single Unknown bucket', () => {
    // Anti-abuse: a valid class-name token that is not on the allowlist must not
    // get its own public issue — it maps to the `Unknown` bucket. safeMessage is
    // still checked against the RAW client token (self-consistency), while the
    // PUBLIC errorType/fingerprint use the bucketed `Unknown` type.
    const result = extractSignInFailureReport({
      featureArea: SIGN_IN_FEATURE_AREA,
      platform: 'android',
      severity: 'error',
      errorCode: 'A0Exception',
      safeMessage: 'Sign-in failed: A0Exception',
    });
    expect(result?.errorType).toBe(UNKNOWN_ERROR_TYPE);
    expect(result?.fingerprint).toBe(computeSignInFingerprint(UNKNOWN_ERROR_TYPE));
  });

  it('maps TWO distinct non-allowlisted tokens to the SAME fingerprint (bounded)', () => {
    // The core anti-abuse property: an unauthenticated caller cycling through
    // distinct fabricated tokens cannot spawn distinct public issues — they all
    // collapse to one `Unknown` bucket, so the distinct-issue count is bounded.
    const mk = (code: string) =>
      extractSignInFailureReport({
        featureArea: SIGN_IN_FEATURE_AREA,
        platform: 'android',
        severity: 'error',
        errorCode: code,
        safeMessage: `Sign-in failed: ${code}`,
      });
    const a = mk('A0Exception');
    const b = mk('A1Exception');
    expect(a?.errorType).toBe(UNKNOWN_ERROR_TYPE);
    expect(b?.errorType).toBe(UNKNOWN_ERROR_TYPE);
    expect(a?.fingerprint).toBe(b?.fingerprint);
  });

  it('buckets a fully-qualified allowlisted token by its simple name', () => {
    // com.google.firebase.auth.FirebaseAuthException collapses onto the same
    // bucket as the bare simple name, so qualified/unqualified do not fork issues.
    const result = extractSignInFailureReport({
      featureArea: SIGN_IN_FEATURE_AREA,
      platform: 'android',
      severity: 'error',
      errorCode: 'com.google.firebase.auth.FirebaseAuthException',
      safeMessage: 'Sign-in failed: com.google.firebase.auth.FirebaseAuthException',
    });
    expect(result?.errorType).toBe('FirebaseAuthException');
    expect(result?.fingerprint).toBe(computeSignInFingerprint('FirebaseAuthException'));
  });
});

describe('bucketExceptionType (anti-abuse allowlist + single bucket)', () => {
  it('returns an allowlisted simple class name unchanged', () => {
    expect(bucketExceptionType('GetCredentialException')).toBe('GetCredentialException');
    expect(bucketExceptionType('FirebaseAuthException')).toBe('FirebaseAuthException');
    expect(bucketExceptionType('ApiException')).toBe('ApiException');
  });

  it('collapses the simple name of a fully-qualified allowlisted token', () => {
    expect(bucketExceptionType('com.google.firebase.auth.FirebaseAuthException')).toBe(
      'FirebaseAuthException',
    );
  });

  it('collapses any non-allowlisted valid token to the single Unknown bucket', () => {
    expect(bucketExceptionType('A0Exception')).toBe(UNKNOWN_ERROR_TYPE);
    expect(bucketExceptionType('A1Exception')).toBe(UNKNOWN_ERROR_TYPE);
    expect(bucketExceptionType('SomeRandomException')).toBe(UNKNOWN_ERROR_TYPE);
    expect(bucketExceptionType('com.evil.Nope')).toBe(UNKNOWN_ERROR_TYPE);
  });

  it('the allowlist is non-empty and every entry buckets to itself', () => {
    expect(KNOWN_SIGN_IN_EXCEPTION_TYPES.size).toBeGreaterThan(0);
    for (const type of KNOWN_SIGN_IN_EXCEPTION_TYPES) {
      expect(bucketExceptionType(type)).toBe(type);
    }
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

  it('increments when a link is in flight (creating) or done (created)', () => {
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

  it('re-creates (retries) when a prior attempt is marked failed', () => {
    const failed: SignInIssueLink = {
      fingerprint,
      status: 'failed',
      issueNumber: null,
      issueUrl: null,
      count: 4,
    };
    expect(decideSignInIssueAction(failed)).toBe('create');
  });

  it('increments a FRESH creating claim but repairs a STALE one', () => {
    const creating: SignInIssueLink = {
      fingerprint,
      status: 'creating',
      issueNumber: null,
      issueUrl: null,
      count: 2,
    };
    const now = 1_000_000_000_000;
    // Fresh in-flight claim (within the stale window) → increment, so concurrent
    // creations never double-file.
    expect(
      decideSignInIssueAction(creating, {
        nowMs: now,
        lastActivityMs: now - (SIGN_IN_ISSUE_STALE_CREATING_MS - 1),
      }),
    ).toBe('increment');
    // Stranded claim (past the stale window) → create (repair retry) so the
    // dedup index is not stuck in-flight forever.
    expect(
      decideSignInIssueAction(creating, {
        nowMs: now,
        lastActivityMs: now - (SIGN_IN_ISSUE_STALE_CREATING_MS + 1),
      }),
    ).toBe('create');
    // No timestamp available → treated as fresh (never re-file on garbage).
    expect(
      decideSignInIssueAction(creating, { nowMs: now, lastActivityMs: null }),
    ).toBe('increment');
    // No context at all (pure default) → creating is always an increment.
    expect(decideSignInIssueAction(creating)).toBe('increment');
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

  it('builds a retry patch that re-claims (creating) and counts this occurrence', () => {
    const patch = buildSignInIssueLinkRetry('INC', () => 'TS');
    expect(patch).toEqual({ status: 'creating', count: 'INC', lastSeenAt: 'TS' });
    // Never resets firstSeenAt / issueNumber (tally is preserved).
    expect(Object.keys(patch).sort()).toEqual(['count', 'lastSeenAt', 'status']);
  });

  it('builds a failed patch that only flips status (preserving the tally)', () => {
    const patch = buildSignInIssueLinkFailed();
    expect(patch).toEqual({ status: 'failed' });
    // Must NOT touch count / lastSeenAt / firstSeenAt, so concurrent occurrences
    // recorded before the failure are never lost.
    expect(Object.keys(patch)).toEqual(['status']);
  });
});

// ---------------------------------------------------------------------------
// Public GitHub issue
// ---------------------------------------------------------------------------

describe('buildSignInIssueTitle', () => {
  it('tags with [Sign-in] and uses the validated error type', () => {
    expect(buildSignInIssueTitle(report)).toBe('[Sign-in] GetCredentialException');
  });

  it('uses the Unknown placeholder when the type failed validation', () => {
    expect(buildSignInIssueTitle({ ...report, errorType: UNKNOWN_ERROR_TYPE })).toBe(
      '[Sign-in] Unknown',
    );
  });
});

describe('buildSignInIssueBody', () => {
  it('includes the validated type, a FIXED reason, context, fingerprint and count', () => {
    const body = buildSignInIssueBody(report, meta);
    expect(body).toContain('- Error type: GetCredentialException');
    expect(body).toContain(`- Reason: ${SIGN_IN_PUBLIC_REASON}`);
    // Client context scalars are wrapped in inline-code spans (markdown-safe).
    expect(body).toContain('- App version: `1.4.0`');
    expect(body).toContain('- Build number: `42`');
    expect(body).toContain('- OS version: `Android 14 (API 34)`');
    expect(body).toContain('- Device model: `Google Pixel 8`');
    expect(body).toContain(`- Fingerprint: ${fingerprint}`);
    expect(body).toContain('- First seen: 2026-07-09T12:00:00.000Z');
    expect(body).toContain('- Occurrences: 1');
  });

  it('renders Unknown/unknown for an absent type and context', () => {
    const body = buildSignInIssueBody(
      { ...report, errorType: UNKNOWN_ERROR_TYPE, appVersion: null, osVersion: null, deviceModel: null },
      meta,
    );
    expect(body).toContain('- Error type: Unknown');
    // Absent scalars render as the bare word `unknown` (no code span, no value).
    expect(body).toContain('- App version: unknown');
    expect(body).toContain('- Device model: unknown');
  });

  it('neutralizes an attacker-controlled markdown LINK in a context scalar', () => {
    // On the unauthenticated endpoint appVersion/deviceModel are attacker-set.
    // A markdown link/image must NOT render clickable in the world-readable body:
    // wrapping the value in an inline-code span makes markdown treat it literally.
    const hostile: SignInFailureReport = {
      ...report,
      appVersion: '[click me](http://evil.example)',
      deviceModel: '![img](http://evil.example/x.png)',
    };
    const body = buildSignInIssueBody(hostile, meta);
    // The value survives verbatim but only INSIDE a code span (backtick-wrapped).
    expect(body).toContain('- App version: `[click me](http://evil.example)`');
    expect(body).toContain('- Device model: `![img](http://evil.example/x.png)`');
    // And it never appears as a bare (renderable) link on the line.
    expect(body).not.toContain('- App version: [click me](http://evil.example)');
  });

  it('strips backticks so a scalar cannot break OUT of its code span', () => {
    // A raw backtick is the only char that could escape the inline-code span; it
    // is replaced so the injected `](http://evil)` cannot become a live link.
    const hostile: SignInFailureReport = {
      ...report,
      appVersion: 'v1`](http://evil.example)`x',
    };
    const body = buildSignInIssueBody(hostile, meta);
    expect(body).toContain("- App version: `v1'](http://evil.example)'x`");
    // No raw backtick from the value leaked into the line.
    expect(body).not.toContain('v1`');
  });
});

describe('public payload safety (world-readable repo, unauthenticated source)', () => {
  it('labels every issue sign-in-failure + auto-generated', () => {
    expect(buildSignInIssuePayload(report, meta).labels).toEqual([
      SIGN_IN_ISSUE_LABEL,
      AUTO_GENERATED_LABEL,
    ]);
    expect(SIGN_IN_ISSUE_LABEL).toBe('sign-in-failure');
    expect(AUTO_GENERATED_LABEL).toBe('auto-generated');
  });

  it('NEVER leaks a uid, email, token, or raw exception message', () => {
    const serialized = JSON.stringify(buildSignInIssuePayload(report, meta)).toLowerCase();
    expect(serialized).not.toContain('uid');
    expect(serialized).not.toContain('@');
    expect(serialized).not.toContain('token');
    expect(serialized).not.toContain('password');
    expect(serialized).not.toContain('userid');
  });

  it('files NO issue at all for a hostile report (PII in errorCode/safeMessage)', () => {
    // Full pipeline: a hostile UNAUTHENTICATED report with an email in errorCode
    // and free-text PII in safeMessage. The strict extractor REJECTS it (returns
    // null → the trigger no-ops), so no public issue is ever created — strictly
    // safer than filing an `Unknown` issue, and the attacker text never leaves
    // the private diagnosticsReports doc.
    const raw = {
      featureArea: SIGN_IN_FEATURE_AREA,
      platform: 'android',
      severity: 'error',
      safeMessage: 'contact me at victim@example.com — password is hunter2',
      errorCode: 'phish@evil.com',
      appVersion: '1.4.0',
    };
    expect(extractSignInFailureReport(raw)).toBeNull();
  });

  it('neutralizes @mention / #ref as defence in depth on any included scalar', () => {
    // A validated type can never contain @/#, but the neutralizer still runs on
    // whatever IS included (belt-and-suspenders). Feed a raw errorType with a
    // mention directly to the builder and confirm it is defused, not live.
    const hostile: SignInFailureReport = {
      ...report,
      errorType: 'Boom @maintainer',
      appVersion: '1.0 @ci',
    };
    const payload = buildSignInIssuePayload(hostile, meta);
    expect(payload.title).toBe(`[Sign-in] Boom @${ZWSP}maintainer`);
    expect(payload.body).toContain(`1.0 @${ZWSP}ci`);
  });
});
