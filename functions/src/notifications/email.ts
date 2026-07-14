/**
 * Email delivery abstraction — DELIBERATELY a no-op for the MVP.
 *
 * Transactional email (like FCM push) is deferred to the end-of-MVP Firebase
 * console setup. Nothing is wired to an email provider yet, so every send
 * degrades to a logged no-op and reports `sent: false`. This module exists so
 * that domains which must "warn the user by email" (currently only the
 * inactive-account sweep) can route through ONE seam today and light up for real
 * later by editing only this file.
 *
 * The inactive-account hard-delete gate additionally consults
 * `isEmailDeliveryAvailable()` so that no account can ever be deleted for
 * inactivity while the warning it is predicated on cannot actually be delivered.
 *
 * TODO(email, end-of-MVP): integrate a provider (e.g. the Firebase "Trigger
 * Email" extension, SendGrid, or Postmark), set EMAIL_DELIVERY_ENABLED=true (or
 * replace the env probe with a real config/credentials check), and implement the
 * send below. Until then `isEmailDeliveryAvailable()` stays false.
 */

import { logger } from 'firebase-functions';

/**
 * Compile-time guard: TRUE only once a real provider is wired into
 * `sendAccountEmail` below. While this is false, `isEmailDeliveryAvailable()`
 * can NEVER report true in production no matter how the env is configured, so a
 * mere config/env flip cannot open the inactive-account hard-delete gate before
 * a warning email can actually be delivered. Flip to true in the SAME change
 * that integrates the provider (see the module TODO above).
 */
const EMAIL_PROVIDER_INTEGRATED = false;

/**
 * Whether outbound email can actually be delivered right now. FALSE for the MVP.
 *
 * Requires BOTH a real integrated provider (EMAIL_PROVIDER_INTEGRATED) AND the
 * env flag. The env probe alone does NOT make email work, so in production this
 * stays false until the provider TODO above is done. Tests that need the "email
 * available" branch stub this function at the module seam
 * (`vi.spyOn(emailModule, 'isEmailDeliveryAvailable')`) rather than only setting
 * the env var — keeping production hard-closed while both branches stay covered.
 */
export function isEmailDeliveryAvailable(): boolean {
  return EMAIL_PROVIDER_INTEGRATED && process.env.EMAIL_DELIVERY_ENABLED === 'true';
}

export interface AccountEmail {
  /** Recipient address; null when the account has no email on file. */
  to: string | null;
  subject: string;
  body: string;
  /** Coarse category for logging (e.g. 'inactivity_warning'). Never PII. */
  kind: string;
}

export interface SendEmailResult {
  sent: boolean;
  /** Set when sent=false: email_unavailable | no_recipient | not_implemented. */
  reason?: string;
}

/**
 * Attempts to send one transactional email. NEVER throws — email is best-effort
 * and must never fail the caller. Returns `{ sent: false }` (with a reason) while
 * email is unwired, logging a would-send at info level. Recipient addresses are
 * NOT logged.
 */
export async function sendAccountEmail(email: AccountEmail): Promise<SendEmailResult> {
  if (!isEmailDeliveryAvailable()) {
    logger.info('Email delivery unavailable — would-send skipped (no-op)', {
      kind: email.kind,
      // Truthiness, not != null: an empty-string address is not a usable recipient.
      hasRecipient: Boolean(email.to),
    });
    return { sent: false, reason: 'email_unavailable' };
  }
  if (!email.to) {
    return { sent: false, reason: 'no_recipient' };
  }
  // TODO(email, end-of-MVP): call the real provider here and return sent: true.
  logger.warn('sendAccountEmail reached but no provider is integrated', { kind: email.kind });
  return { sent: false, reason: 'not_implemented' };
}
