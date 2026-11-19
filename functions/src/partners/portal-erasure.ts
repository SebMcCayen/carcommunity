/** Canonical account erasure, separate from aggregate business statistics. */
import { FieldPath } from 'firebase-admin/firestore';
import type { Query, Transaction } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { hash } from './portal-core';

const PAGE_SIZE = 200;
const MAX_PAGES = 20;
const MAX_RESERVATIONS = 100;
type ReferenceBudget = { pages: number };

/** Reads only: callers finish all other transaction reads before deleting
 * these references. A 101st row keeps the owner for the next bounded pass.
 */
export async function readPartnerReservationErasurePage(tx: Transaction, reservationId: string) {
  const pages = await Promise.all([
    tx.get(
      db
        .collection('partnerRedemptionTokens')
        .where('reservationId', '==', reservationId)
        .limit(101),
    ),
    tx.get(
      db.collection('partnerPortalRequests').where('reservationId', '==', reservationId).limit(101),
    ),
    tx.get(
      db
        .collection('partnerPortalRequests')
        .where('reservation.reservationId', '==', reservationId)
        .limit(101),
    ),
  ]);
  return {
    complete: pages.every((page) => page.size <= 100),
    refs: pages.flatMap((page) => page.docs.slice(0, 100).map((row) => row.ref)),
  };
}

async function eraseReferences(query: Query, budget: ReferenceBudget) {
  for (;;) {
    const rows = await query.limit(budget.pages === 0 ? 1 : PAGE_SIZE).get();
    if (rows.empty) return;
    if (budget.pages === 0)
      throw new Error('Partner reservation reference erasure is pending; retry.');
    budget.pages--;
    const batch = db.batch();
    for (const row of rows.docs) batch.delete(row.ref);
    await batch.commit();
    if (rows.size < PAGE_SIZE) return;
  }
}

/** Canonical Auth lockdown and the partner-erasure marker precede this step.
 * Keep each reservation until its token/retry references are erased, so a
 * failed attempt can rediscover the references without a new identity index.
 */
async function eraseReservations(uid: string) {
  const budget = { pages: MAX_PAGES };
  {
    const rows = await db
      .collection('partnerReservations')
      .where('uid', '==', uid)
      .limit(MAX_RESERVATIONS)
      .get();
    if (rows.empty) return;
    for (const row of rows.docs) {
      await eraseReferences(
        db.collection('partnerRedemptionTokens').where('reservationId', '==', row.id),
        budget,
      );
      await eraseReferences(
        db.collection('partnerPortalRequests').where('reservationId', '==', row.id),
        budget,
      );
      await eraseReferences(
        db.collection('partnerPortalRequests').where('reservation.reservationId', '==', row.id),
        budget,
      );
      await db.runTransaction(async (tx) => {
        const current = await tx.get(row.ref);
        if (!current.exists || current.data()!.uid !== uid) return;
        const reservation = current.data()!;
        const offerId = reservation.offerId as string;
        const quotaRef = db.collection('partnerOfferQuotas').doc(offerId);
        const claimRef = db.collection('partnerClaimLocks').doc(hash(`${uid}:${offerId}`));
        const [offer, quota, claim] = await Promise.all([
          tx.get(db.collection('offers').doc(offerId)),
          tx.get(quotaRef),
          tx.get(claimRef),
        ]);
        if (reservation.status === 'reserved') {
          const reserved = Math.max(0, Number(quota.data()?.reservedCount ?? 0) - 1);
          tx.set(quotaRef, { reservedCount: reserved }, { merge: true });
          if (offer.exists)
            tx.update(offer.ref, {
              remainingCount:
                offer.data()!.capacity == null
                  ? null
                  : Math.max(
                      0,
                      Number(offer.data()!.capacity) -
                        reserved -
                        Number(quota.data()?.redeemedCount ?? 0),
                    ),
            });
        }
        if (claim.data()?.reservationId === current.id) tx.delete(claim.ref);
        tx.delete(current.ref);
      });
    }
    const remaining = await db
      .collection('partnerReservations')
      .where('uid', '==', uid)
      .limit(1)
      .get();
    if (!remaining.empty) throw new Error('Partner reservation erasure is pending; retry.');
  }
}

/** Used claims deliberately carry no stable customer identifier. Walk bounded
 * pages and derive this account's campaign-scoped key instead of introducing
 * a cross-campaign identity field. Missing offer parents are covered too.
 */
async function eraseUsedClaims(uid: string, pageSize: number, maxPages: number) {
  const progress = db.collection('accountPurgeProgress').doc(uid);
  const saved = (await progress.get()).data();
  if (saved?.partnerClaimsComplete === true) return;
  let after =
    typeof saved?.partnerClaimsCursor === 'string' ? saved.partnerClaimsCursor : undefined;
  for (let page = 0; page < maxPages; page++) {
    let query = db
      .collection('partnerClaimLocks')
      .where('used', '==', true)
      .orderBy(FieldPath.documentId())
      .limit(pageSize);
    if (after) query = query.startAfter(after);
    const rows = await query.get();
    const batch = db.batch();
    for (const row of rows.docs) {
      const offerId = row.data().offerId;
      if (typeof offerId === 'string' && row.id === hash(`${uid}:${offerId}`))
        batch.delete(row.ref);
    }
    after = rows.docs.at(-1)?.id ?? after;
    const complete = rows.size < pageSize;
    batch.set(
      progress,
      { partnerClaimsCursor: after ?? null, partnerClaimsComplete: complete },
      { merge: true },
    );
    await batch.commit();
    if (complete) return;
  }
  throw new Error('Partner campaign-claim erasure is pending; resume from its durable cursor.');
}

export async function erasePartnerAccountData(
  uid: string,
  limits = { pageSize: PAGE_SIZE, maxPages: MAX_PAGES },
) {
  if (
    !Number.isInteger(limits.pageSize) ||
    limits.pageSize < 1 ||
    limits.pageSize > PAGE_SIZE ||
    !Number.isInteger(limits.maxPages) ||
    limits.maxPages < 1 ||
    limits.maxPages > MAX_PAGES
  )
    throw new RangeError('Invalid partner erasure work budget');
  // Some immediate admin purges have no accountDeletionRequests record. Fence
  // their in-flight portal writes before querying owned data; the canonical
  // purge removes this existing progress document only after Auth deletion.
  await db
    .collection('accountPurgeProgress')
    .doc(uid)
    .set({ partnerErasureActive: true }, { merge: true });
  await eraseReservations(uid);
  await eraseUsedClaims(uid, limits.pageSize, limits.maxPages);
}
