import { createHash } from 'node:crypto';
import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';

export const LIMITS = Object.freeze({
  maxCurrentEvents: 128,
  maxRememberedEvents: 256,
  pollIntervalSeconds: 3600,
  retentionDays: 400,
});
export const RETENTION_MS = LIMITS.retentionDays * 86400000;
export const SUBSCRIPTIONS = 'clubCalendarSubscriptions';
export const TOKENS = 'clubCalendarTokens';
export const id = z
  .string()
  .regex(/^[A-Za-z0-9._-]{1,160}$/)
  .refine((s) => s !== '.' && s !== '..');
export const clubInput = z.object({ clubId: id }).strict();
export const manageInput = z.discriminatedUnion('action', [
  clubInput.extend({ action: z.literal('status') }).strict(),
  clubInput
    .extend({ action: z.literal('issue'), expectedVersion: z.number().int().nonnegative() })
    .strict(),
  clubInput
    .extend({
      action: z.literal('revoke'),
      expectedVersion: z.number().int().nonnegative().optional(),
    })
    .strict(),
]);
export const hash = (value: string): string => createHash('sha256').update(value).digest('hex');
export const millis = (value: unknown): number | null =>
  value instanceof Timestamp ? value.toMillis() : null;
export const epochTime = (value: unknown): string | null =>
  value instanceof Timestamp ? `${value.seconds}:${value.nanoseconds}` : null;
export function deny(): never {
  throw new HttpsError('permission-denied', 'Calendar access unavailable.');
}
export function parse<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) throw new HttpsError('invalid-argument', 'Invalid calendar request.');
  return result.data;
}

export interface CalendarEvent {
  id: string;
  title: string;
  startMillis: number;
  endMillis: number | null;
  locationName: string | null;
}
export interface CalendarRow extends CalendarEvent {
  status: 'CONFIRMED' | 'CANCELLED';
  sequence: number;
  stampMillis: number;
  intentCreatedMillis: number;
}

const date = z.number().finite().min(0).max(253402300799000);
export const rowSchema = z
  .object({
    id,
    title: z.string().max(300),
    startMillis: date,
    endMillis: date.nullable(),
    locationName: z.string().max(300).nullable(),
    status: z.enum(['CONFIRMED', 'CANCELLED']),
    sequence: z.number().int().min(0).max(2147483646),
    stampMillis: date,
    intentCreatedMillis: date,
  })
  .strict();

/** Source fields are deliberately no broader than the existing Driver ClubEvent DTO. */
export function canonicalEvent(
  eventId: string,
  data: Record<string, unknown> | undefined,
): CalendarEvent | null {
  if (!data || !['published', 'completed'].includes(String(data.status))) return null;
  const startMillis = millis(data.startsAt),
    endMillis = millis(data.endsAt);
  const title = typeof data.title === 'string' ? data.title.trim() : '';
  if (
    startMillis === null ||
    !title ||
    title.length > 300 ||
    (data.endsAt != null && (endMillis === null || endMillis <= startMillis))
  )
    return null;
  const locationName = typeof data.locationName === 'string' ? data.locationName.trim() : null;
  if (locationName && locationName.length > 300) return null;
  if (
    !date.safeParse(startMillis).success ||
    (endMillis !== null && !date.safeParse(endMillis).success)
  )
    return null;
  return { id: eventId, title, startMillis, endMillis, locationName: locationName || null };
}

export function journal(value: unknown): CalendarRow[] {
  const parsed = z.array(rowSchema).max(LIMITS.maxRememberedEvents).safeParse(value);
  if (!parsed.success || new Set(parsed.data.map((row) => row.id)).size !== parsed.data.length)
    throw new HttpsError('unavailable', 'Calendar temporarily unavailable.');
  return parsed.data;
}

export function subscriptionUrl(project: string | undefined, token: string): string {
  if (!project || !/^[a-z][a-z0-9-]{4,62}$/.test(project))
    throw new HttpsError('unavailable', 'Calendar temporarily unavailable.');
  return `https://europe-west1-${project}.cloudfunctions.net/clubCalendar-feed/calendar.ics?token=${token}`;
}
