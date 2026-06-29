import { z } from 'zod';

export const API_NAME = '@carcommunity/api';
export const API_VERSION = '0.1.0';
export const LOCAL_DATABASE_URL =
  'postgresql://placeholder@localhost:5432/carcommunity_api?schema=public';
export const DEFAULT_AUTH_VERIFICATION_MODE = 'placeholder' as const;
const APPLE_JWKS_URL = 'https://appleid.apple.com/auth/keys';
const GOOGLE_JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
const APPLE_ISSUERS = ['https://appleid.apple.com'] as const;
const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'] as const;

export type AuthVerificationMode = 'placeholder' | 'strict';

export interface AuthProviderVerificationConfig {
  apple: {
    allowedAudiences: string[];
    bundleId: string | null;
    serviceId: string | null;
    issuers: readonly string[];
    jwksUrl: string;
  };
  google: {
    allowedClientIds: string[];
    issuers: readonly string[];
    jwksUrl: string;
  };
}

function parseCommaSeparatedValues(value: string | undefined): string[] {
  if (!value) {
    return [];
  }

  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}

function buildAuthProviderVerificationConfig(input: {
  appleAllowedAudiences?: string;
  appleBundleId?: string;
  appleServiceId?: string;
  googleAllowedClientIds?: string;
}): AuthProviderVerificationConfig {
  const appleBundleId = input.appleBundleId?.trim() || null;
  const appleServiceId = input.appleServiceId?.trim() || null;
  const appleAllowedAudiences = new Set(parseCommaSeparatedValues(input.appleAllowedAudiences));

  if (appleBundleId) {
    appleAllowedAudiences.add(appleBundleId);
  }

  if (appleServiceId) {
    appleAllowedAudiences.add(appleServiceId);
  }

  return {
    apple: {
      allowedAudiences: [...appleAllowedAudiences],
      bundleId: appleBundleId,
      serviceId: appleServiceId,
      issuers: APPLE_ISSUERS,
      jwksUrl: APPLE_JWKS_URL,
    },
    google: {
      allowedClientIds: parseCommaSeparatedValues(input.googleAllowedClientIds),
      issuers: GOOGLE_ISSUERS,
      jwksUrl: GOOGLE_JWKS_URL,
    },
  };
}

const envSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    API_PORT: z
      .string()
      .default('4000')
      .transform((value, ctx) => {
        const port = Number.parseInt(value, 10);

        if (!Number.isInteger(port) || port < 1 || port > 65_535) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'API_PORT must be a valid TCP port number.',
          });

          return z.NEVER;
        }

        return port;
      }),
    DATABASE_URL: z.string().optional(),
    AUTH_VERIFICATION_MODE: z.enum(['placeholder', 'strict']).default(DEFAULT_AUTH_VERIFICATION_MODE),
    AUTH_APPLE_ALLOWED_AUDIENCES: z.string().optional(),
    AUTH_APPLE_BUNDLE_ID: z.string().optional(),
    AUTH_APPLE_SERVICE_ID: z.string().optional(),
    AUTH_GOOGLE_ALLOWED_CLIENT_IDS: z.string().optional(),
    /**
     * ISO 8601 date string (YYYY-MM-DD or full datetime).
     * Users whose accounts were created before this date receive the early_member badge.
     * Leave unset in production until the cutoff date is officially decided.
     * Safe default: not configured = badge not awarded.
     *
     * Example: EARLY_MEMBER_CUTOFF_DATE=2026-07-01
     */
    EARLY_MEMBER_CUTOFF_DATE: z.string().optional(),
    PARTNER_INSIGHTS_MIN_THRESHOLD: z
      .string()
      .default('10')
      .transform((value, ctx) => {
        const parsed = Number.parseInt(value, 10);
        if (!Number.isInteger(parsed) || parsed < 1) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'PARTNER_INSIGHTS_MIN_THRESHOLD must be a positive integer.',
          });
          return z.NEVER;
        }
        return parsed;
      }),
    PARTNER_INSIGHTS_PASS_BY_ENABLED: z
      .string()
      .default('false')
      .transform((value) => value === 'true'),
    /**
     * Firebase project ID used to initialize Firebase Admin SDK.
     * Required in production for Firebase ID token verification.
     * When absent, Firebase ID token verification is disabled and the
     * server falls back to the legacy session-based mechanism.
     */
    FIREBASE_PROJECT_ID: z.string().optional(),
  })
  .superRefine((value, ctx) => {
    if (value.NODE_ENV === 'production' && !value.FIREBASE_PROJECT_ID?.trim()) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'FIREBASE_PROJECT_ID is required in production for Firebase ID token verification.',
        path: ['FIREBASE_PROJECT_ID'],
      });
    }

    if (value.NODE_ENV === 'production' && !value.DATABASE_URL) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'DATABASE_URL is required in production.',
        path: ['DATABASE_URL'],
      });
    }

    if (value.NODE_ENV === 'production' && value.AUTH_VERIFICATION_MODE !== 'strict') {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'AUTH_VERIFICATION_MODE must be strict in production.',
        path: ['AUTH_VERIFICATION_MODE'],
      });
    }

    if (value.AUTH_VERIFICATION_MODE === 'strict') {
      const authProviders = buildAuthProviderVerificationConfig({
        appleAllowedAudiences: value.AUTH_APPLE_ALLOWED_AUDIENCES,
        appleBundleId: value.AUTH_APPLE_BUNDLE_ID,
        appleServiceId: value.AUTH_APPLE_SERVICE_ID,
        googleAllowedClientIds: value.AUTH_GOOGLE_ALLOWED_CLIENT_IDS,
      });

      if (authProviders.apple.allowedAudiences.length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message:
            'At least one Apple audience is required in strict mode. Use AUTH_APPLE_ALLOWED_AUDIENCES, AUTH_APPLE_BUNDLE_ID, or AUTH_APPLE_SERVICE_ID.',
          path: ['AUTH_APPLE_ALLOWED_AUDIENCES'],
        });
      }

      if (authProviders.google.allowedClientIds.length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'At least one Google allowed client ID is required in strict mode.',
          path: ['AUTH_GOOGLE_ALLOWED_CLIENT_IDS'],
        });
      }
    }
  });

export type AppConfig = {
  nodeEnv: 'development' | 'test' | 'production';
  port: number;
  databaseUrl: string;
  isProduction: boolean;
  authVerificationMode?: AuthVerificationMode;
  authProviders?: AuthProviderVerificationConfig;
  /**
   * Cutoff date for the early_member badge.
   * Null when not configured — badge is not awarded in that case.
   */
  earlyMemberCutoffDate?: Date | null;
  partnerInsightsMinThreshold?: number;
  partnerInsightsPassByFeatureEnabled?: boolean;
  /**
   * Firebase project ID. When set, Firebase ID token verification is enabled.
   * Clients send Firebase ID tokens as the `Authorization: Bearer <token>`.
   */
  firebaseProjectId?: string | null;
};

export function resolveAuthVerificationConfig(config: Pick<AppConfig, 'authVerificationMode' | 'authProviders'>): {
  mode: AuthVerificationMode;
  providers: AuthProviderVerificationConfig;
} {
  return {
    mode: config.authVerificationMode ?? DEFAULT_AUTH_VERIFICATION_MODE,
    providers:
      config.authProviders ??
      buildAuthProviderVerificationConfig({
        appleAllowedAudiences: undefined,
        appleBundleId: undefined,
        appleServiceId: undefined,
        googleAllowedClientIds: undefined,
      }),
  };
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = envSchema.parse(env);
  const authVerification = buildAuthProviderVerificationConfig({
    appleAllowedAudiences: parsed.AUTH_APPLE_ALLOWED_AUDIENCES,
    appleBundleId: parsed.AUTH_APPLE_BUNDLE_ID,
    appleServiceId: parsed.AUTH_APPLE_SERVICE_ID,
    googleAllowedClientIds: parsed.AUTH_GOOGLE_ALLOWED_CLIENT_IDS,
  });

  return {
    nodeEnv: parsed.NODE_ENV,
    port: parsed.API_PORT,
    databaseUrl: parsed.DATABASE_URL ?? LOCAL_DATABASE_URL,
    isProduction: parsed.NODE_ENV === 'production',
    authVerificationMode: parsed.AUTH_VERIFICATION_MODE,
    authProviders: authVerification,
    earlyMemberCutoffDate: parsed.EARLY_MEMBER_CUTOFF_DATE
      ? (() => {
          const d = new Date(parsed.EARLY_MEMBER_CUTOFF_DATE!);
          return isNaN(d.getTime()) ? null : d;
        })()
      : null,
    partnerInsightsMinThreshold: parsed.PARTNER_INSIGHTS_MIN_THRESHOLD,
    partnerInsightsPassByFeatureEnabled: parsed.PARTNER_INSIGHTS_PASS_BY_ENABLED,
    firebaseProjectId: parsed.FIREBASE_PROJECT_ID?.trim() || null,
  };
}
