/** Backward-compatible module path; legacy targetUid/reason requests remain valid. */
export { warnUser } from './warnings';
export interface WarnUserResponse {
  targetUid: string;
  actionId: string;
  alreadyApplied?: boolean;
  notificationStatus?: string;
}
