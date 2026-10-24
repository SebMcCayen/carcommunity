import { onCall } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { DriverFeedReader } from './reader';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  minInstances: 0,
  maxInstances: MAX_INSTANCES_MEMBER,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};
const reader = new DriverFeedReader(db);

export const list = onCall(CALLABLE_OPTS, (request) => reader.list(request));
export const detail = onCall(CALLABLE_OPTS, (request) => reader.detail(request));
