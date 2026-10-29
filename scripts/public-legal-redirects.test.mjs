import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const config = JSON.parse(readFileSync(resolve(root, 'firebase.json'), 'utf8'));
const destinations = new Map([
  ['privacy', 'integritetspolicy.html'],
  ['terms', 'anvandarvillkor.html'],
  ['support', 'support.html'],
]);

test('installed app legal pages forward to one fixed HTTPS homepage source without JavaScript', () => {
  for (const [route, page] of destinations) {
    const html = readFileSync(resolve(root, `apps/admin/public/${route}/index.html`), 'utf8');
    const destination = `https://kungsbackacarcommunity.se/${page}`;
    const refresh = html.match(/<meta\s+http-equiv="refresh"\s+content="([^"]+)"/);
    assert.equal(refresh?.[1], `0;url=${destination}`);
    assert.equal(html.match(/<link\s+rel="canonical"\s+href="([^"]+)"/)?.[1], destination);
    const links = [...html.matchAll(/<a\s+href="([^"]+)"\s*>/g)].map((match) => match[1]);
    assert.ok(links.includes(destination), 'Readable fallback without automatic navigation');
    assert.ok(links.includes(`${destination}#english`), 'English section is reachable');
    assert.match(html, /<html lang="sv">/);
    assert.match(html, /<main>/);
    assert.doesNotMatch(html, /<script|<iframe|<form|<link rel="stylesheet"/i);
    assert.doesNotMatch(
      html,
      /Publiceringsutkast|Publication draft|raderingsplan|approved retention/i,
    );
  }
});

test('legal compatibility pages preserve existing Hosting and event routing', () => {
  const admin = config.hosting.find((site) => site.target === 'admin');
  assert.equal(admin.public, 'apps/admin/dist');
  assert.deepEqual(admin.rewrites, [{ source: '**', destination: '/index.html' }]);
  const security = admin.headers.find((rule) => rule.source === '**').headers;
  assert.ok(security.some((header) => header.key === 'Content-Security-Policy'));
  const events = config.hosting.find((site) => site.target === 'events');
  assert.equal(events.public, 'apps/public-events');
  assert.deepEqual(events.rewrites, [
    {
      source: '/e/**',
      function: { functionId: 'publicweb-eventPage', region: 'europe-west1' },
    },
  ]);
  assert.equal(
    admin.redirects,
    undefined,
    'Do not change shared Firebase configuration for these static pages',
  );
  assert.equal(events.redirects, undefined);
  assert.equal(existsSync(resolve(root, 'apps/admin/public/legal.css')), false);
});

test('Android locale contracts keep compatible installed-app links', () => {
  for (const locale of ['sv', 'en']) {
    const contract = JSON.parse(
      readFileSync(resolve(root, `contracts/localization/${locale}.json`), 'utf8'),
    );
    for (const route of ['privacy', 'terms']) {
      const url = new URL(contract.url[route]);
      assert.equal(url.origin, 'https://kungsbacka-car-community.web.app');
      assert.equal(url.pathname, `/${route}`);
      assert.equal(existsSync(resolve(root, `apps/admin/public${url.pathname}/index.html`)), true);
    }
  }
});
