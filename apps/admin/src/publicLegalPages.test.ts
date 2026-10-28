import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { JSDOM } from 'jsdom';
import { describe, expect, it } from 'vitest';

const publicRoot = resolve(import.meta.dirname, '../public');

// These URLs are opened outside an authenticated admin session, including by
// App Review. Guard against accidentally shipping the empty admin SPA again.
describe('public app information', () => {
  for (const route of ['privacy', 'terms', 'support']) {
    it(`${route} is readable without JavaScript, authentication or remote assets`, () => {
      const html = readFileSync(resolve(publicRoot, route, 'index.html'), 'utf8');
      const { document } = new JSDOM(html).window;
      expect(document.documentElement.lang).toBe('sv');
      expect(document.querySelector('meta[name="viewport"]')?.getAttribute('content')).toContain(
        'width=device-width',
      );
      expect(document.querySelector('h1')?.textContent?.trim().length).toBeGreaterThan(5);
      expect(document.querySelector('main')?.textContent?.trim().length).toBeGreaterThan(1000);
      expect(document.querySelector('#english')?.getAttribute('lang')).toBe('en');
      expect(document.querySelector('#english')?.textContent?.length).toBeGreaterThan(700);
      expect(document.querySelectorAll('script, iframe, form, img, video, audio').length).toBe(0);
      expect(
        document.querySelectorAll('a[href="mailto:kontakt@kungsbackacarcommunity.se"]').length,
      ).toBeGreaterThan(0);
      for (const link of document.querySelectorAll('a[href]')) {
        const href = link.getAttribute('href')!;
        if (href.startsWith('#')) expect(document.getElementById(href.slice(1))).not.toBeNull();
        else if (href.startsWith('/')) {
          expect(readFileSync(resolve(publicRoot, href.slice(1), 'index.html'), 'utf8')).toContain(
            '<main',
          );
        } else expect(href).toMatch(/^(https:|mailto:)/);
      }
      for (const asset of document.querySelectorAll('link[href]')) {
        const href = asset.getAttribute('href')!;
        expect(href).toBe('/legal.css');
        expect(readFileSync(resolve(publicRoot, href.slice(1)), 'utf8')).toContain('focus-visible');
      }
    });
  }

  it('keeps the unresolved owner decisions explicit until reviewed', () => {
    for (const route of ['privacy', 'terms', 'support']) {
      const { document } = new JSDOM(readFileSync(resolve(publicRoot, route, 'index.html'), 'utf8'))
        .window;
      expect(document.querySelector('.draft')?.textContent).toContain('Pre-release draft');
      expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe(
        'noindex',
      );
    }
  });
});
