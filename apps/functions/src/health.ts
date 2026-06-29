/**
 * Health check handler.
 *
 * Kept as a pure function with no Firebase dependency so it can be unit-tested
 * without any emulator or SDK initialisation.
 */

export interface HealthResponse {
  status: 'ok';
}

/** Minimal response type used by the handler — subset of Express.Response. */
export interface HandlerResponse {
  status(code: number): { json(body: unknown): void };
}

export function handleHealth(_req: unknown, res: HandlerResponse): void {
  const body: HealthResponse = { status: 'ok' };
  res.status(200).json(body);
}
