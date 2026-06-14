import { createHash } from 'node:crypto';

import type { PrismaClient } from '@prisma/client';
import {
  DIAGNOSTICS_FEATURE_AREAS,
  DIAGNOSTICS_PLATFORMS,
  DIAGNOSTICS_SEVERITIES,
  type AdminDiagnosticsEntry,
  type DiagnosticsFeatureArea,
  type DiagnosticsPlatform,
  type DiagnosticsSeverity,
} from '@carcommunity/shared/diagnostics';

// ---------------------------------------------------------------------------
// Sanitization
// ---------------------------------------------------------------------------

/** Metadata keys that must never be stored. */
const BLOCKED_METADATA_KEYS = new Set([
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
]);

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
 * Removes sensitive fields from user-supplied metadata.
 * Strips auth tokens, credentials, and coordinate fields.
 * Returns null if the input is not a plain object.
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
    if (BLOCKED_METADATA_KEYS.has(key) || BLOCKED_COORDINATE_KEYS.has(lowerKey)) {
      continue;
    }

    // Block any key that looks like an auth token or credential via substring check.
    if (
      lowerKey.includes('token') ||
      lowerKey.includes('secret') ||
      lowerKey.includes('password') ||
      lowerKey.includes('credential') ||
      lowerKey.includes('auth')
    ) {
      continue;
    }

    if (key.length > MAX_METADATA_KEY_LENGTH) {
      continue;
    }

    // Only store safe scalar values.
    if (value === null || value === undefined) {
      sanitized[key] = null;
    } else if (typeof value === 'boolean' || typeof value === 'number') {
      sanitized[key] = value;
    } else if (typeof value === 'string') {
      sanitized[key] = value.slice(0, MAX_METADATA_VALUE_LENGTH);
    } else {
      // Skip objects and arrays — too much risk of nesting sensitive data.
      continue;
    }

    count += 1;
  }

  return Object.keys(sanitized).length > 0 ? sanitized : null;
}

// ---------------------------------------------------------------------------
// Fingerprint
// ---------------------------------------------------------------------------

/**
 * Generates a simple deduplication fingerprint from the stable report attributes.
 * Used to group similar reports in the future (not a cryptographic guarantee).
 */
export function generateFingerprint(input: {
  severity: string;
  platform: string;
  featureArea: string;
  errorCode: string | null | undefined;
  safeMessage: string;
}): string {
  const normalized = [
    input.severity,
    input.platform,
    input.featureArea,
    input.errorCode ?? '',
    // Normalize the message: lowercase, strip numbers and UUIDs to reduce noise.
    input.safeMessage
      .toLowerCase()
      .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '<uuid>')
      .replace(/\b\d+\b/g, '<n>')
      .slice(0, 200),
  ].join('|');

  return createHash('sha256').update(normalized).digest('hex').slice(0, 64);
}

// ---------------------------------------------------------------------------
// Service input / output types
// ---------------------------------------------------------------------------

export interface CreateDiagnosticsReportInput {
  userId?: string | null;
  severity: DiagnosticsSeverity;
  platform: DiagnosticsPlatform;
  featureArea: DiagnosticsFeatureArea;
  appVersion?: string | null;
  buildNumber?: string | null;
  osVersion?: string | null;
  errorCode?: string | null;
  safeMessage: string;
  metadata?: Record<string, unknown> | null;
}

export interface CreatedDiagnosticsReport {
  id: string;
  fingerprint: string | null;
}

export interface ListDiagnosticsReportsInput {
  page: number;
  pageSize: number;
}

export interface DiagnosticsReportsPage {
  reports: AdminDiagnosticsEntry[];
  total: number;
}

// ---------------------------------------------------------------------------
// DiagnosticsService
// ---------------------------------------------------------------------------

export class DiagnosticsService {
  constructor(private readonly prisma: PrismaClient) {}

  async createReport(input: CreateDiagnosticsReportInput): Promise<CreatedDiagnosticsReport> {
    const sanitizedMetadata = sanitizeMetadata(input.metadata ?? null);
    const fingerprint = generateFingerprint({
      severity: input.severity,
      platform: input.platform,
      featureArea: input.featureArea,
      errorCode: input.errorCode,
      safeMessage: input.safeMessage,
    });

    const report = await this.prisma.diagnosticsReport.create({
      data: {
        userId: input.userId ?? null,
        severity: input.severity,
        platform: input.platform,
        featureArea: input.featureArea,
        appVersion: input.appVersion ?? null,
        buildNumber: input.buildNumber ?? null,
        osVersion: input.osVersion ?? null,
        errorCode: input.errorCode ?? null,
        safeMessage: input.safeMessage,
        fingerprint,
        metadata: sanitizedMetadata ?? undefined,
      },
      select: { id: true, fingerprint: true },
    });

    return {
      id: report.id,
      fingerprint: report.fingerprint,
    };
  }

  async listReports(input: ListDiagnosticsReportsInput): Promise<DiagnosticsReportsPage> {
    const { page, pageSize } = input;
    const skip = (page - 1) * pageSize;

    const [total, rows] = await this.prisma.$transaction([
      this.prisma.diagnosticsReport.count(),
      this.prisma.diagnosticsReport.findMany({
        skip,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
        select: {
          id: true,
          userId: true,
          severity: true,
          platform: true,
          featureArea: true,
          appVersion: true,
          buildNumber: true,
          osVersion: true,
          errorCode: true,
          safeMessage: true,
          fingerprint: true,
          createdAt: true,
          // Intentionally exclude metadata from admin list view.
        },
      }),
    ]);

    const reports: AdminDiagnosticsEntry[] = rows.map((row) => ({
      id: row.id,
      userId: row.userId,
      severity: row.severity as DiagnosticsSeverity,
      platform: row.platform as DiagnosticsPlatform,
      featureArea: row.featureArea as DiagnosticsFeatureArea,
      appVersion: row.appVersion,
      buildNumber: row.buildNumber,
      osVersion: row.osVersion,
      errorCode: row.errorCode,
      safeMessage: row.safeMessage,
      fingerprint: row.fingerprint,
      createdAt: row.createdAt.toISOString(),
    }));

    return { reports, total };
  }
}

// Re-export for convenience.
export { DIAGNOSTICS_SEVERITIES, DIAGNOSTICS_PLATFORMS, DIAGNOSTICS_FEATURE_AREAS };
