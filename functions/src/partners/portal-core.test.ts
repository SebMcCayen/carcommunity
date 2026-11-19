import { describe, expect, it } from 'vitest';
import { PNG } from 'pngjs';
import { crc32, deflateSync } from 'node:zlib';
import { inWindow, portalSchemas, offerProjection, reservationProjection } from './portal-core';
import { sanitizeLogo } from './portal-logo';

describe('partner portal contracts and privacy projections', () => {
  it('rejects unknown privileges, invalid capacities and missing terms versions', () => {
    expect(
      portalSchemas.reserve.safeParse({ offerId: 'offer', requestId: 'request-1' }).success,
    ).toBe(false);
    expect(
      portalSchemas.membership.safeParse({ companyId: 'a', uid: 'u', role: 'admin', enabled: true })
        .success,
    ).toBe(false);
    expect(portalSchemas.workspace.safeParse({ admin: true }).success).toBe(false);
    expect(
      portalSchemas.submitOffer.safeParse({
        companyId: 'a',
        title: 'Title',
        teaserText: 'Teaser',
        description: 'Desc',
        terms: 'Terms',
        capacity: 0,
      }).success,
    ).toBe(false);
  });
  it('fails closed for malformed dates and uses exclusive expiry', () => {
    const now = Date.now();
    expect(inWindow({ availableFrom: 'bad' }, now)).toBe(false);
    expect(inWindow({ availableUntil: new Date(now).toISOString() }, now)).toBe(false);
    expect(inWindow({ availableFrom: new Date(now + 1).toISOString() }, now)).toBe(false);
    expect(inWindow({ availableFrom: null, availableUntil: null }, now)).toBe(true);
  });
  it('never projects identity, QR secrets, exact event time or raw counts to a customer', () => {
    const privateData = {
      uid: 'private-customer',
      email: 'private@example.test',
      companyId: 'c',
      offerId: 'o',
      tokenHash: 'secret',
      requestId: 'secret-request',
      status: 'reserved',
      expiresAt: '2026-12-01T12:00:00Z',
      title: 'Benefit',
      terms: 'Agreed terms',
      companyName: 'Company',
    };
    const result = reservationProjection(
      'random-reference',
      privateData,
      Date.parse('2026-11-17T12:00:00Z'),
    );
    expect(result).not.toHaveProperty('uid');
    expect(result).not.toHaveProperty('email');
    expect(result).not.toHaveProperty('tokenHash');
    expect(
      offerProjection('o', { capacity: 3, remainingCount: 2, redeemedCount: 1, reservedCount: 0 }),
    ).not.toHaveProperty('redeemedCount');
    expect(offerProjection('o', { capacity: 3, remainingCount: 2 })).toHaveProperty(
      'remainingCount',
      2,
    );
  });
});
const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
function chunk(type: string, content: Buffer): Buffer {
  const value = Buffer.alloc(content.length + 12);
  value.writeUInt32BE(content.length, 0);
  value.write(type, 4, 'ascii');
  content.copy(value, 8);
  value.writeUInt32BE(crc32(value.subarray(4, value.length - 4)), value.length - 4);
  return value;
}
function header(width: number, height: number, depth = 8, color = 6, interlace = 0) {
  const bytes = Buffer.alloc(13);
  bytes.writeUInt32BE(width);
  bytes.writeUInt32BE(height, 4);
  bytes[8] = depth;
  bytes[9] = color;
  bytes[12] = interlace;
  return chunk('IHDR', bytes);
}
const end = chunk('IEND', Buffer.alloc(0));
const encode = (...chunks: Buffer[]) => Buffer.concat([signature, ...chunks]).toString('base64');
function rgbaFixture(width: number, height: number, interlace = 0) {
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
  const rows: Buffer[] = [];
  for (const [x0, y0, dx, dy] of passes as [number, number, number, number][]) {
    for (let y = y0; y < height && x0 < width; y += dy) {
      const row = [0];
      for (let x = x0; x < width; x += dx) row.push(x % 256, y % 256, 3, 128);
      rows.push(Buffer.from(row));
    }
  }
  return encode(
    header(width, height, 8, 6, interlace),
    chunk('IDAT', deflateSync(Buffer.concat(rows))),
    end,
  );
}

describe('logo publication boundary', () => {
  it('decodes/re-encodes PNG pixels, removes all source metadata and preserves proportions/alpha', async () => {
    const pixels = new PNG({ width: 1024, height: 512 });
    pixels.data.fill(Buffer.from([1, 2, 3, 128]));
    const original = PNG.sync.write(pixels);
    const bytes = Buffer.concat([
      original.subarray(0, 33),
      chunk('tEXt', Buffer.from('contact\0private@example.test')),
      chunk('eXIf', Buffer.from('private metadata')),
      original.subarray(33),
    ]);
    const safe = await sanitizeLogo(bytes.toString('base64'));
    const decoded = PNG.sync.read(safe);
    expect([decoded.width, decoded.height, decoded.alpha]).toEqual([512, 256, true]);
    expect([...decoded.data.subarray(0, 4)]).toEqual([1, 2, 3, 128]);
    const types: string[] = [];
    for (let offset = 8; offset < safe.length; offset += safe.readUInt32BE(offset) + 12)
      types.push(safe.toString('ascii', offset + 4, offset + 8));
    expect(types).toEqual(['IHDR', 'IDAT', 'IEND']);
  });
  it.each([0, 1])(
    'rejects an IDAT bomb with a tiny header before either decoder (interlace %i)',
    async (interlace) => {
      const bomb = encode(
        header(1, 1, 8, 6, interlace),
        chunk('IDAT', deflateSync(Buffer.alloc(1_000_000))),
        end,
      );
      await expect(sanitizeLogo(bomb)).rejects.toThrow();
    },
  );
  it('accepts Adam7 interlaced pixels through the same bounded output path', async () => {
    const safe = await sanitizeLogo(rgbaFixture(13, 9, 1));
    const image = PNG.sync.read(safe);
    expect([image.width, image.height, image.interlace]).toEqual([13, 9, false]);
    for (let y = 0; y < 9; y++)
      for (let x = 0; x < 13; x++)
        expect([...image.data.subarray((y * 13 + x) * 4, (y * 13 + x + 1) * 4)]).toEqual([
          x,
          y,
          3,
          128,
        ]);
  });
  it('supports small 16-bit PNGs but caps their larger decoded raster before allocation', async () => {
    const raw = Buffer.from([0, 1, 1, 2, 2, 3, 3, 128, 128]);
    const safe = await sanitizeLogo(encode(header(1, 1, 16), chunk('IDAT', deflateSync(raw)), end));
    expect([...PNG.sync.read(safe).data]).toEqual([1, 2, 3, 128]);
    await expect(
      sanitizeLogo(
        encode(header(3000, 3000, 16), chunk('IDAT', deflateSync(Buffer.from([0]))), end),
      ),
    ).rejects.toThrow();
  });
  it('rejects duplicate IHDR, invalid type/depth, dimensions and trailing data before allocation', async () => {
    const valid = Buffer.from(rgbaFixture(1, 1), 'base64');
    await expect(
      sanitizeLogo(
        Buffer.concat([valid.subarray(0, 33), header(5000, 5000), valid.subarray(33)]).toString(
          'base64',
        ),
      ),
    ).rejects.toThrow();
    for (const h of [
      header(5000, 5000),
      header(16_385, 1),
      header(0, 1),
      header(1, 1, 16, 3),
      header(1, 1, 8, 6, 2),
    ])
      await expect(
        sanitizeLogo(encode(h, chunk('IDAT', deflateSync(Buffer.from([0]))), end)),
      ).rejects.toThrow();
    await expect(
      sanitizeLogo(Buffer.concat([valid, Buffer.from([0])]).toString('base64')),
    ).rejects.toThrow();
  });
  it('rejects animated PNG and corrupt ancillary CRC, not only critical CRC', async () => {
    const valid = Buffer.from(rgbaFixture(1, 1), 'base64');
    const metadata = chunk('tEXt', Buffer.from('key\0value'));
    metadata[metadata.length - 1] = metadata[metadata.length - 1]! ^ 1;
    for (const extra of [chunk('acTL', Buffer.alloc(8)), metadata])
      await expect(
        sanitizeLogo(
          Buffer.concat([valid.subarray(0, 33), extra, valid.subarray(33)]).toString('base64'),
        ),
      ).rejects.toThrow();
  });
  it('rejects repeated palettes, oversized indexed palettes and duplicate transparency/gamma', async () => {
    const palette = chunk('PLTE', Buffer.from([1, 2, 3]));
    const idat = chunk('IDAT', deflateSync(Buffer.from([0, 0])));
    await expect(
      sanitizeLogo(encode(header(1, 1, 8, 3), palette, palette, idat, end)),
    ).rejects.toThrow();
    await expect(
      sanitizeLogo(encode(header(1, 1, 1, 3), chunk('PLTE', Buffer.alloc(9)), idat, end)),
    ).rejects.toThrow();
    await expect(
      sanitizeLogo(encode(header(1, 1, 8, 3), palette, chunk('tRNS', Buffer.alloc(2)), idat, end)),
    ).rejects.toThrow();
    await expect(
      sanitizeLogo(
        encode(
          header(1, 1, 8, 3),
          palette,
          chunk('tRNS', Buffer.from([128])),
          chunk('tRNS', Buffer.from([128])),
          idat,
          end,
        ),
      ),
    ).rejects.toThrow();
    const gamma = chunk('gAMA', Buffer.from([0, 0, 177, 143]));
    await expect(
      sanitizeLogo(
        encode(
          header(1, 1),
          gamma,
          gamma,
          chunk('IDAT', deflateSync(Buffer.from([0, 0, 0, 0, 0]))),
          end,
        ),
      ),
    ).rejects.toThrow();
  });
  it('accepts valid indexed transparency and strips it into RGBA pixels', async () => {
    const safe = await sanitizeLogo(
      encode(
        header(1, 1, 1, 3),
        chunk('PLTE', Buffer.from([1, 2, 3])),
        chunk('tRNS', Buffer.from([128])),
        chunk('IDAT', deflateSync(Buffer.from([0, 0]))),
        end,
      ),
    );
    expect([...PNG.sync.read(safe).data]).toEqual([1, 2, 3, 128]);
  });
  it('rejects excessive chunk count, non-contiguous IDAT and SVG/corrupt input', async () => {
    const small = Buffer.from(rgbaFixture(1, 1), 'base64');
    const many = Buffer.concat([
      small.subarray(0, 33),
      ...Array.from({ length: 4096 }, () => chunk('tEXt', Buffer.alloc(0))),
      small.subarray(33),
    ]);
    await expect(sanitizeLogo(many.toString('base64'))).rejects.toThrow();
    const compressed = deflateSync(Buffer.from([0, 1, 2, 3, 128]));
    await expect(
      sanitizeLogo(
        encode(
          header(1, 1),
          chunk('IDAT', compressed.subarray(0, 2)),
          chunk('tEXt', Buffer.alloc(0)),
          chunk('IDAT', compressed.subarray(2)),
          end,
        ),
      ),
    ).rejects.toThrow();
    await expect(sanitizeLogo(Buffer.from('<svg/>').toString('base64'))).rejects.toThrow();
    await expect(sanitizeLogo('iVBORw0KGgo=')).rejects.toThrow();
  });
  it('bounds a high-entropy encoded preview below the Firestore inline budget', async () => {
    const image = new PNG({ width: 512, height: 512 });
    let seed = 123456789;
    for (let i = 0; i < image.data.length; i++) {
      seed ^= seed << 13;
      seed ^= seed >>> 17;
      seed ^= seed << 5;
      image.data[i] = seed & 255;
    }
    const safe = await sanitizeLogo(PNG.sync.write(image).toString('base64'));
    expect(safe.length).toBeLessThanOrEqual(450_000);
    expect(PNG.sync.read(safe).width).toBeLessThan(512);
  });
});
