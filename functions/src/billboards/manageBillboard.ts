/**
 * billboards.create / update / activate / setStatus — admin callables, and
 * billboards.recordInteraction — authenticated callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `billboards` export group. Legacy billboard-service
 * parity:
 * - Drafts require an existing partner; only draft/paused billboards can
 *   be edited or activated; ended is terminal.
 * - Activation is the strictest safety gate in the codebase: all SIX
 *   safety confirmations plus an approval reason, and the sponsoring
 *   partner must be ACTIVE at activation time. approvedAt/approvedByUserId
 *   are stamped and the reason lands in the audit record.
 * - Billboard taps map to partner-insights interaction types and flow
 *   through the 9j privacy pipeline; analytics failure never blocks the
 *   user's action.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { requireActiveActor } from '../shared/memberActor';
import { writeInteractionEvent } from '../partnerInsights/recordInteraction';
import {
  BILLBOARDS_FLAG_KEY,
  BILLBOARD_TO_INSIGHTS_TYPE,
  buildBillboardDocument,
  guardAvailabilityWindow,
  guardCallToActionPair,
  guardEditableBillboard,
  parseActivateBillboardInput,
  parseCreateBillboardInput,
  parseRecordBillboardInteractionInput,
  parseSetBillboardStatusInput,
  parseUpdateBillboardInput,
  type BillboardStatus,
} from './billboards-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface BillboardIdResponse {
  billboardId: string;
  status: BillboardStatus;
}

export const create = onCall(CALLABLE_OPTS, async (request): Promise<BillboardIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseCreateBillboardInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  for (const guard of [
    guardAvailabilityWindow(input.availableFrom, input.availableUntil),
    guardCallToActionPair(input.callToActionType, input.callToActionValue),
  ]) {
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }
  }

  const partnerSnap = await db.collection('companies').doc(input.partnerCompanyId).get();
  if (!partnerSnap.exists) {
    throw new HttpsError('not-found', 'Partner company not found.');
  }

  const billboardRef = db.collection('billboards').doc();
  const serverTimestamp = () => FieldValue.serverTimestamp();
  const batch = db.batch();
  batch.set(billboardRef, buildBillboardDocument(input, actor.uid, serverTimestamp));
  batch.set(
    db.collection('adminAuditEvents').doc(),
    buildAdminAuditEvent(
      {
        adminId: actor.uid,
        action: 'billboards.create',
        targetType: 'billboard',
        targetId: billboardRef.id,
        reason: 'Billboard created (draft).',
        details: { partnerCompanyId: input.partnerCompanyId, headline: input.headline },
      },
      serverTimestamp,
    ),
  );
  await batch.commit();

  return { billboardId: billboardRef.id, status: 'draft' };
});

export const update = onCall(CALLABLE_OPTS, async (request): Promise<BillboardIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseUpdateBillboardInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const billboardRef = db.collection('billboards').doc(input.billboardId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  const status = await db.runTransaction(async (tx) => {
    const snap = await tx.get(billboardRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Billboard not found.');
    }
    const existing = snap.data()!;
    const editGuard = guardEditableBillboard(existing.status as string);
    if (!editGuard.ok) {
      throw new HttpsError(editGuard.code, editGuard.message);
    }

    const effectiveFrom =
      input.availableFrom !== undefined
        ? input.availableFrom
        : (existing.availableFrom?.toDate?.().toISOString() ?? null);
    const effectiveUntil =
      input.availableUntil !== undefined
        ? input.availableUntil
        : (existing.availableUntil?.toDate?.().toISOString() ?? null);
    const effectiveCtaType =
      input.callToActionType !== undefined
        ? input.callToActionType
        : ((existing.callToActionType as string | null) ?? null);
    const effectiveCtaValue =
      input.callToActionValue !== undefined
        ? input.callToActionValue
        : ((existing.callToActionValue as string | null) ?? null);
    for (const guard of [
      guardAvailabilityWindow(effectiveFrom, effectiveUntil),
      guardCallToActionPair(effectiveCtaType, effectiveCtaValue),
    ]) {
      if (!guard.ok) {
        throw new HttpsError(guard.code, guard.message);
      }
    }

    const update: Record<string, unknown> = { updatedAt: serverTimestamp() };
    for (const [key, value] of Object.entries(input)) {
      if (key === 'billboardId' || value === undefined) continue;
      if (key === 'availableFrom' || key === 'availableUntil') {
        update[key] = value ? new Date(value as string) : null;
      } else {
        update[key] = value;
      }
    }
    if (Object.keys(update).length === 1) {
      throw new HttpsError('invalid-argument', 'No billboard fields to update.');
    }

    tx.update(billboardRef, update);
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'billboards.update',
          targetType: 'billboard',
          targetId: input.billboardId,
          reason: 'Billboard updated.',
          details: { changedFields: Object.keys(update).filter((k) => k !== 'updatedAt') },
        },
        serverTimestamp,
      ),
    );
    return existing.status as BillboardStatus;
  });

  return { billboardId: input.billboardId, status };
});

export const activate = onCall(CALLABLE_OPTS, async (request): Promise<BillboardIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseActivateBillboardInput(request.data);
  if (!parsed.ok) {
    // The schema requires ALL six confirmations to be literally true.
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { billboardId, approvalReason } = parsed.input;
  const billboardRef = db.collection('billboards').doc(billboardId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(billboardRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Billboard not found.');
    }
    const billboard = snap.data()!;
    const editGuard = guardEditableBillboard(billboard.status as string);
    if (!editGuard.ok) {
      throw new HttpsError(editGuard.code, editGuard.message);
    }

    // The sponsoring partner must be ACTIVE at activation time (legacy).
    const partnerSnap = await tx.get(
      db.collection('companies').doc(billboard.partnerCompanyId as string),
    );
    if (!partnerSnap.exists) {
      throw new HttpsError('not-found', 'Partner company not found.');
    }
    if (partnerSnap.data()!.status !== 'active') {
      throw new HttpsError(
        'failed-precondition',
        'Partner company must be active to activate a billboard.',
      );
    }

    tx.update(billboardRef, {
      status: 'active',
      approvedAt: serverTimestamp(),
      approvedByUserId: actor.uid,
      updatedAt: serverTimestamp(),
    });
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'billboards.activate',
          targetType: 'billboard',
          targetId: billboardId,
          reason: approvalReason,
          details: { allSafetyConfirmationsAccepted: true },
        },
        serverTimestamp,
      ),
    );
  });

  return { billboardId, status: 'active' };
});

export const setStatus = onCall(CALLABLE_OPTS, async (request): Promise<BillboardIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseSetBillboardStatusInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { billboardId, action, reason } = parsed.input;
  const billboardRef = db.collection('billboards').doc(billboardId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  const nextStatus: BillboardStatus = action === 'pause' ? 'paused' : 'ended';
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(billboardRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Billboard not found.');
    }
    if (snap.data()!.status === 'ended') {
      throw new HttpsError('failed-precondition', 'Ended billboards cannot change status.');
    }
    tx.update(billboardRef, { status: nextStatus, updatedAt: serverTimestamp() });
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: `billboards.${action}`,
          targetType: 'billboard',
          targetId: billboardId,
          reason: reason?.trim() || `Billboard ${action === 'pause' ? 'paused' : 'ended'}.`,
        },
        serverTimestamp,
      ),
    );
  });

  return { billboardId, status: nextStatus };
});

export interface RecordBillboardInteractionResponse {
  recorded: boolean;
}

/** digitalBillboards flag via the shared reader (Phase 9m). */
async function isBillboardsEnabled(): Promise<boolean> {
  return readFeatureFlag(BILLBOARDS_FLAG_KEY);
}

export const recordInteraction = onCall(
  CALLABLE_OPTS,
  async (request): Promise<RecordBillboardInteractionResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseRecordBillboardInteractionInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { billboardId, interactionType } = parsed.input;

    if (!(await isBillboardsEnabled())) {
      throw new HttpsError('failed-precondition', 'Digital billboards feature is disabled.');
    }

    const billboardSnap = await db.collection('billboards').doc(billboardId).get();
    if (!billboardSnap.exists || billboardSnap.data()!.status !== 'active') {
      throw new HttpsError('not-found', 'Billboard not found or not active.');
    }

    // Billboard tap → partner-insights event through the 9j privacy
    // pipeline. Analytics failure never blocks the user's action (legacy):
    // recorded=false covers dedupes AND analytics failures alike.
    try {
      const recorded = await writeInteractionEvent(actor.uid, {
        companyId: billboardSnap.data()!.partnerCompanyId as string,
        interactionType: BILLBOARD_TO_INSIGHTS_TYPE[interactionType],
      });
      return { recorded };
    } catch (error) {
      logger.debug('Billboard interaction analytics failed', {
        billboardId,
        error: String(error),
      });
      return { recorded: false };
    }
  },
);
