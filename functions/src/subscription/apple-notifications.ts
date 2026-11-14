import { onRequest } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { adminAuth, db } from '../firebase';
import {
  getAppleSubscriptionProviderConfig,
  type AppleSubscriptionProviderConfig,
} from './provider-config';
import {
  appleSignedDataVerifier,
  AppleSignedDataError,
  rethrowAppleVerificationError,
} from './apple-store';
import { appleAppAccountTokenForUid, AppleVerificationError } from './apple-store-core';
import {
  appleNotificationState,
  appleTransactionState,
  type AppleSubscriptionState,
} from './apple-state-core';
import { applyAppleSubscriptionState } from './apple-state';
import { hashPurchaseToken } from './subscription-core';
import { PurchaseTokenOwnershipError } from './purchase-token-ownership-core';

const LIFECYCLE_TYPES = new Set([
  'SUBSCRIBED',
  'DID_RENEW',
  'DID_FAIL_TO_RENEW',
  'DID_CHANGE_RENEWAL_STATUS',
  'DID_CHANGE_RENEWAL_PREF',
  'EXPIRED',
  'GRACE_PERIOD_EXPIRED',
  'REFUND',
  'REFUND_REVERSED',
  'REVOKE',
  'RENEWAL_EXTENDED',
  'OFFER_REDEEMED',
]);

export type VerifiedAppleNotification =
  | { kind: 'test' | 'ignored' }
  | {
      kind: 'subscription';
      transaction: Record<string, unknown>;
      renewal: Record<string, unknown> | null;
      status: unknown;
      signedDate: unknown;
    };

/** The envelope AND nested transaction/renewal JWS must all pass Apple's verifier. */
export async function verifyAppleNotification(
  signedPayload: string,
  config: AppleSubscriptionProviderConfig,
): Promise<VerifiedAppleNotification> {
  try {
    const verifier = await appleSignedDataVerifier(config);
    const notification = await verifier.verifyAndDecodeNotification(signedPayload);
    if (notification.version !== '2.0') throw new AppleSignedDataError('invalid_signature');
    if (notification.notificationType === 'TEST') return { kind: 'test' };
    if (!LIFECYCLE_TYPES.has(notification.notificationType ?? '')) return { kind: 'ignored' };
    const data = notification.data;
    if (!data?.signedTransactionInfo) throw new AppleSignedDataError('invalid_signature');
    const transaction = await verifier.verifyAndDecodeTransaction(data.signedTransactionInfo);
    const renewal = data.signedRenewalInfo
      ? await verifier.verifyAndDecodeRenewalInfo(data.signedRenewalInfo)
      : null;
    return {
      kind: 'subscription',
      transaction: transaction as Record<string, unknown>,
      renewal: renewal as Record<string, unknown> | null,
      status: data.status,
      signedDate: notification.signedDate,
    };
  } catch (error) {
    rethrowAppleVerificationError(error);
  }
}

export interface AppleNotificationDeps {
  config: () => Promise<AppleSubscriptionProviderConfig | null>;
  verify: typeof verifyAppleNotification;
  resolveOwner: (hash: string) => Promise<string | null>;
  accountAvailable: (uid: string) => Promise<boolean>;
  apply: (uid: string, state: AppleSubscriptionState, now: Date) => Promise<unknown>;
  now: () => Date;
}

/** Returns only an HTTP status. Never returns a UID, transaction, or signed payload. */
export async function runAppleNotification(
  signedPayload: string,
  deps: AppleNotificationDeps,
): Promise<number> {
  const config = await deps.config();
  if (config === null) return 503; // Ask Apple to retry when an operator re-enables the provider.
  try {
    const notification = await deps.verify(signedPayload, config);
    if (notification.kind !== 'subscription') return 200;
    const originalId = notification.transaction.originalTransactionId;
    if (typeof originalId !== 'string' || originalId.length === 0 || originalId.length > 128)
      return 400;
    const uid = await deps.resolveOwner(hashPurchaseToken(originalId));
    // First purchase can arrive before the device callable creates ownership.
    // Acknowledge only after that race can be retried, rather than losing renewal state.
    if (uid === null) return 503;
    if (!(await deps.accountAvailable(uid))) return 200;
    const now = deps.now();
    const accountToken = appleAppAccountTokenForUid(uid);
    const transactionState = appleTransactionState(notification.transaction, accountToken, now);
    // V2 status describes the whole chain; a refund can describe an OLD
    // transaction while that chain is still active/in grace. Its old dates
    // cannot establish the current period, so preserve canonical state until a
    // current transaction/renewal arrives instead of revoking a paying member.
    if (
      transactionState.outcome.status === 'revoked' &&
      [1, 4].includes(notification.status as number)
    )
      return 200;
    const state = appleNotificationState(
      notification.transaction,
      notification.renewal,
      notification.status,
      notification.signedDate,
      accountToken,
      now,
    );
    await deps.apply(uid, state, now);
    return 200;
  } catch (error) {
    if (error instanceof AppleVerificationError) return 400;
    if (error instanceof AppleSignedDataError)
      return error.reason === 'invalid_signature' ? 400 : 503;
    if (error instanceof PurchaseTokenOwnershipError) {
      return error.reason === 'verification_in_progress' ||
        error.reason === 'different_active_token'
        ? 503
        : 400;
    }
    // Error objects can include request bodies. Log only a fixed message.
    logger.error('Apple server notification processing failed; retry requested');
    return 503;
  }
}

export function productionAppleNotificationDeps(): AppleNotificationDeps {
  return {
    config: getAppleSubscriptionProviderConfig,
    verify: verifyAppleNotification,
    resolveOwner: async (hash) => {
      const snap = await db.collection('subscriptionPurchaseTokens').doc(hash).get();
      const uid = snap.data()?.uid;
      return typeof uid === 'string' && uid.length > 0 ? uid : null;
    },
    accountAvailable: async (uid) => {
      try {
        return !(await adminAuth.getUser(uid)).disabled;
      } catch (error) {
        if ((error as { code?: string }).code === 'auth/user-not-found') return false;
        throw error;
      }
    },
    apply: (uid, state, now) => applyAppleSubscriptionState(uid, state, now, true),
    now: () => new Date(),
  };
}

/** Apple authenticates this public webhook using JWS, not Firebase Auth/App Check. */
export const handleAppleNotification = onRequest(
  {
    region: 'europe-west1',
    minInstances: 0,
    maxInstances: 2,
    memory: '256MiB',
    timeoutSeconds: 60,
    invoker: 'public',
    cors: false,
  },
  async (request, response) => {
    response.set('Cache-Control', 'no-store');
    if (request.method !== 'POST') {
      response.set('Allow', 'POST').status(405).end();
      return;
    }
    if (!request.is('application/json')) {
      response.status(415).end();
      return;
    }
    const payload: unknown = request.body?.signedPayload;
    if (
      typeof payload !== 'string' ||
      payload.length === 0 ||
      payload.length > 131072 ||
      (request.rawBody?.length ?? 0) > 131072
    ) {
      response.status(400).end();
      return;
    }
    const status = await runAppleNotification(payload, productionAppleNotificationDeps());
    if (status === 503) response.set('Retry-After', '60');
    response.status(status).end();
  },
);
