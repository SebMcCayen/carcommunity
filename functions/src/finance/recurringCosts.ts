/**
 * finance.addRecurringCost / finance.updateRecurringCost /
 * finance.deleteRecurringCost — admin callables (contracts/functions/functions.json).
 *
 * The admin-editable CRUD behind the Finance & Cost board's "Recurring costs"
 * section. Each is an OPERATOR-ENTERED ACTUAL (Claude, a domain, a SaaS tool …)
 * stored in `financeRecurringCosts/{id}` and folded into the board's monthly
 * grand total by finance/model.ts. They replace the old hardcoded Claude
 * placeholder in assumptions.ts, so the list is the single source of truth and
 * nothing is double-counted.
 *
 * Every mutation:
 *  - is admin-gated (requireAdminActor — admin custom claim + live users/{uid}
 *    check, suspension overrides),
 *  - validates + bounds its input with zod (recurringCosts-core.ts): non-empty
 *    label, bounded description, a finite positive amount, currency + period
 *    enums,
 *  - writes the document AND an immutable adminAuditEvents record in ONE batch,
 *    so an audited history exists for every change (dot-namespaced actions:
 *    finance.addRecurringCost / finance.updateRecurringCost /
 *    finance.deleteRecurringCost).
 *
 * Deployed via the `finance` grouped export as `finance-addRecurringCost` etc.
 * (functions/src/index.ts). Each sits on the admin instance/CPU tier.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import {
  RECURRING_COSTS_COLLECTION,
  parseAddRecurringCostInput,
  parseDeleteRecurringCostInput,
  parseUpdateRecurringCostInput,
  type RecurringCostFields,
} from './recurringCosts-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface RecurringCostResponse extends RecurringCostFields {
  id: string;
}

/** Audit `details` payload — the entered figures (small, non-PII). */
function auditDetails(fields: RecurringCostFields): Record<string, unknown> {
  return {
    label: fields.label,
    amount: fields.amount,
    currency: fields.currency,
    period: fields.period,
  };
}

// ---------------------------------------------------------------------------
// finance.addRecurringCost
// ---------------------------------------------------------------------------

export const addRecurringCost = onCall(
  CALLABLE_OPTS,
  async (request): Promise<RecurringCostResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseAddRecurringCostInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const fields = parsed.input;

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const ref = db.collection(RECURRING_COSTS_COLLECTION).doc();
    const batch = db.batch();
    batch.set(ref, {
      ...fields,
      createdByUid: actor.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'finance.addRecurringCost',
          targetType: 'financeRecurringCost',
          targetId: ref.id,
          reason: `Added recurring cost "${fields.label}".`,
          details: auditDetails(fields),
        },
        serverTimestamp,
      ),
    );
    await batch.commit();

    return { id: ref.id, ...fields };
  },
);

// ---------------------------------------------------------------------------
// finance.updateRecurringCost
// ---------------------------------------------------------------------------

export const updateRecurringCost = onCall(
  CALLABLE_OPTS,
  async (request): Promise<RecurringCostResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseUpdateRecurringCostInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { id, ...fields } = parsed.input;

    const ref = db.collection(RECURRING_COSTS_COLLECTION).doc(id);
    const existing = await ref.get();
    if (!existing.exists) {
      throw new HttpsError('not-found', 'Recurring cost not found.');
    }

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const batch = db.batch();
    // Use update(), NOT set(merge:true): update() requires the document to
    // still exist at commit time. If the row is DELETED between the existence
    // read above and this commit (a concurrent delete), update() fails the
    // batch instead of RESURRECTING the deleted cost — set(merge:true) would
    // silently recreate it. createdByUid/createdAt are left untouched by
    // update() (only the named fields change).
    batch.update(ref, { ...fields, updatedAt: serverTimestamp() });
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'finance.updateRecurringCost',
          targetType: 'financeRecurringCost',
          targetId: id,
          reason: `Updated recurring cost "${fields.label}".`,
          details: auditDetails(fields),
        },
        serverTimestamp,
      ),
    );
    try {
      await batch.commit();
    } catch (err) {
      // If the row is deleted in the window between the existence read above and
      // this commit, update() rejects the batch with Firestore NOT_FOUND (gRPC
      // code 5). Surface that as the SAME not-found the pre-check raises — without
      // this, onCall would wrap the raw error as `internal`, contradicting the
      // guarantee documented on the batch.update() above.
      const code = (err as { code?: number | string } | null)?.code;
      if (code === 5 || code === 'not-found' || code === 'NOT_FOUND') {
        throw new HttpsError('not-found', 'Recurring cost not found.');
      }
      throw err;
    }

    return { id, ...fields };
  },
);

// ---------------------------------------------------------------------------
// finance.deleteRecurringCost
// ---------------------------------------------------------------------------

export interface DeleteRecurringCostResponse {
  id: string;
  deleted: boolean;
}

export const deleteRecurringCost = onCall(
  CALLABLE_OPTS,
  async (request): Promise<DeleteRecurringCostResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseDeleteRecurringCostInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { id } = parsed.input;

    const ref = db.collection(RECURRING_COSTS_COLLECTION).doc(id);
    const existing = await ref.get();
    if (!existing.exists) {
      throw new HttpsError('not-found', 'Recurring cost not found.');
    }
    const label =
      typeof existing.data()?.label === 'string' ? (existing.data()!.label as string) : id;

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const batch = db.batch();
    batch.delete(ref);
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'finance.deleteRecurringCost',
          targetType: 'financeRecurringCost',
          targetId: id,
          reason: `Deleted recurring cost "${label}".`,
        },
        serverTimestamp,
      ),
    );
    await batch.commit();

    return { id, deleted: true };
  },
);
