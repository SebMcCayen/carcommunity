import { describe, expect, it } from 'vitest';
import {
  computeOnboardingWrites,
  parseCompleteOnboardingInput,
  type ExistingOnboardingState,
} from '../auth/onboarding-core';
// The SAME derivation computeOnboardingWrites uses for `displayNameLower`, so
// the expectation cannot drift from the implementation the way a re-spelled
// `.trim().toLowerCase()` in the test would.
import { toSearchKey } from '../friends/friends-core';

const SERVER_TIMESTAMP = Symbol('serverTimestamp');
const serverTimestamp = () => SERVER_TIMESTAMP;

const validInput = {
  ageConfirmed: true,
  termsAccepted: true,
  privacyPolicyAccepted: true,
} as const;

const nothingSet: ExistingOnboardingState = {
  onboardingCompletedAt: null,
  ageConfirmedAt: null,
  termsAcceptedAt: null,
  privacyPolicyAcceptedAt: null,
};

describe('parseCompleteOnboardingInput', () => {
  it('accepts all three consents without a display name', () => {
    const result = parseCompleteOnboardingInput(validInput);
    expect(result.ok).toBe(true);
  });

  it('accepts an optional display name and trims it', () => {
    const result = parseCompleteOnboardingInput({ ...validInput, displayName: '  Anna  ' });
    expect(result).toStrictEqual({ ok: true, input: { ...validInput, displayName: 'Anna' } });
  });

  it.each(['ageConfirmed', 'termsAccepted', 'privacyPolicyAccepted'])(
    'rejects when %s is missing',
    (field) => {
      const input: Record<string, unknown> = { ...validInput };
      delete input[field];
      expect(parseCompleteOnboardingInput(input).ok).toBe(false);
    },
  );

  it.each(['ageConfirmed', 'termsAccepted', 'privacyPolicyAccepted'])(
    'rejects when %s is false',
    (field) => {
      expect(parseCompleteOnboardingInput({ ...validInput, [field]: false }).ok).toBe(false);
    },
  );

  it('rejects unknown fields (strict contract)', () => {
    expect(parseCompleteOnboardingInput({ ...validInput, role: 'admin' }).ok).toBe(false);
  });

  it('rejects a blank display name', () => {
    expect(parseCompleteOnboardingInput({ ...validInput, displayName: '   ' }).ok).toBe(false);
  });

  it('rejects an overlong display name', () => {
    expect(parseCompleteOnboardingInput({ ...validInput, displayName: 'x'.repeat(121) }).ok).toBe(
      false,
    );
  });

  it('rejects null and non-object payloads', () => {
    expect(parseCompleteOnboardingInput(null).ok).toBe(false);
    expect(parseCompleteOnboardingInput('yes').ok).toBe(false);
  });
});

describe('computeOnboardingWrites', () => {
  it('writes all timestamps server-side on first completion', () => {
    const { profileUpdate, privateUpdate } = computeOnboardingWrites(
      validInput,
      nothingSet,
      serverTimestamp,
    );
    expect(profileUpdate).toStrictEqual({
      updatedAt: SERVER_TIMESTAMP,
      onboardingCompletedAt: SERVER_TIMESTAMP,
    });
    expect(privateUpdate).toStrictEqual({
      updatedAt: SERVER_TIMESTAMP,
      ageConfirmedAt: SERVER_TIMESTAMP,
      termsAcceptedAt: SERVER_TIMESTAMP,
      privacyPolicyAcceptedAt: SERVER_TIMESTAMP,
    });
  });

  it('preserves already-recorded consent timestamps (idempotent repeat call)', () => {
    const existing: ExistingOnboardingState = {
      onboardingCompletedAt: 'existing-completed',
      ageConfirmedAt: 'existing-age',
      termsAcceptedAt: 'existing-terms',
      privacyPolicyAcceptedAt: 'existing-privacy',
    };
    const { profileUpdate, privateUpdate } = computeOnboardingWrites(
      validInput,
      existing,
      serverTimestamp,
    );
    expect(profileUpdate).toStrictEqual({ updatedAt: SERVER_TIMESTAMP });
    expect(privateUpdate).toStrictEqual({ updatedAt: SERVER_TIMESTAMP });
  });

  it('fills only the missing consent timestamps', () => {
    const existing: ExistingOnboardingState = {
      ...nothingSet,
      ageConfirmedAt: 'existing-age',
    };
    const { privateUpdate } = computeOnboardingWrites(validInput, existing, serverTimestamp);
    expect(privateUpdate).toStrictEqual({
      updatedAt: SERVER_TIMESTAMP,
      termsAcceptedAt: SERVER_TIMESTAMP,
      privacyPolicyAcceptedAt: SERVER_TIMESTAMP,
    });
  });

  it('updates the display name when provided', () => {
    const { profileUpdate } = computeOnboardingWrites(
      { ...validInput, displayName: 'Anna' },
      nothingSet,
      serverTimestamp,
    );
    expect(profileUpdate.displayName).toBe('Anna');
    // The pair, not just the name: friend nickname resolution and
    // users.searchMembers range-scan `displayNameLower` ONLY, so a displayName
    // written without refreshing the key leaves the member findable under their
    // OLD name (the bug fixed in #576). Asserting only `displayName` here would
    // not notice the key going missing.
    expect(profileUpdate.displayNameLower).toBe(toSearchKey('Anna'));
  });

  // The member-typed name is the one place a display name can actually carry
  // surrounding whitespace, so it is where the TRIM half of the search-key rule
  // has teeth: `computeOnboardingWrites` is exported and reachable with an
  // untrimmed name (the callable's schema trims first, this does not depend on
  // that). An untrimmed key would sort outside every prefix range the search
  // derives from trimmed query text, making the member unfindable by nickname.
  it('folds and trims the display name into the search key', () => {
    const { profileUpdate } = computeOnboardingWrites(
      { ...validInput, displayName: '  Anna Andersson  ' },
      nothingSet,
      serverTimestamp,
    );
    expect(profileUpdate.displayNameLower).toBe(toSearchKey('  Anna Andersson  '));
    // Spelled out as well, so the case still fails if `toSearchKey` itself ever
    // stops trimming or folding rather than both sides moving together.
    expect(profileUpdate.displayNameLower).toBe('anna andersson');
  });

  it('leaves the display name untouched when omitted', () => {
    const { profileUpdate } = computeOnboardingWrites(validInput, nothingSet, serverTimestamp);
    expect(profileUpdate).not.toHaveProperty('displayName');
  });
});
