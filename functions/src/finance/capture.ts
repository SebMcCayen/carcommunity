import { FieldValue } from 'firebase-admin/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { FINANCE_SNAPSHOTS_COLLECTION } from './evidence';
import { collectBilling, collectUsage } from './providers';

/** Two bounded aggregate documents, overwritten daily; no per-user telemetry. */
export async function captureFinanceEvidence(now = new Date()): Promise<void> {
  // Emulators/CI can never accidentally contact live Google APIs.
  if (process.env.FUNCTIONS_EMULATOR === 'true' || process.env.FIRESTORE_EMULATOR_HOST) return;
  const project = process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT;
  const table = process.env.FINANCE_BILLING_TABLE;
  const location = process.env.FINANCE_BILLING_LOCATION ?? 'EU';
  const providers = [
    {
      id: 'usage',
      enabled: process.env.FINANCE_MONITORING_ENABLED === 'true',
      load: () => collectUsage(project!, now),
    },
    {
      id: 'billing',
      enabled: Boolean(table),
      load: () => collectBilling(project!, table!, location, now),
    },
  ];
  await Promise.all(
    providers.map(async (provider) => {
      const ref = db.collection(FINANCE_SNAPSHOTS_COLLECTION).doc(provider.id);
      if (!provider.enabled || !project) {
        await ref.set({
          state: 'not-configured',
          lastAttemptFailed: false,
          updatedAt: FieldValue.serverTimestamp(),
        });
        return;
      }
      try {
        const data = await provider.load();
        await ref.set({
          state: 'ready',
          data,
          lastAttemptFailed: false,
          updatedAt: FieldValue.serverTimestamp(),
        });
      } catch {
        // Keep the previous successful result and its capture date. A failed
        // refresh is visible; provider errors may contain credentials/query data.
        const previous = await ref.get();
        await ref.set(
          {
            state: previous.get('state') === 'ready' ? 'ready' : 'unavailable',
            lastAttemptFailed: true,
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
        logger.warn('Finance evidence refresh failed', { provider: provider.id });
      }
    }),
  );
}

export const captureDaily = onSchedule(
  {
    region: 'europe-west1',
    schedule: '30 5 * * *',
    timeZone: 'Europe/Stockholm',
    serviceAccount: process.env.FINANCE_CAPTURE_SERVICE_ACCOUNT || undefined,
    minInstances: 0,
    maxInstances: 1,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 180,
  },
  async () => {
    await captureFinanceEvidence();
  },
);
