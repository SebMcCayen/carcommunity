/**
 * admin.setFeatureFlag — admin callable (contracts/functions/functions.json).
 *
 * Sets one boolean field on the flat `config/featureFlags` document. The
 * key must be in the canonical contract list (FEATURE_FLAG_KEYS) — the
 * flag namespace is closed, so a typo can never create a phantom flag
 * that readers silently ignore. The flag write and its adminAuditEvents
 * record commit atomically (merge-set: the document is created on first
 * use and other flags are untouched). Clients never persist flag changes
 * locally as authoritative (contract note); readers fall back to contract
 * defaults when a field is absent.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from './actorContext';
import { buildAdminAuditEvent } from './claims-core';
import {
  parseSetFeatureFlagInput,
  type FeatureFlagKey,
} from '../shared/featureFlags-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface SetFeatureFlagResponse {
  key: FeatureFlagKey;
  enabled: boolean;
}

export const setFeatureFlag = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SetFeatureFlagResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseSetFeatureFlagInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { key, enabled, reason } = parsed.input;

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const batch = db.batch();
    batch.set(db.collection('config').doc('featureFlags'), { [key]: enabled }, { merge: true });
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'admin.setFeatureFlag',
          targetType: 'featureFlag',
          targetId: key,
          reason: reason ?? `Feature flag ${key} set to ${enabled}.`,
          details: { enabled },
        },
        serverTimestamp,
      ),
    );
    await batch.commit();

    return { key, enabled };
  },
);
