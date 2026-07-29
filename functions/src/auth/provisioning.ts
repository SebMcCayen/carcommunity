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

/** Fallback shown until the user picks a display name during onboarding. */
export const DEFAULT_DISPLAY_NAME = 'New member';

export interface ProvisionUserInput {
  uid: string;
  /** Display name from the identity provider, if any. */
  displayName?: string | null;
  /** Email from the identity provider — contact channel, never an identity key. */
  email?: string | null;
}

/**
 * Resolves the initial display name: the identity provider's name (trimmed,
 * clamped to the contract max length) or a neutral fallback.
 */
export function resolveInitialDisplayName(providerDisplayName: string | null | undefined): string {
  const trimmed = providerDisplayName?.trim() ?? '';
  if (trimmed.length === 0) {
    return DEFAULT_DISPLAY_NAME;
  }
  return trimmed.slice(0, DISPLAY_NAME_MAX_LENGTH);
}

/**
 * Initial public profile document for `users/{uid}`.
 *
 * Protected fields (`role`, `activeMember`, `suspended`, `deleted`,
 * `onboardingCompletedAt`) are backend-managed only; Security Rules block
 * client writes to them.
 */
export function buildUserProfileDocument(
  input: ProvisionUserInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const displayName = resolveInitialDisplayName(input.displayName);
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
    // Driving-licence consent. Replaces the legacy `ageConfirmedAt` (18+),
    // which is deliberately NOT seeded here: a new member makes no age
    // attestation, so a document provisioned from now on has no such field.
    //
    // Do NOT read the mere PRESENCE of `ageConfirmedAt` as evidence of an age
    // attestation. This function DID seed `ageConfirmedAt: null` from the
    // original Phase 7 shape until the licence wording landed, so a PRE-CHANGE
    // document can be in any of three states:
    //   - present and null        — provisioned, old onboarding never completed;
    //   - present and a Timestamp — the owner really did confirm the 18+ wording
    //                              (computeOnboardingWrites stamped it once);
    //   - absent                  — rare: the document was created by some other
    //                              backend merge-write before provisioning ran,
    //                              and onUserCreate then only refreshed
    //                              updatedAt rather than back-filling the shape.
    // Only a NON-NULL value is a consent record. Nothing branches on this field:
    // completeOnboarding echoes it back read-only (null for both the null and
    // the absent case) and Security Rules exclude it via a write allowlist, so
    // null-vs-absent has no behavioural consequence.
    licenceConfirmedAt: null,
    termsAcceptedAt: null,
    privacyPolicyAcceptedAt: null,
    anonymousPartnerStatsOptIn: false,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}
