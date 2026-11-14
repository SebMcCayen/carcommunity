import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const raw = readFileSync(resolve(root, 'contracts/chat-reactions-v1/emoji-catalog.json'));
const catalog = JSON.parse(raw);
assert.equal(catalog.version, '18.0');
assert.equal(catalog.entries.length, 3963);
assert.match(catalog.license, /UNICODE LICENSE V3/);
const ids = catalog.entries.map(([id, glyph, name, words, category, english]) => {
  assert.equal(id, Array.from(glyph, (point) => point.codePointAt(0).toString(16).padStart(4, '0')).join('-'));
  for (const value of [name, words, category, english]) assert.equal(typeof value, 'string');
  return id;
});
assert.equal(new Set(ids).size, ids.length);
const source = readFileSync(resolve(root, 'functions/src/chatReactions/emoji-ids.ts'), 'utf8');
assert.deepEqual(JSON.parse(source.match(/export const emojiIds:[\s\S]*?= ([\s\S]+);\s*$/)[1]), ids);
for (const path of ['apps/android/app/src/main/assets/emoji-catalog.json', 'apps/ios/KCC/Chat/EmojiCatalog.json']) {
  if (existsSync(resolve(root, path))) assert.deepEqual(readFileSync(resolve(root, path)), raw, path);
}
const largestCounts = Object.fromEntries(ids.map((id) => [id, Number.MAX_SAFE_INTEGER]));
const bytes = Buffer.byteLength(JSON.stringify(largestCounts));
assert.ok(bytes < 256 * 1024, `Count-map budget exceeded: ${bytes}`);
console.log(JSON.stringify({ version: catalog.version, entries: ids.length, worstCountMapBytes: bytes,
  catalogSha256: createHash('sha256').update(raw).digest('hex') }));
