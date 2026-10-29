import { describe, expect, it } from 'vitest';
import { publicContentReportSchema } from './public-content-core';
describe('private public-content reports', () => {
  const post = {
    surface: 'driver_post',
    threadId: 'thread_123',
    expectedUid: 'viewer',
    reason: 'other',
  };
  it('accepts only the selected target and a known reason', () => {
    expect(publicContentReportSchema.safeParse(post).success).toBe(true);
    expect(
      publicContentReportSchema.safeParse({
        ...post,
        surface: 'driver_comment',
        commentId: 'reply_123',
      }).success,
    ).toBe(true);
    expect(
      publicContentReportSchema.safeParse({ ...post, snapshot: { text: 'forged evidence' } })
        .success,
    ).toBe(false);
    expect(
      publicContentReportSchema.safeParse({ ...post, threadId: '../private/path' }).success,
    ).toBe(false);
    expect(publicContentReportSchema.safeParse({ ...post, reason: 'invented' }).success).toBe(
      false,
    );
    expect(publicContentReportSchema.safeParse({ ...post, expectedUid: '' }).success).toBe(false);
  });
});
