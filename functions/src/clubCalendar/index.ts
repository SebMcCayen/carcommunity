import { onRequest, HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { adminAuth, db } from '../firebase';
import { CPU_ADMIN, MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { ClubCalendarService } from './service';
import { handleFeed, feedAdmission } from './http';

const OPTIONS = {
  region: 'europe-west1',
  minInstances: 0,
  maxInstances: MAX_INSTANCES_SCHEDULED,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  cpu: CPU_ADMIN,
  concurrency: 1,
};
const CALLABLE_OPTS = {
  ...OPTIONS,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const service = new ClubCalendarService(
  db,
  (uid) => adminAuth.getUser(uid),
  process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT,
);

export const manage = onCall(CALLABLE_OPTS, async (request) => {
  try {
    return await service.manage(request);
  } catch (error) {
    if (error instanceof HttpsError) throw error;
    // Never let SDK/path/document/internal error strings reach clients or logs.
    throw new HttpsError('unavailable', 'Calendar temporarily unavailable.');
  }
});

// Calendar providers cannot mint Firebase Auth or App Check tokens. The 256-bit
// URL bearer is scoped to ONE current membership and reauthorized at every poll.
// Platform requestUrl logging requires the documented operator mitigation.
const admit = feedAdmission();
export const feed = onRequest(OPTIONS, (request, response) =>
  handleFeed(service, request, response, admit),
);
