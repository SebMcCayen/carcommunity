import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  Environment,
  SignedDataVerifier,
  VerificationException,
  VerificationStatus,
  type JWSTransactionDecodedPayload,
} from '@apple/app-store-server-library';
import { APPLE_BUNDLE_ID, AppleVerificationError } from './apple-store-core';
import type { AppleSubscriptionProviderConfig } from './provider-config';
import { appleTransactionState, type AppleSubscriptionState } from './apple-state-core';

let rootCertificate: Promise<Buffer> | undefined;

function loadAppleRootCertificate(): Promise<Buffer> {
  rootCertificate ??= readFile(resolve(__dirname, '../../certs/AppleRootCA-G3.cer'));
  return rootCertificate;
}

export class AppleSignedDataError extends Error {
  constructor(readonly reason: 'invalid_signature' | 'configuration' | 'unavailable') {
    super('Apple signed transaction verification failed.');
    this.name = 'AppleSignedDataError';
  }
}

export async function appleSignedDataVerifier(
  config: AppleSubscriptionProviderConfig,
): Promise<SignedDataVerifier> {
  return new SignedDataVerifier(
    [await loadAppleRootCertificate()],
    true,
    config.environment === 'production' ? Environment.PRODUCTION : Environment.SANDBOX,
    APPLE_BUNDLE_ID,
    config.appAppleId,
  );
}

export function rethrowAppleVerificationError(error: unknown): never {
  if (error instanceof VerificationException) {
    throw new AppleSignedDataError(
      error.status === VerificationStatus.RETRYABLE_VERIFICATION_FAILURE
        ? 'unavailable'
        : 'invalid_signature',
    );
  }
  if (error instanceof AppleSignedDataError || error instanceof AppleVerificationError) throw error;
  throw new AppleSignedDataError('configuration');
}

async function verifiedTransaction(
  signedTransaction: string,
  config: AppleSubscriptionProviderConfig,
): Promise<JWSTransactionDecodedPayload> {
  try {
    const verifier = await appleSignedDataVerifier(config);
    return await verifier.verifyAndDecodeTransaction(signedTransaction);
  } catch (error) {
    rethrowAppleVerificationError(error);
  }
}

export async function verifyAppleTransactionState(
  signedTransaction: string,
  expectedAppAccountToken: string,
  config: AppleSubscriptionProviderConfig,
  now: Date = new Date(),
): Promise<AppleSubscriptionState> {
  const transaction = await verifiedTransaction(signedTransaction, config);
  return appleTransactionState(
    transaction as Record<string, unknown>,
    expectedAppAccountToken,
    now,
  );
}
