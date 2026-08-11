/**
 * Unit tests for drives.save error mapping (drives/saveDrive-errors.ts).
 *
 * Covers the hardening for GitHub #800: an unexpected throw from the handler
 * (e.g. a transient Firestore failure in the users read or the transaction) is
 * logged with triage context and surfaced as a RETRYABLE HttpsError('unavailable')
 * instead of an opaque INTERNAL/500, while deliberate HttpsError outcomes pass
 * through unchanged. No emulators — the mapping is pure of the Admin SDK.
 */

import { describe, expect, it, vi } from 'vitest';
import { HttpsError } from 'firebase-functions/v2/https';
import {
  SAVE_DRIVE_RETRYABLE_MESSAGE,
  mapSaveDriveError,
  type ErrorLogger,
} from '../drives/saveDrive-errors';

const makeLogger = () => ({ error: vi.fn() }) satisfies ErrorLogger;

describe('mapSaveDriveError', () => {
  it('logs an unexpected error and returns a retryable Https(unavailable)', () => {
    const log = makeLogger();
    const cause = new Error('FIRESTORE_UNAVAILABLE: backend unavailable');

    const mapped = mapSaveDriveError(
      cause,
      { uid: 'user-1', sourceSessionId: 'sess-9', pointCount: 1207 },
      log,
    );

    expect(mapped).toBeInstanceOf(HttpsError);
    expect(mapped.code).toBe('unavailable');
    // No internal detail leaks to the client-facing message.
    expect(mapped.message).toBe(SAVE_DRIVE_RETRYABLE_MESSAGE);
    expect(mapped.message).not.toContain('FIRESTORE_UNAVAILABLE');

    // The root cause + triage context is logged server-side (the #800 gap).
    expect(log.error).toHaveBeenCalledTimes(1);
    const [message, data] = log.error.mock.calls[0]!;
    expect(message).toContain('drives.save');
    expect(data).toMatchObject({
      uid: 'user-1',
      sourceSessionId: 'sess-9',
      pointCount: 1207,
      errorMessage: 'FIRESTORE_UNAVAILABLE: backend unavailable',
    });
    expect(typeof data.stack).toBe('string');
  });

  it('passes an existing HttpsError through unchanged and does not log', () => {
    const log = makeLogger();
    const deliberate = new HttpsError('invalid-argument', 'Expected saveDriveRequest.');

    const mapped = mapSaveDriveError(deliberate, { uid: 'user-1' }, log);

    // Same instance, so code + message contract is preserved exactly.
    expect(mapped).toBe(deliberate);
    expect(mapped.code).toBe('invalid-argument');
    expect(log.error).not.toHaveBeenCalled();
  });

  it('preserves a permission-denied HttpsError (member gate) unchanged', () => {
    const log = makeLogger();
    const denied = new HttpsError('permission-denied', 'Member subscription required.');

    expect(mapSaveDriveError(denied, {}, log)).toBe(denied);
    expect(log.error).not.toHaveBeenCalled();
  });

  it('treats a duck-typed HttpsError (v1 / duplicated module) as deliberate', () => {
    const log = makeLogger();
    // Shape isDeliberateHttpsError recognises without an instanceof match.
    const v1Like = Object.assign(new Error('nope'), {
      name: 'HttpsError',
      code: 'not-found',
      httpErrorCode: { canonicalName: 'NOT_FOUND', status: 404 },
    });

    expect(mapSaveDriveError(v1Like, {}, log)).toBe(v1Like);
    expect(log.error).not.toHaveBeenCalled();
  });

  it('stringifies a non-Error throw and still returns retryable', () => {
    const log = makeLogger();

    const mapped = mapSaveDriveError('boom', { uid: 'user-1' }, log);

    expect(mapped.code).toBe('unavailable');
    expect(log.error).toHaveBeenCalledTimes(1);
    const [, data] = log.error.mock.calls[0]!;
    expect(data).toMatchObject({ errorMessage: 'boom', stack: undefined });
  });
});
