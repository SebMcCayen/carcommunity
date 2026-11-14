import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';

it('keeps backend IDs, native catalog bytes and the complete count-map budget in sync', () => {
  const report = JSON.parse(execFileSync(process.execPath, [resolve(__dirname, '../../../scripts/verify-chat-reactions.mjs')], { encoding: 'utf8' }));
  expect(report.entries).toBe(3963);
  expect(report.worstCountMapBytes).toBeLessThan(256 * 1024);
  expect(report.catalogSha256).toMatch(/^[a-f0-9]{64}$/);
});
