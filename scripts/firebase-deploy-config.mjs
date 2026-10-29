import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function selectedFirestoreConfig(target, source) {
  if (!['firestore:indexes', 'firestore:rules'].includes(target)) {
    throw new Error('Only an explicit Firestore index or rules deployment is supported.');
  }
  const field = target === 'firestore:indexes' ? 'indexes' : 'rules';
  if (
    !source.firestore ||
    Array.isArray(source.firestore) ||
    typeof source.firestore[field] !== 'string' ||
    !source.firestore[field]
  ) {
    throw new Error(`No single-database Firestore ${field} path is configured.`);
  }
  const firestore = { [field]: source.firestore[field] };
  if (source.firestore.database) firestore.database = source.firestore.database;
  return { firestore };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const source = JSON.parse(await readFile('firebase.json', 'utf8'));
  await writeFile(
    '.firebase-selected-deploy.json',
    JSON.stringify(selectedFirestoreConfig(process.argv[2], source)),
    { mode: 0o600 },
  );
}
