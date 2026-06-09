import type {
  SubscriptionEntitlement,
  UserRole,
  UserStatus,
} from './users.js';
import { isSuspendedStatus } from './users.js';

export const LIVE_LOCATION_SESSION_STATUSES = ['active', 'stopped', 'expired'] as const;
export type LiveLocationSessionStatus = (typeof LIVE_LOCATION_SESSION_STATUSES)[number];

export const LIVE_LOCATION_DURATIONS = ['1h', '2h', '4h'] as const;
export type LiveLocationDuration = (typeof LIVE_LOCATION_DURATIONS)[number];

export const LIVE_LOCATION_STOP_REASONS = ['user_stop', 'hide_me_now', 'admin_stop'] as const;
export type LiveLocationStopReason = (typeof LIVE_LOCATION_STOP_REASONS)[number];

export const LIVE_LOCATION_ROUTE_PATHS = {
  sessions: '/v1/live-location/sessions',
  hideMeNow: '/v1/live-location/hide-me-now',
  markers: '/v1/live-location/markers',
  adminSummary: '/v1/admin/live-location',
} as const;

export const DEFAULT_LIVE_LOCATION_PAGE_SIZE = 20;
export const MAX_LIVE_LOCATION_PAGE_SIZE = 50;
export const LIVE_LOCATION_TTL_MINUTES_MAX = 15;

export const LIVE_LOCATION_DURATION_MS: Record<LiveLocationDuration, number> = {
  '1h': 60 * 60 * 1000,
  '2h': 2 * 60 * 60 * 1000,
  '4h': 4 * 60 * 60 * 1000,
};

export interface LiveLocationCoordinate {
  latitude: number;
  longitude: number;
  accuracyMeters?: number;
  headingDegrees?: number;
  speedMetersPerSecond?: number;
  recordedAt: string;
}

export interface LiveLocationSessionSummary {
  id: string;
  status: LiveLocationSessionStatus;
  duration: LiveLocationDuration;
  startedAt: string;
  expiresAt: string;
  stoppedAt: string | null;
}

export interface LiveLocationStartRequest {
  duration: LiveLocationDuration;
}

export interface LiveLocationUpdateRequest {
  coordinate: LiveLocationCoordinate;
}

export interface LiveLocationStopRequest {
  reason?: LiveLocationStopReason;
}

export interface LiveLocationResponseMeta {
  source: 'placeholder' | 'database';
  productionReady: boolean;
  ttlCleanupPrepared: boolean;
}

export interface LiveLocationPaginationMeta extends LiveLocationResponseMeta {
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
}

export interface LiveLocationOwnSessionResponse {
  ok: true;
  data: {
    session: LiveLocationSessionSummary;
    latestPosition: LiveLocationCoordinate | null;
    latestPositionRemoved: boolean;
  };
  meta: LiveLocationResponseMeta;
}

export type LiveLocationStartResponse = LiveLocationOwnSessionResponse;
export type LiveLocationPositionUpdateResponse = LiveLocationOwnSessionResponse;
export type LiveLocationStopResponse = LiveLocationOwnSessionResponse;
export type HideMeNowResponse = LiveLocationOwnSessionResponse;

export interface PublicLiveLocationMarker {
  sessionId: string;
  coordinate: LiveLocationCoordinate;
  status: Extract<LiveLocationSessionStatus, 'active'>;
}

export interface PublicLiveLocationMarkerResponse {
  ok: true;
  data: {
    markers: PublicLiveLocationMarker[];
    generatedAt: string;
  };
  meta: LiveLocationPaginationMeta;
}

export interface AdminLiveLocationSessionSummary {
  sessionId: string;
  status: LiveLocationSessionStatus;
  startedAt: string;
  expiresAt: string;
  supportAction: 'placeholder_only';
}

export interface AdminLiveLocationSummaryResponse {
  ok: true;
  data: {
    activeSessionCount: number;
    expiredSessionCount: number;
    operationalStatus: 'placeholder_safe_default';
    featureFlagKey: 'liveLocation';
    featureFlagEnabled: boolean;
    latestPositionTtlMinutesMax: number;
    sessions: AdminLiveLocationSessionSummary[];
  };
  meta: LiveLocationPaginationMeta;
}

export function buildLiveLocationPositionPath(sessionId: string): string {
  return `${LIVE_LOCATION_ROUTE_PATHS.sessions}/${sessionId}/position`;
}

export function buildLiveLocationStopPath(sessionId: string): string {
  return `${LIVE_LOCATION_ROUTE_PATHS.sessions}/${sessionId}/stop`;
}

export function getLiveLocationDurationMs(duration: LiveLocationDuration): number {
  return LIVE_LOCATION_DURATION_MS[duration];
}

export function calculateLiveLocationExpiresAt(
  startedAt: Date | string,
  duration: LiveLocationDuration,
): string {
  const startedAtDate = typeof startedAt === 'string' ? new Date(startedAt) : startedAt;

  return new Date(startedAtDate.getTime() + getLiveLocationDurationMs(duration)).toISOString();
}

export function canViewOtherUsersLiveLocation(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }

  if (input.role === 'admin' || input.role === 'owner') {
    return true;
  }

  return input.subscriptionEntitlement === 'member_monthly';
}
