/** Real Firestore contention coverage. Never runs outside an explicit demo emulator. */
import { initializeApp, getApps } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { beforeAll, afterEach, describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { GLOBAL_PHOTO_BYTES_PER_DAY, PHOTO_RESERVED_BYTES } from './quota';

const projectId = process.env.GCLOUD_PROJECT ?? '';
if (!process.env.FIRESTORE_EMULATOR_HOST || !projectId.startsWith('demo-')) {
  throw new Error('Camera quota integration tests require an explicit demo Firestore emulator.');
}
if (!getApps().length)
  initializeApp({ projectId, databaseURL: `https://${projectId}.firebaseio.com` });
const db = getFirestore();
let reserve: typeof import('./callables').reserveCameraQuota;
const collection = db.collection('roadCameraRateLimits');
const key = (uid: string) => createHash('sha256').update(uid).digest('hex');

beforeAll(async () => {
  reserve = (await import('./callables')).reserveCameraQuota;
});
afterEach(async () => {
  const docs = await collection.limit(100).get();
  const batch = db.batch();
  docs.docs.forEach((doc) => batch.delete(doc.ref));
  await batch.commit();
});

describe('distributed camera budgets on the Firestore emulator', () => {
  it('concurrent devices cannot exceed one UID minute limit', async () => {
    const outcomes = await Promise.allSettled(
      Array.from({ length: 8 }, () => reserve('quota-test-user', 'photo')),
    );
    const accepted = outcomes.filter((outcome) => outcome.status === 'fulfilled').length;
    expect(accepted).toBeGreaterThan(0);
    expect(accepted).toBeLessThanOrEqual(6);
    const user = await collection.doc(key('quota-test-user')).get();
    const global = await collection.doc('global').get();
    expect(user.get('photosMinute')).toBe(accepted);
    expect(user.get('photosDay')).toBe(accepted);
    expect(global.get('photoBytes')).toBe(accepted * PHOTO_RESERVED_BYTES);
    expect(user.get('expiresAt').toMillis()).toBeGreaterThan(Date.now());
  });

  it('different UIDs cannot overspend the shared remaining byte budget', async () => {
    const now = Date.now();
    const originalBytes = GLOBAL_PHOTO_BYTES_PER_DAY - 2 * PHOTO_RESERVED_BYTES;
    await collection
      .doc('global')
      .set({ day: Math.floor(now / 86_400_000), photoBytes: originalBytes });
    const outcomes = await Promise.allSettled(
      Array.from({ length: 8 }, (_, i) => reserve(`quota-global-${i}`, 'photo')),
    );
    const accepted = outcomes.filter((outcome) => outcome.status === 'fulfilled').length;
    expect(accepted).toBeGreaterThan(0);
    expect(accepted).toBeLessThanOrEqual(2);
    const global = await collection.doc('global').get();
    expect(global.get('photoBytes')).toBe(originalBytes + accepted * PHOTO_RESERVED_BYTES);
    expect(global.get('photoBytes')).toBeLessThanOrEqual(GLOBAL_PHOTO_BYTES_PER_DAY);
    const users = (await collection.limit(100).get()).docs.filter((doc) => doc.id !== 'global');
    expect(users.length).toBe(accepted);
  });
});
