/** Partner-scoped command boundary. Private operational records never become analytics. */
import { projectPartnerDriveHeat } from '../partnerInsights/drive-heat-snapshot';
import { randomBytes, randomUUID } from 'node:crypto';
import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import {
  FieldValue,
  Timestamp,
  type Transaction,
  type DocumentData,
} from 'firebase-admin/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { onCall } from '../access/callable';
import { db, adminAuth, adminStorage } from '../firebase';
import {
  requireActiveActor,
  requirePaidActor,
  requirePaidOrAdminActor,
} from '../shared/memberActor';
import { requireAdminActor, requireAdminActorInTransaction } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { toUserAccessState, isRestricted } from '../shared/access';
import { buildApplicationDocument, normalizePartnerWebsiteUrl } from './partners-core';
import { MAX_INSTANCES_MEMBER, MAX_INSTANCES_ADMIN, CPU_SCHEDULED } from '../shared/instanceLimits';
import {
  hash,
  inWindow,
  iso,
  millis,
  offerProjection,
  portalSchemas,
  reservationProjection,
  type PortalAction,
} from './portal-core';
import { sanitizeLogo } from './portal-logo';
import { readPartnerReservationErasurePage } from './portal-erasure';
import { readFeatureFlag } from '../shared/featureFlags';
import { identityKeys, isCarCommunityRestricted } from '../access/carCommunity-core';
import {
  effectiveSubscriptionTierFromStoredRecord,
  isPaidSubscriptionTier,
} from '../subscription/subscription-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '512MiB' as const,
  // PNG decoding/resampling is CPU-bound; serialize bounded image memory per instance.
  concurrency: 1,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const ADMIN = new Set<PortalAction>([
  'reviewApplication',
  'membership',
  'members',
  'adminStatistics',
  'billboardLogo',
]);
const DAY = 86400000;
const stamp = () => FieldValue.serverTimestamp();
function fail(message: string): never {
  throw new HttpsError('failed-precondition', message);
}
function missing(): never {
  throw new HttpsError('not-found', 'Record not found or unavailable.');
}
function denied(): never {
  throw new HttpsError('permission-denied', 'This operation is not allowed.');
}
/** A retained deletion request fences inactivity erasure too, where Auth is
 * disabled before the existing users document is necessarily removed.
 */
async function assertNotErasing(uid: string, tx?: Transaction) {
  const refs = [
    db.collection('users').doc(uid),
    db.collection('accountDeletionRequests').doc(uid),
    db.collection('accountPurgeProgress').doc(uid),
  ];
  const [user, request, progress] = await Promise.all(
    refs.map((ref) => (tx ? tx.get(ref) : ref.get())),
  );
  if (
    !user!.exists ||
    isRestricted(toUserAccessState(user!.data())) ||
    ['pending', 'processed'].includes(request!.data()?.status) ||
    progress!.data()?.partnerErasureActive === true
  )
    denied();
}
async function customerPolicy(tx: Transaction, uid: string) {
  const [user, subscription, access, policy] = await Promise.all([
    tx.get(db.collection('users').doc(uid)),
    tx.get(db.collection('subscriptions').doc(uid)),
    tx.get(db.collection('carCommunityAccess').doc(uid)),
    tx.get(db.collection('carCommunityPolicy').doc('access')),
    assertNotErasing(uid, tx),
  ]);
  if (
    !user.exists ||
    isRestricted(toUserAccessState(user.data())) ||
    !isPaidSubscriptionTier(effectiveSubscriptionTierFromStoredRecord(subscription.data(), uid))
  )
    denied();
  if (policy.data()?.enforceInitialized !== false) {
    const keys = identityKeys(access.data());
    const identities = await Promise.all(
      keys.map((key) => tx.get(db.collection('carCommunityIdentityBlocks').doc(key))),
    );
    if (
      isCarCommunityRestricted(
        access.data(),
        identities.map((s) => s.data() ?? {}),
      )
    )
      denied();
  }
  // Reads can wait for transaction locks. Evaluate entitlement expiry against
  // the server clock after those reads, and again on every transaction retry.
  if (
    subscription.data()?.expiresAt != null &&
    (millis(subscription.data()!.expiresAt) ?? 0) <= Date.now()
  )
    denied();
}

async function rateLimit(uid: string, action: PortalAction) {
  const now = Date.now(),
    window = Math.floor(now / 60000);
  const limit = ['submitApplication', 'uploadLogo'].includes(action)
    ? 5
    : action === 'token'
      ? 30
      : 120;
  const ref = db.collection('partnerPortalRateLimits').doc(hash(`${uid}:${action}:${window}`));
  await db.runTransaction(async (tx) => {
    await assertNotErasing(uid, tx);
    const snap = await tx.get(ref);
    const count = Number(snap.data()?.count ?? 0);
    if (count >= limit)
      throw new HttpsError('resource-exhausted', 'Too many requests. Try again later.');
    tx.set(ref, { count: count + 1, expiresAt: Timestamp.fromMillis(now + DAY) });
  });
}
async function staff(
  tx: Transaction,
  request: CallableRequest,
  companyId: string,
  manager = false,
) {
  const uid = request.auth!.uid;
  const [user, company, membership] = await Promise.all([
    tx.get(db.collection('users').doc(uid)),
    tx.get(db.collection('companies').doc(companyId)),
    tx.get(db.collection('partnerMembers').doc(hash(`${companyId}:${uid}`))),
    assertNotErasing(uid, tx),
  ]);
  if (!user.exists || isRestricted(toUserAccessState(user.data()))) denied();
  const admin =
    request.auth!.token.admin === true && ['admin', 'owner'].includes(user.data()!.role);
  const member = membership.data();
  if (
    !admin &&
    (!member?.enabled ||
      member.companyId !== companyId ||
      member.uid !== uid ||
      !['manager', 'redeemer'].includes(member.role) ||
      (manager && member.role !== 'manager'))
  )
    denied();
  if (!company.exists || company.data()!.status !== 'active')
    fail('Partner company is not active.');
  return company.data()!;
}
function audit(tx: Transaction, uid: string, action: string, target: string, reason: string) {
  tx.set(
    db.collection('adminAuditEvents').doc(),
    buildAdminAuditEvent(
      {
        adminId: uid,
        action: `partners.${action}`,
        targetType: 'partnerCompany',
        targetId: target,
        reason,
      },
      stamp,
    ),
  );
}
function companyProjection(companyId: string, c: DocumentData, role: string) {
  return {
    companyId,
    name: c.name,
    description: c.description ?? '',
    category: c.category,
    address: c.address ?? '',
    latitude: c.latitude ?? null,
    longitude: c.longitude ?? null,
    phone: c.phone ?? null,
    website: c.website ?? null,
    logoPath: c.logoPath ?? null,
    status: c.status,
    role,
  };
}
function available(offer: DocumentData, company: DocumentData | undefined, now: number) {
  if (offer.status !== 'active' || company?.status !== 'active' || !inWindow(offer, now)) missing();
  if (offer.redemptionMode !== 'reservation')
    fail('This offer does not use personal reservations.');
}
function lockRef(uid: string, offerId: string) {
  return db.collection('partnerClaimLocks').doc(hash(`${uid}:${offerId}`));
}
function remaining(
  o: DocumentData,
  reserved = Number(o.reservedCount ?? 0),
  redeemed = Number(o.redeemedCount ?? 0),
) {
  return o.capacity == null ? null : Math.max(0, o.capacity - reserved - redeemed);
}
async function readApplication(uid: string) {
  const lock = await db.collection('partnerApplicationOwners').doc(uid).get();
  const current = lock.data()?.applicationId;
  const snaps = current
    ? [await db.collection('partnerApplications').doc(current).get()]
    : (
        await db
          .collection('partnerApplications')
          .where('submittedByUserId', '==', uid)
          .limit(20)
          .get()
      ).docs;
  const snap = snaps
    .filter((s) => s.exists && s.data()!.submittedByUserId === uid)
    .sort((a, b) => (millis(b.data()!.submittedAt) ?? 0) - (millis(a.data()!.submittedAt) ?? 0))[0];
  if (!snap) return null;
  const a = snap.data()!;
  return {
    applicationId: snap.id,
    companyName: a.companyName,
    organizationNumber: a.organizationNumber ?? '',
    category: a.category,
    contactName: a.contactName,
    contactEmail: a.contactEmail,
    contactPhone: a.contactPhone ?? '',
    website: a.websiteUrl ?? '',
    description: a.proposedDescription ?? '',
    address: a.proposedAddress ?? '',
    publicPhone: a.publicPhone ?? '',
    message: a.message ?? '',
    selfServiceRequested: a.selfServiceRequested === true,
    billboardRequested: a.billboardRequested === true,
    billboardPlacementRequest: a.billboardPlacementRequest ?? '',
    logoPreviewBase64: a.logoPreviewBase64 ?? null,
    reviewReason: a.reviewReason ?? a.reviewNote ?? null,
    revision: a.revision ?? 0,
    status: a.status,
  };
}
async function saveLogo(companyId: string, base64: string) {
  const bytes = await sanitizeLogo(base64);
  const path = `companyImages/${companyId}/${hash(bytes.toString('base64'))}.png`;
  await adminStorage
    .bucket()
    .file(path)
    .save(bytes, {
      resumable: false,
      contentType: 'image/png',
      metadata: { cacheControl: 'public,max-age=86400' },
    });
  return path;
}

async function handlePortal(request: CallableRequest) {
  const actor = await requireActiveActor(request);
  if (!request.data || typeof request.data !== 'object' || Array.isArray(request.data))
    throw new HttpsError('invalid-argument', 'A command is required.');
  const { action: rawAction, ...body } = request.data;
  if (typeof rawAction !== 'string' || !Object.hasOwn(portalSchemas, rawAction))
    throw new HttpsError('invalid-argument', 'Unknown command.');
  const action = rawAction as PortalAction;
  const parsed = portalSchemas[action].safeParse(body);
  if (!parsed.success) throw new HttpsError('invalid-argument', 'Invalid command fields.');
  // The discriminated command is validated above. Branches project only their schema's fields.
  const input = parsed.data as Record<string, unknown>;
  await assertNotErasing(actor.uid);
  if (ADMIN.has(action)) await requireAdminActor(request);
  if (
    [
      'uploadLogo',
      'updateProfile',
      'submitOffer',
      'pauseReservations',
      'reserve',
      'driveHeat',
    ].includes(action) &&
    !(await readFeatureFlag('partnerSelfService'))
  )
    fail('Partner self-service is not enabled.');
  await rateLimit(actor.uid, action);
  const uid = actor.uid;

  if (action === 'submitApplication') {
    const a = portalSchemas.submitApplication.parse(body);
    const website = a.website ? normalizePartnerWebsiteUrl(a.website) : null;
    if (a.website && !website) throw new HttpsError('invalid-argument', 'Use an HTTPS website.');
    if (a.logoBase64 && !a.logoRightsConfirmed)
      throw new HttpsError('invalid-argument', 'Confirm permission to use this logo.');
    const logo = a.logoBase64 ? (await sanitizeLogo(a.logoBase64)).toString('base64') : null;
    const ownerRef = db.collection('partnerApplicationOwners').doc(uid);
    const newRef = db.collection('partnerApplications').doc();
    return db.runTransaction(async (tx) => {
      // A business contact address is applicant-supplied contact data, not proof
      // of identity or exclusive ownership. Only the authenticated UID owns an
      // application; administrators review potential business duplicates.
      await assertNotErasing(uid, tx);
      const [owner, legacy] = await Promise.all([
        tx.get(ownerRef),
        tx.get(
          db
            .collection('partnerApplications')
            .where('submittedByUserId', '==', uid)
            .where('status', 'in', ['submitted', 'under_review', 'changes_requested'])
            .limit(1),
        ),
      ]);
      const applicationId = owner.data()?.applicationId ?? legacy.docs[0]?.id;
      const existing = applicationId
        ? await tx.get(db.collection('partnerApplications').doc(applicationId))
        : null;
      if (
        existing?.exists &&
        existing.data()!.status !== 'changes_requested' &&
        ['submitted', 'under_review', 'approved'].includes(existing.data()!.status)
      )
        throw new HttpsError('already-exists', 'Your application is already being processed.');
      const ref =
        existing?.exists && existing.data()!.status === 'changes_requested' ? existing.ref : newRef;
      if (a.billboardRequested && !logo && !existing?.data()?.logoPreviewBase64)
        fail('A logo is required when requesting billboards.');
      const data = buildApplicationDocument(
        {
          companyName: a.companyName,
          category: a.category,
          contactName: a.contactName,
          contactEmail: a.contactEmail,
          contactPhone: a.contactPhone,
          websiteUrl: website ?? undefined,
          organizationNumber: a.organizationNumber,
          proposedDescription: a.description,
          proposedAddress: a.address,
          message: a.message,
        },
        uid,
        stamp,
      );
      tx.set(ref, {
        ...data,
        publicPhone: a.publicPhone ?? null,
        selfServiceRequested: a.selfServiceRequested,
        billboardRequested: a.billboardRequested,
        billboardPlacementRequest: a.billboardPlacementRequest ?? null,
        logoPreviewBase64: logo ?? existing?.data()?.logoPreviewBase64 ?? null,
        logoRightsConfirmed:
          a.logoRightsConfirmed === true || existing?.data()?.logoRightsConfirmed === true,
        revision: Number(existing?.data()?.revision ?? 0) + 1,
        reviewReason: null,
      });
      tx.set(ownerRef, { applicationId: ref.id });
      return { applicationId: ref.id, status: 'submitted' };
    });
  }
  if (action === 'workspace') {
    const memberships = await db
      .collection('partnerMembers')
      .where('uid', '==', uid)
      .where('enabled', '==', true)
      .limit(30)
      .get();
    const companies = await Promise.all(
      memberships.docs.map(async (m) => {
        const s = await db.collection('companies').doc(m.data().companyId).get();
        return s.exists ? companyProjection(s.id, s.data()!, m.data().role) : null;
      }),
    );
    return { companies: companies.filter(Boolean), application: await readApplication(uid) };
  }
  if (action === 'reviewApplication') {
    const a = portalSchemas.reviewApplication.parse(body);
    const ref = db.collection('partnerApplications').doc(a.applicationId);
    const initial = await ref.get();
    if (!initial.exists) missing();
    const initialData = initial.data()!;
    const companyId = initialData.partnerCompanyId ?? db.collection('companies').doc().id;
    if (a.decision === 'publish' && a.grantSelfService) {
      const target = await adminAuth.getUser(initialData.submittedByUserId);
      if (!target.emailVerified || target.disabled)
        fail('Verify the applicant account before granting company access.');
    }
    const logoPath =
      a.decision === 'publish' && initialData.logoPreviewBase64
        ? await saveLogo(companyId, initialData.logoPreviewBase64)
        : null;
    return db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) missing();
      const data = snap.data()!;
      await requireAdminActorInTransaction(request, tx);
      // A revised application must be re-read and re-approved; never publish an older logo/review.
      if (data.revision !== initialData.revision) fail('Application changed. Reload the review.');
      if (data.partnerCompanyId)
        return { applicationId: ref.id, companyId: data.partnerCompanyId, status: 'approved' };
      if (a.decision === 'publish' && a.expectedRevision !== Number(data.revision ?? 0))
        fail('Application changed. Review the latest revision.');
      if (!['submitted', 'under_review', 'changes_requested'].includes(data.status))
        fail('Application cannot be reviewed.');
      if (a.decision === 'request_changes') {
        if (!a.reason?.trim()) throw new HttpsError('invalid-argument', 'A reason is required.');
        tx.update(ref, {
          status: 'changes_requested',
          reviewReason: a.reason,
          reviewedByUserId: uid,
          updatedAt: stamp(),
        });
        audit(tx, uid, 'requestApplicationChanges', ref.id, a.reason);
        return { applicationId: ref.id, status: 'changes_requested' };
      }
      if (
        !data.proposedDescription?.trim() ||
        !data.proposedAddress?.trim() ||
        a.latitude == null ||
        a.longitude == null
      )
        fail('Description, address and verified map location are required before publication.');
      if (a.grantSelfService && !data.selfServiceRequested)
        fail('Applicant has not requested company access.');
      const target = a.grantSelfService
        ? await tx.get(db.collection('users').doc(data.submittedByUserId))
        : null;
      if (a.grantSelfService) await assertNotErasing(data.submittedByUserId, tx);
      if (target && (!target.exists || isRestricted(toUserAccessState(target.data())))) denied();
      tx.set(db.collection('companies').doc(companyId), {
        name: data.companyName,
        category: data.category,
        description: data.proposedDescription,
        address: data.proposedAddress,
        latitude: a.latitude,
        longitude: a.longitude,
        website: normalizePartnerWebsiteUrl(data.websiteUrl),
        phone: data.publicPhone ?? null,
        logoPath,
        status: 'active',
        sourceApplicationId: ref.id,
        createdByUserId: uid,
        createdAt: stamp(),
        updatedAt: stamp(),
        activatedAt: stamp(),
      });
      tx.update(ref, {
        status: 'approved',
        partnerCompanyId: companyId,
        reviewedByUserId: uid,
        reviewReason: a.reason ?? null,
        decidedAt: stamp(),
        updatedAt: stamp(),
      });
      if (a.grantSelfService)
        tx.set(
          db.collection('partnerMembers').doc(hash(`${companyId}:${data.submittedByUserId}`)),
          {
            companyId,
            uid: data.submittedByUserId,
            role: 'manager',
            enabled: true,
            updatedAt: stamp(),
          },
        );
      if (data.billboardRequested)
        tx.set(db.collection('partnerBillboardRequests').doc(companyId), {
          companyId,
          placementRequest: data.billboardPlacementRequest ?? '',
          status: 'requested',
          createdAt: stamp(),
        });
      audit(
        tx,
        uid,
        'publishApplication',
        companyId,
        'Application approved and company published.',
      );
      return { applicationId: ref.id, companyId, status: 'approved' };
    });
  }
  if (action === 'membership') {
    const a = portalSchemas.membership.parse(body);
    const target = await adminAuth.getUser(a.uid);
    if (a.enabled && (!target.emailVerified || target.disabled))
      fail('A verified active account is required.');
    return db.runTransaction(async (tx) => {
      await requireAdminActorInTransaction(request, tx);
      await assertNotErasing(a.uid, tx);
      const [company, user] = await Promise.all([
        tx.get(db.collection('companies').doc(a.companyId)),
        tx.get(db.collection('users').doc(a.uid)),
      ]);
      if (!company.exists) missing();
      if (a.enabled && (!user.exists || isRestricted(toUserAccessState(user.data())))) denied();
      tx.set(db.collection('partnerMembers').doc(hash(`${a.companyId}:${a.uid}`)), {
        ...a,
        updatedAt: stamp(),
      });
      audit(tx, uid, 'setMembership', a.companyId, 'Company staff permission changed.');
      return { companyId: a.companyId };
    });
  }
  if (action === 'members') {
    const members = await db
      .collection('partnerMembers')
      .where('companyId', '==', input.companyId)
      .limit(100)
      .get();
    return {
      members: members.docs.map((s) => ({
        uid: s.data().uid,
        role: s.data().role,
        enabled: s.data().enabled === true,
      })),
    };
  }
  if (action === 'uploadLogo') {
    const a = portalSchemas.uploadLogo.parse(body);
    await db.runTransaction((tx) => staff(tx, request, a.companyId, true));
    const logoPath = await saveLogo(a.companyId, a.logoBase64);
    await db.runTransaction(async (tx) => {
      await staff(tx, request, a.companyId, true);
      tx.update(db.collection('companies').doc(a.companyId), { logoPath, updatedAt: stamp() });
      audit(tx, uid, 'updateLogo', a.companyId, 'Validated company logo updated.');
    });
    return { logoPath };
  }
  if (action === 'updateProfile') {
    const a = portalSchemas.updateProfile.parse(body);
    const website = a.website ? normalizePartnerWebsiteUrl(a.website) : null;
    if (a.website && !website) throw new HttpsError('invalid-argument', 'Use an HTTPS website.');
    await db.runTransaction(async (tx) => {
      await staff(tx, request, a.companyId, true);
      tx.update(db.collection('companies').doc(a.companyId), {
        description: a.description,
        ...(a.phone !== undefined ? { phone: a.phone } : {}),
        ...(a.website !== undefined ? { website } : {}),
        updatedAt: stamp(),
      });
      audit(tx, uid, 'updateProfile', a.companyId, 'Public company presentation updated.');
    });
    return { companyId: a.companyId };
  }
  if (action === 'listOffers') {
    await db.runTransaction((tx) => staff(tx, request, input.companyId as string, true));
    const offers = await db
      .collection('offers')
      .where('companyId', '==', input.companyId)
      .limit(101)
      .get();
    if (offers.size > 100) {
      throw new HttpsError(
        'resource-exhausted',
        'This workspace exceeds the 100-campaign limit. Contact KCC Admin; no partial list is returned.',
      );
    }
    const published = await db.collection('partnerRedemptionStatsState').doc('published').get();
    const generation = published.data()?.generation;
    return {
      offers: await Promise.all(
        offers.docs.map(async (s) => {
          const [details, stats] = await Promise.all([
            s.ref.collection('details').doc('member').get(),
            generation
              ? db
                  .collection('partnerRedemptionStatsPublic')
                  .doc(generation)
                  .collection('entries')
                  .doc(hash(`${s.id}:all`))
                  .get()
              : null,
          ]);
          return offerProjection(
            s.id,
            { ...s.data(), redeemedCount: stats?.data()?.totalRedeemed ?? 0 },
            details.data(),
            true,
          );
        }),
      ),
    };
  }
  if (action === 'submitOffer') {
    const a = portalSchemas.submitOffer.parse(body);
    if (
      a.availableFrom &&
      a.availableUntil &&
      Date.parse(a.availableFrom) >= Date.parse(a.availableUntil)
    )
      throw new HttpsError('invalid-argument', 'End must follow start.');
    const ref = a.offerId ? db.collection('offers').doc(a.offerId) : db.collection('offers').doc();
    const day = new Date().toISOString().slice(0, 10);
    const dayStart = Date.parse(`${day}T00:00:00.000Z`);
    const budgetRef = db.collection('partnerCompanyBudgets').doc(hash(`${a.companyId}:${day}`));
    await db.runTransaction(async (tx) => {
      const quotaRef = db.collection('partnerOfferQuotas').doc(ref.id);
      const [company, old, quota, budget] = await Promise.all([
        staff(tx, request, a.companyId, true),
        tx.get(ref),
        tx.get(quotaRef),
        a.offerId ? Promise.resolve(null) : tx.get(budgetRef),
      ]);
      if (a.offerId && (!old.exists || old.data()!.companyId !== a.companyId)) denied();
      if (
        old.exists &&
        (!['draft', 'paused'].includes(old.data()!.status) ||
          Number(quota.data()?.issuedCount ?? 0) > 0)
      )
        fail('Issued or active campaigns cannot be edited. Create a new offer.');
      if (!old.exists) {
        // Check the shared day budget before the bounded workspace query: an
        // exhausted company cannot turn retries or more staff accounts into
        // repeated 100-document reads. Edits do not consume creation budget.
        const recordedToday = Number(budget?.data()?.createdCount ?? 0);
        if (recordedToday >= 10) {
          throw new HttpsError(
            'resource-exhausted',
            'This business has reached the limit of 10 new campaigns per UTC day.',
          );
        }
        const campaigns = await tx.get(
          db.collection('offers').where('companyId', '==', a.companyId).limit(101),
        );
        if (campaigns.size >= 100) {
          throw new HttpsError(
            'resource-exhausted',
            'This business has reached the 100-campaign workspace limit. Contact KCC Admin.',
          );
        }
        // Bootstrap companies created before budgets existed without giving
        // their existing campaigns a second allowance on migration day.
        const existingToday = campaigns.docs.filter(
          (s) => (millis(s.data().createdAt) ?? 0) >= dayStart,
        ).length;
        const usedToday = Math.max(recordedToday, existingToday);
        if (usedToday >= 10) {
          throw new HttpsError(
            'resource-exhausted',
            'This business has reached the limit of 10 new campaigns per UTC day.',
          );
        }
        tx.set(budgetRef, {
          companyId: a.companyId,
          day,
          createdCount: usedToday + 1,
          expiresAt: Timestamp.fromMillis(dayStart + 90 * DAY),
        });
      }
      const type = a.percentageDiscount ? 'percentage_discount' : 'member_benefit';
      tx.set(ref, {
        companyId: a.companyId,
        partnerCompanyName: company.name,
        title: a.title,
        teaserText: a.teaserText,
        offerType: type,
        status: 'draft',
        redemptionMode: 'reservation',
        availableFrom: a.availableFrom ? Timestamp.fromDate(new Date(a.availableFrom)) : null,
        availableUntil: a.availableUntil ? Timestamp.fromDate(new Date(a.availableUntil)) : null,
        capacity: a.capacity ?? null,
        reservationDays: a.reservationDays,
        remainingCount: a.capacity ?? null,
        reservationsPaused: false,
        version: Number(old.data()?.version ?? 0) + 1,
        createdAt: old.data()?.createdAt ?? stamp(),
        updatedAt: stamp(),
      });
      tx.set(ref.collection('details').doc('member'), {
        description: a.description,
        terms: a.terms,
        percentageDiscount: a.percentageDiscount ?? null,
        redemptionInstructions: null,
      });
      tx.set(quotaRef, { reservedCount: 0, redeemedCount: 0, issuedCount: 0 });
      audit(tx, uid, 'submitManagedOffer', a.companyId, 'Offer submitted for admin review.');
    });
    return { offerId: ref.id, status: 'draft' };
  }
  if (action === 'pauseReservations') {
    const a = portalSchemas.pauseReservations.parse(body);
    await db.runTransaction(async (tx) => {
      const [s] = await Promise.all([
        tx.get(db.collection('offers').doc(a.offerId)),
        staff(tx, request, a.companyId, true),
      ]);
      if (!s.exists || s.data()!.companyId !== a.companyId) denied();
      tx.update(s.ref, { reservationsPaused: a.paused, updatedAt: stamp() });
      audit(tx, uid, 'pauseReservations', a.companyId, 'New reservation availability changed.');
    });
    return { offerId: a.offerId };
  }
  if (action === 'offer') {
    await requirePaidOrAdminActor(request);
    const s = await db
      .collection('offers')
      .doc(input.offerId as string)
      .get();
    if (!s.exists) missing();
    const [c, detail, lock] = await Promise.all([
      db.collection('companies').doc(s.data()!.companyId).get(),
      s.ref.collection('details').doc('member').get(),
      lockRef(uid, s.id).get(),
    ]);
    available(s.data()!, c.data(), Date.now());
    const reservation =
      typeof lock.data()?.reservationId === 'string'
        ? await db.collection('partnerReservations').doc(lock.data()!.reservationId).get()
        : null;
    return {
      offer: offerProjection(s.id, s.data()!, detail.data()),
      reservation: reservation?.exists
        ? reservationProjection(reservation.id, reservation.data()!)
        : null,
      alreadyUsed: lock.data()?.used === true,
    };
  }
  if (action === 'reserve') {
    await requirePaidActor(request); // No admin bypass for taking a customer's place.
    const a = portalSchemas.reserve.parse(body),
      cleanupAt = Date.now();
    const offerRef = db.collection('offers').doc(a.offerId),
      claimRef = lockRef(uid, a.offerId);
    const requestRef = db
      .collection('partnerPortalRequests')
      .doc(hash(`${uid}:reserve:${a.requestId}`));
    const freshRef = db.collection('partnerReservations').doc();
    const expiredBefore = await db
      .collection('partnerReservations')
      .where('offerId', '==', a.offerId)
      .where('status', '==', 'reserved')
      .where('expiresAt', '<=', Timestamp.fromMillis(cleanupAt))
      .limit(100)
      .get();
    for (const expired of expiredBefore.docs)
      await releaseExpiredReservation(expired.ref.id, cleanupAt);
    return db.runTransaction(async (tx) => {
      const quotaRef = db.collection('partnerOfferQuotas').doc(a.offerId);
      // Lock the common quota first. Parallel offer+quota reads can deadlock a burst
      // when one caller holds the offer read lock while another holds its quota.
      const quota = await tx.get(quotaRef);
      await customerPolicy(tx, uid);
      const [o, claim, previous] = await Promise.all([
        tx.get(offerRef),
        tx.get(claimRef),
        tx.get(requestRef),
      ]);
      if (!o.exists) missing();
      const offer = o.data()!;
      if (claim.data()?.used === true) fail('This campaign has already been used.');
      const [company, detail] = await Promise.all([
        tx.get(db.collection('companies').doc(offer.companyId)),
        tx.get(offerRef.collection('details').doc('member')),
      ]);
      const now = Date.now();
      available(offer, company.data(), now);
      const ref = claim.exists
        ? db.collection('partnerReservations').doc(claim.data()!.reservationId)
        : freshRef;
      const current = claim.exists ? await tx.get(ref) : null;
      if (previous.exists) {
        if (previous.data()!.offerId !== a.offerId)
          fail('Request ID was already used for another offer.');
        if (current?.exists) return reservationProjection(ref.id, current.data()!, now);
        fail('Previous reservation is no longer available.');
      }
      if (Number(offer.version ?? 1) !== a.expectedVersion)
        fail('Offer terms changed. Review the offer again.');
      if (current?.exists && current.data()!.status === 'redeemed')
        fail('This campaign has already been used.');
      if (current?.exists && current.data()!.status === 'revoked')
        fail('This reservation has been revoked.');
      if (
        current?.exists &&
        current.data()!.status === 'reserved' &&
        (millis(current.data()!.expiresAt) ?? 0) > now
      )
        return reservationProjection(ref.id, current.data()!, now);
      if (offer.reservationsPaused) fail('New reservations are paused.');
      const expiredOwn = current?.exists && current.data()!.status === 'reserved' ? 1 : 0;
      const reserved = Math.max(0, Number(quota.data()?.reservedCount ?? 0) - expiredOwn);
      const redeemed = Number(quota.data()?.redeemedCount ?? 0);
      if (offer.capacity != null && reserved + redeemed >= offer.capacity)
        throw new HttpsError('resource-exhausted', 'All places have been reserved.');
      const expiresAt = Timestamp.fromMillis(
        Math.min(
          now + Number(offer.reservationDays ?? 7) * DAY,
          millis(offer.availableUntil) ?? Infinity,
        ),
      );
      const data = {
        uid,
        companyId: offer.companyId,
        companyName: company.data()!.name,
        offerId: a.offerId,
        title: offer.title,
        terms: detail.data()?.terms ?? '',
        version: offer.version ?? 1,
        status: 'reserved',
        expiresAt,
        createdAt: Timestamp.fromMillis(now),
        redeemedAt: null,
        tokenHash: null,
        tokenExpiresAt: null,
        requestId: a.requestId,
      };
      tx.set(ref, data);
      tx.set(claimRef, { reservationId: ref.id });
      tx.set(requestRef, {
        offerId: a.offerId,
        reservationId: ref.id,
        expiresAt: Timestamp.fromMillis(now + 30 * DAY),
      });
      tx.set(quotaRef, {
        reservedCount: reserved + 1,
        issuedCount: Number(quota.data()?.issuedCount ?? 0) + 1,
        redeemedCount: redeemed,
      });
      tx.update(offerRef, { remainingCount: remaining(offer, reserved + 1, redeemed) });
      return reservationProjection(ref.id, data, now);
    });
  }
  if (action === 'reservations') {
    const s = await db
      .collection('partnerReservations')
      .where('uid', '==', uid)
      .orderBy('createdAt', 'desc')
      .limit(100)
      .get();
    return { reservations: s.docs.map((d) => reservationProjection(d.id, d.data())) };
  }
  if (action === 'cancel') {
    const ref = db.collection('partnerReservations').doc(input.reservationId as string);
    return db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists || snap.data()!.uid !== uid) missing();
      const r = snap.data()!;
      const quotaRef = db.collection('partnerOfferQuotas').doc(r.offerId);
      const [o, quota] = await Promise.all([
        tx.get(db.collection('offers').doc(r.offerId)),
        tx.get(quotaRef),
      ]);
      if (r.status === 'reserved') {
        const status = (millis(r.expiresAt) ?? 0) <= Date.now() ? 'expired' : 'cancelled';
        tx.update(ref, { status, tokenHash: null, tokenExpiresAt: null, closedAt: stamp() });
        if (o.exists) {
          const reserved = Math.max(0, Number(quota.data()?.reservedCount ?? 0) - 1);
          tx.set(quotaRef, { reservedCount: reserved }, { merge: true });
          tx.update(o.ref, {
            remainingCount: remaining(
              o.data()!,
              reserved,
              Number(quota.data()?.redeemedCount ?? 0),
            ),
          });
        }
        return reservationProjection(snap.id, { ...r, status });
      }
      return reservationProjection(snap.id, r);
    });
  }
  if (action === 'token') {
    const token = `kcc1.${randomBytes(32).toString('base64url')}`,
      tokenHash = hash(token);
    const ref = db.collection('partnerReservations').doc(input.reservationId as string);
    const expiresAt = await db.runTransaction(async (tx) => {
      const s = await tx.get(ref);
      if (!s.exists || s.data()!.uid !== uid) missing();
      const r = s.data()!,
        [o, c] = await Promise.all([
          tx.get(db.collection('offers').doc(r.offerId)),
          tx.get(db.collection('companies').doc(r.companyId)),
          customerPolicy(tx, uid),
        ]);
      const now = Date.now();
      if (!o.exists) missing();
      available(o.data()!, c.data(), now);
      if (r.status !== 'reserved' || (millis(r.expiresAt) ?? 0) <= now)
        fail('Reservation is not usable.');
      const expiry = Timestamp.fromMillis(Math.min(now + 60000, millis(r.expiresAt)!));
      tx.set(db.collection('partnerRedemptionTokens').doc(tokenHash), {
        reservationId: s.id,
        expiresAt: expiry,
      });
      tx.update(ref, { tokenHash, tokenExpiresAt: expiry });
      return expiry;
    });
    return { token, expiresAt: expiresAt.toDate().toISOString() };
  }
  if (action === 'inspect' || action === 'redeem') {
    const token = input.token as string;
    if (!/^kcc1\.[A-Za-z0-9_-]{43}$/.test(token)) missing();
    const tokenHash = hash(token);
    const requestId = action === 'redeem' ? (input.requestId as string) : null;
    const retryRef = requestId
      ? db.collection('partnerPortalRequests').doc(hash(`${uid}:redeem:${requestId}`))
      : null;
    return db.runTransaction(async (tx) => {
      const [t, retry] = await Promise.all([
        tx.get(db.collection('partnerRedemptionTokens').doc(tokenHash)),
        retryRef ? tx.get(retryRef) : null,
      ]);
      if (retry?.exists) {
        if (retry.data()!.tokenHash !== tokenHash) fail('Request ID has already been used.');
        await staff(tx, request, retry.data()!.companyId);
        return { reservation: retry.data()!.reservation, receiptId: retry.data()!.receiptId };
      }
      if (!t.exists) missing();
      const ref = db.collection('partnerReservations').doc(t.data()!.reservationId);
      const s = await tx.get(ref);
      if (!s.exists) missing();
      const r = s.data()!;
      const quotaRef = db.collection('partnerOfferQuotas').doc(r.offerId);
      const [company, o, customer, quota] = await Promise.all([
        staff(tx, request, r.companyId),
        tx.get(db.collection('offers').doc(r.offerId)),
        tx.get(db.collection('users').doc(r.uid)),
        tx.get(quotaRef),
        customerPolicy(tx, r.uid),
      ]);
      const now = Date.now();
      if ((millis(t.data()!.expiresAt) ?? 0) <= now) missing();
      if (!customer.exists || isRestricted(toUserAccessState(customer.data()))) denied();
      if (!o.exists) missing();
      available(o.data()!, company, now);
      if (r.status !== 'reserved' || r.tokenHash !== tokenHash || (millis(r.expiresAt) ?? 0) <= now)
        fail('Reservation is not usable.');
      if (action === 'inspect') return { reservation: reservationProjection(s.id, r, now) };
      const receiptId = randomUUID(),
        projection = reservationProjection(
          s.id,
          { ...r, status: 'redeemed', redeemedAt: Timestamp.fromMillis(now) },
          now,
        );
      const offer = o.data()!,
        reserved = Math.max(0, Number(quota.data()?.reservedCount ?? 0) - 1),
        redeemed = Number(quota.data()?.redeemedCount ?? 0) + 1;
      tx.update(ref, {
        status: 'redeemed',
        tokenHash: null,
        tokenExpiresAt: null,
        redeemedAt: Timestamp.fromMillis(now),
        closedAt: stamp(),
      });
      tx.set(quotaRef, { reservedCount: reserved, redeemedCount: redeemed }, { merge: true });
      tx.update(o.ref, { remainingCount: remaining(offer, reserved, redeemed) });
      tx.set(retryRef!, {
        companyId: r.companyId,
        tokenHash,
        reservation: projection,
        receiptId,
        expiresAt: Timestamp.fromMillis(now + 30 * DAY),
      });
      // Outbox carries no UID, reservation ID, receipt or exact redemption timestamp.
      tx.set(db.collection('partnerRedemptionOutbox').doc(randomUUID()), {
        companyId: r.companyId,
        offerId: r.offerId,
        companyName: r.companyName,
        title: r.title,
        month: new Date(now).toISOString().slice(0, 7),
        count: 1,
        processed: false,
      });
      return { reservation: projection, receiptId };
    });
  }
  if (action === 'driveHeat') {
    // Re-read authority in the SAME transaction as the published snapshot.
    // Entry guards and a cached workspace role do not authorize this read.
    return db.runTransaction(async (tx) => {
      await staff(tx, request, input.companyId as string, true);
      const snapshot = await tx.get(db.collection('partnerDriveHeat').doc('current'));
      return projectPartnerDriveHeat(snapshot.data(), Date.now());
    });
  }
  if (action === 'statistics' || action === 'adminStatistics') {
    const period = input.period as string,
      companyId = input.companyId as string | undefined;
    if (period !== 'all' && period >= new Date().toISOString().slice(0, 7))
      throw new HttpsError('invalid-argument', 'Choose a closed calendar month.');
    if (action === 'statistics')
      await db.runTransaction((tx) => staff(tx, request, companyId!, true));
    const state = await db.collection('partnerRedemptionStatsState').doc('published').get();
    const snapshot = state.data()?.generation as string | undefined;
    if (!snapshot)
      return {
        updatedAt: iso(state.data()?.updatedAt) ?? new Date(0).toISOString(),
        period,
        totalRedeemed: 0,
        partners: [],
      };
    let query = db
      .collection('partnerRedemptionStatsPublic')
      .doc(snapshot)
      .collection('entries')
      .where('period', '==', period);
    if (companyId) query = query.where('companyId', '==', companyId);
    const rows = await query.limit(10001).get();
    if (rows.size > 10000)
      throw new HttpsError(
        'resource-exhausted',
        'This statistics scope exceeds the initial reporting limit. Select a partner.',
      );
    const partners = new Map<
      string,
      {
        companyId: string;
        companyName: string;
        totalRedeemed: number;
        offers: { offerId: string; title: string; totalRedeemed: number }[];
      }
    >();
    for (const s of rows.docs) {
      const d = s.data();
      const p: {
        companyId: string;
        companyName: string;
        totalRedeemed: number;
        offers: { offerId: string; title: string; totalRedeemed: number }[];
      } = partners.get(d.companyId) ?? {
        companyId: d.companyId,
        companyName: d.companyName,
        totalRedeemed: 0,
        offers: [],
      };
      p.totalRedeemed += d.totalRedeemed;
      p.offers.push({ offerId: d.offerId, title: d.title, totalRedeemed: d.totalRedeemed });
      partners.set(d.companyId, p);
    }
    return {
      updatedAt: iso(state.data()?.updatedAt),
      period,
      totalRedeemed: [...partners.values()].reduce((n, p) => n + p.totalRedeemed, 0),
      partners: [...partners.values()],
    };
  }
  if (action === 'billboardLogo') {
    const ref = db.collection('billboards').doc(input.billboardId as string),
      b = await ref.get();
    if (!b.exists) missing();
    if (!['draft', 'paused'].includes(b.data()!.status))
      fail('Pause the billboard before replacing its logo.');
    const company = await db.collection('companies').doc(b.data()!.partnerCompanyId).get();
    const path = company.data()?.logoPath;
    if (typeof path !== 'string' || !path.startsWith(`companyImages/${company.id}/`))
      fail('Publish a company logo first.');
    const imagePath = `billboardImages/${b.id}/${hash(path)}.png`;
    await adminStorage.bucket().file(path).copy(adminStorage.bucket().file(imagePath));
    await db.runTransaction(async (tx) => {
      await requireAdminActorInTransaction(request, tx);
      const current = await tx.get(ref);
      if (!current.exists || current.data()!.partnerCompanyId !== company.id)
        fail('Billboard changed. Reload.');
      if (!['draft', 'paused'].includes(current.data()!.status))
        fail('Pause the billboard before replacing its logo.');
      tx.update(ref, { imagePath, updatedAt: stamp() });
      audit(tx, uid, 'setBillboardLogo', b.id, 'Validated company logo assigned to billboard.');
    });
    return { imagePath };
  }
  throw new HttpsError('invalid-argument', 'Unknown command.');
}
export const portal = onCall(CALLABLE_OPTS, async (request) => {
  try {
    return await handlePortal(request);
  } catch (error) {
    // Firestore permits finite retries under contention; preserve an explicit,
    // safe client retry instead of turning an aborted reservation into INTERNAL.
    if (
      error &&
      typeof error === 'object' &&
      'code' in error &&
      (error.code === 10 || error.code === 4)
    )
      throw new HttpsError('aborted', 'High demand. Retry the same request.');
    throw error;
  }
});

async function releaseExpiredReservation(id: string, now: number) {
  const ref = db.collection('partnerReservations').doc(id);
  const initial = await ref.get();
  if (!initial.exists) return;
  await db.runTransaction(async (tx) => {
    const quotaRef = db.collection('partnerOfferQuotas').doc(initial.data()!.offerId);
    const quota = await tx.get(quotaRef);
    const r = await tx.get(ref);
    if (
      !r.exists ||
      r.data()!.status !== 'reserved' ||
      (millis(r.data()!.expiresAt) ?? Infinity) > now
    )
      return;
    const o = await tx.get(db.collection('offers').doc(r.data()!.offerId));
    tx.update(ref, { status: 'expired', tokenHash: null, tokenExpiresAt: null, closedAt: stamp() });
    if (o.exists) {
      const reserved = Math.max(0, Number(quota.data()?.reservedCount ?? 0) - 1);
      tx.set(quotaRef, { reservedCount: reserved }, { merge: true });
      tx.update(o.ref, {
        remainingCount: remaining(o.data()!, reserved, Number(quota.data()?.redeemedCount ?? 0)),
      });
    }
  });
}
/** Reservation expiry and data minimization are server-owned, not TTL correctness. */
export async function expireReservations(now = Date.now()) {
  const records = await db
    .collection('partnerReservations')
    .where('status', '==', 'reserved')
    .where('expiresAt', '<=', Timestamp.fromMillis(now))
    .limit(200)
    .get();
  for (const s of records.docs)
    await db.runTransaction(async (tx) => {
      const r = await tx.get(s.ref);
      if (
        !r.exists ||
        r.data()!.status !== 'reserved' ||
        (millis(r.data()!.expiresAt) ?? Infinity) > now
      )
        return;
      const quotaRef = db.collection('partnerOfferQuotas').doc(r.data()!.offerId);
      const [o, quota] = await Promise.all([
        tx.get(db.collection('offers').doc(r.data()!.offerId)),
        tx.get(quotaRef),
      ]);
      tx.update(r.ref, {
        status: 'expired',
        tokenHash: null,
        tokenExpiresAt: null,
        closedAt: stamp(),
      });
      if (o.exists) {
        const reserved = Math.max(0, Number(quota.data()?.reservedCount ?? 0) - 1);
        tx.set(quotaRef, { reservedCount: reserved }, { merge: true });
        tx.update(o.ref, {
          remainingCount: remaining(o.data()!, reserved, Number(quota.data()?.redeemedCount ?? 0)),
        });
      }
    });
  const closed = await db
    .collection('partnerReservations')
    .where('closedAt', '<', Timestamp.fromMillis(now - 30 * DAY))
    .limit(200)
    .get();
  for (const s of closed.docs)
    await db.runTransaction(async (tx) => {
      const current = await tx.get(s.ref);
      if (!current.exists) return;
      const r = current.data()!;
      // A cancelled/expired record may have been reserved again after the query.
      if (r.status === 'reserved' || (millis(r.closedAt) ?? Infinity) >= now - 30 * DAY) return;
      const references = await readPartnerReservationErasurePage(tx, current.id);
      if (!references.complete) {
        references.refs.forEach((ref) => tx.delete(ref));
        return;
      }
      const claim = lockRef(r.uid, r.offerId);
      const [locked, offer] = await Promise.all([
        tx.get(claim),
        tx.get(db.collection('offers').doc(r.offerId)),
      ]);
      references.refs.forEach((ref) => tx.delete(ref));
      if (locked.data()?.reservationId === current.id) {
        if (r.status === 'redeemed' || r.status === 'revoked') {
          const end = millis(offer.data()?.availableUntil);
          tx.set(claim, {
            used: true,
            offerId: r.offerId,
            expiresAt: end == null ? null : Timestamp.fromMillis(end + 30 * DAY),
          });
        } else tx.delete(claim);
      }
      // Remove the exact usage record and the pointer to it, not merely its UID.
      tx.delete(current.ref);
    });
  for (const name of [
    'partnerRedemptionTokens',
    'partnerPortalRequests',
    'partnerPortalRateLimits',
    'partnerCompanyBudgets',
  ]) {
    const expired = await db
      .collection(name)
      .where('expiresAt', '<=', Timestamp.fromMillis(now))
      .limit(200)
      .get();
    if (!expired.empty) {
      const cleanup = db.batch();
      expired.docs.forEach((s) => cleanup.delete(s.ref));
      await cleanup.commit();
    }
  }
  // Retire minimal anti-reuse tombstones after their campaign ends. An open-ended
  // live campaign needs this one bit for as long as its one-use promise applies.
  const cursorRef = db.collection('partnerPortalMaintenance').doc('claims');
  const cursor = (await cursorRef.get()).data()?.after as string | undefined;
  let query = db.collection('partnerClaimLocks').orderBy('__name__').limit(200);
  if (cursor) query = query.startAfter(cursor);
  const claims = await query.get();
  for (const claim of claims.docs) {
    if (!claim.data().used || typeof claim.data().offerId !== 'string') continue;
    await db.runTransaction(async (tx) => {
      const current = await tx.get(claim.ref);
      if (!current.data()?.used) return;
      const offer = await tx.get(db.collection('offers').doc(current.data()!.offerId));
      const until = millis(current.data()!.expiresAt);
      const ended =
        offer.exists &&
        ['ended', 'expired'].includes(offer.data()!.status) &&
        (millis(offer.data()!.updatedAt) ?? now) < now - 30 * DAY;
      if ((until != null && until <= now) || ended || !offer.exists) tx.delete(current.ref);
    });
  }
  await cursorRef.set({ after: claims.size === 200 ? claims.docs.at(-1)!.id : null });
  return records.size;
}
export const sweepPortal = onSchedule(
  {
    cpu: CPU_SCHEDULED,
    schedule: 'every 5 minutes',
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    memory: '256MiB',
    timeZone: 'Europe/Stockholm',
  },
  async () => {
    await expireReservations();
  },
);
