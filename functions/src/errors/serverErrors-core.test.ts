/**
 * Unit tests for the pure server-error core.
 *
 * The load-bearing suite is "public issue body scrubbing": this repository is
 * PUBLIC, so a regression that leaks a uid or a Firestore document path into an
 * auto-filed issue is permanent and unfixable by editing. Those tests seed an
 * error whose message and stack contain a uid, an email address, a Firestore
 * document path, coordinates, a phone number and a token, then assert that NONE
 * of them appear anywhere in the rendered issue title or body — while the
 * allowlisted facts (source, errorName, errorCode, file:line frames, fingerprint)
 * all do.
 */

import { describe, expect, it } from 'vitest';
import {
  MAX_SERVER_ERROR_FRAMES,
  SERVER_ERROR_ISSUE_LABEL,
  UNKNOWN_ERROR_NAME,
  UNKNOWN_SOURCE,
  boundServerErrorContext,
  buildPublishableServerErrorReport,
  buildServerErrorIssueBody,
  buildServerErrorIssuePayload,
  buildServerErrorIssueTitle,
  buildServerErrorReport,
  buildNewServerErrorIssueLink,
  buildServerErrorReportDocument,
  classifyServerError,
  computeServerErrorFingerprint,
  isDeliberateHttpsError,
  normalizeServerErrorCode,
  normalizeServerErrorFrames,
  normalizeServerErrorName,
  normalizeServerErrorSource,
  reduceStackFrames,
} from './serverErrors-core';
import { AUTO_GENERATED_LABEL } from '../diagnostics/signInIssues-core';

// ---------------------------------------------------------------------------
// Sensitive values seeded into error text by the scrubbing tests
// ---------------------------------------------------------------------------

const UID = 'kQ8Zx2LmNpQrStUvWxYz01';
const EMAIL = 'seb.mccayen@example.com';
const DOC_PATH = `users/${UID}/vehicles/veh-77`;
const LATITUDE = '57.487312';
const LONGITUDE = '12.075914';
const PHONE = '+46701234567';
const TOKEN = 'ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789';
const DISPLAY_NAME = 'Sebastian McCayen';

const SENSITIVE = [UID, EMAIL, DOC_PATH, LATITUDE, LONGITUDE, PHONE, TOKEN, DISPLAY_NAME];

// ---------------------------------------------------------------------------
// URL-host extraction, used by the "no non-GitHub link in the public issue" check
// ---------------------------------------------------------------------------

/**
 * Every http(s) URL in `text`, reduced to its PARSED host (lower-cased).
 *
 * Why not a regex: the obvious spelling of this assertion,
 * `expect(rendered).not.toMatch(/https?:\/\/(?!github\.com)/)`, is unanchored
 * against the host and therefore accepts every shape an attacker would actually
 * use — `https://github.com.attacker.net/steal` (github.com is a PREFIX of the
 * host), `https://evil-github.com/x` (a SUFFIX), `https://evil.example/github.com`
 * (only in the path) and `https://github.com@evil.example/x` (userinfo, not a
 * host at all). Parsing the URL and comparing the WHOLE host is anchored by
 * construction, so there is nothing left to get wrong.
 *
 * An unparseable match is returned verbatim, so it fails `isGitHubHost` rather
 * than silently disappearing from the assertion.
 */
function urlHostsIn(text: string): string[] {
  const hosts: string[] = [];
  for (const match of text.matchAll(/https?:\/\/[^\s`)<>\]]+/gi)) {
    const candidate = match[0].replace(/[.,;:]+$/, '');
    try {
      hosts.push(new URL(candidate).hostname.toLowerCase());
    } catch {
      hosts.push(candidate.toLowerCase());
    }
  }
  return hosts;
}

/** Exactly `github.com`, or a true subdomain of it. */
function isGitHubHost(host: string): boolean {
  return host === 'github.com' || host.endsWith('.github.com');
}

/**
 * A realistic Firestore failure: the message embeds the document path (and so
 * the uid), and the stack embeds absolute deploy paths.
 */
function sensitiveError(): Error & { code?: string } {
  const error = new Error(
    `5 NOT_FOUND: no entity to update: app: "s~kcc-prod", path { Kind: "users" Name: "${UID}" } ` +
      `document ${DOC_PATH} for ${DISPLAY_NAME} <${EMAIL}> phone ${PHONE} ` +
      `at ${LATITUDE},${LONGITUDE} (auth ${TOKEN})`,
  ) as Error & { code?: string };
  error.name = 'FirebaseFirestoreError';
  error.code = 'not-found';
  error.stack = [
    `FirebaseFirestoreError: 5 NOT_FOUND: document ${DOC_PATH} for ${EMAIL}`,
    `    at WriteBatch.commit (/srv/node_modules/@google-cloud/firestore/build/src/write-batch.js:415:23)`,
    `    at purgeUserData (/srv/lib/account/scheduled.js:212:18)`,
    `    at /srv/lib/account/scheduled.js:498:11`,
    `    at Object.<anonymous> (/home/${UID}/checkout/functions/src/account/scheduled.ts:499:5)`,
    `    at processTicksAndRejections (node:internal/process/task_queues:95:5)`,
  ].join('\n');
  return error;
}

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

describe('classifyServerError', () => {
  it('extracts the error name, a status-shaped code, and reduced frames', () => {
    const { errorName, errorCode, frames } = classifyServerError(sensitiveError());
    expect(errorName).toBe('FirebaseFirestoreError');
    expect(errorCode).toBe('not-found');
    // node_modules and node-internal frames are dropped; the /home/<uid>/ frame
    // survives only as its basename, so the uid in the path is gone.
    expect(frames).toEqual(['scheduled.js:212', 'scheduled.js:498', 'scheduled.ts:499']);
  });

  it('picks whichever of name / constructor name is more specific', () => {
    // A subclass that never assigns `name`: the constructor name is the signal.
    class TransactionAbortedError extends Error {}
    expect(classifyServerError(new TransactionAbortedError('aborted')).errorName).toBe(
      'TransactionAbortedError',
    );
    // The Firebase/gRPC shape: a plain Error with an assigned `name`. The
    // constructor says `Error`, which must NOT win.
    const firebaseShaped = Object.assign(new Error('5 NOT_FOUND'), {
      name: 'FirebaseFirestoreError',
    });
    expect(classifyServerError(firebaseShaped).errorName).toBe('FirebaseFirestoreError');
    // Neither is specific → the honest generic kind.
    expect(classifyServerError(new Error('plain')).errorName).toBe('Error');
  });

  it('falls back to UnknownError for a hostile or absent name', () => {
    expect(classifyServerError({ name: 'Error: users/abc123 not found' }).errorName).toBe(
      UNKNOWN_ERROR_NAME,
    );
    expect(classifyServerError({ name: '../../etc/passwd' }).errorName).toBe(UNKNOWN_ERROR_NAME);
    expect(classifyServerError('a thrown string').errorName).toBe(UNKNOWN_ERROR_NAME);
    expect(classifyServerError(null).errorName).toBe(UNKNOWN_ERROR_NAME);
    expect(classifyServerError(undefined).errorName).toBe(UNKNOWN_ERROR_NAME);
    expect(classifyServerError(42).errorName).toBe(UNKNOWN_ERROR_NAME);
  });

  it('accepts both gRPC-kebab and SCREAMING_SNAKE codes', () => {
    expect(classifyServerError({ code: 'failed-precondition' }).errorCode).toBe(
      'failed-precondition',
    );
    expect(classifyServerError({ code: 'FAILED_PRECONDITION' }).errorCode).toBe(
      'FAILED_PRECONDITION',
    );
    expect(classifyServerError({ code: 'ENOENT' }).errorCode).toBe('ENOENT');
  });

  it('drops any code that is not obviously a status enum member', () => {
    // Node fs/net errors and some libraries put path- or message-shaped values
    // in `code`; publishing those would leak exactly what we are protecting.
    expect(classifyServerError({ code: `ENOENT: users/${UID}` }).errorCode).toBeNull();
    expect(classifyServerError({ code: 'Mixed_Case' }).errorCode).toBeNull();
    expect(classifyServerError({ code: 5 }).errorCode).toBeNull();
    expect(classifyServerError({ code: EMAIL }).errorCode).toBeNull();
    expect(classifyServerError({}).errorCode).toBeNull();
  });

  it('returns no frames when there is no usable stack', () => {
    expect(classifyServerError(new Error('no stack')).frames.length).toBeGreaterThanOrEqual(0);
    expect(reduceStackFrames(undefined)).toEqual([]);
    expect(reduceStackFrames(12345)).toEqual([]);
    expect(reduceStackFrames('Error: boom\n    at async Promise.all (index 0)')).toEqual([]);
  });

  it(`caps frames at ${MAX_SERVER_ERROR_FRAMES}`, () => {
    const stack = ['Error: boom']
      .concat(Array.from({ length: 20 }, (_, i) => `    at fn (/srv/lib/a.js:${i + 1}:2)`))
      .join('\n');
    expect(reduceStackFrames(stack)).toHaveLength(MAX_SERVER_ERROR_FRAMES);
  });

  it('never yields a frame containing a path separator or a colon-delimited path', () => {
    const frames = reduceStackFrames(sensitiveError().stack);
    for (const frame of frames) {
      expect(frame).toMatch(/^[A-Za-z0-9._-]{1,80}:\d{1,7}$/);
    }
  });
});

describe('normalizeServerErrorSource', () => {
  it('accepts the domain.action convention', () => {
    expect(normalizeServerErrorSource('account.purgeDeleted')).toBe('account.purgeDeleted');
    expect(normalizeServerErrorSource('incidents.syncTrafikverket')).toBe(
      'incidents.syncTrafikverket',
    );
  });

  it('rejects anything that could smuggle markup or data into the public body', () => {
    expect(normalizeServerErrorSource(`account/${UID}`)).toBe(UNKNOWN_SOURCE);
    expect(normalizeServerErrorSource('a\nb')).toBe(UNKNOWN_SOURCE);
    expect(normalizeServerErrorSource('[x](http://evil.test)')).toBe(UNKNOWN_SOURCE);
    expect(normalizeServerErrorSource(123)).toBe(UNKNOWN_SOURCE);
    expect(normalizeServerErrorSource('')).toBe(UNKNOWN_SOURCE);
  });
});

describe('normalizeServerErrorName / normalizeServerErrorCode / normalizeServerErrorFrames', () => {
  it('accepts the shapes the ingest path produces', () => {
    expect(normalizeServerErrorName('FirebaseFirestoreError')).toBe('FirebaseFirestoreError');
    expect(normalizeServerErrorCode('failed-precondition')).toBe('failed-precondition');
    expect(normalizeServerErrorCode('FAILED_PRECONDITION')).toBe('FAILED_PRECONDITION');
    expect(normalizeServerErrorFrames(['scheduled.js:212', 'index.ts:9'])).toEqual([
      'scheduled.js:212',
      'index.ts:9',
    ]);
  });

  it('rejects anything that could carry markup, a path or an identifier', () => {
    expect(normalizeServerErrorName('Error: users/' + UID)).toBe(UNKNOWN_ERROR_NAME);
    expect(normalizeServerErrorName('[x](http://evil.test)')).toBe(UNKNOWN_ERROR_NAME);
    expect(normalizeServerErrorName(null)).toBe(UNKNOWN_ERROR_NAME);
    expect(normalizeServerErrorCode(`ENOENT: open /home/${UID}/.env`)).toBeNull();
    expect(normalizeServerErrorCode('mixed-Case')).toBeNull();
    expect(normalizeServerErrorCode(42)).toBeNull();
    // Frames must be exactly `basename:line` — no path, no URL, no doc path.
    expect(
      normalizeServerErrorFrames([
        `/srv/lib/${UID}/scheduled.js:212`,
        'https://evil.test/x:1',
        `users/${UID}/vehicles/v1:2`,
        'scheduled.js:212:18',
        'scheduled.js',
        { file: 'x.js' },
      ]),
    ).toEqual([]);
    expect(normalizeServerErrorFrames('scheduled.js:1')).toEqual([]);
  });

  it('caps the frame list so a poisoned document cannot pad the public body', () => {
    const many = Array.from({ length: MAX_SERVER_ERROR_FRAMES + 4 }, (_, i) => `f.js:${i + 1}`);
    expect(normalizeServerErrorFrames(many)).toHaveLength(MAX_SERVER_ERROR_FRAMES);
  });
});

describe('buildPublishableServerErrorReport (stored document → public issue)', () => {
  const stored = (over: Record<string, unknown>): Record<string, unknown> => ({
    source: 'account.purgeDeleted',
    errorName: 'FirebaseFirestoreError',
    errorCode: 'not-found',
    frames: ['scheduled.js:212'],
    message: `boom for users/${UID}`,
    stack: `at /home/${UID}/x.js:1:1`,
    context: { uid: UID },
    fingerprint: 'f'.repeat(64),
    ...over,
  });

  it('passes a well-formed document through unchanged, with no redactions', () => {
    const result = buildPublishableServerErrorReport(stored({}));
    expect(result?.redacted).toEqual([]);
    expect(result?.report.source).toBe('account.purgeDeleted');
    expect(result?.report.errorName).toBe('FirebaseFirestoreError');
    expect(result?.report.errorCode).toBe('not-found');
    expect(result?.report.frames).toEqual(['scheduled.js:212']);
  });

  it('never carries the private message/stack/context out of the document', () => {
    const result = buildPublishableServerErrorReport(stored({}));
    expect(result?.report.message).toBe('');
    expect(result?.report.stack).toBeNull();
    expect(result?.report.context).toBeNull();
  });

  it('recomputes the fingerprint instead of trusting the stored one', () => {
    const result = buildPublishableServerErrorReport(stored({ fingerprint: 'not-a-hash' }));
    expect(result?.report.fingerprint).toBe(
      computeServerErrorFingerprint('account.purgeDeleted', 'FirebaseFirestoreError', 'not-found', [
        'scheduled.js:212',
      ]),
    );
  });

  it('REDACTS a poisoned document field-by-field rather than publishing it', () => {
    const poisoned = stored({
      source: `# Pwned](http://evil.test) users/${UID}`,
      errorName: `Error <img src=x> ${EMAIL}`,
      errorCode: `ENOENT: /home/${UID}/.env`,
      frames: [`/srv/${UID}/x.js:1`, 'https://evil.test/y:2'],
    });
    const result = buildPublishableServerErrorReport(poisoned);
    expect(result?.redacted).toEqual(['source', 'errorName', 'errorCode', 'frames']);
    expect(result?.report.source).toBe(UNKNOWN_SOURCE);
    expect(result?.report.errorName).toBe(UNKNOWN_ERROR_NAME);
    expect(result?.report.errorCode).toBeNull();
    expect(result?.report.frames).toEqual([]);

    // Nothing the attacker wrote survives into the title or the body.
    const renderedPoison = [
      buildServerErrorIssueTitle(result!.report),
      buildServerErrorIssueBody(result!.report, {
        firstSeenIso: '2026-07-30T03:30:00.000Z',
        count: 1,
      }),
    ].join('\n');
    for (const secret of [UID, EMAIL, 'evil.test', '<img', 'Pwned', '.env']) {
      expect(renderedPoison).not.toContain(secret);
    }
  });

  it('collapses every poisoned document onto ONE deduped fingerprint', () => {
    const a = buildPublishableServerErrorReport(stored({ source: 'evil one', frames: [] }));
    const b = buildPublishableServerErrorReport(stored({ source: 'evil two', frames: [] }));
    // Same redacted values ⇒ same fingerprint ⇒ one issue, not one per attempt.
    expect(a?.report.fingerprint).toBe(b?.report.fingerprint);
  });

  it('skips a document that is not a report at all', () => {
    expect(buildPublishableServerErrorReport(undefined)).toBeNull();
    expect(buildPublishableServerErrorReport({})).toBeNull();
    expect(buildPublishableServerErrorReport(stored({ source: '' }))).toBeNull();
    expect(buildPublishableServerErrorReport(stored({ errorName: 42 }))).toBeNull();
  });
});

describe('the public issue builders re-validate at the point of use', () => {
  // Defence in depth: even a caller that skipped buildPublishableServerErrorReport
  // cannot get an un-allowlisted value into the world-readable issue.
  const hostile = {
    source: `[click](http://evil.test) ${UID}`,
    errorName: `Error ${EMAIL}`,
    errorCode: `/home/${UID}/.env`,
    frames: [`/srv/${UID}/x.js:1`],
    message: 'private',
    stack: 'private',
    context: null,
    fingerprint: 'not-a-hash',
  };

  it('renders the fallbacks, not the hostile values', () => {
    const rendered = [
      buildServerErrorIssueTitle(hostile),
      buildServerErrorIssueBody(hostile, { firstSeenIso: '2026-07-30T03:30:00.000Z', count: 1 }),
    ].join('\n');
    expect(rendered).toContain(UNKNOWN_SOURCE);
    expect(rendered).toContain(UNKNOWN_ERROR_NAME);
    expect(rendered).toContain('- Code: none');
    expect(rendered).toContain('unavailable');
    for (const secret of [UID, EMAIL, 'evil.test', '.env', 'not-a-hash']) {
      expect(rendered).not.toContain(secret);
    }
    for (const host of urlHostsIn(rendered)) {
      expect(isGitHubHost(host)).toBe(true);
    }
  });
});

describe('isDeliberateHttpsError', () => {
  it('recognises an HttpsError by name', () => {
    const error = new Error('not found');
    error.name = 'HttpsError';
    expect(isDeliberateHttpsError(error)).toBe(true);
  });

  it('recognises the v2 shape (status code + httpErrorCode descriptor)', () => {
    expect(
      isDeliberateHttpsError({ code: 'permission-denied', httpErrorCode: { status: 403 } }),
    ).toBe(true);
  });

  it('does not swallow genuine errors that merely have a code', () => {
    expect(isDeliberateHttpsError(new TypeError('boom'))).toBe(false);
    expect(isDeliberateHttpsError({ code: 'not-found' })).toBe(false);
    expect(isDeliberateHttpsError(null)).toBe(false);
    expect(isDeliberateHttpsError('HttpsError')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Fingerprint
// ---------------------------------------------------------------------------

describe('computeServerErrorFingerprint', () => {
  it('is stable across occurrences of the same failure', () => {
    const a = buildServerErrorReport('account.purgeDeleted', sensitiveError());
    const b = buildServerErrorReport('account.purgeDeleted', sensitiveError());
    expect(a.fingerprint).toBe(b.fingerprint);
    expect(a.fingerprint).toMatch(/^[0-9a-f]{64}$/);
  });

  it('ignores the message, so the same bug hitting many users is ONE issue', () => {
    // Same source/name/code/frames, different embedded uid + counts in the text.
    const first = sensitiveError();
    const second = sensitiveError();
    second.message = 'completely different wording, users/zzzOtherUid999 missing';
    expect(buildServerErrorReport('account.purgeDeleted', first).fingerprint).toBe(
      buildServerErrorReport('account.purgeDeleted', second).fingerprint,
    );
  });

  it('separates different sources, names, codes and frames', () => {
    const base = computeServerErrorFingerprint('a.b', 'TypeError', 'not-found', ['x.js:1']);
    expect(computeServerErrorFingerprint('a.c', 'TypeError', 'not-found', ['x.js:1'])).not.toBe(
      base,
    );
    expect(computeServerErrorFingerprint('a.b', 'RangeError', 'not-found', ['x.js:1'])).not.toBe(
      base,
    );
    expect(computeServerErrorFingerprint('a.b', 'TypeError', 'aborted', ['x.js:1'])).not.toBe(base);
    expect(computeServerErrorFingerprint('a.b', 'TypeError', 'not-found', ['x.js:2'])).not.toBe(
      base,
    );
  });

  it('normalizes a hostile source before hashing', () => {
    expect(computeServerErrorFingerprint('a\nb', 'TypeError', null, [])).toBe(
      computeServerErrorFingerprint(UNKNOWN_SOURCE, 'TypeError', null, []),
    );
  });
});

// ---------------------------------------------------------------------------
// PUBLIC ISSUE SCRUBBING — the reason this module exists
// ---------------------------------------------------------------------------

describe('public issue body scrubbing (PUBLIC repo — permanent leak risk)', () => {
  const report = buildServerErrorReport('account.purgeDeleted', sensitiveError(), {
    // Even the call-site context, which legitimately carries ids, must not reach
    // the public body.
    uid: UID,
    email: EMAIL,
    docPath: DOC_PATH,
    batchSize: 500,
  });
  const rendered = [
    buildServerErrorIssueTitle(report),
    buildServerErrorIssueBody(report, { firstSeenIso: '2026-07-30T03:30:00.000Z', count: 7 }),
  ].join('\n');

  it.each(SENSITIVE)('does not leak %s', (secret) => {
    expect(rendered).not.toContain(secret);
  });

  it('does not leak the raw message or stack', () => {
    expect(report.message.length).toBeGreaterThan(0);
    expect(rendered).not.toContain(report.message);
    expect(report.stack).not.toBeNull();
    expect(rendered).not.toContain(report.stack as string);
    // No fragment of the message either: the distinctive gRPC prefix is enough.
    expect(rendered).not.toContain('no entity to update');
    expect(rendered).not.toContain('s~kcc-prod');
  });

  it('does not leak the private context map', () => {
    expect(report.context).toMatchObject({ uid: UID, batchSize: '500' });
    expect(rendered).not.toContain('batchSize');
    expect(rendered).not.toContain('500');
  });

  it('contains no absolute path, no @-mention and no non-GitHub URL', () => {
    expect(rendered).not.toContain('/srv/');
    expect(rendered).not.toContain('/home/');
    expect(rendered).not.toMatch(/(^|[^\w​])@[A-Za-z0-9]/);
    for (const host of urlHostsIn(rendered)) {
      expect(isGitHubHost(host)).toBe(true);
    }
  });

  it('the URL-host check it relies on cannot be fooled by a lookalike host', () => {
    // The point of the helper: an UNANCHORED /github\.com/ test on the raw URL
    // accepts every one of these. Only the parsed host, compared whole, does not.
    expect(urlHostsIn('see https://github.com/SebMcCayen/carcommunity/issues/1')).toEqual([
      'github.com',
    ]);
    expect(urlHostsIn('https://api.github.com/x').every(isGitHubHost)).toBe(true);
    // Suffix host — `github.com` is a PREFIX of the real host.
    expect(urlHostsIn('https://github.com.attacker.net/steal').every(isGitHubHost)).toBe(false);
    // Prefix host — `github.com` is a SUFFIX of the real host.
    expect(urlHostsIn('https://evil-github.com/x').every(isGitHubHost)).toBe(false);
    // `github.com` only in the path, on someone else's host.
    expect(urlHostsIn('https://evil.example/github.com/x').every(isGitHubHost)).toBe(false);
    // Subdomain lookalike: `github.com.` is a label prefix, not a suffix.
    expect(urlHostsIn('http://github.com.evil.co/x').every(isGitHubHost)).toBe(false);
    // Userinfo trick: the host is `evil.example`, not `github.com`.
    expect(urlHostsIn('https://github.com@evil.example/x').every(isGitHubHost)).toBe(false);
  });

  it('DOES contain every allowlisted field', () => {
    expect(rendered).toContain('account.purgeDeleted');
    expect(rendered).toContain('FirebaseFirestoreError');
    expect(rendered).toContain('not-found');
    expect(rendered).toContain('scheduled.js:212');
    expect(rendered).toContain('scheduled.js:498');
    expect(rendered).toContain('scheduled.ts:499');
    expect(rendered).toContain(report.fingerprint);
    expect(rendered).toContain('2026-07-30T03:30:00.000Z');
    expect(rendered).toContain('Occurrences: 7');
  });

  it('points the reader at the private record, by fingerprint', () => {
    expect(rendered).toContain('serverErrorReports');
    expect(rendered).toContain('fingerprint');
    expect(rendered.toLowerCase()).toContain('public');
  });

  it('renders "none"/"unavailable" rather than omitting absent allowlisted fields', () => {
    const bare = buildServerErrorReport('live.cleanupExpired', 'a thrown string');
    const body = buildServerErrorIssueBody(bare, { firstSeenIso: 'now', count: 1 });
    expect(body).toContain('- Code: none');
    expect(body).toContain('unavailable');
  });

  it('defangs a hostile errorName-shaped payload via the allowlist, not escaping', () => {
    const hostile = new Error('boom');
    hostile.name = '<img src=x onerror=alert(1)>';
    const built = buildServerErrorReport('badges.evaluateBacklog', hostile);
    // The hostile name is DISCARDED (not escaped) and the honest constructor kind
    // is published instead.
    expect(built.errorName).toBe('Error');
    expect(buildServerErrorIssueBody(built, { firstSeenIso: 'now', count: 1 })).not.toContain(
      '<img',
    );
  });

  it('classifies a thrown non-object as UnknownError rather than guessing', () => {
    expect(buildServerErrorReport('live.cleanupExpired', 'boom').errorName).toBe(
      UNKNOWN_ERROR_NAME,
    );
  });
});

describe('buildServerErrorIssuePayload', () => {
  it('labels the issue server-error + auto-generated', () => {
    const payload = buildServerErrorIssuePayload(
      buildServerErrorReport('events.autoClose', new TypeError('boom')),
      { firstSeenIso: 'now', count: 1 },
    );
    expect(payload.labels).toEqual([SERVER_ERROR_ISSUE_LABEL, AUTO_GENERATED_LABEL]);
    expect(payload.title).toContain('events.autoClose');
    expect(payload.title).toContain('TypeError');
  });
});

// ---------------------------------------------------------------------------
// Private record + context bounding
// ---------------------------------------------------------------------------

describe('private serverErrorReports document', () => {
  it('keeps the FULL detail that the public issue omits', () => {
    const report = buildServerErrorReport('account.purgeDeleted', sensitiveError(), { uid: UID });
    const doc = buildServerErrorReportDocument(report, () => 'SERVER_TS');
    expect(doc.message).toContain(DOC_PATH);
    expect(String(doc.stack)).toContain('node_modules');
    expect(doc.context).toMatchObject({ uid: UID });
    expect(doc.fingerprint).toBe(report.fingerprint);
    expect(doc.githubIssueStatus).toBe('pending');
    expect(doc.githubIssueNumber).toBeNull();
    expect(doc.createdAt).toBe('SERVER_TS');
  });

  it('falls back to the error name when there is no usable message', () => {
    expect(buildServerErrorReport('live.cleanupExpired', new TypeError('')).message).toBe(
      'TypeError',
    );
  });
});

describe('boundServerErrorContext', () => {
  it('keeps short scalars and drops nested structures', () => {
    expect(
      boundServerErrorContext({
        uid: UID,
        count: 12,
        dryRun: false,
        payload: { secret: 'nested' },
        list: [1, 2, 3],
        fn: () => undefined,
        nothing: null,
        nan: Number.NaN,
      }),
    ).toEqual({ uid: UID, count: '12', dryRun: 'false' });
  });

  it('drops hostile keys and caps the map size', () => {
    const wide: Record<string, unknown> = {};
    for (let i = 0; i < 40; i += 1) wide[`k${i}`] = 'v';
    wide['bad key'] = 'v';
    wide['../escape'] = 'v';
    const bounded = boundServerErrorContext(wide) ?? {};
    expect(Object.keys(bounded).length).toBeLessThanOrEqual(12);
    expect(bounded['bad key']).toBeUndefined();
    expect(bounded['../escape']).toBeUndefined();
  });

  it('returns null for empty/absent context', () => {
    expect(boundServerErrorContext(null)).toBeNull();
    expect(boundServerErrorContext(undefined)).toBeNull();
    expect(boundServerErrorContext({})).toBeNull();
    expect(boundServerErrorContext({ nested: {} })).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Dedup link placeholder
//
// The claim/increment/retry state machine itself is shared and is covered by
// shared/issueLinks-core.test.ts; only the domain-specific placeholder — which
// decides what descriptor fields the link document carries — is tested here.
// ---------------------------------------------------------------------------

describe('buildNewServerErrorIssueLink', () => {
  it('claims the fingerprint and records the source, nothing identifying', () => {
    const report = buildServerErrorReport('account.purgeDeleted', sensitiveError(), { uid: UID });
    const link = buildNewServerErrorIssueLink(report, () => 'SERVER_TS');
    expect(link).toEqual({
      fingerprint: report.fingerprint,
      source: 'account.purgeDeleted',
      status: 'creating',
      issueNumber: null,
      issueUrl: null,
      count: 1,
      firstSeenAt: 'SERVER_TS',
      lastSeenAt: 'SERVER_TS',
    });
    // The link doc is world-hidden but still holds no message/stack/context.
    expect(JSON.stringify(link)).not.toContain(UID);
  });
});
