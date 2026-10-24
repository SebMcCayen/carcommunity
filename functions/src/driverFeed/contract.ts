import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';

export const PAGE_LIMIT = 20;
export const READS_PER_HOUR = 120;
export const documentId = z.string().regex(/^[A-Za-z0-9_-]{8,128}$/);
export const cursorSchema = z.object({
  seconds: z.number().int().min(-62135596800).max(253402300799),
  nanoseconds: z.number().int().min(0).max(999999999),
  id: documentId,
}).strict();
const page = {
  limit: z.number().int().min(1).max(PAGE_LIMIT).default(PAGE_LIMIT),
  cursor: cursorSchema.optional(),
};
export const listSchema = z.object(page).strict();
export const detailSchema = z.object({ ...page, threadId: documentId }).strict();
export type Cursor = z.infer<typeof cursorSchema>;
export type PageInput = z.infer<typeof listSchema>;
export type DetailInput = z.infer<typeof detailSchema>;
export interface Comment {
  id: string;
  authorUid: string;
  authorName: string;
  authorHandle: string;
  text: string;
  createdAtMillis: number;
}
export interface Post extends Comment {
  mediaUrls: string[];
  likeCount: number;
  replyCount: number;
}
export interface ListResponse { posts: Post[]; nextCursor: Cursor | null }
export interface DetailResponse { post: Post; comments: Comment[]; nextCursor: Cursor | null }
export type Reason = 'canonical-onboarding-required' | 'driver-onboarding-required' |
  'account-restricted' | 'thread-unavailable';

export function deny(reason: Reason): never {
  const code = reason === 'thread-unavailable' ? 'not-found' :
    reason === 'account-restricted' ? 'permission-denied' : 'failed-precondition';
  throw new HttpsError(code, 'Driver feed access unavailable.', { reason });
}

export function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new HttpsError('invalid-argument', 'Invalid Driver feed request.');
  return result.data;
}

// users is canonical; driverUsers is Driver's private onboarding/status record.
// Missing status means active in Driver's completeProfile writer and KCC rules.
// Unknown statuses fail closed rather than acquiring access by omission.
export function profileFailure(
  canonical: Record<string, unknown> | undefined,
  driver: Record<string, unknown> | undefined,
): Reason | null {
  if (canonical?.suspended === true || canonical?.deleted === true ||
      (driver?.status !== undefined && driver.status !== 'active')) return 'account-restricted';
  if (!canonical || !(canonical.onboardingCompletedAt instanceof Timestamp)) {
    return 'canonical-onboarding-required';
  }
  if (!driver || driver.onboardingComplete !== true) return 'driver-onboarding-required';
  return null;
}

export function timestampCursor(id: string, value: unknown): Cursor | null {
  if (!(value instanceof Timestamp) || !documentId.safeParse(id).success) return null;
  return { seconds: value.seconds, nanoseconds: value.nanoseconds, id };
}

export function nonnegativeCount(value: unknown): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
