/**
 * Tests for onboarding and privacy settings API endpoints.
 *
 * Covers:
 *  - PATCH /v1/users/me/profile validation and security
 *  - GET/PATCH /v1/users/me/privacy-settings
 *  - GET /v1/app/settings-links
 *  - Onboarding completion logic
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { LOCAL_DATABASE_URL } from './config.js';
import { createServer } from './server.js';
import type { UserService, UserProfileRecord } from './lib/user-service.js';

// ---------------------------------------------------------------------------
// Fake UserService
// ---------------------------------------------------------------------------

const NOW_ISO = '2026-06-18T00:00:00.000Z';
const NOW = new Date(NOW_ISO);

function makeDefaultProfile(overrides: Partial<UserProfileRecord> = {}): UserProfileRecord {
  return {
    id: 'dev-user-id',
    displayName: null,
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'member_monthly',
    onboardingCompletedAt: null,
    ageConfirmedAt: null,
    termsAcceptedAt: null,
    privacyPolicyAcceptedAt: null,
    anonymousPartnerStatsOptIn: false,
    ...overrides,
  };
}

class FakeUserService implements UserService {
  profile: UserProfileRecord;
  public lastUpdateInput: Parameters<UserService['updateUserProfile']>[0] | null = null;
  public lastPrivacyUpdate: boolean | null = null;

  constructor(profile: Partial<UserProfileRecord> = {}) {
    this.profile = makeDefaultProfile(profile);
  }

  async getUserProfile(): Promise<UserProfileRecord> {
    return { ...this.profile };
  }

  async updateUserProfile(
    input: Parameters<UserService['updateUserProfile']>[0],
  ): Promise<UserProfileRecord> {
    this.lastUpdateInput = input;
    const updated = { ...this.profile };
    if ('displayName' in input) updated.displayName = input.displayName ?? null;
    if (input.ageConfirmed) updated.ageConfirmedAt = NOW;
    if (input.termsAccepted) updated.termsAcceptedAt = NOW;
    if (input.privacyPolicyAccepted) updated.privacyPolicyAcceptedAt = NOW;
    if (
      !updated.onboardingCompletedAt &&
      updated.ageConfirmedAt &&
      updated.termsAcceptedAt &&
      updated.privacyPolicyAcceptedAt
    ) {
      updated.onboardingCompletedAt = NOW;
    }
    this.profile = updated;
    return { ...updated };
  }

  async getPrivacySettings(): Promise<{ anonymousPartnerStatsOptIn: boolean }> {
    return { anonymousPartnerStatsOptIn: this.profile.anonymousPartnerStatsOptIn };
  }

  async updatePrivacySettings(
    _userId: string,
    anonymousPartnerStatsOptIn: boolean,
  ): Promise<{ anonymousPartnerStatsOptIn: boolean }> {
    this.lastPrivacyUpdate = anonymousPartnerStatsOptIn;
    this.profile.anonymousPartnerStatsOptIn = anonymousPartnerStatsOptIn;
    return { anonymousPartnerStatsOptIn };
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function devAuth(overrides: {
  role?: 'user' | 'admin' | 'owner';
  status?: 'active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' | 'deleted';
  subscriptionEntitlement?: 'none' | 'member_monthly';
} = {}) {
  return JSON.stringify({
    userId: 'dev-user-id',
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'member_monthly',
    sessionId: 'dev-session-id',
    ...overrides,
  });
}

async function createTestApp(port: number, userService?: UserService) {
  return createServer(
    { nodeEnv: 'test', port, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { userService },
  );
}

// ---------------------------------------------------------------------------
// PATCH /v1/users/me/profile — authentication
// ---------------------------------------------------------------------------

test('PATCH /v1/users/me/profile returns 401 when unauthenticated', async () => {
  const app = await createTestApp(5001);
  try {
    const res = await app.inject({ method: 'PATCH', url: '/v1/users/me/profile', payload: {} });
    assert.equal(res.statusCode, 401);
    assert.equal(res.json().error.code, 'unauthenticated');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// PATCH /v1/users/me/profile — validation
// ---------------------------------------------------------------------------

test('PATCH /v1/users/me/profile rejects displayName exceeding 120 characters', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5002, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { displayName: 'A'.repeat(121) },
    });
    assert.equal(res.statusCode, 400);
    assert.equal(res.json().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile rejects unknown fields', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5003, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { role: 'admin' },
    });
    assert.equal(res.statusCode, 400);
    assert.equal(res.json().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile does not allow updating role', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5004, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { role: 'owner' },
    });
    assert.equal(res.statusCode, 400);
    assert.equal(res.json().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile does not allow updating status', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5005, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { status: 'active' },
    });
    assert.equal(res.statusCode, 400);
    assert.equal(res.json().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile does not allow updating subscriptionEntitlement', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5006, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { subscriptionEntitlement: 'member_monthly' },
    });
    assert.equal(res.statusCode, 400);
    assert.equal(res.json().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// PATCH /v1/users/me/profile — onboarding
// ---------------------------------------------------------------------------

test('PATCH /v1/users/me/profile sets age confirmation', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5007, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { ageConfirmed: true },
    });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.ok, true);
    assert.notEqual(body.data.user.onboarding.ageConfirmedAt, null);
    // Onboarding not complete — terms and privacy not yet accepted
    assert.equal(body.data.user.onboarding.onboardingCompletedAt, null);
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile completes onboarding when all confirmations provided', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5008, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: {
        ageConfirmed: true,
        termsAccepted: true,
        privacyPolicyAccepted: true,
      },
    });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.ok, true);
    assert.notEqual(body.data.user.onboarding.onboardingCompletedAt, null);
    assert.notEqual(body.data.user.onboarding.ageConfirmedAt, null);
    assert.notEqual(body.data.user.onboarding.termsAcceptedAt, null);
    assert.notEqual(body.data.user.onboarding.privacyPolicyAcceptedAt, null);
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile requires age confirmation for onboarding completion', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5009, svc);
  try {
    // Terms and privacy accepted but not age — onboarding must not complete
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { termsAccepted: true, privacyPolicyAccepted: true },
    });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.data.user.onboarding.onboardingCompletedAt, null);
    assert.equal(body.data.user.onboarding.ageConfirmedAt, null);
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile requires terms acceptance for onboarding completion', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5010, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { ageConfirmed: true, privacyPolicyAccepted: true },
    });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.data.user.onboarding.onboardingCompletedAt, null);
    assert.equal(body.data.user.onboarding.termsAcceptedAt, null);
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile requires privacy policy acceptance for onboarding completion', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5011, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: { ageConfirmed: true, termsAccepted: true },
    });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.data.user.onboarding.onboardingCompletedAt, null);
    assert.equal(body.data.user.onboarding.privacyPolicyAcceptedAt, null);
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/profile partner stats opt-in defaults to false', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5012, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/profile',
      headers: { 'x-dev-user': devAuth() },
      payload: {
        ageConfirmed: true,
        termsAccepted: true,
        privacyPolicyAccepted: true,
      },
    });
    assert.equal(res.statusCode, 200);
    // Profile update doesn't change partner stats — must be false by default
    assert.equal(svc.profile.anonymousPartnerStatsOptIn, false);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// GET /v1/users/me/privacy-settings
// ---------------------------------------------------------------------------

test('GET /v1/users/me/privacy-settings returns 401 when unauthenticated', async () => {
  const app = await createTestApp(5013);
  try {
    const res = await app.inject({ method: 'GET', url: '/v1/users/me/privacy-settings' });
    assert.equal(res.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('GET /v1/users/me/privacy-settings returns anonymousPartnerStatsOptIn false by default', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5014, svc);
  try {
    const res = await app.inject({
      method: 'GET',
      url: '/v1/users/me/privacy-settings',
      headers: { 'x-dev-user': devAuth() },
    });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.ok, true);
    assert.equal(body.data.anonymousPartnerStatsOptIn, false);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// PATCH /v1/users/me/privacy-settings
// ---------------------------------------------------------------------------

test('PATCH /v1/users/me/privacy-settings returns 401 when unauthenticated', async () => {
  const app = await createTestApp(5015);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/privacy-settings',
      payload: { anonymousPartnerStatsOptIn: true },
    });
    assert.equal(res.statusCode, 401);
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/privacy-settings updates anonymousPartnerStatsOptIn', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5016, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/privacy-settings',
      headers: { 'x-dev-user': devAuth() },
      payload: { anonymousPartnerStatsOptIn: true },
    });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.ok, true);
    assert.equal(body.data.anonymousPartnerStatsOptIn, true);
    assert.equal(svc.lastPrivacyUpdate, true);
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/privacy-settings only changes anonymousPartnerStatsOptIn', async () => {
  const svc = new FakeUserService();
  const app = await createTestApp(5017, svc);
  try {
    // Try passing extra fields — should be rejected
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/privacy-settings',
      headers: { 'x-dev-user': devAuth() },
      payload: { anonymousPartnerStatsOptIn: true, role: 'admin' },
    });
    assert.equal(res.statusCode, 400);
    assert.equal(res.json().error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('PATCH /v1/users/me/privacy-settings can opt out', async () => {
  const svc = new FakeUserService({ anonymousPartnerStatsOptIn: true });
  const app = await createTestApp(5018, svc);
  try {
    const res = await app.inject({
      method: 'PATCH',
      url: '/v1/users/me/privacy-settings',
      headers: { 'x-dev-user': devAuth() },
      payload: { anonymousPartnerStatsOptIn: false },
    });
    assert.equal(res.statusCode, 200);
    assert.equal(res.json().data.anonymousPartnerStatsOptIn, false);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// GET /v1/app/settings-links
// ---------------------------------------------------------------------------

test('GET /v1/app/settings-links returns ok response with links array', async () => {
  const app = await createTestApp(5019);
  try {
    const res = await app.inject({ method: 'GET', url: '/v1/app/settings-links' });
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.equal(body.ok, true);
    assert.ok(Array.isArray(body.data.links));
    assert.ok(body.data.links.length > 0);
  } finally {
    await app.close();
  }
});

test('GET /v1/app/settings-links response does not include secrets', async () => {
  const app = await createTestApp(5020);
  try {
    const res = await app.inject({ method: 'GET', url: '/v1/app/settings-links' });
    const raw = res.body;
    // Confirm no sensitive patterns
    assert.ok(!raw.includes('password'));
    assert.ok(!raw.includes('secret'));
    assert.ok(!raw.includes('token'));
    assert.ok(!raw.includes('admin'));
    assert.ok(!raw.includes('internal'));
  } finally {
    await app.close();
  }
});

test('GET /v1/app/settings-links includes required link keys', async () => {
  const app = await createTestApp(5021);
  try {
    const res = await app.inject({ method: 'GET', url: '/v1/app/settings-links' });
    const body = res.json();
    const keys = (body.data.links as Array<{ key: string }>).map((l) => l.key);
    assert.ok(keys.includes('support'));
    assert.ok(keys.includes('terms'));
    assert.ok(keys.includes('privacy_policy'));
    assert.ok(keys.includes('account_deletion_info'));
    assert.ok(keys.includes('github'));
  } finally {
    await app.close();
  }
});

test('GET /v1/app/settings-links is accessible without authentication', async () => {
  const app = await createTestApp(5022);
  try {
    // No auth header — should still return 200
    const res = await app.inject({ method: 'GET', url: '/v1/app/settings-links' });
    assert.equal(res.statusCode, 200);
  } finally {
    await app.close();
  }
});
