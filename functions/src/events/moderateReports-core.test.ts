import { describe, expect, it } from 'vitest';
import { parseListChatReportsInput, parseResolveChatReportInput } from './chat-core';

describe('parseResolveChatReportInput', () => {
  it('accepts a valid resolve transition', () => {
    const parsed = parseResolveChatReportInput({ eventId: 'e1', reportId: 'r1', status: 'resolved' });
    expect(parsed).toEqual({ ok: true, input: { eventId: 'e1', reportId: 'r1', status: 'resolved' } });
  });

  it('rejects the initial "new" status and unknown statuses', () => {
    expect(parseResolveChatReportInput({ eventId: 'e1', reportId: 'r1', status: 'new' }).ok).toBe(false);
    expect(parseResolveChatReportInput({ eventId: 'e1', reportId: 'r1', status: 'bogus' }).ok).toBe(false);
  });

  it('requires eventId and reportId', () => {
    expect(parseResolveChatReportInput({ reportId: 'r1', status: 'resolved' }).ok).toBe(false);
    expect(parseResolveChatReportInput({ eventId: 'e1', status: 'resolved' }).ok).toBe(false);
    expect(parseResolveChatReportInput({ eventId: 'e1', reportId: 'r1', status: 'resolved', extra: 1 }).ok).toBe(false);
  });
});

describe('parseListChatReportsInput', () => {
  it('accepts an empty request and optional filters', () => {
    expect(parseListChatReportsInput({}).ok).toBe(true);
    expect(parseListChatReportsInput({ status: 'new', pageSize: 20 }).ok).toBe(true);
  });

  it('rejects an unknown status filter, bad pageSize, or the removed page field', () => {
    expect(parseListChatReportsInput({ status: 'bogus' }).ok).toBe(false);
    expect(parseListChatReportsInput({ pageSize: 0 }).ok).toBe(false);
    // `page` was removed (single newest-first window); .strict() rejects it.
    expect(parseListChatReportsInput({ page: 1 }).ok).toBe(false);
  });
});
