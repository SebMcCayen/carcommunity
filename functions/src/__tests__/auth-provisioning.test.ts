import { describe, expect, it } from 'vitest';
import {
  DEFAULT_DISPLAY_NAME,
  DISPLAY_NAME_MAX_LENGTH,
  buildUserPrivateDocument,
  buildUserProfileDocument,
  resolveInitialDisplayName,
} from '../auth/provisioning';

const SERVER_TIMESTAMP = Symbol('serverTimestamp');
const serverTimestamp = () => SERVER_TIMESTAMP;

describe('resolveInitialDisplayName', () => {
  it('uses the provider display name when present', () => {
    expect(resolveInitialDisplayName('Anna Andersson')).toBe('Anna Andersson');
  });

  it('trims surrounding whitespace', () => {
    expect(resolveInitialDisplayName('  Anna  Andersson ')).toBe('Anna  Andersson');
  });

  it('falls back for null, undefined, and blank names', () => {
    expect(resolveInitialDisplayName(null)).toBe(DEFAULT_DISPLAY_NAME);
    expect(resolveInitialDisplayName(undefined)).toBe(DEFAULT_DISPLAY_NAME);
    expect(resolveInitialDisplayName('   ')).toBe(DEFAULT_DISPLAY_NAME);
  });

  it('clamps to the contract max length', () => {
    const long = 'x'.repeat(DISPLAY_NAME_MAX_LENGTH + 50);
    expect(resolveInitialDisplayName(long)).toHaveLength(DISPLAY_NAME_MAX_LENGTH);
  });
});

describe('buildUserProfileDocument', () => {
  it('creates the contract-shaped public profile with safe defaults', () => {
    const doc = buildUserProfileDocument(
      { uid: 'uid-1', displayName: 'Anna', email: 'anna@example.com' },
      serverTimestamp,
    );
    expect(doc).toStrictEqual({
      displayName: 'Anna',
      role: 'user',
      activeMember: false,
      suspended: false,
      deleted: false,
      onboardingCompletedAt: null,
      createdAt: SERVER_TIMESTAMP,
      updatedAt: SERVER_TIMESTAMP,
    });
  });

  it('never grants role, entitlement, or moderation flags', () => {
    const doc = buildUserProfileDocument({ uid: 'uid-1' }, serverTimestamp);
    expect(doc.role).toBe('user');
    expect(doc.activeMember).toBe(false);
    expect(doc.suspended).toBe(false);
    expect(doc.deleted).toBe(false);
  });

  it('never places the email on the public profile', () => {
    const doc = buildUserProfileDocument(
      { uid: 'uid-1', email: 'secret@example.com' },
      serverTimestamp,
    );
    expect(JSON.stringify(doc)).not.toContain('secret@example.com');
  });
});

describe('buildUserPrivateDocument', () => {
  it('stores the provider email as a contact channel', () => {
    const doc = buildUserPrivateDocument(
      { uid: 'uid-1', email: 'anna@example.com' },
      serverTimestamp,
    );
    expect(doc).toStrictEqual({
      email: 'anna@example.com',
      ageConfirmedAt: null,
      termsAcceptedAt: null,
      privacyPolicyAcceptedAt: null,
      anonymousPartnerStatsOptIn: false,
      createdAt: SERVER_TIMESTAMP,
      updatedAt: SERVER_TIMESTAMP,
    });
  });

  it('omits the email field when the provider gives none', () => {
    const doc = buildUserPrivateDocument({ uid: 'uid-1', email: null }, serverTimestamp);
    expect(doc).not.toHaveProperty('email');
  });

  it('defaults partner stats opt-in to false (opt-in is explicit)', () => {
    const doc = buildUserPrivateDocument({ uid: 'uid-1' }, serverTimestamp);
    expect(doc.anonymousPartnerStatsOptIn).toBe(false);
  });
});
