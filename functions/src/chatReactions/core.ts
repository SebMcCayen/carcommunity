import { createHash } from 'node:crypto';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { emojiIds } from './emoji-ids';

export const REACTION_CATALOG_VERSION = '18.0';
export const allowedEmojiIds: ReadonlySet<string> = new Set(emojiIds);
const id = z.string().regex(/^[A-Za-z0-9_-]{1,160}$/);
export const roomSchema = z.object({
  scope: z.enum(['community', 'convoy', 'dm', 'event', 'club']),
  roomId: id,
});
export const setSchema = roomSchema
  .extend({
    messageId: id,
    emojiId: z
      .string()
      .refine((value) => allowedEmojiIds.has(value))
      .nullable(),
    expectedVersion: z
      .number()
      .int()
      .min(0)
      .max(Number.MAX_SAFE_INTEGER - 1),
    operationId: z.string().regex(/^[A-Za-z0-9_-]{16,160}$/),
  })
  .strict();
export const mineSchema = roomSchema.extend({ messageIds: z.array(id).min(1).max(50) }).strict();
export type ReactionInput = z.infer<typeof setSchema>;
export type ReactionRoom = z.infer<typeof roomSchema>;
export type ReactionState = { emojiId: string | null; version: number };
export function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const value = schema.safeParse(data);
  if (!value.success) throw new HttpsError('invalid-argument', 'Invalid reaction request.');
  return value.data;
}
export const digest = (value: string): string => createHash('sha256').update(value).digest('hex');
export const unavailable = (): never => {
  throw new HttpsError('not-found', 'Message unavailable.');
};
export function messagePath(room: ReactionRoom, messageId: string): string {
  switch (room.scope) {
    case 'community':
      if (room.roomId !== 'global') return unavailable();
      return `communityChat/global/messages/${messageId}`;
    case 'convoy':
      return `convoyChats/${room.roomId}/messages/${messageId}`;
    case 'dm':
      return `conversations/${room.roomId}/messages/${messageId}`;
    case 'event':
      return `events/${room.roomId}/messages/${messageId}`;
    case 'club':
      return `driverClubs/${room.roomId}/messages/${messageId}`;
  }
}
/** Reject damaged server state rather than overwriting a count we cannot verify. */
export function reactionCounts(raw: unknown): Record<string, number> {
  if (raw === undefined) return {};
  if (!raw || typeof raw !== 'object' || Array.isArray(raw))
    throw new HttpsError('failed-precondition', 'Reaction state unavailable.');
  const counts: Record<string, number> = {};
  for (const [key, value] of Object.entries(raw)) {
    if (!allowedEmojiIds.has(key) || !Number.isSafeInteger(value) || (value as number) <= 0)
      throw new HttpsError('failed-precondition', 'Reaction state unavailable.');
    counts[key] = value as number;
  }
  return counts;
}
export function nextCounts(
  raw: unknown,
  previous: string | null,
  desired: string | null,
): Record<string, number> {
  const counts = reactionCounts(raw);
  if (previous === desired) return counts;
  if (previous) {
    if (!counts[previous])
      throw new HttpsError('failed-precondition', 'Reaction state unavailable.');
    if (--counts[previous] === 0) delete counts[previous];
  }
  if (desired) {
    counts[desired] = (counts[desired] ?? 0) + 1;
    if (!Number.isSafeInteger(counts[desired]))
      throw new HttpsError('resource-exhausted', 'Reaction limit reached.');
  }
  if (Buffer.byteLength(JSON.stringify(counts)) > 256 * 1024)
    throw new HttpsError('resource-exhausted', 'Reaction limit reached.');
  return counts;
}
