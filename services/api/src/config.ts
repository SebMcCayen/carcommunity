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
  })
  .superRefine((value, ctx) => {
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
            'At least one Apple audience placeholder is required in strict mode. Use AUTH_APPLE_ALLOWED_AUDIENCES, AUTH_APPLE_BUNDLE_ID, or AUTH_APPLE_SERVICE_ID.',
          path: ['AUTH_APPLE_ALLOWED_AUDIENCES'],
        });
      }

      if (authProviders.google.allowedClientIds.length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'At least one Google allowed client ID placeholder is required in strict mode.',
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
  };
}
