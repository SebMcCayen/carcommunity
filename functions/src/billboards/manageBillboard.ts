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
  isBillboardMapVisible,
  parseActivateBillboardInput,
  parseCreateBillboardInput,
  parseRecordBillboardInteractionInput,
  parseSetBillboardStatusInput,
  parseUpdateBillboardInput,
  type BillboardStatus,
} from './billboards-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface BillboardIdResponse {
  billboardId: string;
  status: BillboardStatus;
}

/**
 * Keys `update` seeds on every edit that are bookkeeping rather than something
 * the admin changed — excluded from the audit entry's `changedFields`, and the
 * baseline for "did this request actually ask for anything?".
 */
const UPDATE_BOOKKEEPING_KEYS = ['updatedAt', 'mapVisible'] as const;

/**
 * Reads a stored availability bound as a `Date`.
 *
 * Firestore hands these back as `Timestamp`, but a document written before the
 * field existed (or by a test fixture) may hold a plain `Date`, a number, or
 * nothing at all. Anything that is not a usable instant becomes null —
 * "unbounded on this side" — which is safe here only because the bound it
 * pairs with is evaluated independently and `status` still has to be active.
 */
function toDateOrNull(value: unknown): Date | null {
  if (value == null) return null;
  if (value instanceof Date) return value;
  const maybeTimestamp = value as { toDate?: () => Date };
  if (typeof maybeTimestamp.toDate === 'function') {
    return runOrNull(() => maybeTimestamp.toDate!());
  }
  if (typeof value === 'number') return new Date(value);
  return null;
}

function runOrNull(fn: () => Date): Date | null {
  try {
    return fn();
  } catch {
    return null;
  }
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

    // An edit is only reachable on a draft or paused billboard (the guard
    // above), neither of which may be on the map — so re-assert it. Belt and
    // braces against a future status ever becoming editable without someone
    // remembering that the map reads this field. Not counted as a "changed
    // field" below, because it is an invariant repair rather than something
    // the admin asked for.
    const update: Record<string, unknown> = { updatedAt: serverTimestamp(), mapVisible: false };
    for (const [key, value] of Object.entries(input)) {
      if (key === 'billboardId' || value === undefined) continue;
      if (key === 'availableFrom' || key === 'availableUntil') {
        update[key] = value ? new Date(value as string) : null;
      } else {
        update[key] = value;
      }
    }
    // The two bookkeeping keys seeded above (updatedAt, mapVisible) are not an
    // edit — an update carrying nothing else is still "no fields to update".
    if (Object.keys(update).length === UPDATE_BOOKKEEPING_KEYS.length) {
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
          details: {
            changedFields: Object.keys(update).filter(
              (k) => !(UPDATE_BOOKKEEPING_KEYS as readonly string[]).includes(k),
            ),
          },
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
      // Resolve the availability window NOW rather than waiting for the sweep,
      // so activating a billboard that is already inside its window puts it on
      // the map immediately — and, more importantly, so activating one whose
      // window has NOT opened yet (or has already closed) does not put it on
      // the map at all. The sweep then only has to handle the boundary being
      // crossed later, with nobody touching the record.
      mapVisible: isBillboardMapVisible(
        'active',
        toDateOrNull(billboard.availableFrom),
        toDateOrNull(billboard.availableUntil),
        new Date(),
      ),
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
    // Pausing or ending takes the marker off every member's map in the SAME
    // write that changes the status, so "I paused it" means gone now rather
    // than gone within a sweep interval. Neither target status can ever be
    // map-visible, so this is an unconditional false.
    tx.update(billboardRef, {
      status: nextStatus,
      mapVisible: false,
      updatedAt: serverTimestamp(),
    });
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
    const billboard = billboardSnap.exists ? billboardSnap.data()! : null;
    // Gated on the SAME predicate the map draws by, not merely on `status`.
    // A marker the client is still holding after a pause, or one whose window
    // closed between the draw and the tap, must not be able to bill the partner
    // for an impression on a billboard nobody is allowed to be looking at.
    if (
      billboard == null ||
      !isBillboardMapVisible(
        billboard.status as string,
        toDateOrNull(billboard.availableFrom),
        toDateOrNull(billboard.availableUntil),
        new Date(),
      )
    ) {
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
