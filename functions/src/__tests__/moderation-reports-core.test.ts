/**
 * Unit tests for the moderation-report domain core (pure logic behind
 * chatchannels.reportMessage / dm.reportMessage / moderation.reportUser).
 *
 * These assert the DECISIONS, not just the shapes: that the reason vocabulary
 * really is identical to the event-chat one it claims parity with, that the
 * message dedup key separates on every dimension it says it does, and that the
 * user dedup key deliberately does NOT separate on reason.
 */

import { describe, expect, it } from 'vitest';
import { CHAT_MESSAGE_REPORT_REASONS } from '../events/chat-core';
import {
  MODERATION_REPORT_DETAILS_MAX_LENGTH,
  MODERATION_REPORT_INITIAL_STATUS,
  MODERATION_REPORT_RATE_LIMIT_MAX,
  MODERATION_REPORT_REASONS,
  MODERATION_SNAPSHOT_TEXT_MAX_LENGTH,
  buildMessageReportDocument,
  buildMessageReportRepeatUpdate,
  buildUserReportDocument,
  buildUserReportRepeatUpdate,
  buildUserSummaryUpdate,
  isModerationRateLimited,
  moderationMessageReportId,
  moderationUserReportId,
  normalizeDetails,
  parseReportChannelMessageInput,
  parseReportDirectMessageInput,
  parseReportUserInput,
  toReportedMessageSnapshot,
} from '../moderation/moderation-core';

const TS = 'server-timestamp';
const ts = () => TS;

describe('reason vocabulary', () => {
  it('is identical to the event-chat report reasons', () => {
    // Not "is a superset" or "overlaps": admins triage event-chat reports and
    // these reports in ONE queue, so a reason on one surface and not the other
    // would make the queue's reason filter silently lie.
    expect([...MODERATION_REPORT_REASONS]).toEqual([...CHAT_MESSAGE_REPORT_REASONS]);
  });

  it('caps details at the same length as the event-chat report', () => {
    expect(MODERATION_REPORT_DETAILS_MAX_LENGTH).toBe(500);
  });
});

describe('parseReportChannelMessageInput', () => {
  it('accepts a community report without a convoyId', () => {
    const result = parseReportChannelMessageInput({
      channel: 'community',
      messageId: 'm1',
      reason: 'spam',
    });
    expect(result.ok).toBe(true);
  });

  it('accepts a convoy report with a convoyId', () => {
    const result = parseReportChannelMessageInput({
      channel: 'convoy',
      convoyId: 'c1',
      messageId: 'm1',
      reason: 'harassment',
      details: 'x',
    });
    expect(result).toMatchObject({ ok: true, input: { convoyId: 'c1' } });
  });

  it('rejects a convoy report with no convoyId', () => {
    expect(
      parseReportChannelMessageInput({ channel: 'convoy', messageId: 'm1', reason: 'spam' }).ok,
    ).toBe(false);
  });

  it('rejects a community report that also passes a convoyId', () => {
    // Rejected rather than ignored: a client sending both has a bug, and
    // swallowing it would file the report against the wrong scope.
    expect(
      parseReportChannelMessageInput({
        channel: 'community',
        convoyId: 'c1',
        messageId: 'm1',
        reason: 'spam',
      }).ok,
    ).toBe(false);
  });

  it('rejects an unknown channel, an unknown reason, and unknown keys', () => {
    expect(
      parseReportChannelMessageInput({ channel: 'dm', messageId: 'm1', reason: 'spam' }).ok,
    ).toBe(false);
    expect(
      parseReportChannelMessageInput({ channel: 'community', messageId: 'm1', reason: 'rude' }).ok,
    ).toBe(false);
    expect(
      parseReportChannelMessageInput({
        channel: 'community',
        messageId: 'm1',
        reason: 'spam',
        status: 'dismissed',
      }).ok,
    ).toBe(false);
  });

  it('rejects details longer than the cap', () => {
    expect(
      parseReportChannelMessageInput({
        channel: 'community',
        messageId: 'm1',
        reason: 'spam',
        details: 'x'.repeat(MODERATION_REPORT_DETAILS_MAX_LENGTH + 1),
      }).ok,
    ).toBe(false);
  });
});

describe('parseReportDirectMessageInput', () => {
  it('accepts a dmPairId-shaped conversation id (contains `__`)', () => {
    const result = parseReportDirectMessageInput({
      conversationId: 'uidAAA__uidBBB',
      messageId: 'm1',
      reason: 'privacy',
    });
    expect(result.ok).toBe(true);
  });

  it('rejects a missing conversationId and an empty messageId', () => {
    expect(parseReportDirectMessageInput({ messageId: 'm1', reason: 'spam' }).ok).toBe(false);
    expect(parseReportDirectMessageInput({ conversationId: 'a__b', messageId: '', reason: 'spam' }).ok).toBe(
      false,
    );
  });
});

describe('parseReportUserInput', () => {
  it('accepts a reported uid + reason', () => {
    expect(parseReportUserInput({ reportedUserId: 'u2', reason: 'harassment' }).ok).toBe(true);
  });

  it('rejects unknown keys (a client cannot seed status or a snapshot)', () => {
    expect(
      parseReportUserInput({ reportedUserId: 'u2', reason: 'harassment', status: 'reviewed' }).ok,
    ).toBe(false);
  });
});

describe('moderationMessageReportId', () => {
  const base = {
    surface: 'community' as const,
    scopeId: 'global',
    messageId: 'm1',
    reporterUserId: 'u1',
    reason: 'spam' as const,
  };

  it('is deterministic', () => {
    expect(moderationMessageReportId(base)).toBe(moderationMessageReportId(base));
  });

  it('separates on every dimension of its dedup grain', () => {
    const ids = new Set([
      moderationMessageReportId(base),
      moderationMessageReportId({ ...base, surface: 'convoy', scopeId: 'c1' }),
      moderationMessageReportId({ ...base, scopeId: 'other' }),
      moderationMessageReportId({ ...base, messageId: 'm2' }),
      moderationMessageReportId({ ...base, reporterUserId: 'u2' }),
      moderationMessageReportId({ ...base, reason: 'harassment' }),
    ]);
    expect(ids.size).toBe(6);
  });

  it('cannot be forged across field boundaries', () => {
    // A naive `${a}_${b}` join collides for ('a','b_c') vs ('a_b','c'); the
    // length-prefixed hash does not. conversationIds contain `__`, so this is
    // a live concern on the dm surface, not a theoretical one.
    const a = moderationMessageReportId({ ...base, scopeId: 'a', messageId: 'b__c' });
    const b = moderationMessageReportId({ ...base, scopeId: 'a__b', messageId: 'c' });
    expect(a).not.toBe(b);
  });

  it('never collides with a user report id', () => {
    expect(moderationMessageReportId(base)).not.toBe(moderationUserReportId('u1', 'global'));
  });
});

describe('moderationUserReportId', () => {
  it('is stable for one (reporter, target) pair regardless of reason', () => {
    // The whole point of the user dedup grain: cycling the reason enum must not
    // mint a fresh queue row for the same accuser and the same target.
    expect(moderationUserReportId('u1', 'u2')).toBe(moderationUserReportId('u1', 'u2'));
  });

  it('is directional — reporting someone is not the same as being reported', () => {
    expect(moderationUserReportId('u1', 'u2')).not.toBe(moderationUserReportId('u2', 'u1'));
  });
});

describe('normalizeDetails', () => {
  it('trims, nulls blank input, and truncates at the cap', () => {
    expect(normalizeDetails(undefined)).toBeNull();
    expect(normalizeDetails('   ')).toBeNull();
    expect(normalizeDetails('  hi  ')).toBe('hi');
    expect(normalizeDetails('x'.repeat(600))).toHaveLength(MODERATION_REPORT_DETAILS_MAX_LENGTH);
  });
});

describe('toReportedMessageSnapshot', () => {
  it('captures the evidence and caps the body', () => {
    const snapshot = toReportedMessageSnapshot({
      text: 'x'.repeat(MODERATION_SNAPSHOT_TEXT_MAX_LENGTH + 50),
      authorUserId: 'author',
      authorDisplayName: 'Author',
      createdAtIso: '2026-07-01T00:00:00.000Z',
    });
    expect(snapshot.text).toHaveLength(MODERATION_SNAPSHOT_TEXT_MAX_LENGTH);
    expect(snapshot).toMatchObject({ authorUserId: 'author', authorDisplayName: 'Author' });
  });

  it('degrades to empty text / null name rather than throwing on a malformed doc', () => {
    const snapshot = toReportedMessageSnapshot({
      text: undefined,
      authorUserId: 'author',
      authorDisplayName: 42,
      createdAtIso: null,
    });
    expect(snapshot).toEqual({
      text: '',
      authorUserId: 'author',
      authorDisplayName: null,
      createdAt: null,
    });
  });
});

describe('buildMessageReportDocument', () => {
  const doc = buildMessageReportDocument(
    {
      surface: 'dm',
      scopeId: 'u1__u2',
      messageId: 'm1',
      reporterUserId: 'u1',
      reason: 'harassment',
      details: '  note  ',
      snapshot: {
        text: 'the reported line',
        authorUserId: 'u2',
        authorDisplayName: 'Bad Actor',
        createdAt: '2026-07-01T00:00:00.000Z',
      },
    },
    ts,
  );

  it('writes the legacy field names the admin queue already renders', () => {
    expect(doc).toMatchObject({
      reportedBy: 'u1',
      targetType: 'message',
      targetId: 'm1',
      reason: 'harassment',
      status: MODERATION_REPORT_INITIAL_STATUS,
      createdAt: TS,
    });
  });

  it('adds the locating fields and the snapshot', () => {
    expect(doc).toMatchObject({
      surface: 'dm',
      scopeId: 'u1__u2',
      reportedUserId: 'u2',
      occurrences: 1,
      details: 'note',
    });
    expect(doc.snapshot).toMatchObject({ text: 'the reported line', authorUserId: 'u2' });
  });

  it('denormalizes the reported author so admins can pivot per person', () => {
    expect(doc.reportedUserId).toBe(doc.snapshot && (doc.snapshot as { authorUserId: string }).authorUserId);
  });
});

describe('buildMessageReportRepeatUpdate', () => {
  it('touches ONLY details — never status, never the snapshot', () => {
    // A repeat must not resurrect a report a moderator already resolved.
    expect(Object.keys(buildMessageReportRepeatUpdate('again'))).toEqual(['details']);
  });
});

describe('buildUserReportDocument', () => {
  const doc = buildUserReportDocument(
    {
      reportedUserId: 'u2',
      reporterUserId: 'u1',
      reason: 'harassment',
      details: undefined,
      snapshot: { displayName: 'Bad Actor', avatarPath: 'profileImages/u2/a.jpg' },
    },
    ts,
  );

  it('targets the person and starts pending', () => {
    expect(doc).toMatchObject({
      reportedBy: 'u1',
      targetType: 'user',
      targetId: 'u2',
      reportedUserId: 'u2',
      status: MODERATION_REPORT_INITIAL_STATUS,
      details: null,
      occurrences: 1,
      surface: null,
      scopeId: null,
    });
  });

  it('captures ONLY the public profile projection — no history', () => {
    expect(doc.snapshot).toEqual({
      displayName: 'Bad Actor',
      avatarPath: 'profileImages/u2/a.jpg',
    });
  });
});

describe('buildUserReportRepeatUpdate', () => {
  it('tallies and refreshes the accusation without reopening a resolved report', () => {
    const update = buildUserReportRepeatUpdate(
      { reason: 'spam', details: ' more ' },
      'INCREMENT(1)',
      ts,
    );
    expect(update).toEqual({
      reason: 'spam',
      details: 'more',
      occurrences: 'INCREMENT(1)',
      lastReportedAt: TS,
    });
    expect(Object.keys(update)).not.toContain('status');
  });
});

describe('buildUserSummaryUpdate', () => {
  const increment = (by: number) => `INCREMENT(${by})`;

  it('counts a distinct reporter only when the report document is new', () => {
    expect(
      buildUserSummaryUpdate({ reportedUserId: 'u2', newReporter: true }, increment, ts),
    ).toMatchObject({ reporterCount: 'INCREMENT(1)', totalSubmissions: 'INCREMENT(1)' });
    // A repeat from someone who already reported them advances the submission
    // tally but NOT the distinct-reporter count — otherwise one person could
    // manufacture "reported by 20 people".
    expect(
      buildUserSummaryUpdate({ reportedUserId: 'u2', newReporter: false }, increment, ts),
    ).toMatchObject({ reporterCount: 'INCREMENT(0)', totalSubmissions: 'INCREMENT(1)' });
  });
});

describe('isModerationRateLimited', () => {
  it('allows up to the cap and rejects at it', () => {
    expect(isModerationRateLimited(MODERATION_REPORT_RATE_LIMIT_MAX - 1)).toBe(false);
    expect(isModerationRateLimited(MODERATION_REPORT_RATE_LIMIT_MAX)).toBe(true);
    expect(isModerationRateLimited(MODERATION_REPORT_RATE_LIMIT_MAX + 5)).toBe(true);
  });
});
