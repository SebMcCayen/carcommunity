/**
 * Unit coverage for the live Trafikverket fetcher's resilience paths
 * (issue #664): a hung/slow API is bounded by a fetch timeout, and a non-JSON
 * 200 (HTML maintenance/error page, truncated body) throws a *diagnosable*
 * error carrying a body snippet — instead of an opaque JSON parse error that
 * files as a bare `Error` in the server-error reports.
 *
 * Pure unit test: global `fetch` is stubbed, so it never hits the live API and
 * never touches Firestore. `../firebase` is mocked so importing the entry-point
 * module does not initialise the Admin SDK (getDatabase() would otherwise throw
 * without a database URL).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../firebase', () => ({
  db: {},
  adminAuth: {},
  adminStorage: {},
  adminRtdb: {},
}));

import { FETCH_TIMEOUT_MS, httpFetcher } from './trafikverket';

const okHeaders = { 'content-type': 'application/json' };

function stubFetch(impl: typeof fetch): void {
  vi.stubGlobal('fetch', vi.fn(impl));
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** Invokes the fetcher expecting rejection and returns the thrown Error. */
async function captureError(authKey: string): Promise<Error> {
  try {
    await httpFetcher(authKey);
  } catch (e) {
    return e as Error;
  }
  throw new Error('expected httpFetcher to reject, but it resolved');
}

describe('httpFetcher (Trafikverket live fetcher)', () => {
  it('throws a descriptive non-JSON error with a body snippet on a non-JSON 200', async () => {
    const body =
      '<html><head><title>503 Service Unavailable</title></head>' +
      '<body>The Trafikverket API is temporarily down for maintenance.</body></html>';
    stubFetch(async () => new Response(body, { status: 200, headers: { 'content-type': 'text/html' } }));

    const err = await captureError('secret-key');
    expect(err.message).toMatch(/Trafikverket API returned non-JSON body:/);
    // The snippet is drawn from the RESPONSE body only — never the auth key,
    // which lives solely in the request body.
    expect(err.message).toMatch(/503 Service Unavailable/);
    // The underlying parse error is preserved on `cause` for full context in
    // the server-error report.
    expect(err.cause).toBeInstanceOf(SyntaxError);
  });

  it('caps the non-JSON snippet at 200 characters', async () => {
    const body = 'x'.repeat(500);
    stubFetch(async () => new Response(body, { status: 200, headers: { 'content-type': 'text/plain' } }));

    const err = await captureError('secret-key');
    expect(err).toBeInstanceOf(Error);
    const snippet = err.message.replace('Trafikverket API returned non-JSON body: ', '');
    expect(snippet.length).toBe(200);
  });

  it('never echoes the auth key in the non-JSON error (no request-body leak)', async () => {
    const authKey = 'super-secret-auth-key-12345';
    stubFetch(async () => new Response('not json at all', { status: 200, headers: { 'content-type': 'text/plain' } }));

    const err = await captureError(authKey);
    expect(err.message).not.toContain(authKey);
  });

  it('throws a status error (not a parse error) on a non-OK response', async () => {
    stubFetch(async () => new Response('nope', { status: 500, headers: { 'content-type': 'text/plain' } }));

    await expect(httpFetcher('secret-key')).rejects.toThrow('Trafikverket API responded 500');
  });

  it('parses and returns a valid JSON body', async () => {
    const payload = { RESPONSE: { RESULT: [{ Situation: [] }] } };
    stubFetch(async () => new Response(JSON.stringify(payload), { status: 200, headers: okHeaders }));

    await expect(httpFetcher('secret-key')).resolves.toEqual(payload);
  });

  it('bounds the request with its own AbortSignal.timeout and surfaces the abort as a reportable TimeoutError', async () => {
    // vitest/sinon fake timers do NOT drive AbortSignal.timeout (it is not
    // backed by the mockable setTimeout — verified empirically), so we cannot
    // advance a fake clock to fire the real 30s signal. Instead we spy on
    // AbortSignal.timeout to (a) assert httpFetcher requests exactly the 30s
    // bound, and (b) substitute a fast REAL timeout signal so the SAME
    // abort → TimeoutError path fires without a 30s wait. The DOMException a
    // short AbortSignal.timeout raises is byte-for-byte the one the 30s signal
    // would raise, so the assertion is faithful. This test has teeth: removing
    // the `signal: AbortSignal.timeout(...)` line from httpFetcher makes the
    // spy assertion fail (and the fetch never receives a signal to abort).
    const requestedTimeouts: number[] = [];
    const realTimeout = AbortSignal.timeout.bind(AbortSignal);
    const timeoutSpy = vi
      .spyOn(AbortSignal, 'timeout')
      .mockImplementation((ms: number) => {
        requestedTimeouts.push(ms);
        return realTimeout(5); // fast real signal, same TimeoutError semantics
      });

    // The stub honors the exact signal httpFetcher passes it: it rejects with
    // that signal's own abort reason when it fires — i.e. the internal signal,
    // not one supplied by the test to fetch.
    stubFetch(
      (_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          const signal = init?.signal;
          if (!signal) {
            // No signal wired (e.g. the `signal:` line was removed) — reject
            // fast so the spy assertion below fails cleanly instead of hanging.
            reject(new Error('httpFetcher passed no AbortSignal to fetch'));
            return;
          }
          signal.addEventListener('abort', () => reject(signal.reason as Error));
        }),
    );

    const err = await captureError('secret-key');
    expect(timeoutSpy).toHaveBeenCalledWith(FETCH_TIMEOUT_MS);
    expect(requestedTimeouts).toContain(FETCH_TIMEOUT_MS);
    expect(err).toBeInstanceOf(Error);
    expect((err as DOMException).name).toBe('TimeoutError');
  });
});
