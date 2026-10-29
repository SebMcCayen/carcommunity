import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

/** Only explicitly declared collection-group field indexes, not documents. */
export function expectedGroupFields(config) {
  return (config.fieldOverrides ?? []).flatMap((field) => {
    const indexes = (field.indexes ?? []).filter(
      (index) => index.queryScope === 'COLLECTION_GROUP',
    );
    return indexes.length
      ? [{ group: field.collectionGroup, field: field.fieldPath, indexes }]
      : [];
  });
}

export function fieldIndexesReady(expected, actual) {
  return expected.indexes.every((wanted) =>
    (actual.indexConfig?.indexes ?? []).some(
      (index) =>
        index.state === 'READY' &&
        index.queryScope === 'COLLECTION_GROUP' &&
        (index.fields ?? []).some(
          (field) =>
            field.fieldPath === expected.field &&
            (wanted.order
              ? field.order === wanted.order
              : field.arrayConfig === wanted.arrayConfig),
        ),
    ),
  );
}

async function main(project) {
  if (!project || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(project)) {
    throw new Error('Supply the approved Firebase project ID.');
  }
  const config = JSON.parse(
    await readFile(new URL('../firebase/firestore.indexes.json', import.meta.url), 'utf8'),
  );
  const expected = expectedGroupFields(config);
  if (!expected.length) {
    console.log('No collection-group field overrides declared.');
    return;
  }
  // Loaded only for the credentialed CLI. Pure tests never load the Admin SDK
  // or credentials. Reuse the existing deployment dependencies and WIF ADC.
  const require = createRequire(new URL('../functions/package.json', import.meta.url));
  const credential = require('firebase-admin/app').applicationDefault();
  for (let attempt = 1; attempt <= 30; attempt++) {
    let access_token;
    try {
      ({ access_token } = await credential.getAccessToken());
    } catch {
      throw new Error(
        'Existing deployment credentials could not authenticate the index metadata check.',
      );
    }
    const pending = [];
    for (const field of expected) {
      const name = `projects/${project}/databases/(default)/collectionGroups/${encodeURIComponent(field.group)}/fields/${encodeURIComponent(field.field)}`;
      const response = await fetch(`https://firestore.googleapis.com/v1/${name}`, {
        headers: { Authorization: `Bearer ${access_token}` },
        signal: AbortSignal.timeout(20_000),
      });
      if (!response.ok)
        throw new Error(
          `Index metadata read failed (HTTP ${response.status}) for ${field.group}.${field.field}.`,
        );
      if (!fieldIndexesReady(field, await response.json()))
        pending.push(`${field.group}.${field.field}`);
    }
    if (!pending.length) {
      console.log(
        `All ${expected.length} declared collection-group field configurations are READY.`,
      );
      return;
    }
    console.log(`Waiting for index readiness (${attempt}/30): ${pending.join(', ')}`);
    if (attempt < 30) await delay(10_000);
  }
  throw new Error(
    'Field indexes are still building. Check readiness before enabling dependent queries; rerun this read-only check later.',
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv[2]).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
