import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { DriverFeedReader } from '../driverFeed/reader';
import { publicContentReportSchema } from './public-content-core';
import { filePublicContentReport } from './reportStore';
import { requireActiveActor } from '../shared/memberActor';
import { readFeatureFlag } from '../shared/featureFlags';
import { BILLBOARDS_FLAG_KEY, isBillboardMapVisible } from '../billboards/billboards-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

export const reportPublicContent = onCall(
  {
    region: 'europe-west1',
    memory: '256MiB',
    maxInstances: MAX_INSTANCES_MEMBER,
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request) => {
    const actor = await requireActiveActor(request);
    const parsed = publicContentReportSchema.safeParse(request.data);
    if (!parsed.success) throw new HttpsError('invalid-argument', 'Invalid content report.');
    const input = parsed.data;
    if (input.expectedUid !== actor.uid)
      throw new HttpsError('permission-denied', 'Account changed.');
    if (input.surface !== 'billboard') {
      const snapshot = await new DriverFeedReader(db).captureForReport(
        request,
        input.threadId,
        input.surface === 'driver_comment' ? input.commentId : undefined,
      );
      if (snapshot.authorUserId === actor.uid)
        throw new HttpsError('invalid-argument', 'Cannot report your own content.');
      return filePublicContentReport({
        surface: input.surface,
        scopeId: input.threadId,
        targetId: input.surface === 'driver_comment' ? input.commentId : input.threadId,
        reporterUserId: actor.uid,
        reason: input.reason,
        snapshot,
      });
    }
    if (!(await readFeatureFlag(BILLBOARDS_FLAG_KEY)))
      throw new HttpsError('not-found', 'Advertisement unavailable.');
    const item = await db.collection('billboards').doc(input.billboardId).get();
    const data = item.data();
    const date = (value: unknown) =>
      value != null && typeof (value as { toDate?: unknown }).toDate === 'function'
        ? (value as { toDate: () => Date }).toDate()
        : value == null
          ? null
          : new Date(NaN);
    if (
      !data ||
      !isBillboardMapVisible(
        String(data.status),
        date(data.availableFrom),
        date(data.availableUntil),
        new Date(),
      )
    ) {
      throw new HttpsError('not-found', 'Advertisement unavailable.');
    }
    return filePublicContentReport({
      surface: 'billboard',
      scopeId: input.billboardId,
      targetId: input.billboardId,
      reporterUserId: actor.uid,
      reason: input.reason,
      snapshot: {
        authorUserId: null,
        authorDisplayName: null,
        text: [data.headline, data.message]
          .filter((v): v is string => typeof v === 'string')
          .join('\n')
          .slice(0, 4000),
        createdAt: data.createdAt ?? null,
      },
    });
  },
);
