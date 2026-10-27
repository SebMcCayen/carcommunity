import { adminAccessView } from '../access/adminAccess';
import { createHash } from 'node:crypto';
import { FieldValue } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { z } from 'zod';
import { db } from '../firebase';
import { requireAdminActor } from './actorContext';
import { buildAdminAuditEvent, guardModerationTarget } from './claims-core';
import { isOwnerRole, toUserAccessState } from '../shared/access';
import { DISPLAY_NAME_MAX_LENGTH } from '../auth/provisioning';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const uid = z
  .string()
  .trim()
  .min(1)
  .max(128)
  .refine((value) => !value.includes('/') && value !== '.' && value !== '..');
const reason = z.string().trim().min(1).max(500);
const name = z
  .string()
  .trim()
  .min(1)
  .max(DISPLAY_NAME_MAX_LENGTH)
  .refine((value) => !/[\u0000-\u001f\u007f]/.test(value));
const rename = z
  .object({
    targetUid: uid,
    displayName: name,
    previousDisplayName: z.string().max(500),
    expectedActorUid: uid,
    reason,
    idempotencyKey: z.string().regex(/^[A-Za-z0-9._-]{16,128}$/),
  })
  .strict();
function date(value: unknown): string | null {
  return value && typeof (value as { toDate?: unknown }).toDate === 'function'
    ? (value as FirebaseFirestore.Timestamp).toDate().toISOString()
    : null;
}
export const updateDisplayName = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request);
  const parsed = rename.safeParse(request.data);
  if (!parsed.success)
    throw new HttpsError(
      'invalid-argument',
      'Ange ett giltigt visningsnamn, tidigare namn och en orsak.',
    );
  const input = parsed.data;
  if (input.expectedActorUid !== actor.uid)
    throw new HttpsError(
      'permission-denied',
      'Administratörens session har ändrats. Läs medlemskortet igen.',
    );
  const fingerprint = createHash('sha256').update(JSON.stringify(input)).digest('hex');
  const operationId =
    'name-' +
    createHash('sha256')
      .update(JSON.stringify([actor.uid, input.idempotencyKey]))
      .digest('hex');
  const receipt = db
    .collection('adminMemberOperations')
    .doc(input.targetUid)
    .collection('items')
    .doc(operationId);
  const target = db.collection('users').doc(input.targetUid);
  return db.runTransaction(async (tx) => {
    const [profile, operation] = await Promise.all([tx.get(target), tx.get(receipt)]);
    if (!profile.exists) throw new HttpsError('not-found', 'Medlemmen finns inte.');
    if (
      isOwnerRole(toUserAccessState(profile.data()).role) &&
      actor.uid !== input.targetUid &&
      !isOwnerRole(actor.state.role)
    )
      throw new HttpsError(
        'permission-denied',
        'Endast en ägare får ändra en annan ägares profil.',
      );
    if (operation.exists) {
      if (operation.get('fingerprint') !== fingerprint)
        throw new HttpsError('failed-precondition', 'Åtgärdsnyckeln avser en annan namnändring.');
      return { ...operation.get('result'), alreadyApplied: true };
    }
    if (String(profile.get('displayName') ?? '') !== input.previousDisplayName)
      throw new HttpsError(
        'failed-precondition',
        'Namnet har ändrats sedan kortet lästes. Uppdatera och granska ändringen igen.',
      );
    const result = {
      targetUid: input.targetUid,
      displayName: input.displayName,
      previousDisplayName: input.previousDisplayName,
      alreadyApplied: false,
    };
    tx.update(target, { displayName: input.displayName, updatedAt: FieldValue.serverTimestamp() });
    tx.create(receipt, { fingerprint, result, createdAt: FieldValue.serverTimestamp() });
    tx.create(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'user.displayName.update',
          targetType: 'user',
          targetId: input.targetUid,
          reason: input.reason,
          details: {
            previousDisplayName: input.previousDisplayName,
            displayName: input.displayName,
          },
        },
        () => FieldValue.serverTimestamp(),
      ),
    );
    return result;
  });
});

export const getMember = onCall(CALLABLE_OPTS, async (request) => {
  const actor = await requireAdminActor(request);
  const input = z.object({ targetUid: uid }).strict().safeParse(request.data);
  if (!input.success) throw new HttpsError('invalid-argument', 'Invalid member request.');
  const targetUid = input.data.targetUid;
  const [profile, lifecycle, wallet] = await Promise.all([
    db.collection('users').doc(targetUid).get(),
    db.collection('userLifecycle').doc(targetUid).get(),
    db.collection('pointsLedger').doc(targetUid).get(),
  ]);
  if (!profile.exists) return { user: null };
  const data = profile.data()!,
    state = toUserAccessState(data);
  const moderate = guardModerationTarget({
    actorUid: actor.uid,
    actorRole: actor.state.role,
    targetUid,
    targetRole: state.role,
  }).ok;
  const editOwner =
    !isOwnerRole(state.role) || actor.uid === targetUid || isOwnerRole(actor.state.role);
  return {
    user: {
      uid: targetUid,
      actorUid: actor.uid,
      displayName: String(data.displayName ?? ''),
      role: state.role,
      activeMember: state.activeMember,
      suspended: state.suspended,
      deleted: state.deleted,
      bio: typeof data.bio === 'string' ? data.bio : null,
      avatarPath: typeof data.avatarPath === 'string' ? data.avatarPath : null,
      createdAt: date(data.createdAt),
      updatedAt: date(data.updatedAt),
      lastLoginAt: date(lifecycle.get('lastLoginAt')),
      loginFrequency: null,
      carCommunityAccess: await adminAccessView(targetUid),
      pointsBalance: wallet.get('balance') ?? 0,
      permissions: {
        canRename: editOwner,
        canWarn: moderate,
        canSuspend: moderate && !state.suspended && !state.deleted,
        canRestore: moderate && state.suspended && !state.deleted,
        canAdjustPoints: editOwner && !state.suspended && !state.deleted,
        canReversePoints: editOwner,
      },
    },
  };
});
