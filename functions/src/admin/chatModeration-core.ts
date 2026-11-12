import { z } from 'zod';

/** Product policy: a day is 24 hours, not a client timezone/calendar day. */
export const MAX_CHAT_PAUSE_DAYS = 30;
export const MAX_CHAT_PAUSE_MS = MAX_CHAT_PAUSE_DAYS * 86_400_000;
const id = z
  .string()
  .min(1)
  .max(128)
  .refine((s) => !s.includes('/') && s !== '.' && s !== '..');
export const chatModerationTarget = z
  .object({
    source: z.enum(['community', 'event']),
    eventId: id.optional(),
    messageId: id,
  })
  .strict()
  .refine((v) => (v.source === 'event' ? !!v.eventId : v.eventId === undefined));
export type ChatModerationTarget = z.infer<typeof chatModerationTarget>;
export const chatPause = z
  .object({
    amount: z
      .number()
      .int()
      .positive()
      .max(MAX_CHAT_PAUSE_DAYS * 1440),
    unit: z.enum(['minutes', 'days']),
  })
  .strict()
  .refine((v) => chatPauseDuration(v) <= MAX_CHAT_PAUSE_MS);
export function chatPauseDuration(value: { amount: number; unit: 'minutes' | 'days' }) {
  return value.amount * (value.unit === 'days' ? 86_400_000 : 60_000);
}
export const chatModerationContextInput = z.object({ target: chatModerationTarget }).strict();
export const chatModerationDecision = z
  .object({
    target: chatModerationTarget,
    expectedActorUid: id,
    expectedVersion: z.string().regex(/^[0-9a-f]{64}$/),
    action: z.enum(['remove', 'pause']),
    reason: z.string().trim().min(1).max(500),
    warn: z.boolean(),
    pause: chatPause.nullable(),
    idempotencyKey: z.string().regex(/^[A-Za-z0-9._-]{16,128}$/),
  })
  .strict()
  .refine((v) => v.action !== 'pause' || v.pause !== null);

/** Exact expiry boundary: at expiresAt writing is allowed again. */
export function isChatPauseActive(pauseUntilMillis: number, nowMillis: number) {
  return pauseUntilMillis > nowMillis;
}
