import { describe, expect, it } from 'vitest';
import {
  DEFAULT_DISPLAY_NAME,
  buildUserPrivateDocument,
  buildUserProfileDocument,
  type ProvisionUserInput,
} from '../auth/provisioning';

const SERVER_TIMESTAMP = Symbol('serverTimestamp');
const serverTimestamp = () => SERVER_TIMESTAMP;

describe('buildUserProfileDocument', () => {
  it('creates the contract-shaped public profile with safe defaults', () => {
    const doc = buildUserProfileDocument(
      { uid: 'uid-1', email: 'anna@example.com' },
      serverTimestamp,
    );
    expect(doc).toStrictEqual({
      displayName: DEFAULT_DISPLAY_NAME,
      displayNameLower: DEFAULT_DISPLAY_NAME.toLowerCase(),
      role: 'user',
      activeMember: false,
      suspended: false,
      deleted: false,
      onboardingCompletedAt: null,
      createdAt: SERVER_TIMESTAMP,
      updatedAt: SERVER_TIMESTAMP,
    });
  });

  // ---------------------------------------------------------------------------
  // THE PROMISE ON THE ONBOARDING SCREEN.
  //
  // contracts/localization en/sv `onboarding.displayNameDescription` tells every
  // new member: "Your real Google account name is never shown." This is the test
  // that makes that sentence true, so it is written against the PROMISE, not the
  // current shape: it asserts the Google name is absent from the WHOLE document
  // rather than that some specific field equals some specific value.
  //
  // It has to hold here because `users/{uid}` is readable by any signed-in
  // member from the instant onUserCreate commits — before onboarding runs — and
  // `displayNameLower` is the key users.searchMembers and friend.sendRequest
  // nickname resolution range-scan, neither of which filters on
  // `onboardingCompletedAt`.
  //
  // The regression this pins is real: provisioning used to seed `displayName`
  // from the provider name (onUserCreate passed `user.displayName`,
  // completeOnboarding passed `auth.token.name`), so every Google sign-up was
  // published under the member's real name until they finished onboarding — and
  // permanently if they never did.
  // ---------------------------------------------------------------------------
  it('never seeds the public profile from the identity provider', () => {
    const GOOGLE_NAME = 'Anna Andersson';
    // Passed the way a caller that had not read the invariant would pass it —
    // an excess `displayName` property must be ignored, not honoured.
    const input = {
      uid: 'uid-1',
      displayName: GOOGLE_NAME,
      email: 'anna.andersson@gmail.com',
    } as ProvisionUserInput;

    const doc = buildUserProfileDocument(input, serverTimestamp);

    // Nothing anywhere in the document — not displayName, not the search key,
    // not a field added later.
    const serialized = JSON.stringify(doc).toLowerCase();
    expect(serialized).not.toContain('anna');
    expect(serialized).not.toContain('andersson');
    expect(doc.displayName).toBe(DEFAULT_DISPLAY_NAME);
    expect(doc.displayNameLower).toBe(DEFAULT_DISPLAY_NAME.toLowerCase());
  });

  // `buildUserProfileDocument` takes no provider-name parameter, so the leak
  // cannot be reintroduced by a caller — only by editing the function. Pin that
  // the provisioned name is a constant, independent of every input.
  it('provisions the same neutral name regardless of input', () => {
    const inputs = [
      { uid: 'a' },
      { uid: 'b', email: 'b@example.com' },
      { uid: 'c', displayName: 'Gt86_swe' } as ProvisionUserInput,
      { uid: 'd', displayName: 'ÅKE' } as ProvisionUserInput,
    ];
    for (const input of inputs) {
      const doc = buildUserProfileDocument(input, serverTimestamp);
      expect(doc.displayName).toBe(DEFAULT_DISPLAY_NAME);
    }
  });

  // The provisioning KDoc claims displayNameLower is written in LOCKSTEP with
  // displayName. Friend nickname resolution reads ONLY the key, so a profile
  // provisioned without it is unfindable by nickname — pin the claim directly
  // rather than just the shape above.
  it('always derives displayNameLower from the resolved displayName', () => {
    const doc = buildUserProfileDocument({ uid: 'uid-1' }, serverTimestamp);
    expect(doc.displayNameLower).toBe(String(doc.displayName).toLowerCase());
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
