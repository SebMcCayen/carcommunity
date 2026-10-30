import { HttpsError } from 'firebase-functions/v2/https';
import type { ClubCalendarService } from './service';

type Request = {
  method: string;
  path: string;
  protocol: string;
  query: Record<string, unknown>;
  get(name: string): string | undefined;
};
type Response = {
  set(name: string, value: string): Response;
  status(code: number): Response;
  send(body: string): unknown;
  end(): unknown;
};

/** Best-effort per-instance admission cap, no token/IP retention or database writes.
 * This reduces database amplification; it is not a distributed billing guarantee.
 */
export function feedAdmission(now = Date.now): () => boolean {
  let bucket = -1,
    count = 0;
  return () => {
    const current = Math.floor(now() / 60000);
    if (current !== bucket) {
      bucket = current;
      count = 0;
    }
    return ++count <= 120;
  };
}

/** No redirects, logs, HTML, analytics, embedded URLs, or error details containing secrets. */
export async function handleFeed(
  service: Pick<ClubCalendarService, 'fetch'>,
  req: Request,
  res: Response,
  admit: () => boolean = () => true,
) {
  for (const [key, value] of Object.entries({
    'Cache-Control': 'no-store',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
    'Strict-Transport-Security': 'max-age=31536000',
  }))
    res.set(key, value);
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.status(405).set('Allow', 'GET, HEAD').end();
    return;
  }
  if (
    req.protocol !== 'https' ||
    req.path !== '/calendar.ics' ||
    typeof req.query.token !== 'string' ||
    Object.keys(req.query).some((key) => key !== 'token')
  ) {
    res.status(404).send('Not found.');
    return;
  }
  if (!admit()) {
    res.status(429).set('Retry-After', '60').send('Try again later.');
    return;
  }
  try {
    // Complete token, account, membership and canonical event reads BEFORE validators.
    const { body, etag } = await service.fetch(req.query.token);
    res
      .set('Cache-Control', 'private, no-cache, max-age=0, must-revalidate')
      .set('Content-Type', 'text/calendar; charset=utf-8')
      .set('Content-Disposition', 'inline; filename="club-calendar.ics"')
      .set('ETag', etag);
    const validator = req.get('If-None-Match');
    if (
      validator &&
      validator.length <= 1024 &&
      validator
        .split(',')
        .some((value) => value.trim() === '*' || value.trim().replace(/^W\//, '') === etag)
    ) {
      res.status(304).end();
    } else if (req.method === 'HEAD') res.status(200).end();
    else res.status(200).send(body);
  } catch (error) {
    // Unknown/revoked/restricted all have identical status/body. A failed source
    // read is never a successful empty feed or a stale 304 (mass deletion risk).
    const hidden =
      error instanceof HttpsError &&
      ['permission-denied', 'unauthenticated', 'not-found', 'failed-precondition'].includes(
        error.code,
      );
    if (hidden) res.status(404).send('Not found.');
    else res.status(503).set('Retry-After', '3600').send('Calendar temporarily unavailable.');
  }
}
