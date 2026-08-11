import { describe, expect, it } from 'vitest';
import { parseSubmitApplicationInput } from './partners-core';

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

  it('leaves an http URL untouched (scheme match is case-insensitive)', () => {
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: 'HTTP://example.com' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input.websiteUrl).toBe('HTTP://example.com');
    }
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

  it('still rejects an empty website string', () => {
    // Empty string is not a valid URL even after trimming — clients omit the
    // field instead of sending "".
    const result = parseSubmitApplicationInput({ ...base, websiteUrl: '   ' });
    expect(result.ok).toBe(false);
  });
});
