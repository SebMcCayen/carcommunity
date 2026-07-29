/**
 * User document provisioning — pure logic.
 *
 * Builds the initial Firestore `users/{uid}` (public profile) and
 * `userPrivate/{uid}` (owner-only) documents created on first sign-in.
 * Shapes follow docs/firebase-data-model.md and
 * contracts/schemas/user-profile.schema.json; field-level placement follows
 * docs/migration/backend-domain-mapping.md.
 *
 * Kept free of Firebase Admin SDK imports so it can be unit-tested without
 * emulators. The server-timestamp sentinel is injected by the caller
 * (FieldValue.serverTimestamp() in production code).
 */

import { toSearchKey } from '../friends/friends-core';

export const DISPLAY_NAME_MAX_LENGTH = 120;

/**
 * The public display name every account is provisioned with, and the name it
 * keeps until the member picks their own during onboarding.
 *
 * It is a NEUTRAL PLACEHOLDER on purpose — see the privacy invariant on
 * [buildUserProfileDocument]. It must never be derived from the identity
 * provider.
 */
export const DEFAULT_DISPLAY_NAME = 'New member';

export interface ProvisionUserInput {
  uid: string;
  /**
   * Email from the identity provider — contact channel, never an identity key.
   * Lands on `userPrivate/{uid}` (owner-only read), NEVER on the public profile.
   */
  email?: string | null;
}

/**
 * Initial public profile document for `users/{uid}`.
 *
 * Protected fields (`role`, `activeMember`, `suspended`, `deleted`,
 * `onboardingCompletedAt`) are backend-managed only; Security Rules block
 * client writes to them.
 *
 * PRIVACY INVARIANT — THE PUBLIC NAME IS NEVER DERIVED FROM THE GOOGLE ACCOUNT.
 * `displayName` is provisioned as the neutral [DEFAULT_DISPLAY_NAME] and is
 * only ever replaced by a name the MEMBER typed (auth.completeOnboarding's
 * `displayName`, or a later owner profile update). This function deliberately
 * takes NO provider-name parameter, so there is nothing here to wire the Google
 * name into.
 *
 * This is a user-facing PROMISE, not a nicety. The onboarding screen tells every
 * new member verbatim (contracts/localization en/sv `onboarding
 * .displayNameDescription`): "This is the public name other members see (in
 * events, chat and more). Your real Google account name is never shown."
 *
 * It has to hold HERE, at provisioning, because `users/{uid}` is world-readable
 * to any signed-in member (firebase/firestore.rules: `allow read: if
 * isAuthenticated()`) from the instant onUserCreate commits — which is BEFORE
 * onboarding runs. `displayNameLower` is derived from the same value and is the
 * key that users.searchMembers and friend.sendRequest nickname resolution
 * range-scan; neither filters on `onboardingCompletedAt`, so a name written
 * here is immediately searchable by every other member. Every denormalized copy
 * downstream (chat `senderDisplayName`, convoy members, event attendees, friend
 * requests, DM, live markers, push previews) is snapshotted from this field, so
 * seeding it from the provider would leak the member's real name into all of
 * them at once, permanently for anyone who abandons onboarding.
 *
 * Regression coverage: functions/src/__tests__/auth-provisioning.test.ts
 * ('never seeds the public profile from the identity provider').
 */
export function buildUserProfileDocument(
  input: ProvisionUserInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const displayName = DEFAULT_DISPLAY_NAME;
  return {
    displayName,
    // Denormalized case-folded search key — friend nickname resolution queries
    // this, never `displayName`. Written in LOCKSTEP with `displayName` on every
    // path that sets it (here and computeOnboardingWrites) so the two can never
    // drift; see toSearchKey in friends/friends-core.ts.
    displayNameLower: toSearchKey(displayName),
    role: 'user',
    activeMember: false,
    suspended: false,
    deleted: false,
    onboardingCompletedAt: null,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

/**
 * Initial private document for `userPrivate/{uid}` (owner-only access).
 * Consent timestamps are backend-written during onboarding and start as null.
 */
export function buildUserPrivateDocument(
  input: ProvisionUserInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const email = input.email?.trim();
  return {
    ...(email ? { email } : {}),
    ageConfirmedAt: null,
    termsAcceptedAt: null,
    privacyPolicyAcceptedAt: null,
    anonymousPartnerStatsOptIn: false,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}
