import { describe, expect, it } from 'vitest';
import {
  PURGE_CONFIRM_TOKEN,
  classifyAccount,
  isAdminOrOwner,
  isConfirmed,
  isNeverOnboarded,
  parsePurgeInput,
} from './purgeNeverOnboarded-core';

describe('isNeverOnboarded', () => {
  it('is true only when onboardingCompletedAt is null or absent', () => {
    expect(isNeverOnboarded({ onboardingCompletedAt: null })).toBe(true);
    expect(isNeverOnboarded({})).toBe(true);
    expect(isNeverOnboarded(undefined)).toBe(true);
  });

  it('is false for ANY value — a completed account is never selected', () => {
    // The stored value is a Timestamp in production; the guard rejects anything
    // non-null so the failure mode is "skip an unsure account", never "delete a
    // completed one".
    expect(isNeverOnboarded({ onboardingCompletedAt: { seconds: 1 } })).toBe(false);
    expect(isNeverOnboarded({ onboardingCompletedAt: 'x' })).toBe(false);
    expect(isNeverOnboarded({ onboardingCompletedAt: 0 })).toBe(false);
  });
});

describe('isAdminOrOwner', () => {
  it('detects admin and owner roles', () => {
    expect(isAdminOrOwner({ role: 'admin' })).toBe(true);
    expect(isAdminOrOwner({ role: 'owner' })).toBe(true);
  });

  it('treats plain / missing / garbage roles as non-admin', () => {
    expect(isAdminOrOwner({ role: 'user' })).toBe(false);
    expect(isAdminOrOwner({})).toBe(false);
    expect(isAdminOrOwner({ role: 'nonsense' })).toBe(false);
  });
});

describe('classifyAccount', () => {
  it('selects a plain never-onboarded account', () => {
    expect(classifyAccount('u1', { onboardingCompletedAt: null, role: 'user' })).toEqual({
      selected: true,
    });
  });

  it('EXCLUDES an admin/owner even when onboarding is null (role wins)', () => {
    // The core safety net: an operator account is excluded on ROLE grounds
    // regardless of its onboarding flag.
    expect(classifyAccount('admin-uid', { onboardingCompletedAt: null, role: 'admin' })).toEqual({
      selected: false,
      reason: 'admin_or_owner',
    });
    expect(classifyAccount('owner-uid', { onboardingCompletedAt: null, role: 'owner' })).toEqual({
      selected: false,
      reason: 'admin_or_owner',
    });
  });

  it('does NOT select a completed account', () => {
    expect(classifyAccount('u2', { onboardingCompletedAt: { seconds: 1 }, role: 'user' })).toEqual({
      selected: false,
      reason: 'onboarded',
    });
  });
});

describe('isConfirmed / parsePurgeInput', () => {
  it('confirms only the exact sentinel', () => {
    expect(isConfirmed(PURGE_CONFIRM_TOKEN)).toBe(true);
    expect(isConfirmed('purge')).toBe(false);
    expect(isConfirmed('')).toBe(false);
    expect(isConfirmed(undefined)).toBe(false);
  });

  it('parses valid input and rejects malformed input', () => {
    expect(parsePurgeInput({ dryRun: true })).toEqual({ ok: true, input: { dryRun: true } });
    expect(parsePurgeInput({ dryRun: false, confirmToken: 'PURGE' })).toEqual({
      ok: true,
      input: { dryRun: false, confirmToken: 'PURGE' },
    });
    expect(parsePurgeInput({}).ok).toBe(false);
    expect(parsePurgeInput({ dryRun: 'yes' }).ok).toBe(false);
    expect(parsePurgeInput({ dryRun: true, extra: 1 }).ok).toBe(false);
  });
});
