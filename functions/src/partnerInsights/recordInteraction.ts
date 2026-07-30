/**
 * partnerInsights.recordInteraction — callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `partnerInsights` export group as
 * `partnerInsights-recordInteraction`.
 *
 * Records one partner interaction event (legacy recordInteraction parity):
 * - The event stores a PARTNER-SCOPED SHA-256 hash of the caller — never
 *   the raw UID — so events cannot be correlated across partners.
 * - One event per (company, type, UTC day, user): the dedupe is the
 *   deterministic document ID; duplicates return { recorded: false }.
 * - anonymous_pass_by requires the pass-by feature flag (contract default
 *   OFF) AND the caller's anonymousPartnerStatsOptIn. A non-opted-in
 *   contribution returns { recorded: false } silently — never an error —
 *   so opting out is unobservable from the response shape.
 * - Events carry expiresAt (+7 days) and are removed by the scheduled
 *   cleanup; only threshold-enforced aggregates persist.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { requireActiveActor } from '../shared/memberActor';
import {
  PASS_BY_FLAG_KEY,
  buildScopedHash,
  eventExpiry,
  interactionEventId,
  parseRecordInteractionInput,
  startOfUtcDay,
  type PartnerInteractionType,
} from './insights-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

export interface RecordInteractionResponse {
  recorded: boolean;
}

/** partnerInsightsPassBy flag via the shared reader (Phase 9m); default OFF. */
async function isPassByEnabled(): Promise<boolean> {
  return readFeatureFlag(PASS_BY_FLAG_KEY);
}

export const recordInteraction = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<RecordInteractionResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseRecordInteractionInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;

    // anonymous_pass_by: feature flag + explicit opt-in. Opt-out is silent
    // (recorded: false), never an error — unobservable by design.
    if (input.interactionType === 'anonymous_pass_by') {
      if (!(await isPassByEnabled())) {
        throw new HttpsError('failed-precondition', 'Anonymous pass-by collection is disabled.');
      }
      const privateSnap = await db.collection('userPrivate').doc(actor.uid).get();
      if (privateSnap.data()?.anonymousPartnerStatsOptIn !== true) {
        return { recorded: false };
      }
    }

    return { recorded: await writeInteractionEvent(actor.uid, input) };
  },
);

/**
 * Validates the partner/offer and writes one deduped insight event.
 * Shared with the billboards domain (billboards.recordInteraction maps
 * billboard taps to partner interaction types). Gates that depend on the
 * interaction origin (pass-by flag, opt-in) belong to the CALLERS — this
 * writer enforces only origin-independent invariants: active partner,
 * same-partner offer, scoped hash, per-day dedupe, TTL.
 */
export async function writeInteractionEvent(
  uid: string,
  input: {
    companyId: string;
    interactionType: PartnerInteractionType;
    relatedOfferId?: string | null;
  },
): Promise<boolean> {
  // Partner must exist and be active (legacy parity).
  const companySnap = await db.collection('companies').doc(input.companyId).get();
  if (!companySnap.exists) {
    throw new HttpsError('not-found', 'Partner company not found.');
  }
  if (companySnap.data()!.status !== 'active') {
    throw new HttpsError('failed-precondition', 'Partner company is not active.');
  }

  // A related offer must belong to the same partner (legacy parity).
  if (input.relatedOfferId) {
    const offerSnap = await db.collection('offers').doc(input.relatedOfferId).get();
    if (!offerSnap.exists) {
      throw new HttpsError('not-found', 'Related offer not found.');
    }
    if (offerSnap.data()!.companyId !== input.companyId) {
      throw new HttpsError(
        'invalid-argument',
        'Related offer does not belong to the selected partner.',
      );
    }
  }

  const now = new Date();
  const aggregationDate = startOfUtcDay(now);
  const userReferenceHash = buildScopedHash(input.companyId, uid);
  const eventRef = db
    .collection('partnerInsightsEvents')
    .doc(interactionEventId(input.companyId, input.interactionType, aggregationDate, userReferenceHash));

  // Per-day dedupe: the deterministic ID makes the create transactional.
  return db.runTransaction(async (tx) => {
    const existing = await tx.get(eventRef);
    if (existing.exists) {
      return false;
    }
    tx.set(eventRef, {
      companyId: input.companyId,
      interactionType: input.interactionType,
      // Raw UID is never stored — scoped hash only.
      userReferenceHash,
      relatedOfferId: input.relatedOfferId ?? null,
      occurredAt: Timestamp.fromDate(now),
      aggregationDate: Timestamp.fromDate(aggregationDate),
      expiresAt: Timestamp.fromDate(eventExpiry(now)),
    });
    return true;
  });
}
