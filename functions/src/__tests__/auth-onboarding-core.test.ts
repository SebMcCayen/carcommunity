import { describe, expect, it } from 'vitest';
import {
  computeOnboardingWrites,
  parseCompleteOnboardingInput,
  type ExistingOnboardingState,
} from '../auth/onboarding-core';

const SERVER_TIMESTAMP = Symbol('serverTimestamp');
const serverTimestamp = () => SERVER_TIMESTAMP;

const validInput = {
  licenceConfirmed: true,
  termsAccepted: true,
  privacyPolicyAccepted: true,
} as const;

const nothingSet: ExistingOnboardingState = {
  onboardingCompletedAt: null,
  licenceConfirmedAt: null,
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

  it.each(['licenceConfirmed', 'termsAccepted', 'privacyPolicyAccepted'])(
    'rejects when %s is missing',
    (field) => {
      const input: Record<string, unknown> = { ...validInput };
      delete input[field];
      expect(parseCompleteOnboardingInput(input).ok).toBe(false);
    },
  );

  it.each(['licenceConfirmed', 'termsAccepted', 'privacyPolicyAccepted'])(
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
      licenceConfirmedAt: SERVER_TIMESTAMP,
      termsAcceptedAt: SERVER_TIMESTAMP,
      privacyPolicyAcceptedAt: SERVER_TIMESTAMP,
    });
  });

  it('preserves already-recorded consent timestamps (idempotent repeat call)', () => {
    const existing: ExistingOnboardingState = {
      onboardingCompletedAt: 'existing-completed',
      licenceConfirmedAt: 'existing-licence',
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
      licenceConfirmedAt: 'existing-licence',
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
  });

  it('leaves the display name untouched when omitted', () => {
    const { profileUpdate } = computeOnboardingWrites(validInput, nothingSet, serverTimestamp);
    expect(profileUpdate).not.toHaveProperty('displayName');
  });
});
