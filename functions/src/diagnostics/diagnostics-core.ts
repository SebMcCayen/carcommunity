/**
 * Diagnostics domain — constants, sanitization, fingerprinting, and
 * builders (Phase 9n).
 *
 * Ports packages/shared/src/diagnostics.ts and the pure parts of the
 * legacy diagnostics-service.ts to the Firestore model:
 *
 * - `diagnosticsReports/{reportId}` — crash/error telemetry. Admin-only
 *   read; ALL writes go through the diagnostics.submitReport callable so
 *   the privacy sanitization below runs server-side on every report
 *   (replaces the Phase 8 `errorReports` scaffold, which allowed
 *   unsanitized client creates and does not exist in the migration
 *   mapping).
 * - Reports may be ANONYMOUS (legacy optionalAuthHook parity): sign-in
 *   failures must be reportable, so the callable stores userId: null for
 *   unauthenticated callers.
 * - PRIVACY: metadata is sanitized verbatim per the legacy rules — auth
 *   tokens, credentials, cookies, coordinates, and anything smelling of
 *   stack traces are stripped; only bounded scalars survive. Stack traces
 *   and raw headers are never stored.
 * - Reports get a SHA-256-based dedup fingerprint over the stable
 *   attributes with numbers/UUIDs normalized out of the message.
 * - Retention: 90 days, enforced by the scheduled monthly cleanup
 *   (backend-domain-mapping.md, scheduled cleanup table).
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';

// ---------------------------------------------------------------------------
// Enums and limits (packages/shared/src/diagnostics.ts + legacy route)
// ---------------------------------------------------------------------------

export const DIAGNOSTICS_SEVERITIES = ['info', 'warning', 'error', 'critical'] as const;
export type DiagnosticsSeverity = (typeof DIAGNOSTICS_SEVERITIES)[number];

export const DIAGNOSTICS_PLATFORMS = ['ios', 'android', 'web', 'unknown'] as const;
export type DiagnosticsPlatform = (typeof DIAGNOSTICS_PLATFORMS)[number];

export const DIAGNOSTICS_FEATURE_AREAS = [
  'auth',
  'live_location',
  'events',
  'subscription',
  'admin',
  'map',
  'network',
  'unknown',
] as const;
export type DiagnosticsFeatureArea = (typeof DIAGNOSTICS_FEATURE_AREAS)[number];

export const MAX_SAFE_MESSAGE_LENGTH = 2000;
export const MAX_APP_VERSION_LENGTH = 50;
export const MAX_BUILD_NUMBER_LENGTH = 50;
export const MAX_OS_VERSION_LENGTH = 100;
export const MAX_ERROR_CODE_LENGTH = 100;

export const DIAGNOSTICS_RETENTION_DAYS = 90;

// ---------------------------------------------------------------------------
// Metadata sanitization (legacy sanitizeMetadata, ported verbatim)
// ---------------------------------------------------------------------------

/** Metadata keys that must never be stored. */
const BLOCKED_METADATA_KEYS = new Set(
  [
    'token',
    'accessToken',
    'access_token',
    'refreshToken',
    'refresh_token',
    'idToken',
    'id_token',
    'identityToken',
    'identity_token',
    'authorization',
    'authorization_header',
    'cookie',
    'password',
    'secret',
    'apiKey',
    'api_key',
    'privateKey',
    'private_key',
    'sessionToken',
    'session_token',
  ].map((key) => key.toLowerCase()),
);

/** Coordinate keys that must never be stored in metadata. */
const BLOCKED_COORDINATE_KEYS = new Set([
  'latitude',
  'longitude',
  'lat',
  'lng',
  'lon',
  'coords',
  'coordinates',
  'location',
  'position',
]);

const MAX_METADATA_KEY_COUNT = 20;
const MAX_METADATA_VALUE_LENGTH = 500;
const MAX_METADATA_KEY_LENGTH = 100;

/**
 * Removes sensitive fields from user-supplied metadata: strips auth
 * tokens, credentials, coordinates, and stack-trace-like keys; keeps only
 * bounded scalar values. Returns null when nothing safe remains.
 */
export function sanitizeMetadata(
  input: Record<string, unknown> | null | undefined,
): Record<string, unknown> | null {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    return null;
  }

  const sanitized: Record<string, unknown> = {};
  let count = 0;

  for (const [key, value] of Object.entries(input)) {
    if (count >= MAX_METADATA_KEY_COUNT) break;

    const lowerKey = key.toLowerCase();
    if (BLOCKED_METADATA_KEYS.has(lowerKey) || BLOCKED_COORDINATE_KEYS.has(lowerKey)) {
      continue;
    }

    // Block anything that smells like an auth token, credential, or stack
    // trace via substring check (legacy rule).
    if (
      lowerKey.includes('token') ||
      lowerKey.includes('secret') ||
      lowerKey.includes('password') ||
      lowerKey.includes('credential') ||
      lowerKey.includes('auth') ||
      lowerKey.includes('stack') ||
      lowerKey.includes('trace')
    ) {
      continue;
    }

    if (key.length > MAX_METADATA_KEY_LENGTH) {
      continue;
    }

    // Only store safe scalar values; objects/arrays risk nesting sensitive
    // data and are skipped entirely (legacy rule).
    if (value === null || value === undefined) {
      sanitized[key] = null;
    } else if (typeof value === 'boolean' || typeof value === 'number') {
      sanitized[key] = value;
    } else if (typeof value === 'string') {
      sanitized[key] = value.slice(0, MAX_METADATA_VALUE_LENGTH);
    } else {
      continue;
    }

    count += 1;
  }

  return Object.keys(sanitized).length > 0 ? sanitized : null;
}

// ---------------------------------------------------------------------------
// Fingerprint (legacy generateFingerprint, ported verbatim)
// ---------------------------------------------------------------------------

/**
 * Deduplication fingerprint over the stable report attributes, with
 * numbers and UUIDs normalized out of the message to reduce noise. Not a
 * cryptographic guarantee — a grouping key.
 */
export function generateFingerprint(input: {
  severity: string;
  platform: string;
  featureArea: string;
  errorCode?: string | null;
  safeMessage: string;
}): string {
  const normalized = [
    input.severity,
    input.platform,
    input.featureArea,
    input.errorCode ?? '',
    input.safeMessage
      .toLowerCase()
      .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '<uuid>')
      .replace(/\b\d+\b/g, '<n>')
      .slice(0, 200),
  ].join('|');

  return createHash('sha256').update(normalized).digest('hex').slice(0, 64);
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

const submitDiagnosticsReportInputSchema = z
  .object({
    severity: z.enum(DIAGNOSTICS_SEVERITIES),
    platform: z.enum(DIAGNOSTICS_PLATFORMS),
    featureArea: z.enum(DIAGNOSTICS_FEATURE_AREAS),
    safeMessage: z.string().min(1).max(MAX_SAFE_MESSAGE_LENGTH),
    appVersion: z.string().max(MAX_APP_VERSION_LENGTH).optional(),
    buildNumber: z.string().max(MAX_BUILD_NUMBER_LENGTH).optional(),
    osVersion: z.string().max(MAX_OS_VERSION_LENGTH).optional(),
    errorCode: z.string().max(MAX_ERROR_CODE_LENGTH).optional(),
    metadata: z.record(z.string(), z.unknown()).optional(),
  })
  .strict();

export type SubmitDiagnosticsReportInput = z.infer<typeof submitDiagnosticsReportInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseSubmitDiagnosticsReportInput(
  data: unknown,
): ParseResult<SubmitDiagnosticsReportInput> {
  const result = submitDiagnosticsReportInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        'Expected { severity, platform, featureArea, safeMessage, appVersion?, buildNumber?, osVersion?, errorCode?, metadata? }.',
    };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Document builder
// ---------------------------------------------------------------------------

/** diagnosticsReports/{reportId} document. */
export function buildDiagnosticsReportDocument(
  input: SubmitDiagnosticsReportInput,
  userId: string | null,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    userId,
    severity: input.severity,
    platform: input.platform,
    featureArea: input.featureArea,
    safeMessage: input.safeMessage,
    appVersion: input.appVersion ?? null,
    buildNumber: input.buildNumber ?? null,
    osVersion: input.osVersion ?? null,
    errorCode: input.errorCode ?? null,
    metadata: sanitizeMetadata(input.metadata ?? null),
    fingerprint: generateFingerprint(input),
    createdAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// Retention cutoff (scheduled cleanup)
// ---------------------------------------------------------------------------

const DAY_MS = 24 * 60 * 60 * 1000;

/** Reports created before this instant are deleted (90-day retention). */
export function diagnosticsRetentionCutoff(now: Date): Date {
  return new Date(now.getTime() - DIAGNOSTICS_RETENTION_DAYS * DAY_MS);
}

// ---------------------------------------------------------------------------
// Rate limiting (pure helpers — Firestore interaction stays in submitReport.ts)
// ---------------------------------------------------------------------------

/** Maximum diagnostics submissions allowed per IP within one window. */
export const DIAGNOSTICS_RATE_LIMIT_MAX = 20;
/** Rate-limit window duration in milliseconds (1 minute). */
export const DIAGNOSTICS_RATE_LIMIT_WINDOW_MS = 60_000;

/**
 * Extracts the client IP from the Express-compatible request object.
 * Reads the leftmost address in `X-Forwarded-For` (populated by Google
 * Cloud's load balancer), falling back to `req.ip`. Port numbers are
 * stripped; an absent or empty value falls back to `'unknown'`.
 */
export function extractClientIp(req: {
  headers: Record<string, string | string[] | undefined>;
  ip?: string;
}): string {
  const forwarded = req.headers['x-forwarded-for'];
  const raw =
    (typeof forwarded === 'string' ? forwarded.split(',')[0] : undefined) ??
    req.ip ??
    'unknown';
  // Strip optional port from IPv4 (e.g. "1.2.3.4:5678" → "1.2.3.4").
  // IPv6 addresses contain multiple colons and must not be altered.
  return raw.trim().replace(/^(\d{1,3}(?:\.\d{1,3}){3}):\d+$/, '$1') || 'unknown';
}

/**
 * Builds the Firestore document ID for the per-IP rate-limit counter for
 * the given 1-minute window bucket. The IP is hashed so raw addresses are
 * never persisted in document IDs.
 */
export function rateLimitDocId(ip: string, windowBucket: number): string {
  return createHash('sha256').update(`diag:${ip}:${windowBucket}`).digest('hex').slice(0, 40);
}
