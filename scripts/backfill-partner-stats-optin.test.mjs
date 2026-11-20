import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import test from 'node:test';

const script = fileURLToPath(
  new URL('../functions/scripts/backfill-partner-stats-optin.mjs', import.meta.url),
);

test('retired partner-statistics backfill refuses even --apply without credentials or Firebase access', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'retired-partner-consent-'));
  try {
    for (const args of [[], ['--apply'], ['--dry-run']]) {
      const result = spawnSync(process.execPath, [script, ...args], {
        cwd,
        encoding: 'utf8',
        env: {
          PATH: process.env.PATH,
          GOOGLE_APPLICATION_CREDENTIALS: join(cwd, 'credentials-must-not-be-read.json'),
          FIRESTORE_EMULATOR_HOST: '127.0.0.1:1',
        },
        timeout: 5_000,
      });
      assert.ifError(result.error);
      assert.equal(result.status, 1);
      assert.equal(result.stdout, '');
      assert.equal(
        result.stderr,
        'Retired command: Partner analytics requires a new explicit in-app consent. No data was read or changed.\n',
      );
    }
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});
