import { FieldValue } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { onCall } from '../access/callable';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { db } from '../firebase';
import { CPU_ADMIN, MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';
import { SETTINGS_COLLECTION, workloadOverridesSchema } from './settings-core';

const inputSchema = z.object({ overrides: workloadOverridesSchema }).strict();

export const updateWorkloadAssumptions = onCall(
  {
    region: 'europe-west1',
    cpu: CPU_ADMIN,
    maxInstances: MAX_INSTANCES_ADMIN,
    minInstances: 0,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request) => {
    const actor = await requireAdminActor(request);
    const input = inputSchema.safeParse(request.data);
    if (!input.success) throw new HttpsError('invalid-argument', 'Invalid workload assumptions.');
    const serverTimestamp = () => FieldValue.serverTimestamp();
    const batch = db.batch();
    batch.set(db.collection(SETTINGS_COLLECTION).doc('workloads'), {
      overrides: input.data.overrides,
      updatedAt: serverTimestamp(),
    });
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'finance.updateWorkloadAssumptions',
          targetType: 'financeSettings',
          targetId: 'workloads',
          reason: 'Updated fallback workload frequencies.',
          details: { overrides: input.data.overrides },
        },
        serverTimestamp,
      ),
    );
    await batch.commit();
    return { ok: true };
  },
);
