import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  Environment,
  SignedDataVerifier,
  VerificationException,
  VerificationStatus,
} from '@apple/app-store-server-library';
import {
  APPLE_BUNDLE_ID,
  AppleVerificationError,
  parseAppleTransaction,
  type AppleEntitlementOutcome,
} from './apple-store-core';
import type { AppleSubscriptionProviderConfig } from './provider-config';

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

export async function verifyAppleTransaction(
  signedTransaction: string,
  expectedAppAccountToken: string,
  config: AppleSubscriptionProviderConfig,
  now: Date = new Date(),
): Promise<AppleEntitlementOutcome> {
  try {
    const environment =
      config.environment === 'production' ? Environment.PRODUCTION : Environment.SANDBOX;
    const verifier = new SignedDataVerifier(
      [await loadAppleRootCertificate()],
      true,
      environment,
      APPLE_BUNDLE_ID,
      config.appAppleId,
    );
    const decoded = await verifier.verifyAndDecodeTransaction(signedTransaction);
    return parseAppleTransaction(decoded, expectedAppAccountToken, now);
  } catch (error) {
    if (error instanceof VerificationException) {
      if (error.status === VerificationStatus.RETRYABLE_VERIFICATION_FAILURE) {
        throw new AppleSignedDataError('unavailable');
      }
      throw new AppleSignedDataError('invalid_signature');
    }
    if (error instanceof AppleSignedDataError) throw error;
    if (error instanceof AppleVerificationError) throw error;
    throw new AppleSignedDataError('configuration');
  }
}
