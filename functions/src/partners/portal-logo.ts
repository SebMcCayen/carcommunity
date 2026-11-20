import { inflateSync } from 'node:zlib';
import { PNG, type PNGWithMetadata } from 'pngjs';
import { HttpsError } from 'firebase-functions/v2/https';

const SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const MAX_DIMENSION = 16_384;
const MAX_PIXELS = 16_000_000;
const MAX_RASTER_BYTES = 64 * 1024 * 1024;
const MAX_SCANLINE_BYTES = MAX_RASTER_BYTES + 7 * MAX_DIMENSION;
const CRC_TABLE = Uint32Array.from({ length: 256 }, (_, n) => {
  let value = n;
  for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});
function crc32(bytes: Buffer): number {
  let value = 0xffffffff;
  for (const byte of bytes) value = CRC_TABLE[(value ^ byte) & 255]! ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
}

/** Validate structure and allocation bounds before pngjs allocates or inflates pixels. */
function validatePng(input: Buffer) {
  let offset = 8,
    width = 0,
    height = 0,
    depth = 0,
    color = -1,
    interlace = 0;
  let sawHeader = false,
    sawData = false,
    dataClosed = false,
    sawEnd = false;
  const data: Buffer[] = [];
  const singletons = new Set<string>();
  let chunks = 0,
    paletteEntries = 0;
  while (offset < input.length) {
    if (++chunks > 4096) throw new Error('Too many PNG chunks');
    if (input.length - offset < 12) throw new Error('Truncated chunk');
    const length = input.readUInt32BE(offset),
      end = offset + length + 12;
    if (end > input.length) throw new Error('Truncated chunk');
    const type = input.toString('ascii', offset + 4, offset + 8);
    if (
      !input
        .subarray(offset + 4, offset + 8)
        .every((byte) => (byte >= 65 && byte <= 90) || (byte >= 97 && byte <= 122)) ||
      (input[offset + 6]! & 32) !== 0
    )
      throw new Error('Invalid chunk type');
    if (crc32(input.subarray(offset + 4, end - 4)) !== input.readUInt32BE(end - 4))
      throw new Error('Invalid chunk CRC');
    if (!sawHeader && type !== 'IHDR') throw new Error('IHDR must be first');
    if (['PLTE', 'tRNS', 'gAMA'].includes(type)) {
      if (singletons.has(type)) throw new Error('Duplicate singleton chunk');
      singletons.add(type);
    }
    if (type === 'IHDR') {
      if (sawHeader || offset !== 8 || length !== 13) throw new Error('Invalid IHDR');
      sawHeader = true;
      width = input.readUInt32BE(offset + 8);
      height = input.readUInt32BE(offset + 12);
      depth = input[offset + 16]!;
      color = input[offset + 17]!;
      interlace = input[offset + 20]!;
      const validDepths: Record<number, number[]> = {
        0: [1, 2, 4, 8, 16],
        2: [8, 16],
        3: [1, 2, 4, 8],
        4: [8, 16],
        6: [8, 16],
      };
      if (
        !validDepths[color]?.includes(depth) ||
        input[offset + 18] !== 0 ||
        input[offset + 19] !== 0 ||
        interlace > 1
      )
        throw new Error('Unsupported PNG encoding');
      if (
        !width ||
        !height ||
        width > MAX_DIMENSION ||
        height > MAX_DIMENSION ||
        width * height > MAX_PIXELS ||
        width * height * 4 * (depth === 16 ? 2 : 1) > MAX_RASTER_BYTES
      )
        throw new Error('Pixel budget exceeded');
    } else if (['acTL', 'fcTL', 'fdAT'].includes(type)) {
      throw new Error('Animated PNG is not supported');
    } else if (type === 'IDAT') {
      if (dataClosed) throw new Error('Non-contiguous IDAT');
      sawData = true;
      data.push(input.subarray(offset + 8, end - 4));
    } else {
      if (sawData) dataClosed = true;
      if (type === 'IEND') {
        if (!sawData || length !== 0 || end !== input.length) throw new Error('Invalid IEND');
        sawEnd = true;
      } else if (type === 'PLTE') {
        if (
          sawData ||
          !length ||
          length % 3 !== 0 ||
          length > 768 ||
          color === 0 ||
          color === 4 ||
          (color === 3 && length / 3 > 2 ** depth)
        )
          throw new Error('Invalid palette');
        paletteEntries = length / 3;
      } else if (type === 'tRNS') {
        if (
          sawData ||
          (color === 0
            ? length !== 2
            : color === 2
              ? length !== 6
              : color === 3
                ? !paletteEntries || !length || length > paletteEntries
                : true)
        )
          throw new Error('Invalid transparency');
      } else if (type === 'gAMA') {
        if (sawData || paletteEntries || length !== 4) throw new Error('Invalid gamma');
      } else if ((input[offset + 4]! & 32) === 0) {
        throw new Error('Unknown critical chunk');
      }
    }
    offset = end;
  }
  if (!sawEnd) throw new Error('Missing IEND');
  if (color === 3 && !paletteEntries) throw new Error('Missing palette');
  const channels = ({ 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 } as Record<number, number>)[color]!;
  const passes = interlace
    ? [
        [0, 0, 8, 8],
        [4, 0, 8, 8],
        [0, 4, 4, 8],
        [2, 0, 4, 4],
        [0, 2, 2, 4],
        [1, 0, 2, 2],
        [0, 1, 1, 2],
      ]
    : [[0, 0, 1, 1]];
  let expected = 0;
  for (const [x, y, dx, dy] of passes as [number, number, number, number][]) {
    const columns = Math.max(0, Math.ceil((width - x) / dx)),
      rows = Math.max(0, Math.ceil((height - y) / dy));
    if (columns && rows) expected += (Math.ceil((columns * channels * depth) / 8) + 1) * rows;
  }
  if (expected > MAX_SCANLINE_BYTES) throw new Error('Inflation budget exceeded');
  // pngjs bounds ordinary PNG inflation itself, but its Adam7 path calls an
  // unbounded inflateSync. Preflight the SAME validated IDAT bytes with Node's
  // strict output cap, so neither path can allocate a decompression bomb.
  if (inflateSync(Buffer.concat(data), { maxOutputLength: expected }).length !== expected)
    throw new Error('Invalid scanline length');
  return { width, height };
}

/** Area averaging in premultiplied alpha avoids dark/colored transparent edges. */
function resize(image: PNGWithMetadata, size: number): PNG {
  const scale = Math.min(1, size / image.width, size / image.height);
  const width = Math.max(1, Math.round(image.width * scale)),
    height = Math.max(1, Math.round(image.height * scale));
  const output = new PNG({ width, height });
  const sx = image.width / width,
    sy = image.height / height;
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      const x0 = x * sx,
        x1 = (x + 1) * sx,
        y0 = y * sy,
        y1 = (y + 1) * sy;
      let alpha = 0,
        red = 0,
        green = 0,
        blue = 0;
      for (let iy = Math.floor(y0); iy < Math.min(image.height, Math.ceil(y1)); iy++)
        for (let ix = Math.floor(x0); ix < Math.min(image.width, Math.ceil(x1)); ix++) {
          const weight =
            (Math.min(ix + 1, x1) - Math.max(ix, x0)) * (Math.min(iy + 1, y1) - Math.max(iy, y0));
          const pos = (iy * image.width + ix) * 4,
            weightedAlpha = image.data[pos + 3]! * weight;
          alpha += weightedAlpha;
          red += image.data[pos]! * weightedAlpha;
          green += image.data[pos + 1]! * weightedAlpha;
          blue += image.data[pos + 2]! * weightedAlpha;
        }
      const pos = (y * width + x) * 4;
      output.data[pos] = alpha ? Math.round(red / alpha) : 0;
      output.data[pos + 1] = alpha ? Math.round(green / alpha) : 0;
      output.data[pos + 2] = alpha ? Math.round(blue / alpha) : 0;
      output.data[pos + 3] = Math.round(alpha / (sx * sy));
    }
  return output;
}

/** No URLs, SVG or client MIME trust; publish only freshly encoded PNG pixels. */
export async function sanitizeLogo(base64: string): Promise<Buffer> {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(base64) || base64.length > 7_000_000)
    throw new HttpsError('invalid-argument', 'Upload a PNG logo up to 5 MB.');
  const input = Buffer.from(base64, 'base64');
  if (input.length > 5 * 1024 * 1024 || !input.subarray(0, 8).equals(SIGNATURE))
    throw new HttpsError('invalid-argument', 'Upload a PNG logo up to 5 MB.');
  try {
    const dimensions = validatePng(input);
    const image = PNG.sync.read(input, { checkCRC: true });
    if (image.width !== dimensions.width || image.height !== dimensions.height)
      throw new Error('Unexpected dimensions');
    for (const size of [512, 384, 256]) {
      // A new PNG has no source ancillary chunks, profile, EXIF or text data.
      const output = PNG.sync.write(resize(image, size), { colorType: 6, bitDepth: 8 });
      if (output.length <= 450_000) return output;
    }
    throw new Error('Preview budget exceeded');
  } catch {
    throw new HttpsError(
      'invalid-argument',
      'The PNG could not be decoded safely (maximum 16 megapixels and 64 MiB decoded pixels).',
    );
  }
}
