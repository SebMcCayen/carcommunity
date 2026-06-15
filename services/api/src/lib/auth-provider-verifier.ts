import { webcrypto } from 'node:crypto';

import type { AuthProvider } from '@carcommunity/shared/auth';

import type { AuthProviderVerificationConfig } from '../config.js';
import { AppError } from './errors.js';

const DEFAULT_JWKS_CACHE_TTL_MS = 60 * 60 * 1000;

interface JsonWebKeySetResponse {
  keys?: SigningJsonWebKey[];
}

interface ParsedJwt {
  signingInput: string;
  signature: Uint8Array;
  header: Record<string, unknown>;
  claims: Record<string, unknown>;
}

export interface VerifyIdentityTokenInput {
  provider: AuthProvider;
  identityToken: string;
  nonce?: string;
}

export interface VerifiedIdentityToken {
  provider: AuthProvider;
  providerSubject: string;
  issuer: string;
  audience: string;
  expiresAt: Date;
  email: string | null;
  nonce: string | null;
}

export interface AuthProviderVerifier {
  verifyIdentityToken(input: VerifyIdentityTokenInput): Promise<VerifiedIdentityToken>;
}

interface CreateAuthProviderVerifierOptions {
  config: AuthProviderVerificationConfig;
  fetchImpl?: typeof fetch;
  now?: () => number;
}

type ProviderConfig = AuthProviderVerificationConfig[AuthProvider];
type SigningJsonWebKey = JsonWebKey & {
  kid?: string;
  kty?: string;
  use?: string;
  alg?: string;
};

type CachedJsonWebKeySet = {
  expiresAt: number;
  keys: SigningJsonWebKey[];
};

function invalidIdentityTokenError(): AppError {
  return new AppError(401, 'invalid_identity_token', 'Invalid identity token.');
}

function invalidIdentityProviderError(): AppError {
  return new AppError(401, 'invalid_identity_provider', 'Identity token provider does not match the requested provider.');
}

function invalidIdentityAudienceError(): AppError {
  return new AppError(401, 'invalid_identity_audience', 'Identity token audience is not allowed.');
}

function decodeBase64UrlSegment(segment: string): string {
  return Buffer.from(segment, 'base64url').toString('utf8');
}

function parseJwt(identityToken: string): ParsedJwt {
  const parts = identityToken.split('.');

  if (parts.length !== 3) {
    throw invalidIdentityTokenError();
  }

  const encodedHeader = parts[0];
  const encodedPayload = parts[1];
  const encodedSignature = parts[2];

  if (!encodedHeader || !encodedPayload || !encodedSignature) {
    throw invalidIdentityTokenError();
  }

  try {
    const header = JSON.parse(decodeBase64UrlSegment(encodedHeader)) as Record<string, unknown>;
    const claims = JSON.parse(decodeBase64UrlSegment(encodedPayload)) as Record<string, unknown>;

    return {
      signingInput: `${encodedHeader}.${encodedPayload}`,
      signature: Buffer.from(encodedSignature, 'base64url'),
      header,
      claims,
    };
  } catch {
    throw invalidIdentityTokenError();
  }
}

function getRequiredStringClaim(claims: Record<string, unknown>, name: string): string {
  const value = claims[name];

  if (typeof value !== 'string' || value.trim().length === 0) {
    throw invalidIdentityTokenError();
  }

  return value;
}

function getExpiry(claims: Record<string, unknown>, now: number): Date {
  const exp = claims.exp;

  if (typeof exp !== 'number' || !Number.isFinite(exp)) {
    throw invalidIdentityTokenError();
  }

  const expiresAt = new Date(exp * 1000);

  if (expiresAt.getTime() <= now) {
    throw invalidIdentityTokenError();
  }

  return expiresAt;
}

function getAudienceValue(audClaim: unknown): string[] {
  if (typeof audClaim === 'string' && audClaim.trim().length > 0) {
    return [audClaim];
  }

  if (Array.isArray(audClaim)) {
    const audiences = audClaim.filter((entry): entry is string => typeof entry === 'string' && entry.trim().length > 0);

    if (audiences.length > 0) {
      return audiences;
    }
  }

  throw invalidIdentityTokenError();
}

function resolveConfiguredAudiences(provider: AuthProvider, config: ProviderConfig): string[] {
  return provider === 'apple'
    ? (config as AuthProviderVerificationConfig['apple']).allowedAudiences
    : (config as AuthProviderVerificationConfig['google']).allowedClientIds;
}

function validateProviderIssuer(provider: AuthProvider, providerConfig: ProviderConfig, issuer: string): void {
  if (!providerConfig.issuers.includes(issuer)) {
    throw invalidIdentityProviderError();
  }

  // TODO: Production rollout should also validate provider-specific claims such as azp/auth_time where required.
  if (provider === 'apple') {
    // TODO: Apple Sign In can return relay emails and one-time profile fields. Handle account-linking edge cases separately.
    return;
  }

  // TODO: Google Sign In may require additional hosted-domain or azp validation for future multi-client setups.
}

function validateAudience(provider: AuthProvider, providerConfig: ProviderConfig, audClaim: unknown): string {
  const configuredAudiences = resolveConfiguredAudiences(provider, providerConfig);
  const tokenAudiences = getAudienceValue(audClaim);
  const matchedAudience = tokenAudiences.find((audience) => configuredAudiences.includes(audience));

  if (!matchedAudience) {
    throw invalidIdentityAudienceError();
  }

  return matchedAudience;
}

function validateNonce(inputNonce: string | undefined, claimNonce: unknown): string | null {
  if (!inputNonce) {
    return typeof claimNonce === 'string' && claimNonce.trim().length > 0 ? claimNonce : null;
  }

  // TODO: Production rollout must bind nonce to a server-side challenge and handle provider-specific hashing rules.
  if (typeof claimNonce !== 'string' || claimNonce.trim().length === 0 || claimNonce !== inputNonce) {
    throw invalidIdentityTokenError();
  }

  return claimNonce;
}

function parseCacheControlMaxAge(headerValue: string | null): number | null {
  if (!headerValue) {
    return null;
  }

  const match = headerValue.match(/max-age=(\d+)/i);
  const maxAge = match?.[1];
  return maxAge ? Number.parseInt(maxAge, 10) : null;
}

async function verifyJwtSignature(
  signingInput: string,
  signature: Uint8Array,
  header: Record<string, unknown>,
  keys: SigningJsonWebKey[],
): Promise<void> {
  const kid = header.kid;
  const alg = header.alg;

  if (typeof kid !== 'string' || kid.trim().length === 0 || alg !== 'RS256') {
    throw invalidIdentityTokenError();
  }

  const jwk = keys.find((candidate) => candidate.kid === kid && candidate.kty === 'RSA' && candidate.use !== 'enc');

  if (!jwk) {
    throw invalidIdentityTokenError();
  }

  try {
    const publicKey = await webcrypto.subtle.importKey(
      'jwk',
      jwk,
      {
        name: 'RSASSA-PKCS1-v1_5',
        hash: 'SHA-256',
      },
      false,
      ['verify'],
    );

    const isValid = await webcrypto.subtle.verify(
      'RSASSA-PKCS1-v1_5',
      publicKey,
      signature,
      Buffer.from(signingInput, 'utf8'),
    );

    if (!isValid) {
      throw invalidIdentityTokenError();
    }
  } catch (error) {
    if (error instanceof AppError) {
      throw error;
    }

    throw invalidIdentityTokenError();
  }
}

export function createAuthProviderVerifier(options: CreateAuthProviderVerifierOptions): AuthProviderVerifier {
  const fetchImpl = options.fetchImpl ?? fetch;
  const now = options.now ?? (() => Date.now());
  const jsonWebKeySetCache = new Map<string, CachedJsonWebKeySet>();

  const getJsonWebKeys = async (providerConfig: ProviderConfig, forceRefresh = false): Promise<SigningJsonWebKey[]> => {
    const currentTime = now();
    const cached = jsonWebKeySetCache.get(providerConfig.jwksUrl);

    if (!forceRefresh && cached && cached.expiresAt > currentTime) {
      return cached.keys;
    }

    let response: Response;

    try {
      response = await fetchImpl(providerConfig.jwksUrl, {
        headers: {
          accept: 'application/json',
        },
      });
    } catch {
      throw invalidIdentityTokenError();
    }

    if (!response.ok) {
      throw invalidIdentityTokenError();
    }

    let payload: JsonWebKeySetResponse;

    try {
      payload = (await response.json()) as JsonWebKeySetResponse;
    } catch {
      throw invalidIdentityTokenError();
    }

    if (!Array.isArray(payload.keys) || payload.keys.length === 0) {
      throw invalidIdentityTokenError();
    }

    const maxAgeSeconds = parseCacheControlMaxAge(response.headers.get('cache-control'));
    const expiresAt = currentTime + (maxAgeSeconds !== null ? maxAgeSeconds * 1000 : DEFAULT_JWKS_CACHE_TTL_MS);
    jsonWebKeySetCache.set(providerConfig.jwksUrl, { expiresAt, keys: payload.keys });

    return payload.keys;
  };

  return {
    async verifyIdentityToken(input) {
      const providerConfig = options.config[input.provider];
      const parsedToken = parseJwt(input.identityToken);
      const issuer = getRequiredStringClaim(parsedToken.claims, 'iss');

      validateProviderIssuer(input.provider, providerConfig, issuer);

      const keys = await getJsonWebKeys(providerConfig);
      const kid = typeof parsedToken.header.kid === 'string' ? parsedToken.header.kid : null;

      // If the kid is not in the cached key set, re-fetch once in case signing keys were rotated.
      const keysToVerify =
        kid && !keys.some((k) => k.kid === kid) ? await getJsonWebKeys(providerConfig, true) : keys;

      await verifyJwtSignature(parsedToken.signingInput, parsedToken.signature, parsedToken.header, keysToVerify);

      const providerSubject = getRequiredStringClaim(parsedToken.claims, 'sub');
      const audience = validateAudience(input.provider, providerConfig, parsedToken.claims.aud);
      const expiresAt = getExpiry(parsedToken.claims, now());
      const nonce = validateNonce(input.nonce, parsedToken.claims.nonce);
      const email = typeof parsedToken.claims.email === 'string' && parsedToken.claims.email.trim().length > 0 ? parsedToken.claims.email : null;

      return {
        provider: input.provider,
        providerSubject,
        issuer,
        audience,
        expiresAt,
        email,
        nonce,
      };
    },
  };
}
