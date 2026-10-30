import { describe, expect, it, vi } from 'vitest';
import { HttpsError } from 'firebase-functions/v2/https';
import { handleFeed, feedAdmission } from './http';

function response() {
  return {
    code: 0,
    headers: {} as Record<string, string>,
    body: '',
    ended: false,
    set(name: string, value: string) {
      this.headers[name] = value;
      return this;
    },
    status(code: number) {
      this.code = code;
      return this;
    },
    send(body: string) {
      this.body = body;
    },
    end() {
      this.ended = true;
    },
  };
}
const request = (validator?: string) => ({
  method: 'GET',
  path: '/calendar.ics',
  protocol: 'https',
  query: { token: 'URL_BEARER_SECRET' },
  get: (_name: string) => validator,
});
describe('calendar-provider HTTP boundary', () => {
  it('reauthorizes even matching validators before returning 304 with privacy headers', async () => {
    const fetch = vi.fn().mockResolvedValue({ body: 'CALENDAR', etag: '"revision"' });
    const res = response();
    await handleFeed({ fetch }, request('W/"revision"'), res);
    expect(fetch).toHaveBeenCalledExactlyOnceWith('URL_BEARER_SECRET');
    expect(res.code).toBe(304);
    expect(res.body).toBe('');
    expect(res.headers['Cache-Control']).toBe('private, no-cache, max-age=0, must-revalidate');
    expect(res.headers['Referrer-Policy']).toBe('no-referrer');
    expect(res.headers['Content-Type']).toBe('text/calendar; charset=utf-8');
  });

  it.each(['permission-denied', 'not-found', 'failed-precondition', 'unauthenticated'] as const)(
    '%s has the same generic 404, even with a previously valid ETag',
    async (code) => {
      const res = response();
      await handleFeed(
        {
          fetch: async () => {
            throw new HttpsError(code, 'ROLE USER TOKEN INTERNAL DETAIL');
          },
        },
        request('"revision"'),
        res,
      );
      expect(res.code).toBe(404);
      expect(res.body).toBe('Not found.');
      expect(res.headers['Cache-Control']).toBe('no-store');
      expect(res.headers.ETag).toBeUndefined();
    },
  );

  it.each([
    new Error('TOKEN PATH SDK SECRET'),
    new HttpsError('resource-exhausted', 'SENSITIVE EVENT COUNT'),
  ])(
    'partial/error/overbound result is 503, never 200-empty/304 or detailed data',
    async (failure) => {
      const res = response();
      await handleFeed(
        {
          fetch: async () => {
            throw failure;
          },
        },
        request('*'),
        res,
      );
      expect(res.code).toBe(503);
      expect(res.body).toBe('Calendar temporarily unavailable.');
      expect(res.headers['Cache-Control']).toBe('no-store');
      expect(res.headers['Retry-After']).toBe('3600');
    },
  );

  it('HEAD still reauthorizes; unsupported methods/paths/plaintext never reveal data', async () => {
    const fetch = vi.fn().mockResolvedValue({ body: 'CALENDAR', etag: '"revision"' });
    const head = response();
    await handleFeed({ fetch }, { ...request(), method: 'HEAD' }, head);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(head.code).toBe(200);
    expect(head.body).toBe('');
    for (const fields of [{ method: 'POST' }, { protocol: 'http' }, { path: '/other.ics' }]) {
      const res = response();
      await handleFeed({ fetch }, { ...request(), ...fields }, res);
      expect(res.code).toBe(fields.method ? 405 : 404);
    }
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('bounds per-instance database amplification without storing secrets or issuing cached data', async () => {
    let now = 0;
    const admit = feedAdmission(() => now);
    for (let count = 0; count < 120; count++) expect(admit()).toBe(true);
    const fetch = vi.fn(),
      res = response();
    await handleFeed({ fetch }, request(), res, admit);
    expect(res.code).toBe(429);
    expect(res.headers['Cache-Control']).toBe('no-store');
    expect(fetch).not.toHaveBeenCalled();
    now = 60000;
    expect(admit()).toBe(true);
  });
});
