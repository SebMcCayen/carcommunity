import { z } from 'zod';
import { MODERATION_REPORT_REASONS } from './moderation-core';
const id = z.string().regex(/^[A-Za-z0-9_-]{8,128}$/);
const common = {
  expectedUid: z.string().min(1).max(128),
  reason: z.enum(MODERATION_REPORT_REASONS),
};
export const publicContentReportSchema = z.discriminatedUnion('surface', [
  z.object({ ...common, surface: z.literal('driver_post'), threadId: id }).strict(),
  z
    .object({ ...common, surface: z.literal('driver_comment'), threadId: id, commentId: id })
    .strict(),
  z.object({ ...common, surface: z.literal('billboard'), billboardId: id }).strict(),
]);
