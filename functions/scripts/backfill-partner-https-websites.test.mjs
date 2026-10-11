import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { normalizeHttpsWebsite } from './backfill-partner-https-websites.mjs';

describe('partner HTTPS website backfill', () => {
  it('suppresses cleartext and unsafe legacy values', () => {
    assert.equal(normalizeHttpsWebsite('http://example.com'), null);
    assert.equal(normalizeHttpsWebsite('HTTP://example.com'), null);
    assert.equal(normalizeHttpsWebsite('javascript:alert(1)'), null);
    assert.equal(normalizeHttpsWebsite('not a url'), null);
  });

  it('preserves HTTPS and upgrades scheme-less legacy domains', () => {
    assert.equal(normalizeHttpsWebsite('https://example.com/path'), 'https://example.com/path');
    assert.equal(normalizeHttpsWebsite(' example.com/path '), 'https://example.com/path');
    assert.equal(normalizeHttpsWebsite('https://[2001:db8::1]/'), 'https://[2001:db8::1]/');
  });
});
