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
  'sign_in',
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
// Rate limit (per caller — unauthenticated/unattested write surface protection)
// ---------------------------------------------------------------------------

/** Max reports a single caller may submit per rolling window. */
export const DIAGNOSTICS_RATE_LIMIT_MAX = 20;
/** Rolling window width: one hour. */
export const DIAGNOSTICS_RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;

/** True when a fresh report would exceed the per-caller cap. */
export function isDiagnosticsRateLimited(recentCount: number): boolean {
  return recentCount >= DIAGNOSTICS_RATE_LIMIT_MAX;
}

// ---------------------------------------------------------------------------
// Client-IP extraction (proxy-safe X-Forwarded-For handling for the anon key)
// ---------------------------------------------------------------------------

/**
 * Number of trailing X-Forwarded-For entries that Google's infrastructure
 * appends and that are therefore trustworthy. diagnostics.submitReport runs as
 * a gen2 callable, i.e. Cloud Run behind Google's HTTP(S) load balancer /
 * Google Front End, which appends exactly two values to the RIGHT of any
 * client-supplied header, in this order: `<client-ip>,<load-balancer-ip>`
 * (Google Cloud external Application Load Balancer docs:
 * https://cloud.google.com/load-balancing/docs/https#x-forwarded-for_header).
 * So the last entry is the LB forwarding-rule IP and the second-to-last is the
 * real client IP that Google observed. Everything to the LEFT of those two is
 * caller-supplied and explicitly NOT verified by Google ("The load balancer
 * does not verify any IP addresses that precede …"), hence spoofable.
 */
const GOOGLE_TRUSTED_XFF_SUFFIX = 2;
/** Offset (from the right, 1-based) of the trusted client IP: second-to-last entry. */
const TRUSTED_CLIENT_IP_OFFSET_FROM_RIGHT = GOOGLE_TRUSTED_XFF_SUFFIX;

/**
 * Flattens the raw X-Forwarded-For header into an ordered list of trimmed,
 * non-blank entries. The header can arrive as a single comma-separated string
 * or (per Node's http semantics for repeated headers) as a string[], and each
 * array element may itself be comma-separated — both shapes are handled and
 * blank/whitespace-only tokens are dropped so they never occupy a position.
 */
export function parseForwardedForEntries(forwarded: string | string[] | undefined): string[] {
  if (forwarded === undefined) return [];
  const raw = Array.isArray(forwarded) ? forwarded : [forwarded];
  return raw
    .flatMap((part) => part.split(','))
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}

/**
 * Extracts the originating client IP address from an HTTPS callable request in
 * a proxy-SAFE way. The LEFTMOST X-Forwarded-For entry is client-supplied and
 * spoofable: an attacker can prepend arbitrary fake IPs to mint a fresh
 * rate-limit bucket per request and defeat the throttle. Instead we take the
 * value at a fixed offset from the RIGHT — the position Google's load balancer
 * / Front End guarantees it appended (see GOOGLE_TRUSTED_XFF_SUFFIX) — so any
 * client-prepended entries on the left are ignored.
 *
 * Selection order:
 *  1. If XFF has at least GOOGLE_TRUSTED_XFF_SUFFIX entries, use the trusted
 *     client-IP slot (second-to-last). This is the normal production path.
 *  2. If XFF is shorter than expected (e.g. local/dev or a direct connection
 *     that did not traverse the full proxy chain), fall back to the rightmost
 *     non-blank entry — still the most-trusted position available.
 *  3. Otherwise fall back to the direct connection IP (Express `.ip`).
 *  4. Ultimately `'unknown'`.
 *
 * Blank/whitespace-only values are treated as MISSING at every step so a
 * present-but-empty header (`""`/`"   "`) or whitespace-only fallback never
 * collapses distinct callers into a single `''` bucket. Never returns `''`.
 */
export function extractClientIp(
  forwarded: string | string[] | undefined,
  fallbackIp: string | undefined,
): string {
  const entries = parseForwardedForEntries(forwarded);
  if (entries.length >= TRUSTED_CLIENT_IP_OFFSET_FROM_RIGHT) {
    // Trusted client IP sits at the fixed offset from the right; spoofed
    // client-prepended entries are further left and are ignored.
    const trusted = entries[entries.length - TRUSTED_CLIENT_IP_OFFSET_FROM_RIGHT];
    if (trusted) return trusted;
  }
  if (entries.length > 0) {
    // Proxy chain shorter than expected: use the most-trusted (rightmost) entry.
    const rightmost = entries[entries.length - 1];
    if (rightmost) return rightmost;
  }
  const fromFallback = fallbackIp?.trim();
  if (fromFallback) return fromFallback;
  return 'unknown';
}

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

/**
 * Server-derived context attached to the stored report (never client-supplied).
 *
 * `appCheckPresent` records whether the request carried a VALID App Check token
 * — submitReport is intentionally non-enforcing (pre-auth telemetry must work
 * even when App Check is unavailable), so admins use this flag to tell attested
 * reports from unattested ones. `null` when the caller did not compute it.
 *
 * `rateLimitKey` is the pseudonymised caller identifier used for per-window
 * count queries (`diagnosticsReports where rateLimitKey == X and createdAt >=
 * windowStart`). Authenticated callers use `uid:<uid>`; unauthenticated callers
 * use `ip:<sha256(ip)>`. The raw IP is NEVER stored. `null` when omitted.
 */
export interface DiagnosticsReportContext {
  appCheckPresent?: boolean;
  rateLimitKey?: string;
}

/** diagnosticsReports/{reportId} document. */
export function buildDiagnosticsReportDocument(
  input: SubmitDiagnosticsReportInput,
  userId: string | null,
  serverTimestamp: () => unknown,
  context: DiagnosticsReportContext = {},
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
    // Server-derived attestation flag (see DiagnosticsReportContext). Stored as
    // a bounded boolean; `null` when the caller did not supply it.
    appCheckPresent: context.appCheckPresent ?? null,
    // Pseudonymised caller key used for per-window rate-limit count queries.
    // `uid:<uid>` for authenticated callers; `ip:<sha256(ip)>` for anonymous.
    // Never the raw IP. `null` when omitted (backwards-compatible).
    rateLimitKey: context.rateLimitKey ?? null,
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
