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
  buildServerErrorIssueBody,
  buildServerErrorIssuePayload,
  buildServerErrorIssueTitle,
  buildServerErrorReport,
  buildServerErrorReportDocument,
  classifyServerError,
  computeServerErrorFingerprint,
  decideServerErrorIssueAction,
  isDeliberateHttpsError,
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

  it('contains no absolute path, no @-mention and no bare URL', () => {
    expect(rendered).not.toContain('/srv/');
    expect(rendered).not.toContain('/home/');
    expect(rendered).not.toMatch(/(^|[^\w​])@[A-Za-z0-9]/);
    expect(rendered).not.toMatch(/https?:\/\/(?!github\.com)/);
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
// Dedup decision (delegated to the shared state machine)
// ---------------------------------------------------------------------------

describe('decideServerErrorIssueAction', () => {
  it('creates on first occurrence and retries a failed link', () => {
    expect(decideServerErrorIssueAction(null)).toBe('create');
    expect(decideServerErrorIssueAction({ status: 'failed', count: 4 })).toBe('create');
  });

  it('increments for an in-flight or already-created link', () => {
    expect(decideServerErrorIssueAction({ status: 'creating', count: 1 })).toBe('increment');
    expect(decideServerErrorIssueAction({ status: 'created', count: 9 })).toBe('increment');
  });
});
