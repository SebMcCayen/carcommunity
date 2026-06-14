/**
 * Tests for the diagnostics foundation.
 *
 * Coverage:
 * - sanitizeMetadata: tokens removed, coordinates removed, safe fields kept
 * - generateFingerprint: stable output for same inputs
 * - POST /v1/diagnostics/report: payload validation, unauthenticated accepted, authenticated userId attached
 * - GET /v1/admin/diagnostics: requires admin, non-admin rejected, response excludes sensitive fields
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { sanitizeMetadata, generateFingerprint, DiagnosticsService, type CreateDiagnosticsReportInput, type ListDiagnosticsReportsInput } from './lib/diagnostics-service.js';
import { createServer } from './server.js';
import { LOCAL_DATABASE_URL } from './config.js';

// ---------------------------------------------------------------------------
// sanitizeMetadata
// ---------------------------------------------------------------------------

test('sanitizeMetadata removes token fields', () => {
  const result = sanitizeMetadata({
    token: 'abc',
    accessToken: 'xyz',
    access_token: 'bearer-value',
    refreshToken: 'refresh',
    identityToken: 'id-tok',
    safeField: 'keep-this',
  });

  assert.ok(result !== null);
  assert.equal('token' in result, false);
  assert.equal('accessToken' in result, false);
  assert.equal('access_token' in result, false);
  assert.equal('refreshToken' in result, false);
  assert.equal('identityToken' in result, false);
  assert.equal(result.safeField, 'keep-this');
});

test('sanitizeMetadata removes coordinate fields', () => {
  const result = sanitizeMetadata({
    latitude: 57.7,
    longitude: 11.9,
    lat: 57.7,
    lng: 11.9,
    coords: { lat: 57.7 },
    location: 'somewhere',
    appVersion: '1.0.0',
  });

  assert.ok(result !== null);
  assert.equal('latitude' in result, false);
  assert.equal('longitude' in result, false);
  assert.equal('lat' in result, false);
  assert.equal('lng' in result, false);
  assert.equal('coords' in result, false);
  assert.equal('location' in result, false);
  assert.equal(result.appVersion, '1.0.0');
});

test('sanitizeMetadata removes auth-like keys via substring matching', () => {
  const result = sanitizeMetadata({
    userAuthToken: 'secret-value',
    passwordHash: 'hashed',
    credentialId: 'cred-123',
    secretKey: 'key-value',
    safeKey: 'safe',
  });

  assert.ok(result !== null);
  assert.equal('userAuthToken' in result, false);
  assert.equal('passwordHash' in result, false);
  assert.equal('credentialId' in result, false);
  assert.equal('secretKey' in result, false);
  assert.equal(result.safeKey, 'safe');
});

test('sanitizeMetadata keeps safe scalar values', () => {
  const result = sanitizeMetadata({
    errorCount: 3,
    retried: true,
    component: 'MapScreen',
    value: null,
  });

  assert.ok(result !== null);
  assert.equal(result.errorCount, 3);
  assert.equal(result.retried, true);
  assert.equal(result.component, 'MapScreen');
  assert.equal(result.value, null);
});

test('sanitizeMetadata removes nested objects and arrays', () => {
  const result = sanitizeMetadata({
    nested: { inner: 'value' },
    list: [1, 2, 3],
    safe: 'string',
  });

  assert.ok(result !== null);
  assert.equal('nested' in result, false);
  assert.equal('list' in result, false);
  assert.equal(result.safe, 'string');
});

test('sanitizeMetadata returns null for empty or non-object input', () => {
  assert.equal(sanitizeMetadata(null), null);
  assert.equal(sanitizeMetadata(undefined), null);
  assert.equal(sanitizeMetadata({}), null);
});

test('sanitizeMetadata truncates long string values', () => {
  const longValue = 'x'.repeat(1000);
  const result = sanitizeMetadata({ msg: longValue });
  assert.ok(result !== null);
  assert.equal(typeof result.msg, 'string');
  assert.ok((result.msg as string).length <= 500);
});

// ---------------------------------------------------------------------------
// generateFingerprint
// ---------------------------------------------------------------------------

test('generateFingerprint returns consistent output for same input', () => {
  const input = {
    severity: 'error',
    platform: 'ios',
    featureArea: 'network',
    errorCode: 'network_timeout',
    safeMessage: 'Request timed out after 30 seconds',
  };

  const fp1 = generateFingerprint(input);
  const fp2 = generateFingerprint(input);

  assert.equal(fp1, fp2);
  assert.equal(typeof fp1, 'string');
  assert.ok(fp1.length > 0);
});

test('generateFingerprint differs for different inputs', () => {
  const base = {
    severity: 'error',
    platform: 'ios',
    featureArea: 'network',
    errorCode: 'network_timeout',
    safeMessage: 'Request timed out',
  };

  const fp1 = generateFingerprint(base);
  const fp2 = generateFingerprint({ ...base, featureArea: 'auth' });
  const fp3 = generateFingerprint({ ...base, severity: 'critical' });

  assert.notEqual(fp1, fp2);
  assert.notEqual(fp1, fp3);
});

test('generateFingerprint normalizes UUIDs and numbers in message', () => {
  const base = {
    severity: 'error',
    platform: 'ios',
    featureArea: 'events',
    errorCode: null,
    safeMessage: 'Failed to load event 123',
  };
  const variant = {
    ...base,
    safeMessage: 'Failed to load event 456',
  };

  // Numbers should be normalized so these map to the same fingerprint.
  assert.equal(generateFingerprint(base), generateFingerprint(variant));
});

// ---------------------------------------------------------------------------
// Route: POST /v1/diagnostics/report (in-memory DiagnosticsService stub)
// ---------------------------------------------------------------------------

function createFakeDiagnosticsService(): DiagnosticsService {
  const reports: Array<{ id: string; userId: string | null; fingerprint: string | null; safeMessage: string; metadata: Record<string, unknown> | null }> = [];
  let counter = 0;

  return {
    async createReport(input: CreateDiagnosticsReportInput) {
      counter += 1;
      const id = `diag-${counter}`;
      const fingerprint = `fp-${counter}`;
      reports.push({ id, userId: input.userId ?? null, fingerprint, safeMessage: input.safeMessage, metadata: null });
      return { id, fingerprint };
    },
    async listReports({ page, pageSize }: ListDiagnosticsReportsInput) {
      const start = (page - 1) * pageSize;
      const slice = reports.slice(start, start + pageSize);
      return {
        total: reports.length,
        reports: slice.map((r) => ({
          id: r.id,
          userId: r.userId,
          severity: 'info' as const,
          platform: 'ios' as const,
          featureArea: 'unknown' as const,
          appVersion: null,
          buildNumber: null,
          osVersion: null,
          errorCode: null,
          safeMessage: r.safeMessage,
          fingerprint: r.fingerprint,
          createdAt: new Date().toISOString(),
        })),
      };
    },
  } as unknown as DiagnosticsService;
}

const devUserHeader = (overrides: {
  role?: string;
  status?: string;
  subscriptionEntitlement?: string;
} = {}) =>
  JSON.stringify({
    userId: 'dev-user-id',
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'member_monthly',
    sessionId: 'dev-session-id',
    ...overrides,
  });

test('POST /v1/diagnostics/report accepts unauthenticated report', async () => {
  const diagnosticsService = createFakeDiagnosticsService();
  const app = await createServer(
    { nodeEnv: 'test', port: 5000, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/diagnostics/report',
      payload: {
        severity: 'error',
        platform: 'ios',
        featureArea: 'network',
        safeMessage: 'Connection failed',
        errorCode: 'network_timeout',
      },
    });

    assert.equal(response.statusCode, 201);
    const body = response.json<{ ok: boolean; data: { id: string; fingerprint: string | null } }>();
    assert.equal(body.ok, true);
    assert.equal(typeof body.data.id, 'string');
  } finally {
    await app.close();
  }
});

test('POST /v1/diagnostics/report associates userId when authenticated', async () => {
  let capturedUserId: string | null | undefined = undefined;

  const diagnosticsService = {
    async createReport(input: { userId?: string | null; safeMessage: string }) {
      capturedUserId = input.userId;
      return { id: 'diag-1', fingerprint: 'fp-1' };
    },
    async listReports() {
      return { reports: [], total: 0 };
    },
  } as unknown as DiagnosticsService;

  const app = await createServer(
    { nodeEnv: 'test', port: 5001, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/diagnostics/report',
      headers: { 'x-dev-user': devUserHeader() },
      payload: {
        severity: 'warning',
        platform: 'android',
        featureArea: 'auth',
        safeMessage: 'Login failed',
      },
    });

    assert.equal(response.statusCode, 201);
    assert.equal(capturedUserId, 'dev-user-id');
  } finally {
    await app.close();
  }
});

test('POST /v1/diagnostics/report rejects invalid severity', async () => {
  const app = await createServer(
    { nodeEnv: 'test', port: 5002, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService: createFakeDiagnosticsService() },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/diagnostics/report',
      payload: {
        severity: 'catastrophic',
        platform: 'ios',
        featureArea: 'network',
        safeMessage: 'Something broke',
      },
    });

    assert.equal(response.statusCode, 400);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'validation_error');
  } finally {
    await app.close();
  }
});

test('POST /v1/diagnostics/report rejects missing safeMessage', async () => {
  const app = await createServer(
    { nodeEnv: 'test', port: 5003, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService: createFakeDiagnosticsService() },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/diagnostics/report',
      payload: {
        severity: 'error',
        platform: 'ios',
        featureArea: 'network',
      },
    });

    assert.equal(response.statusCode, 400);
  } finally {
    await app.close();
  }
});

test('POST /v1/diagnostics/report rejects safeMessage exceeding max length', async () => {
  const app = await createServer(
    { nodeEnv: 'test', port: 5004, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService: createFakeDiagnosticsService() },
  );

  try {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/diagnostics/report',
      payload: {
        severity: 'error',
        platform: 'ios',
        featureArea: 'network',
        safeMessage: 'x'.repeat(2001),
      },
    });

    assert.equal(response.statusCode, 400);
  } finally {
    await app.close();
  }
});

// ---------------------------------------------------------------------------
// Route: GET /v1/admin/diagnostics
// ---------------------------------------------------------------------------

test('GET /v1/admin/diagnostics returns 401 when unauthenticated', async () => {
  const app = await createServer(
    { nodeEnv: 'test', port: 5010, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService: createFakeDiagnosticsService() },
  );

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/diagnostics',
    });

    assert.equal(response.statusCode, 401);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'unauthenticated');
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/diagnostics returns 403 for non-admin user', async () => {
  const app = await createServer(
    { nodeEnv: 'test', port: 5011, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService: createFakeDiagnosticsService() },
  );

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/diagnostics',
      headers: { 'x-dev-user': devUserHeader({ role: 'user' }) },
    });

    assert.equal(response.statusCode, 403);
    const body = response.json<{ ok: boolean; error: { code: string } }>();
    assert.equal(body.ok, false);
    assert.equal(body.error.code, 'forbidden');
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/diagnostics returns paginated list for admin', async () => {
  const diagnosticsService = createFakeDiagnosticsService();
  const app = await createServer(
    { nodeEnv: 'test', port: 5012, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService },
  );

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/diagnostics',
      headers: { 'x-dev-user': devUserHeader({ role: 'admin', subscriptionEntitlement: 'none' }) },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<{
      ok: boolean;
      data: { reports: unknown[] };
      meta: { page: number; pageSize: number; total: number; hasNext: boolean };
    }>();
    assert.equal(body.ok, true);
    assert.ok(Array.isArray(body.data.reports));
    assert.equal(typeof body.meta.page, 'number');
    assert.equal(typeof body.meta.total, 'number');
  } finally {
    await app.close();
  }
});

test('GET /v1/admin/diagnostics response does not expose metadata or tokens', async () => {
  const diagnosticsService = createFakeDiagnosticsService();
  // Submit a report with sensitive-looking metadata to verify it is excluded.
  await diagnosticsService.createReport({
    userId: null,
    severity: 'error',
    platform: 'ios',
    featureArea: 'auth',
    safeMessage: 'test',
    metadata: { token: 'should-not-appear' },
  });

  const app = await createServer(
    { nodeEnv: 'test', port: 5013, databaseUrl: LOCAL_DATABASE_URL, isProduction: false },
    { diagnosticsService },
  );

  try {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/diagnostics',
      headers: { 'x-dev-user': devUserHeader({ role: 'admin', subscriptionEntitlement: 'none' }) },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json<{
      ok: boolean;
      data: { reports: Array<Record<string, unknown>> };
    }>();

    for (const report of body.data.reports) {
      // metadata must not appear in admin list response.
      assert.equal('metadata' in report, false);
      // No token-like fields.
      assert.equal('token' in report, false);
    }
  } finally {
    await app.close();
  }
});
