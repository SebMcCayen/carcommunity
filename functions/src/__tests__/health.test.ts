import { describe, expect, it, vi } from 'vitest';
import { handleHealth, type HandlerResponse, type HealthResponse } from '../health';

function makeResponse(): { res: HandlerResponse; json: ReturnType<typeof vi.fn> } {
  const json = vi.fn();
  const res: HandlerResponse = { status: (_code) => ({ json }) };
  vi.spyOn(res, 'status');
  return { res, json };
}

describe('handleHealth', () => {
  it('responds with HTTP 200', () => {
    const { res } = makeResponse();
    handleHealth(undefined, res);
    expect(res.status).toHaveBeenCalledWith(200);
  });

  it('responds with { status: "ok" }', () => {
    const { res, json } = makeResponse();
    handleHealth(undefined, res);
    expect(json).toHaveBeenCalledWith<[HealthResponse]>({ status: 'ok' });
  });

  it('response body contains only the status field', () => {
    const { res, json } = makeResponse();
    handleHealth(undefined, res);
    const [body] = json.mock.calls[0] as [HealthResponse];
    expect(Object.keys(body)).toStrictEqual(['status']);
    expect(body.status).toBe('ok');
  });
});
