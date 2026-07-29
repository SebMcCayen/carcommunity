/**
 * auth.completeOnboarding — pure input validation and write computation.
 *
 * Mirrors the legacy semantics in services/api/src/lib/user-service.ts:
 * consent timestamps are written once and never overwritten (preserving the
 * original acceptance time for auditing/compliance), and onboarding is
 * complete when all three consents are recorded.
 *
 * The driving-licence consent (`licenceConfirmed` → `licenceConfirmedAt`)
 * REPLACED an earlier 18+ age consent (`ageConfirmed` → `ageConfirmedAt`).
 * Those are different statements, so the legacy `ageConfirmedAt` timestamp is
 * NEVER read, copied or rewritten here: it stays on the document purely as the
 * historical record of what those members actually attested to, and members who
 * onboarded under the old wording have no `licenceConfirmedAt` at all.
 *
 * No Firebase Admin SDK imports — the server-timestamp sentinel is injected
 * so this module stays unit-testable without emulators.
 */

import { z } from 'zod';
import { DISPLAY_NAME_MAX_LENGTH } from './provisioning';
import { toSearchKey } from '../friends/friends-core';

/**
 * The callable exists solely to complete onboarding, so all three consents
 * are required to be literally true. A request with a missing or false
 * consent is a client bug → `invalid-argument`.
 */
const completeOnboardingInputSchema = z
  .object({
    displayName: z.string().trim().min(1).max(DISPLAY_NAME_MAX_LENGTH).optional(),
    licenceConfirmed: z.literal(true),
    termsAccepted: z.literal(true),
    privacyPolicyAccepted: z.literal(true),
  })
  .strict();

export type CompleteOnboardingInput = z.infer<typeof completeOnboardingInputSchema>;

export type ParseResult =
  { ok: true; input: CompleteOnboardingInput } | { ok: false; message: string };

export function parseCompleteOnboardingInput(data: unknown): ParseResult {
  const result = completeOnboardingInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        'Expected { licenceConfirmed: true, termsAccepted: true, privacyPolicyAccepted: true, displayName?: string }.',
    };
  }
  return { ok: true, input: result.data };
}

/** Existing onboarding-relevant state read inside the transaction. */
export interface ExistingOnboardingState {
  /** `users/{uid}.onboardingCompletedAt` — null/undefined when not complete. */
  onboardingCompletedAt: unknown;
  /** `userPrivate/{uid}.licenceConfirmedAt` */
  licenceConfirmedAt: unknown;
  /** `userPrivate/{uid}.termsAcceptedAt` */
  termsAcceptedAt: unknown;
  /** `userPrivate/{uid}.privacyPolicyAcceptedAt` */
  privacyPolicyAcceptedAt: unknown;
}

export interface OnboardingWrites {
  profileUpdate: Record<string, unknown>;
  privateUpdate: Record<string, unknown>;
}

function isSet(value: unknown): boolean {
  return value !== null && value !== undefined;
}

/**
 * Computes the Firestore field updates for completing onboarding.
 *
 * - Consent timestamps and `onboardingCompletedAt` are written only when not
 *   already set — repeat calls are idempotent and preserve original values.
 * - `displayName` is updated whenever provided, together with its case-folded
 *   `displayNameLower` search key (always written as a pair — friend nickname
 *   resolution reads only the key).
 * - `updatedAt` is always refreshed on both documents.
 */
export function computeOnboardingWrites(
  input: CompleteOnboardingInput,
  existing: ExistingOnboardingState,
  serverTimestamp: () => unknown,
): OnboardingWrites {
  const profileUpdate: Record<string, unknown> = {
    updatedAt: serverTimestamp(),
  };
  if (input.displayName !== undefined) {
    profileUpdate.displayName = input.displayName;
    // Kept in LOCKSTEP with displayName: friend nickname resolution queries
    // `displayNameLower` only, so a displayName written without refreshing this
    // key would make the member unfindable (or findable under their OLD name).
    profileUpdate.displayNameLower = toSearchKey(input.displayName);
  }
  if (!isSet(existing.onboardingCompletedAt)) {
    profileUpdate.onboardingCompletedAt = serverTimestamp();
  }

  const privateUpdate: Record<string, unknown> = {
    updatedAt: serverTimestamp(),
  };
  if (!isSet(existing.licenceConfirmedAt)) {
    privateUpdate.licenceConfirmedAt = serverTimestamp();
  }
  if (!isSet(existing.termsAcceptedAt)) {
    privateUpdate.termsAcceptedAt = serverTimestamp();
  }
  if (!isSet(existing.privacyPolicyAcceptedAt)) {
    privateUpdate.privacyPolicyAcceptedAt = serverTimestamp();
  }

  return { profileUpdate, privateUpdate };
}
