/**
 * partners.createCompany / updateCompany / setCompanyStatus — admin
 * callables (contracts/functions/functions.json).
 *
 * Deployed via the `partners` export group. Requires an active admin via
 * requireAdminActor. Legacy partner-company-service parity: companies are
 * created as drafts, only draft/paused companies may be edited, the
 * lifecycle is draft → active ⇄ paused → ended (ended is terminal), and
 * every operation writes an adminAuditEvents record.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  buildCompanyDocument,
  guardEditableStatus,
  guardStatusTransition,
  statusActionPastTense,
  parseCreateCompanyInput,
  parseSetCompanyStatusInput,
  parseUpdateCompanyInput,
  type PartnerCompanyStatus,
} from './partners-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface CompanyIdResponse {
  companyId: string;
  status: PartnerCompanyStatus;
}

export const createCompany = onCall(CALLABLE_OPTS, async (request): Promise<CompanyIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseCreateCompanyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  const companyRef = db.collection('companies').doc();
  const serverTimestamp = () => FieldValue.serverTimestamp();
  const batch = db.batch();
  batch.set(
    companyRef,
    buildCompanyDocument(
      parsed.input,
      { createdByUserId: actor.uid, sourceApplicationId: null },
      serverTimestamp,
    ),
  );
  batch.set(
    db.collection('adminAuditEvents').doc(),
    buildAdminAuditEvent(
      {
        adminId: actor.uid,
        action: 'partners.createCompany',
        targetType: 'partnerCompany',
        targetId: companyRef.id,
        reason: 'Company created (draft).',
        details: { name: parsed.input.name },
      },
      serverTimestamp,
    ),
  );
  await batch.commit();

  return { companyId: companyRef.id, status: 'draft' };
});

export const updateCompany = onCall(CALLABLE_OPTS, async (request): Promise<CompanyIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseUpdateCompanyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const companyRef = db.collection('companies').doc(input.companyId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  const status = await db.runTransaction(async (tx) => {
    const snap = await tx.get(companyRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Partner company not found.');
    }
    const guard = guardEditableStatus(snap.data()!.status as string);
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }

    const update: Record<string, unknown> = { updatedAt: serverTimestamp() };
    for (const [key, value] of Object.entries(input)) {
      if (key !== 'companyId' && value !== undefined) {
        update[key] = value;
      }
    }
    if (Object.keys(update).length === 1) {
      throw new HttpsError('invalid-argument', 'No company fields to update.');
    }

    tx.update(companyRef, update);
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'partners.updateCompany',
          targetType: 'partnerCompany',
          targetId: input.companyId,
          reason: 'Company updated.',
          details: { changedFields: Object.keys(update).filter((k) => k !== 'updatedAt') },
        },
        serverTimestamp,
      ),
    );
    return guard.nextStatus;
  });

  return { companyId: input.companyId, status };
});

export const setCompanyStatus = onCall(
  CALLABLE_OPTS,
  async (request): Promise<CompanyIdResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseSetCompanyStatusInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { companyId, action, reason } = parsed.input;
    const companyRef = db.collection('companies').doc(companyId);
    const serverTimestamp = () => FieldValue.serverTimestamp();

    const status = await db.runTransaction(async (tx) => {
      const snap = await tx.get(companyRef);
      if (!snap.exists) {
        throw new HttpsError('not-found', 'Partner company not found.');
      }
      const guard = guardStatusTransition(snap.data()!.status as string, action);
      if (!guard.ok) {
        throw new HttpsError(guard.code, guard.message);
      }
      tx.update(companyRef, { status: guard.nextStatus, updatedAt: serverTimestamp() });
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: `partners.${action}Company`,
            targetType: 'partnerCompany',
            targetId: companyId,
            reason: reason?.trim() || `Company ${statusActionPastTense(action)}.`,
          },
          serverTimestamp,
        ),
      );
      return guard.nextStatus;
    });

    return { companyId, status };
  },
);
