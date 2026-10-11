import { describe, expect, it } from 'vitest';
import {
  normalizePartnerWebsiteUrl,
  parseCreateCompanyInput,
  parseSubmitApplicationInput,
  parseUpdateCompanyInput,
} from './partners-core';

describe('parseSubmitApplicationInput websiteUrl normalization', () => {
  const base = {
    companyName: 'Bilverkstan',
    category: 'workshop',
    contactName: 'Ada',
    contactEmail: 'ada@example.com',
  };

  it('accepts a scheme-less domain and prepends https://', () => {
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: 'www.foretag.se' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input.websiteUrl).toBe('https://www.foretag.se');
    }
  });

  it('leaves a full https URL untouched', () => {
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: 'https://example.com' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input.websiteUrl).toBe('https://example.com');
    }
  });

  it('accepts valid HTTPS IPv6 and single-label hosts consistently with iOS', () => {
    for (const websiteUrl of ['https://[2001:db8::1]/', 'https://intranet/']) {
      const result = parseSubmitApplicationInput({ ...base, websiteUrl });
      expect(result.ok).toBe(true);
      if (result.ok) expect(result.input.websiteUrl).toBe(websiteUrl);
    }
  });

  it('rejects an explicit cleartext URL (scheme match is case-insensitive)', () => {
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: 'HTTP://example.com' });
    expect(result.ok).toBe(false);
  });

  it('rejects other explicit schemes instead of treating them as domains', () => {
    expect(parseSubmitApplicationInput({ ...base, websiteUrl: 'javascript:alert(1)' }).ok).toBe(
      false,
    );
    expect(parseSubmitApplicationInput({ ...base, websiteUrl: 'ftp://example.com' }).ok).toBe(
      false,
    );
  });

  it('accepts the application when websiteUrl is omitted (optional)', () => {
    const result = parseSubmitApplicationInput({ ...base });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input.websiteUrl).toBeUndefined();
    }
  });

  it('accepts the application when websiteUrl is null', () => {
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: null });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input.websiteUrl).toBeNull();
    }
  });

  it('still rejects a genuinely invalid website', () => {
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: 'not a url with spaces' });
    expect(result.ok).toBe(false);
  });

  it('treats a blank/whitespace website string as missing (optional)', () => {
    // Defense-in-depth: a client that accidentally sends "" (or spaces)
    // should not get invalid-argument — it is normalized to undefined.
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: '   ' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input.websiteUrl).toBeUndefined();
    }
  });
});

describe('partner company website HTTPS boundary', () => {
  const company = { name: 'Bilverkstan', category: 'workshop' };

  it('normalizes scheme-less admin create and update values to HTTPS', () => {
    const created = parseCreateCompanyInput({ ...company, website: ' example.com/path ' });
    expect(created.ok).toBe(true);
    if (created.ok) expect(created.input.website).toBe('https://example.com/path');

    const updated = parseUpdateCompanyInput({ companyId: 'c1', website: 'www.example.com' });
    expect(updated.ok).toBe(true);
    if (updated.ok) expect(updated.input.website).toBe('https://www.example.com');
  });

  it('rejects HTTP at direct company ingestion', () => {
    expect(parseCreateCompanyInput({ ...company, website: 'http://example.com' }).ok).toBe(false);
    expect(parseUpdateCompanyInput({ companyId: 'c1', website: 'HTTP://example.com' }).ok).toBe(
      false,
    );
  });

  it('suppresses unsafe legacy values and upgrades legacy scheme-less domains', () => {
    expect(normalizePartnerWebsiteUrl('http://example.com')).toBeNull();
    expect(normalizePartnerWebsiteUrl('javascript:alert(1)')).toBeNull();
    expect(normalizePartnerWebsiteUrl('legacy.example.com')).toBe('https://legacy.example.com');
    expect(normalizePartnerWebsiteUrl('https://example.com')).toBe('https://example.com');
  });
});
