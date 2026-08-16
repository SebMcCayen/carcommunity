/**
 * Unit tests for the open-tickets pure logic (feedback/openTickets-core.ts).
 * No emulators required — input parsing, issue→mirror mapping, deterministic
 * dedup ids, and the document builders.
 */

import { describe, expect, it } from 'vitest';
import {
  ALREADY_INTERACTED_MESSAGE,
  buildGitHubCommentBody,
  buildInteractionDocument,
  buildTicketCommentReportDocument,
  isInteractRateLimited,
  isMirrorableIssue,
  INTERACT_RATE_LIMIT_MAX,
  issueInteractionDocId,
  MAX_TICKET_COMMENT_LENGTH,
  MAX_TICKET_SUMMARY_LENGTH,
  mapIssueToTicketFields,
  parseInteractInput,
  PLUS_ONE_COMMENT_BODY,
  type MappableIssue,
} from '../feedback/openTickets-core';

const baseIssue: MappableIssue = {
  number: 42,
  title: '[Android] Map fails to load',
  body: 'The live map does not load after opening the app.\nSecond line ignored.',
  html_url: 'https://github.com/SebMcCayen/carcommunity/issues/42',
  created_at: '2026-08-16T10:00:00.000Z',
  state: 'open',
  comments: 3,
};

describe('parseInteractInput', () => {
  it('accepts a plus_one and drops any accompanying text', () => {
    const r = parseInteractInput({ issueNumber: 42, type: 'plus_one', text: 'ignored', clientId: 'abc-1' });
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.input.type).toBe('plus_one');
      expect(r.input.commentText).toBeNull();
    }
  });

  it('accepts a comment and bounds its text', () => {
    const r = parseInteractInput({ issueNumber: 42, type: 'comment', text: '  same here  ', clientId: 'c1' });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.input.commentText).toBe('same here');
  });

  it('truncates an over-long comment to the cap', () => {
    const r = parseInteractInput({
      issueNumber: 42,
      type: 'comment',
      text: 'x'.repeat(MAX_TICKET_COMMENT_LENGTH + 500),
      clientId: 'c1',
    });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.input.commentText!.length).toBe(MAX_TICKET_COMMENT_LENGTH);
  });

  it('rejects a comment with no usable text', () => {
    expect(parseInteractInput({ issueNumber: 42, type: 'comment', text: '   ', clientId: 'c1' }).ok).toBe(false);
    expect(parseInteractInput({ issueNumber: 42, type: 'comment', clientId: 'c1' }).ok).toBe(false);
  });

  it('rejects bad issueNumber / clientId / type / extra keys', () => {
    expect(parseInteractInput({ issueNumber: 0, type: 'plus_one', clientId: 'c1' }).ok).toBe(false);
    expect(parseInteractInput({ issueNumber: 1.5, type: 'plus_one', clientId: 'c1' }).ok).toBe(false);
    expect(parseInteractInput({ issueNumber: 1, type: 'plus_one', clientId: 'bad id!' }).ok).toBe(false);
    expect(parseInteractInput({ issueNumber: 1, type: 'plus_one', clientId: 'x'.repeat(65) }).ok).toBe(false);
    expect(parseInteractInput({ issueNumber: 1, type: 'nope', clientId: 'c1' }).ok).toBe(false);
    expect(parseInteractInput({ issueNumber: 1, type: 'plus_one', clientId: 'c1', extra: 1 }).ok).toBe(false);
  });
});

describe('issueInteractionDocId', () => {
  it('is stable and namespaces by type so +1 and comment never collide', () => {
    expect(issueInteractionDocId(42, 'uidX', 'plus_one')).toBe('42__uidX__plus_one');
    expect(issueInteractionDocId(42, 'uidX', 'comment')).toBe('42__uidX__comment');
    expect(issueInteractionDocId(42, 'uidX', 'plus_one')).not.toBe(
      issueInteractionDocId(42, 'uidX', 'comment'),
    );
  });
});

describe('mapIssueToTicketFields / isMirrorableIssue', () => {
  it('maps the scalar fields and derives the summary from the first body line', () => {
    const t = mapIssueToTicketFields(baseIssue);
    expect(t.number).toBe(42);
    expect(t.title).toBe('[Android] Map fails to load');
    expect(t.summary).toBe('The live map does not load after opening the app.');
    expect(t.htmlUrl).toBe(baseIssue.html_url);
    expect(t.createdAtIso).toBe(baseIssue.created_at);
    expect(t.state).toBe('open');
  });

  it('falls back to the title when the body is empty and caps the summary', () => {
    expect(mapIssueToTicketFields({ ...baseIssue, body: '   ' }).summary).toBe(
      '[Android] Map fails to load',
    );
    const longBody = 'a'.repeat(MAX_TICKET_SUMMARY_LENGTH + 200);
    expect(mapIssueToTicketFields({ ...baseIssue, body: longBody }).summary.length).toBe(
      MAX_TICKET_SUMMARY_LENGTH,
    );
  });

  it('mirrors only open, non-PR rows', () => {
    expect(isMirrorableIssue(baseIssue)).toBe(true);
    expect(isMirrorableIssue({ ...baseIssue, state: 'closed' })).toBe(false);
    expect(isMirrorableIssue({ ...baseIssue, pull_request: { url: 'x' } })).toBe(false);
  });
});

describe('rate limit + builders', () => {
  it('caps at INTERACT_RATE_LIMIT_MAX', () => {
    expect(isInteractRateLimited(INTERACT_RATE_LIMIT_MAX - 1)).toBe(false);
    expect(isInteractRateLimited(INTERACT_RATE_LIMIT_MAX)).toBe(true);
  });

  it('builds a dedup ledger doc carrying the uid for the rate-limit query', () => {
    const doc = buildInteractionDocument(
      { issueNumber: 42, uid: 'uidX', type: 'comment', clientId: 'c1' },
      () => 'TS',
    );
    expect(doc).toMatchObject({ uid: 'uidX', issueNumber: 42, type: 'comment', clientId: 'c1', createdAt: 'TS' });
  });

  it('builds a moderationReports row (surface ticket) naming the comment author', () => {
    const doc = buildTicketCommentReportDocument(
      { issueNumber: 42, uid: 'uidX', commentText: 'same here', authorDisplayName: 'Alice' },
      () => 'TS',
    );
    expect(doc).toMatchObject({
      reportedBy: 'uidX',
      reportedUserId: 'uidX',
      targetType: 'message',
      targetId: 'ticket-42',
      surface: 'ticket',
      scopeId: '42',
      reason: 'other',
      status: 'pending',
      occurrences: 1,
    });
    expect((doc.snapshot as Record<string, unknown>).text).toBe('same here');
    expect((doc.snapshot as Record<string, unknown>).authorUserId).toBe('uidX');
  });

  it('exposes the fixed +1 body and a distinct already-done message', () => {
    expect(PLUS_ONE_COMMENT_BODY).toBe('Another user is affected by this issue.');
    expect(ALREADY_INTERACTED_MESSAGE.length).toBeGreaterThan(0);
  });
});

describe('buildGitHubCommentBody', () => {
  it('defangs @mentions and #refs with a zero-width space', () => {
    const body = buildGitHubCommentBody('ping @maintainer about #123');
    expect(body).toContain('@​maintainer');
    expect(body).toContain('#​123');
  });

  it('guarantees the cap on the POSTED text even after neutralization grows it', () => {
    // Every char is an @ → neutralization would double the length to ~2000.
    const body = buildGitHubCommentBody('@'.repeat(MAX_TICKET_COMMENT_LENGTH));
    expect(body.length).toBeLessThanOrEqual(MAX_TICKET_COMMENT_LENGTH);
  });

  it('leaves no dangling live @/# at the boundary after a cap slice', () => {
    const body = buildGitHubCommentBody('#'.repeat(MAX_TICKET_COMMENT_LENGTH));
    expect(body.length).toBeLessThanOrEqual(MAX_TICKET_COMMENT_LENGTH);
    // A trailing bare @/# (its zero-width guard cut off) must be stripped.
    expect(/[@#]$/u.test(body)).toBe(false);
  });
});
