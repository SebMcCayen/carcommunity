import {
  onCall as firebaseOnCall,
  type CallableOptions,
  type CallableRequest,
  type CallableResponse,
} from 'firebase-functions/v2/https';
import { requireCarCommunityAccess } from './carCommunity';
// Retain the SDK's contextual request.data typing at existing entrypoints.
// Every payload still passes its existing domain schema/authorization checks.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function onCall<T = any, Return = unknown, Stream = unknown>(
  options: CallableOptions<T>,
  handler: (request: CallableRequest<T>, response?: CallableResponse<Stream>) => Return,
) {
  return firebaseOnCall(options, async (request, response?: CallableResponse<Stream>) => {
    if (request.auth) await requireCarCommunityAccess(request.auth.uid);
    return handler(request, response);
  });
}
