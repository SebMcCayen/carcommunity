import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { db, adminAuth } from '../firebase';
import { writeInAppNotification } from '../notifications/deliver';
import { CPU_TRIGGER, MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';
import { ClubInvitationNotifications, newInvitationGeneration } from './service';

export const clubInvitationNotifications = new ClubInvitationNotifications(
  db,
  (uid) => adminAuth.getUser(uid),
  writeInAppNotification,
);

export const onInvitationWritten = onDocumentWritten(
  {
    region: 'europe-west1',
    document: 'driverClubs/{clubId}/invitations/{recipientUid}',
    minInstances: 0,
    maxInstances: MAX_INSTANCES_TRIGGER,
    cpu: CPU_TRIGGER,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 60,
    retry: true,
  },
  async (event) => {
    const after = event.data?.after.data();
    if (
      !newInvitationGeneration(
        event.params.clubId,
        event.params.recipientUid,
        event.data?.before.data(),
        after,
      )
    )
      return;
    await clubInvitationNotifications.process(
      event.params.clubId,
      event.params.recipientUid,
      after,
    );
  },
);
