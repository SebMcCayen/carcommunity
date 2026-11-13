import { MAX_PHOTO_BYTES, sanitizePhotoUrl } from './core';

export interface CameraPhoto {
  base64: string;
  contentType: 'image/jpeg' | 'image/png';
  photoTime: string | null;
  cacheSeconds: 300;
}

/** Bounded download: no redirects, arbitrary URLs, SVG, secrets or oversized body. */
export async function fetchCameraPhoto(
  inputUrl: string,
  fetcher: typeof fetch = fetch,
): Promise<CameraPhoto> {
  const url = sanitizePhotoUrl(inputUrl);
  if (!url) throw new Error('Unsupported camera photo URL.');
  const response = await fetcher(url, {
    redirect: 'error',
    signal: AbortSignal.timeout(10_000),
    headers: { Accept: 'image/jpeg,image/png' },
  });
  if (!response.ok || !response.body) {
    await response.body?.cancel();
    throw new Error('Camera photo is unavailable.');
  }
  const contentType = response.headers.get('content-type')?.split(';')[0]?.trim();
  if (contentType !== 'image/jpeg' && contentType !== 'image/png') {
    await response.body.cancel();
    throw new Error('Unsupported camera photo format.');
  }
  const announced = Number(response.headers.get('content-length') ?? 0);
  if (announced > MAX_PHOTO_BYTES) {
    await response.body.cancel();
    throw new Error('Camera photo exceeds size limit.');
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_PHOTO_BYTES) throw new Error('Camera photo exceeds size limit.');
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
  const bytes = Buffer.concat(chunks);
  const validMagic =
    contentType === 'image/jpeg'
      ? bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
      : bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  if (!validMagic) throw new Error('Invalid camera photo.');
  // Use the image's update time, never the older daily catalog's PhotoTime.
  const modified = response.headers.get('last-modified');
  return {
    base64: bytes.toString('base64'),
    contentType,
    cacheSeconds: 300,
    photoTime:
      modified && Number.isFinite(Date.parse(modified)) ? new Date(modified).toISOString() : null,
  };
}
