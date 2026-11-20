import { createServer, type IncomingMessage, type ServerResponse, type Server } from 'node:http';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterAll, beforeAll, expect, it } from 'vitest';

// Use the same serving middleware and strict stack as Firebase's Hosting emulator.
// Header matching happens before SPA rewrites, so inspecting index.html alone
// misses the root/deep-link cache regression. No Firebase/cloud client is started.
const packageRequire = createRequire(resolve(__dirname, '../../package.json'));
const cliRequire = createRequire(packageRequire.resolve('firebase-tools'));
const superstatic = (
  cliRequire('superstatic') as {
    default: (options: {
      cwd: string;
      stack: string;
      config: Record<string, unknown>;
    }) => (
      request: IncomingMessage,
      response: ServerResponse,
      next: (error?: unknown) => void,
    ) => void;
  }
).default;
let directory: string;
let server: Server;
let origin: string;
const html = '<!doctype html><html><body>Current admin shell</body></html>';
const asset = 'console.log("immutable content-addressed fixture");';
beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'kcc-hosting-cache-'));
  await mkdir(join(directory, 'public/assets'), { recursive: true });
  await writeFile(join(directory, 'public/index.html'), html);
  await writeFile(join(directory, 'public/assets/index-a1b2c3.js'), asset);
  const config = JSON.parse(await readFile(resolve(__dirname, '../../../firebase.json'), 'utf8'));
  const admin = config.hosting.find((entry: { target: string }) => entry.target === 'admin');
  const middleware = superstatic({
    cwd: directory,
    stack: 'strict',
    config: { ...admin, public: 'public' },
  });
  server = createServer((request, response) => {
    middleware(request, response, (error) => {
      response.statusCode = error ? 500 : 404;
      response.end(error ? String(error) : 'Not found');
    });
  });
  await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
  const address = server.address();
  if (!address || typeof address === 'string') throw Error('No loopback test listener');
  origin = `http://127.0.0.1:${address.port}`;
});
afterAll(async () => {
  if (server) {
    server.closeAllConnections();
    await new Promise<void>((done, reject) =>
      server.close((error) => (error ? reject(error) : done())),
    );
  }
  if (directory) await rm(directory, { recursive: true, force: true });
});

it('serves root and deep-link SPA HTML without caching and preserves immutable assets/security headers', async () => {
  for (const path of [
    '/',
    '/index.html',
    '/partners',
    '/partners/statistics?partnerId=company-a',
    '/partners/company.with-dots',
  ]) {
    const response = await fetch(origin + path);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(html);
    expect(response.headers.get('cache-control'), path).toBe('no-cache, no-store, must-revalidate');
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
    expect(response.headers.get('x-frame-options')).toBe('DENY');
    expect(response.headers.get('content-security-policy')).toContain("frame-ancestors 'none'");
  }
  const response = await fetch(origin + '/assets/index-a1b2c3.js');
  expect(response.status).toBe(200);
  expect(await response.text()).toBe(asset);
  expect(response.headers.get('cache-control')).toBe('public, max-age=31536000, immutable');
  expect(response.headers.get('x-content-type-options')).toBe('nosniff');
  expect(response.headers.get('content-security-policy')).toContain("frame-ancestors 'none'");
});
