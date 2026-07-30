/**
 * partners.createOffer / updateOffer / setOfferStatus — admin callables,
 * and partners.showOfferCode — member callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `partners` export group. Offers use the THREE-TIER
 * privacy split (legacy parity): the teaser document, the member-gated
 * details/member document, and the backend-only secret/code document. The
 * discount code is returned exclusively by showOfferCode to active members
 * for active offers — and is never logged (legacy rule).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { requireMemberActor } from '../shared/memberActor';
import {
  buildOfferDocuments,
  buildOfferUpdates,
  guardAvailabilityWindow,
  guardEditableStatus,
  guardStatusTransition,
  statusActionPastTense,
  parseCreateOfferInput,
  parseSetOfferStatusInput,
  parseShowOfferCodeInput,
  parseUpdateOfferInput,
  type PartnerOfferStatus,
} from './partners-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface OfferIdResponse {
  offerId: string;
  status: PartnerOfferStatus;
}

export const createOffer = onCall(CALLABLE_OPTS, async (request): Promise<OfferIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseCreateOfferInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const windowGuard = guardAvailabilityWindow(input.availableFrom, input.availableUntil);
  if (!windowGuard.ok) {
    throw new HttpsError(windowGuard.code, windowGuard.message);
  }

  const companySnap = await db.collection('companies').doc(input.companyId).get();
  if (!companySnap.exists) {
    throw new HttpsError('not-found', 'Partner company not found.');
  }

  const offerRef = db.collection('offers').doc();
  const serverTimestamp = () => FieldValue.serverTimestamp();
  const { offerDoc, memberDoc, secretDoc } = buildOfferDocuments(
    input,
    companySnap.data()!.name as string,
    serverTimestamp,
  );

  const batch = db.batch();
  batch.set(offerRef, offerDoc);
  batch.set(offerRef.collection('details').doc('member'), memberDoc);
  batch.set(offerRef.collection('secret').doc('code'), secretDoc);
  batch.set(
    db.collection('adminAuditEvents').doc(),
    buildAdminAuditEvent(
      {
        adminId: actor.uid,
        action: 'partners.createOffer',
        targetType: 'partnerOffer',
        targetId: offerRef.id,
        reason: 'Offer created (draft).',
        details: { companyId: input.companyId, title: input.title },
      },
      serverTimestamp,
    ),
  );
  await batch.commit();

  return { offerId: offerRef.id, status: 'draft' };
});

export const updateOffer = onCall(CALLABLE_OPTS, async (request): Promise<OfferIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseUpdateOfferInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const offerRef = db.collection('offers').doc(input.offerId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  const status = await db.runTransaction(async (tx) => {
    const snap = await tx.get(offerRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Partner offer not found.');
    }
    const existing = snap.data()!;
    const guard = guardEditableStatus(existing.status as string);
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }

    const effectiveFrom =
      input.availableFrom !== undefined
        ? input.availableFrom
        : (existing.availableFrom?.toDate?.().toISOString() ?? null);
    const effectiveUntil =
      input.availableUntil !== undefined
        ? input.availableUntil
        : (existing.availableUntil?.toDate?.().toISOString() ?? null);
    const windowGuard = guardAvailabilityWindow(effectiveFrom, effectiveUntil);
    if (!windowGuard.ok) {
      throw new HttpsError(windowGuard.code, windowGuard.message);
    }

    const { offerDoc, memberDoc, secretDoc, changedFields } = buildOfferUpdates(
      input,
      serverTimestamp,
    );
    if (changedFields.length === 0) {
      throw new HttpsError('invalid-argument', 'No offer fields to update.');
    }

    if (Object.keys(offerDoc).length > 0) tx.update(offerRef, offerDoc);
    if (Object.keys(memberDoc).length > 0)
      tx.set(offerRef.collection('details').doc('member'), memberDoc, { merge: true });
    if (Object.keys(secretDoc).length > 0)
      tx.set(offerRef.collection('secret').doc('code'), secretDoc, { merge: true });
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'partners.updateOffer',
          targetType: 'partnerOffer',
          targetId: input.offerId,
          // The discount code value must never appear in audit details —
          // changedFields carries only the field NAME.
          reason: 'Offer updated.',
          details: { changedFields },
        },
        serverTimestamp,
      ),
    );
    return existing.status as PartnerOfferStatus;
  });

  return { offerId: input.offerId, status };
});

export const setOfferStatus = onCall(CALLABLE_OPTS, async (request): Promise<OfferIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseSetOfferStatusInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { offerId, action, reason } = parsed.input;
  const offerRef = db.collection('offers').doc(offerId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  const status = await db.runTransaction(async (tx) => {
    const snap = await tx.get(offerRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Partner offer not found.');
    }
    const guard = guardStatusTransition(snap.data()!.status as string, action);
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }
    tx.update(offerRef, { status: guard.nextStatus, updatedAt: serverTimestamp() });
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: `partners.${action}Offer`,
          targetType: 'partnerOffer',
          targetId: offerId,
          reason: reason?.trim() || `Offer ${statusActionPastTense(action)}.`,
        },
        serverTimestamp,
      ),
    );
    return guard.nextStatus as PartnerOfferStatus;
  });

  return { offerId, status };
});

export interface ShowOfferCodeResponse {
  offerId: string;
  code: string | null;
  redemptionInstructions: string | null;
  expiresAt: string | null;
}

export const showOfferCode = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ShowOfferCodeResponse> => {
    const actor = await requireMemberActor(request);

    const parsed = parseShowOfferCodeInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { offerId } = parsed.input;
    const offerRef = db.collection('offers').doc(offerId);

    const [offerSnap, secretSnap, memberSnap] = await Promise.all([
      offerRef.get(),
      offerRef.collection('secret').doc('code').get(),
      offerRef.collection('details').doc('member').get(),
    ]);
    if (!offerSnap.exists || offerSnap.data()!.status !== 'active') {
      throw new HttpsError('not-found', 'Offer not found or not active.');
    }

    // The code is returned here ONLY — never logged (legacy rule). The
    // actor variable exists purely for the membership gate above.
    void actor;
    const availableUntil = offerSnap.data()!.availableUntil;
    return {
      offerId,
      code: (secretSnap.data()?.discountCode as string | null) ?? null,
      redemptionInstructions:
        (memberSnap.data()?.redemptionInstructions as string | null) ?? null,
      expiresAt: availableUntil ? availableUntil.toDate().toISOString() : null,
    };
  },
);
