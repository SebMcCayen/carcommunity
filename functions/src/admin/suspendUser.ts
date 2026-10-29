/** Legacy callable name; access decisions are now CarCommunity-only. */
import { createHash } from 'node:crypto';
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { requireAdminActor } from './actorContext';
import { adminAccessView, updateAccess } from '../access/adminAccess';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
export interface ModerationStatusResponse {
  targetUid: string;
  suspended: boolean;
}
export const suspendUser = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 60,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request) => {
    const actor = await requireAdminActor(request);
    const parsed = z
      .object({
        targetUid: z
          .string()
          .trim()
          .min(1)
          .max(128)
          .refine((v) => !v.includes('/')),
        reason: z.string().trim().min(1).max(500),
      })
      .strict()
      .safeParse(request.data);
    if (!parsed.success) throw new HttpsError('invalid-argument', 'Target and reason required.');
    const view = await adminAccessView(parsed.data.targetUid);
    const idempotencyKey = createHash('sha256')
      .update(
        JSON.stringify([
          actor.uid,
          parsed.data.targetUid,
          'suspend',
          view.version,
          parsed.data.reason,
        ]),
      )
      .digest('hex');
    await updateAccess.run({
      ...request,
      data: {
        ...parsed.data,
        action: 'suspend',
        expectedActorUid: actor.uid,
        expectedVersion: view.version,
        expectedBlacklistIds: view.blacklistIds,
        idempotencyKey,
      },
    });
    return { targetUid: parsed.data.targetUid, suspended: true };
  },
);
