import { getAuth } from 'firebase-admin/auth';
/** Real Auth + product authority for domain tests that inject verified requests.
 * The function cannot access a cloud project or an incomplete emulator stack. */
export async function localProductAccount(uid: string) {
  if (
    !process.env.GCLOUD_PROJECT?.startsWith('demo-') ||
    [
      'FIRESTORE_EMULATOR_HOST',
      'FIREBASE_AUTH_EMULATOR_HOST',
      'FIREBASE_DATABASE_EMULATOR_HOST',
    ].some((key) => !/^127\.0\.0\.1:\d+$/.test(process.env[key] ?? ''))
  )
    throw Error('Complete disposable loopback emulators required');
  const auth = getAuth();
  try {
    await auth.getUser(uid);
  } catch (error) {
    if ((error as { code?: string }).code !== 'auth/user-not-found') throw error;
    await auth.createUser({ uid });
  }
  const { initializeCarCommunityAccess } = await import('../../access/carCommunity');
  await initializeCarCommunityAccess(uid);
}
