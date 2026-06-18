/**
 * Shared contracts for onboarding, user profile updates, privacy settings,
 * and app settings links.
 *
 * Backend is the source of truth for all profile and privacy settings.
 */

import type { SubscriptionEntitlement, UserRole, UserStatus } from './users.js';

export const ONBOARDING_ROUTE_PATHS = {
  me: '/v1/users/me',
  profile: '/v1/users/me/profile',
  privacySettings: '/v1/users/me/privacy-settings',
} as const;

export const APP_SETTINGS_LINKS_PATH = '/v1/app/settings-links';

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

export interface UserProfileUpdateRequest {
  /** Optional display name. Max 120 characters. Pass null to clear. */
  displayName?: string | null;
  /** Pass true to set age confirmation timestamp. Cannot be unset. */
  ageConfirmed?: boolean;
  /** Pass true to set terms acceptance timestamp. Cannot be unset. */
  termsAccepted?: boolean;
  /** Pass true to set privacy policy acceptance timestamp. Cannot be unset. */
  privacyPolicyAccepted?: boolean;
}

export interface OnboardingStatusData {
  onboardingCompletedAt: string | null;
  ageConfirmedAt: string | null;
  termsAcceptedAt: string | null;
  privacyPolicyAcceptedAt: string | null;
}

export interface UserProfileData {
  id: string;
  displayName: string | null;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  onboarding: OnboardingStatusData;
}

export interface UserProfileResponse {
  ok: true;
  data: {
    user: UserProfileData;
  };
}

// ---------------------------------------------------------------------------
// Privacy settings
// ---------------------------------------------------------------------------

export interface PrivacySettingsData {
  /**
   * When true the user has opted in to contributing anonymised partner
   * statistics. Must default to false. Do not collect statistics until
   * this is explicitly true.
   */
  anonymousPartnerStatsOptIn: boolean;
}

export interface PrivacySettingsResponse {
  ok: true;
  data: PrivacySettingsData;
}

export interface PrivacySettingsUpdateRequest {
  anonymousPartnerStatsOptIn: boolean;
}

// ---------------------------------------------------------------------------
// App settings links
// ---------------------------------------------------------------------------

export interface AppSettingsLink {
  /** Stable machine-readable key for this link. */
  key: string;
  /** Human-readable label. */
  label: string;
  /** URL safe to open in a browser or Linking.openURL. No internal admin URLs. */
  url: string;
}

export interface AppSettingsLinksResponse {
  ok: true;
  data: {
    links: AppSettingsLink[];
  };
}
