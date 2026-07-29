/**
 * admin.setAppVersion — admin callable (contracts/functions/functions.json).
 *
 * Publishes the server-held "which build is current" record to the flat
 * `config/appVersion` document, so already-installed clients can find out
 * that a newer build exists without needing a release to tell them. This
 * is the operator step that goes with every Play release: bump the Android
 * `versionCode`, roll the release out, then set the same number here — the
 * update prompt is silent until this value is ahead of the installed build.
 *
 * Each call writes the COMPLETE config (see buildAppVersionDocument), so a
 * forgotten `minimumSupportedVersionCode` resets to 0 = block nobody rather
 * than silently keeping an old block alive. The write and its
 * adminAuditEvents record commit atomically.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from './actorContext';
import { buildAdminAuditEvent } from './claims-core';
import {
  APP_VERSION_COLLECTION,
  APP_VERSION_DOC,
  buildAppVersionDocument,
  parseSetAppVersionInput,
  type AppVersionDocument,
} from '../shared/appVersion-core';
import { MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export type SetAppVersionResponse = AppVersionDocument;

export const setAppVersion = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SetAppVersionResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseSetAppVersionInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const document = buildAppVersionDocument(parsed.input);

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const batch = db.batch();
    batch.set(
      db.collection(APP_VERSION_COLLECTION).doc(APP_VERSION_DOC),
      { ...document, updatedAt: serverTimestamp() },
      { merge: true },
    );
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'admin.setAppVersion',
          targetType: 'appVersion',
          targetId: APP_VERSION_DOC,
          reason:
            parsed.input.reason ??
            `Latest app version set to versionCode ${document.latestVersionCode}.`,
          details: { ...document },
        },
        serverTimestamp,
      ),
    );
    await batch.commit();

    return document;
  },
);
