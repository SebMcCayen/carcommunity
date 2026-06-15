import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import test from 'node:test';

import type { AuthProviderVerificationConfig } from '../config.js';
import { createAuthProviderVerifier } from './auth-provider-verifier.js';

type SigningJsonWebKey = JsonWebKey & {
  kid?: string;
  use?: string;
  alg?: string;
};

function createVerifierConfig(): AuthProviderVerificationConfig {
  return {
    apple: {
      allowedAudiences: ['com.example.apple'],
      bundleId: 'com.example.apple',
      serviceId: 'com.example.apple.web',
      issuers: ['https://appleid.apple.com'],
      jwksUrl: 'https://example.test/apple/keys',
    },
    google: {
      allowedClientIds: ['replace-with-google-client-id'],
      issuers: ['https://accounts.google.com', 'accounts.google.com'],
      jwksUrl: 'https://example.test/google/keys',
    },
  };
}

function encodeJsonAsBase64Url(value: Record<string, unknown>): string {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

async function createSignedIdentityToken(claims: Record<string, unknown>) {
  const keyPair = await webcrypto.subtle.generateKey(
    {
      name: 'RSASSA-PKCS1-v1_5',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: 'SHA-256',
    },
    true,
    ['sign', 'verify'],
  );
  const jwk = (await webcrypto.subtle.exportKey('jwk', keyPair.publicKey)) as SigningJsonWebKey;
  jwk.kid = 'test-kid';
  jwk.use = 'sig';
  jwk.alg = 'RS256';

  const header = encodeJsonAsBase64Url({ alg: 'RS256', typ: 'JWT', kid: 'test-kid' });
  const payload = encodeJsonAsBase64Url(claims);
  const signingInput = `${header}.${payload}`;
  const signature = await webcrypto.subtle.sign('RSASSA-PKCS1-v1_5', keyPair.privateKey, Buffer.from(signingInput, 'utf8'));

  return {
    token: `${signingInput}.${Buffer.from(signature).toString('base64url')}`,
    jwk,
  };
}

test('verifyIdentityToken validates signed Apple identity token claims', async () => {
  const now = Date.UTC(2026, 0, 1, 12, 0, 0);
  const { token, jwk } = await createSignedIdentityToken({
    iss: 'https://appleid.apple.com',
    aud: 'com.example.apple',
    sub: 'apple-subject-123',
    email: 'driver@example.com',
    exp: Math.floor(now / 1000) + 3600,
    nonce: 'nonce-123',
  });
  const verifier = createAuthProviderVerifier({
    config: createVerifierConfig(),
    now: () => now,
    fetchImpl: async () =>
      new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
        headers: { 'cache-control': 'max-age=60' },
      }),
  });

  const result = await verifier.verifyIdentityToken({
    provider: 'apple',
    identityToken: token,
    nonce: 'nonce-123',
  });

  assert.equal(result.provider, 'apple');
  assert.equal(result.providerSubject, 'apple-subject-123');
  assert.equal(result.issuer, 'https://appleid.apple.com');
  assert.equal(result.audience, 'com.example.apple');
  assert.equal(result.email, 'driver@example.com');
});

test('verifyIdentityToken validates signed Google identity token claims', async () => {
  const now = Date.UTC(2026, 0, 1, 12, 0, 0);
  const { token, jwk } = await createSignedIdentityToken({
    iss: 'https://accounts.google.com',
    aud: 'replace-with-google-client-id',
    sub: 'google-subject-456',
    exp: Math.floor(now / 1000) + 3600,
  });
  const verifier = createAuthProviderVerifier({
    config: createVerifierConfig(),
    now: () => now,
    fetchImpl: async () =>
      new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
      }),
  });

  const result = await verifier.verifyIdentityToken({
    provider: 'google',
    identityToken: token,
  });

  assert.equal(result.provider, 'google');
  assert.equal(result.providerSubject, 'google-subject-456');
  assert.equal(result.audience, 'replace-with-google-client-id');
});

test('verifyIdentityToken rejects invalid audience safely', async () => {
  const now = Date.UTC(2026, 0, 1, 12, 0, 0);
  const { token, jwk } = await createSignedIdentityToken({
    iss: 'https://accounts.google.com',
    aud: 'unexpected-client-id',
    sub: 'google-subject-456',
    exp: Math.floor(now / 1000) + 3600,
  });
  const verifier = createAuthProviderVerifier({
    config: createVerifierConfig(),
    now: () => now,
    fetchImpl: async () =>
      new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
      }),
  });

  await assert.rejects(
    verifier.verifyIdentityToken({
      provider: 'google',
      identityToken: token,
    }),
    {
      code: 'invalid_identity_audience',
      statusCode: 401,
    },
  );
});

test('verifyIdentityToken rejects provider mismatch issuer safely', async () => {
  const now = Date.UTC(2026, 0, 1, 12, 0, 0);
  const { token, jwk } = await createSignedIdentityToken({
    iss: 'https://accounts.google.com',
    aud: 'replace-with-google-client-id',
    sub: 'google-subject-456',
    exp: Math.floor(now / 1000) + 3600,
  });
  const verifier = createAuthProviderVerifier({
    config: createVerifierConfig(),
    now: () => now,
    fetchImpl: async () =>
      new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
      }),
  });

  await assert.rejects(
    verifier.verifyIdentityToken({
      provider: 'apple',
      identityToken: token,
    }),
    {
      code: 'invalid_identity_provider',
      statusCode: 401,
    },
  );
});

test('verifyIdentityToken rejects expired tokens safely', async () => {
  const now = Date.UTC(2026, 0, 1, 12, 0, 0);
  const { token, jwk } = await createSignedIdentityToken({
    iss: 'https://appleid.apple.com',
    aud: 'com.example.apple',
    sub: 'apple-subject-123',
    exp: Math.floor(now / 1000) - 10,
  });
  const verifier = createAuthProviderVerifier({
    config: createVerifierConfig(),
    now: () => now,
    fetchImpl: async () =>
      new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
      }),
  });

  await assert.rejects(
    verifier.verifyIdentityToken({
      provider: 'apple',
      identityToken: token,
    }),
    {
      code: 'invalid_identity_token',
      statusCode: 401,
    },
  );
});

test('verifyIdentityToken rejects nonce mismatch safely', async () => {
  const now = Date.UTC(2026, 0, 1, 12, 0, 0);
  const { token, jwk } = await createSignedIdentityToken({
    iss: 'https://appleid.apple.com',
    aud: 'com.example.apple',
    sub: 'apple-subject-123',
    exp: Math.floor(now / 1000) + 3600,
    nonce: 'nonce-from-provider',
  });
  const verifier = createAuthProviderVerifier({
    config: createVerifierConfig(),
    now: () => now,
    fetchImpl: async () =>
      new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
      }),
  });

  await assert.rejects(
    verifier.verifyIdentityToken({
      provider: 'apple',
      identityToken: token,
      nonce: 'different-nonce',
    }),
    {
      code: 'invalid_identity_token',
      statusCode: 401,
    },
  );
});
