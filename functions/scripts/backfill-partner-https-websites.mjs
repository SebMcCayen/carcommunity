#!/usr/bin/env node
/**
 * One-off remediation for active partner companies created before website
 * links became HTTPS-only.
 *
 * Active companies are publicly readable, and older app versions can still
 * open stored HTTP links. This script upgrades a valid scheme-less value to
 * HTTPS and suppresses HTTP, invalid, and other-scheme values. It rechecks
 * status and website inside a transaction, is idempotent, and defaults to a
 * dry run.
 *
 * Usage from functions/:
 *   npm ci
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \
 *     npm run backfill:partner-https-websites -- --project <projectId> [--apply]
 *
 * After --apply, run the dry run again and require would-update=0.
 */

import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { pathToFileURL } from 'node:url';

const MAX_PARTNER_WEBSITE_URL_LENGTH = 500;

export function normalizeHttpsWebsite(value) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (trimmed === '') return null;
  const candidate = /^[A-Za-z][A-Za-z0-9+.-]*:/.test(trimmed) ? trimmed : `https://${trimmed}`;
  if (candidate.length > MAX_PARTNER_WEBSITE_URL_LENGTH) return null;
  try {
    const url = new URL(candidate);
    return url.protocol === 'https:' &&
      url.hostname !== '' &&
      url.username === '' &&
      url.password === ''
      ? candidate
      : null;
  } catch {
    return null;
  }
}

function nextWebsite(value) {
  const normalized = normalizeHttpsWebsite(value);
  return normalized === value ? undefined : normalized;
}

async function inspectCompany(company) {
  return nextWebsite(company.data().website);
}

async function reconcileCompany(db, companyRef) {
  return db.runTransaction(async (tx) => {
    const current = await tx.get(companyRef);
    if (!current.exists || current.data()?.status !== 'active') return undefined;
    const website = nextWebsite(current.data()?.website);
    if (website === undefined) return undefined;
    tx.update(companyRef, {
      website,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return website;
  });
}

export async function main(argv = process.argv.slice(2)) {
  const apply = argv.includes('--apply');
  const projectIdx = argv.indexOf('--project');
  const projectId = projectIdx >= 0 ? argv[projectIdx + 1] : process.env.GCLOUD_PROJECT;
  if (!projectId) throw new Error('Missing --project <projectId> (or GCLOUD_PROJECT).');

  initializeApp({ credential: applicationDefault(), projectId });
  const db = getFirestore();
  console.log(`[backfill] project=${projectId} mode=${apply ? 'APPLY' : 'DRY RUN'}`);

  let scanned = 0;
  let preserved = 0;
  let upgraded = 0;
  let suppressed = 0;
  const stream = db.collection('companies').where('status', '==', 'active').stream();
  for await (const company of stream) {
    scanned += 1;
    const website = apply ? await reconcileCompany(db, company.ref) : await inspectCompany(company);
    if (website === undefined) preserved += 1;
    else if (website === null) suppressed += 1;
    else upgraded += 1;
  }

  console.log(
    `[backfill] active-scanned=${scanned} preserved=${preserved} ` +
      `${apply ? 'updated' : 'would-update'}=${upgraded + suppressed} ` +
      `upgraded=${upgraded} suppressed=${suppressed}`,
  );
  if (!apply && upgraded + suppressed > 0) {
    console.log('[backfill] DRY RUN — re-run with --apply to write.');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error('[backfill] failed:', error);
    process.exit(1);
  });
}
