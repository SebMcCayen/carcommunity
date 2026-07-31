export const USER_ROLES = ['user', 'admin', 'owner'] as const;
export type UserRole = (typeof USER_ROLES)[number];

const USER_STATUSES = [
  'active',
  'warned',
  'temporarily_suspended',
  'permanently_suspended',
  'deleted',
] as const;
export type UserStatus = (typeof USER_STATUSES)[number];

export const SUBSCRIPTION_ENTITLEMENTS = ['none', 'member_monthly'] as const;
export type SubscriptionEntitlement = (typeof SUBSCRIPTION_ENTITLEMENTS)[number];

/**
 * The neutral placeholder `displayName` every account is provisioned with and
 * keeps until the member picks their own nickname during onboarding.
 *
 * Single source of truth for WORKSPACE read-side consumers (e.g. the admin app)
 * that compare a stored `displayName` against the placeholder.
 *
 * The Cloud Function that actually WRITES this onto new `users/{uid}` docs —
 * `DEFAULT_DISPLAY_NAME` in functions/src/auth/provisioning.ts — keeps its own
 * copy of the literal on purpose: `functions` is a standalone package (its own
 * lockfile, NOT an npm-workspace member) and deliberately does not depend on
 * this private/unpublished package, so it cannot import from here. The two must
 * stay in sync. It is NEVER derived from the identity provider (privacy
 * invariant — see the note on buildUserProfileDocument in provisioning.ts).
 */
export const DEFAULT_DISPLAY_NAME = 'New member';

