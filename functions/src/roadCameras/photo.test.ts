import { afterEach, describe, expect, it, vi } from 'vitest';
import { MAX_PHOTO_BYTES } from './core';
import { fetchCameraPhoto } from './photo';

const url =
  'https://api.trafikinfo.trafikverket.se/v2/Images/data/road.infrastructure.camera/TrafficFlowCamera_123.jpg';
const jpeg = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
const png = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3]);

function streamResponse(chunks: Uint8Array[], headers: Record<string, string> = {}) {
  const cancel = vi.fn();
  let index = 0;
  const response = new Response(
    new ReadableStream<Uint8Array>({
      pull(controller) {
        if (index < chunks.length) controller.enqueue(chunks[index++]!);
        else controller.close();
      },
      cancel,
    }),
    { headers: { 'content-type': 'image/jpeg', ...headers } },
  );
  return { response, cancel };
}

function withResponse(response: Response) {
  return vi.fn<typeof fetch>().mockResolvedValue(response);
}

afterEach(() => vi.restoreAllMocks());

describe('bounded camera photo retrieval', () => {
  it('downloads only the validated standard-size endpoint with a ten-second abort signal', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout');
    const fetcher = withResponse(streamResponse([jpeg]).response);
    const photo = await fetchCameraPhoto(`${url}?type=fullsize`, fetcher);
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(`${url}?maxage=15`, {
      redirect: 'error',
      signal: expect.any(AbortSignal),
      headers: { Accept: 'image/jpeg,image/png' },
    });
    expect(timeout).toHaveBeenCalledWith(10_000);
    expect(photo).toEqual({
      base64: Buffer.from(jpeg).toString('base64'),
      contentType: 'image/jpeg',
      photoTime: null,
      cacheSeconds: 300,
    });
  });

  it('combines streamed chunks and accepts PNG based on its full signature', async () => {
    const fetcher = withResponse(
      streamResponse([png.slice(0, 4), png.slice(4)], {
        'content-type': 'image/png; charset=binary',
        'last-modified': 'Thu, 12 Nov 2026 21:00:00 GMT',
      }).response,
    );
    expect(await fetchCameraPhoto(url, fetcher)).toMatchObject({
      base64: Buffer.from(png).toString('base64'),
      contentType: 'image/png',
      photoTime: '2026-11-12T21:00:00.000Z',
    });
  });

  it.each([undefined, 'not a timestamp'])(
    'returns no update timestamp for absent or invalid Last-Modified (%s)',
    async (value) => {
      const headers: Record<string, string> = value ? { 'last-modified': value } : {};
      const photo = await fetchCameraPhoto(
        url,
        withResponse(streamResponse([jpeg], headers).response),
      );
      expect(photo.photoTime).toBeNull();
    },
  );

  it('rejects an announced oversized image before reading and cancels the body', async () => {
    const { response, cancel } = streamResponse([jpeg], {
      'content-length': String(MAX_PHOTO_BYTES + 1),
    });
    await expect(fetchCameraPhoto(url, withResponse(response))).rejects.toThrow('size limit');
    expect(cancel).toHaveBeenCalledOnce();
  });

  it('stops an oversized streamed body even when its declared length is small', async () => {
    const { response, cancel } = streamResponse(
      [jpeg, new Uint8Array(MAX_PHOTO_BYTES), new Uint8Array(100)],
      { 'content-length': '7' },
    );
    await expect(fetchCameraPhoto(url, withResponse(response))).rejects.toThrow('size limit');
    expect(cancel).toHaveBeenCalledOnce();
  });

  it('accepts an image exactly at the maximum body size', async () => {
    const bytes = new Uint8Array(MAX_PHOTO_BYTES);
    bytes.set(jpeg);
    const photo = await fetchCameraPhoto(url, withResponse(streamResponse([bytes]).response));
    expect(Buffer.from(photo.base64, 'base64').byteLength).toBe(MAX_PHOTO_BYTES);
  });

  it.each(['image/svg+xml', 'text/html', 'application/octet-stream', ''])(
    'rejects unsupported content type %s',
    async (contentType) => {
      const { response, cancel } = streamResponse([jpeg], { 'content-type': contentType });
      await expect(fetchCameraPhoto(url, withResponse(response))).rejects.toThrow('format');
      expect(cancel).toHaveBeenCalledOnce();
    },
  );

  it.each([
    [new Uint8Array(), 'image/jpeg'],
    [Uint8Array.from([0xff, 0xd8]), 'image/jpeg'],
    [png, 'image/jpeg'],
    [jpeg, 'image/png'],
    [Uint8Array.from([137, 80, 78, 71, 0, 0, 0, 0]), 'image/png'],
  ] as const)('rejects body/header signature mismatch', async (bytes, contentType) => {
    const response = streamResponse([bytes], { 'content-type': contentType }).response;
    await expect(fetchCameraPhoto(url, withResponse(response))).rejects.toThrow(
      'Invalid camera photo',
    );
  });

  it('does not issue a request for arbitrary hosts or credential-bearing endpoints', async () => {
    const fetcher = vi.fn<typeof fetch>();
    await expect(fetchCameraPhoto('https://evil.example/photo.jpeg', fetcher)).rejects.toThrow(
      'URL',
    );
    await expect(fetchCameraPhoto(`${url}?authenticationkey=secret`, fetcher)).rejects.toThrow(
      'URL',
    );
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('refuses redirects and upstream errors', async () => {
    const fetcher = withResponse(
      new Response('', { status: 302, headers: { location: 'https://evil.example/' } }),
    );
    await expect(fetchCameraPhoto(url, fetcher)).rejects.toThrow('unavailable');
    expect(fetcher.mock.calls[0]![1]!.redirect).toBe('error');
    await expect(
      fetchCameraPhoto(url, withResponse(new Response('', { status: 503 }))),
    ).rejects.toThrow('unavailable');
  });

  it('propagates a fetch aborted by the configured timeout signal', async () => {
    const controller = new AbortController();
    vi.spyOn(AbortSignal, 'timeout').mockReturnValue(controller.signal);
    const fetcher = vi.fn<typeof fetch>().mockImplementation(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init!.signal!.addEventListener('abort', () => reject(init!.signal!.reason), {
            once: true,
          });
        }),
    );
    const loading = fetchCameraPhoto(url, fetcher);
    const assertion = expect(loading).rejects.toThrow('Image download timed out');
    controller.abort(new Error('Image download timed out'));
    await assertion;
  });

  it('releases an errored response stream', async () => {
    const response = new Response(
      new ReadableStream<Uint8Array>({
        pull(controller) {
          controller.error(new Error('Body connection lost'));
        },
      }),
      { headers: { 'content-type': 'image/jpeg' } },
    );
    await expect(fetchCameraPhoto(url, withResponse(response))).rejects.toThrow(
      'Body connection lost',
    );
    expect(response.body!.locked).toBe(false);
  });
});
