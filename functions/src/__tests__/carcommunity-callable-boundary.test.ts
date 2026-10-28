import { readFileSync, readdirSync } from 'node:fs';
import { resolve, relative } from 'node:path';
import { expect, it } from 'vitest';
// These entrypoints remain available for shared-account lifecycle, privacy,
// receipts, diagnostics/support, own notices, Driver, or explicit admin guards.
const essential = new Set([
  'driverFeed/index.ts',
  'account/deleteAccount.ts',
  'live/session.ts',
  'subscription/verify.ts',
  'feedback/reportIssue.ts',
  'feedback/interactWithIssue.ts',
  'errors/reportClientError.ts',
  'access/adminAccess.ts',
  'access/callable.ts',
  'access/carCommunity.ts',
  'admin/warnings.ts',
  'admin/suspendUser.ts',
  'admin/restoreAccess.ts',
  'auth/completeOnboarding.ts',
  'auth/recordLogin.ts',
  'diagnostics/submitReport.ts',
  'notifications/manageNotifications.ts',
  'notifications/adminSend.ts',
  'notifications/pushTokens.ts',
]);
const root = resolve('src');
function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? files(resolve(dir, entry.name)) : [resolve(dir, entry.name)],
  );
}
it('every new product callable uses the fresh CarCommunity authority wrapper', () => {
  const raw = files(root)
    .filter((file) => file.endsWith('.ts') && !file.includes('.test.'))
    .filter((file) =>
      /import\s*\{[^}]*\bonCall\b[^}]*\}\s*from ['"]firebase-functions\/v2\/https/s.test(
        readFileSync(file, 'utf8'),
      ),
    )
    .map((file) => relative(root, file))
    .sort();
  expect(raw).toEqual([...essential].sort());
});
