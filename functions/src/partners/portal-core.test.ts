import { describe, expect, it } from 'vitest';
import sharp from 'sharp';
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
describe('logo publication boundary', () => {
  it('decodes/re-encodes PNG pixels, strips metadata and preserves alpha', async () => {
    const bytes = await sharp({
      create: {
        width: 1024,
        height: 1024,
        channels: 4,
        background: { r: 1, g: 2, b: 3, alpha: 0.5 },
      },
    })
      .withMetadata()
      .png()
      .toBuffer();
    const safe = await sanitizeLogo(bytes.toString('base64'));
    const metadata = await sharp(safe).metadata();
    expect(metadata.width).toBe(512);
    expect(metadata.hasAlpha).toBe(true);
    expect(metadata.exif).toBeUndefined();
  });
  it('rejects SVG disguised as PNG, corrupt images and oversized pixel input', async () => {
    await expect(sanitizeLogo(Buffer.from('<svg/>').toString('base64'))).rejects.toThrow();
    await expect(sanitizeLogo('iVBORw0KGgo=')).rejects.toThrow();
    const huge = await sharp({
      create: { width: 5000, height: 5000, channels: 3, background: 'white' },
    })
      .png()
      .toBuffer();
    await expect(sanitizeLogo(huge.toString('base64'))).rejects.toThrow();
  });
});
