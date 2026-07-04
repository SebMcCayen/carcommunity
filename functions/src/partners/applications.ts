/**
 * partners.submitApplication (authenticated) and
 * partners.reviewApplication (admin) — callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `partners` export group. Legacy
 * partner-application-service parity:
 *
 * - Applications carry contact data and are NEVER client-readable; only the
 *   submit callable writes them and only admins (Admin SDK / callable)
 *   process them.
 * - Duplicate-spam guard: a user or contact email with an active
 *   (submitted/under_review) application cannot submit another.
 * - Review flow: submitted → under_review → approved | rejected. Approval
 *   creates the draft partner company IN THE SAME TRANSACTION and links it
 *   on the application; approving twice returns the existing company
 *   (idempotent). Every review step is audited.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { requireActiveActor } from '../shared/memberActor';
import {
  buildApplicationDocument,
  parseReviewApplicationInput,
  parseSubmitApplicationInput,
} from './partners-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface SubmitApplicationResponse {
  applicationId: string;
  status: 'submitted';
}

export const submitApplication = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SubmitApplicationResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseSubmitApplicationInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;
    const applicationsRef = db.collection('partnerApplications');

    // Duplicate-spam guard (legacy): one active application per user or
    // contact email.
    const [byUser, byEmail] = await Promise.all([
      applicationsRef
        .where('submittedByUserId', '==', actor.uid)
        .where('status', 'in', ['submitted', 'under_review'])
        .limit(1)
        .get(),
      applicationsRef
        .where('contactEmail', '==', input.contactEmail.toLowerCase())
        .where('status', 'in', ['submitted', 'under_review'])
        .limit(1)
        .get(),
    ]);
    if (!byUser.empty || !byEmail.empty) {
      throw new HttpsError(
        'already-exists',
        'An application for this user or contact email is already under review.',
      );
    }

    const applicationRef = applicationsRef.doc();
    await applicationRef.set(
      buildApplicationDocument(input, actor.uid, () => FieldValue.serverTimestamp()),
    );

    return { applicationId: applicationRef.id, status: 'submitted' };
  },
);

export interface ReviewApplicationResponse {
  applicationId: string;
  status: string;
  /** Set when action = approve. */
  partnerCompanyId: string | null;
}

export const reviewApplication = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ReviewApplicationResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseReviewApplicationInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { applicationId, action, note } = parsed.input;
    const applicationRef = db.collection('partnerApplications').doc(applicationId);
    const serverTimestamp = () => FieldValue.serverTimestamp();

    return db.runTransaction(async (tx) => {
      const snap = await tx.get(applicationRef);
      if (!snap.exists) {
        throw new HttpsError('not-found', 'Partner application not found.');
      }
      const application = snap.data()!;
      const status = application.status as string;

      if (action === 'start_review') {
        if (status !== 'submitted') {
          throw new HttpsError(
            'failed-precondition',
            `Cannot start review of an application in status "${status}".`,
          );
        }
        tx.update(applicationRef, {
          status: 'under_review',
          reviewedByUserId: actor.uid,
          updatedAt: serverTimestamp(),
        });
        tx.set(
          db.collection('adminAuditEvents').doc(),
          buildAdminAuditEvent(
            {
              adminId: actor.uid,
              action: 'partners.startApplicationReview',
              targetType: 'partnerApplication',
              targetId: applicationId,
              reason: note?.trim() || 'Review started.',
            },
            serverTimestamp,
          ),
        );
        return { applicationId, status: 'under_review', partnerCompanyId: null };
      }

      if (action === 'approve') {
        // Idempotent: a company already created for this application wins.
        if (application.partnerCompanyId) {
          return {
            applicationId,
            status,
            partnerCompanyId: application.partnerCompanyId as string,
          };
        }
        if (!['submitted', 'under_review'].includes(status)) {
          throw new HttpsError(
            'failed-precondition',
            `Cannot approve an application in status "${status}".`,
          );
        }

        const companyRef = db.collection('companies').doc();
        tx.set(companyRef, {
          name: application.companyName,
          category: application.category,
          description: application.proposedDescription ?? null,
          website: application.websiteUrl ?? null,
          phone: application.contactPhone ?? null,
          address: application.proposedAddress ?? null,
          latitude: null,
          longitude: null,
          logoPath: null,
          status: 'draft',
          sourceApplicationId: applicationId,
          createdByUserId: actor.uid,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
        tx.update(applicationRef, {
          status: 'approved',
          reviewedByUserId: actor.uid,
          reviewNote: note?.trim() ?? null,
          partnerCompanyId: companyRef.id,
          decidedAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
        tx.set(
          db.collection('adminAuditEvents').doc(),
          buildAdminAuditEvent(
            {
              adminId: actor.uid,
              action: 'partners.approveApplication',
              targetType: 'partnerApplication',
              targetId: applicationId,
              reason: note?.trim() || 'Application approved.',
              details: { partnerCompanyId: companyRef.id },
            },
            serverTimestamp,
          ),
        );
        return { applicationId, status: 'approved', partnerCompanyId: companyRef.id };
      }

      // action === 'reject'
      if (!['submitted', 'under_review'].includes(status)) {
        throw new HttpsError(
          'failed-precondition',
          `Cannot reject an application in status "${status}".`,
        );
      }
      if (!note?.trim()) {
        throw new HttpsError('invalid-argument', 'A note is required to reject an application.');
      }
      tx.update(applicationRef, {
        status: 'rejected',
        reviewedByUserId: actor.uid,
        reviewNote: note.trim(),
        decidedAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'partners.rejectApplication',
            targetType: 'partnerApplication',
            targetId: applicationId,
            reason: note.trim(),
          },
          serverTimestamp,
        ),
      );
      return { applicationId, status: 'rejected', partnerCompanyId: null };
    });
  },
);
