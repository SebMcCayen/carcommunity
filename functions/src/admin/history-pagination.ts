/** Opaque, query-bound cursors with exact Firestore timestamp precision. */
import { createHash } from 'node:crypto';
import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';

const position = z
  .object({
    seconds: z.number().int().min(-62135596800).max(253402300799),
    nanoseconds: z.number().int().min(0).max(999999999),
    id: z
      .string()
      .min(1)
      .max(1500)
      .refine((id) => !id.includes('/')),
  })
  .strict();
const time = position.omit({ id: true });
const cursorSchema = z
  .object({
    version: z.literal(1),
    scope: z.string().length(64),
    page: z.number().int().min(1).max(1_000_000),
    asOf: time,
    after: z.object({ first: position.nullable(), second: position.nullable() }).strict(),
  })
  .strict();
export type HistoryCursor = z.infer<typeof cursorSchema>;
export const pageInput = {
  cursor: z.string().max(4096).nullish(),
  pageSize: z.number().int().min(1).max(100).default(20),
};

export function historyCursor(
  input: { cursor?: string | null; pageSize: number },
  scope: unknown,
): HistoryCursor {
  const hash = createHash('sha256')
    .update(JSON.stringify([scope, input.pageSize]))
    .digest('hex');
  if (!input.cursor) {
    const now = Timestamp.now();
    return {
      version: 1,
      scope: hash,
      page: 1,
      asOf: { seconds: now.seconds, nanoseconds: now.nanoseconds },
      after: { first: null, second: null },
    };
  }
  try {
    if (!/^[A-Za-z0-9_-]+$/.test(input.cursor)) throw new Error('Invalid encoding');
    const cursor = cursorSchema.parse(
      JSON.parse(Buffer.from(input.cursor, 'base64url').toString('utf8')),
    );
    if (cursor.scope !== hash) throw new Error('Different query');
    return cursor;
  } catch {
    throw new HttpsError(
      'invalid-argument',
      'The page cursor is invalid or belongs to another filter. Start from the first page.',
    );
  }
}

export function timestamp(value: { seconds: number; nanoseconds: number }): Timestamp {
  return new Timestamp(value.seconds, value.nanoseconds);
}
export function nextHistoryCursor(cursor: HistoryCursor): string {
  return Buffer.from(JSON.stringify({ ...cursor, page: cursor.page + 1 })).toString('base64url');
}
export function documentPosition(document: FirebaseFirestore.QueryDocumentSnapshot, field: string) {
  const date = document.get(field) as Timestamp;
  return { seconds: date.seconds, nanoseconds: date.nanoseconds, id: document.id };
}
