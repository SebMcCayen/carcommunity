/**
 * Unit tests for the event chat pure logic (chat-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import type { UserAccessState } from '../shared/access';
import {
  CHAT_AUTO_HIDE_REPORTER_THRESHOLD,
  CHAT_MESSAGE_MAX_LENGTH,
  buildChatMessageAllow,
  buildChatMessageAutoHide,
  buildChatMessageDocument,
  buildChatMessageRemoval,
  buildChatReportDocument,
  chatReportDocId,
  countDistinctReporters,
  guardChatParticipant,
  parseAllowChatMessageInput,
  parsePostChatMessageInput,
  parseRemoveChatMessageInput,
  parseReportChatMessageInput,
  shouldAutoHide,
} from '../events/chat-core';

const serverTimestamp = () => 'SERVER_TS';

const member: UserAccessState = {
  role: 'user',
  activeMember: true,
  suspended: false,
  deleted: false,
};

describe('chat-core input parsing', () => {
  it('accepts a valid post input and rejects empty/overlong messages', () => {
    expect(parsePostChatMessageInput({ eventId: 'e1', message: 'hi' }).ok).toBe(true);
    expect(parsePostChatMessageInput({ eventId: 'e1', message: '' }).ok).toBe(false);
    expect(
      parsePostChatMessageInput({ eventId: 'e1', message: 'x'.repeat(CHAT_MESSAGE_MAX_LENGTH + 1) })
        .ok,
    ).toBe(false);
    expect(parsePostChatMessageInput({ eventId: 'e1', message: 'hi', extra: 1 }).ok).toBe(false);
  });

  it('validates report reason enum and details length', () => {
    expect(
      parseReportChatMessageInput({ eventId: 'e1', messageId: 'm1', reason: 'spam' }).ok,
    ).toBe(true);
    expect(
      parseReportChatMessageInput({ eventId: 'e1', messageId: 'm1', reason: 'bogus' }).ok,
    ).toBe(false);
    expect(
      parseReportChatMessageInput({
        eventId: 'e1',
        messageId: 'm1',
        reason: 'other',
        details: 'x'.repeat(501),
      }).ok,
    ).toBe(false);
  });

  it('requires a removal reason', () => {
    expect(
      parseRemoveChatMessageInput({ eventId: 'e1', messageId: 'm1', reason: 'Abusive.' }).ok,
    ).toBe(true);
    expect(parseRemoveChatMessageInput({ eventId: 'e1', messageId: 'm1', reason: ' ' }).ok).toBe(
      false,
    );
  });
});

describe('chat-core eligibility (legacy canReadEventChat parity)', () => {
  it('allows an active member with a going or maybe RSVP on a published event', () => {
    expect(
      guardChatParticipant({ state: member, eventStatus: 'published', rsvpStatus: 'going' }).ok,
    ).toBe(true);
    expect(
      guardChatParticipant({ state: member, eventStatus: 'published', rsvpStatus: 'maybe' }).ok,
    ).toBe(true);
  });

  it('rejects not_going and missing RSVPs', () => {
    expect(
      guardChatParticipant({ state: member, eventStatus: 'published', rsvpStatus: 'not_going' })
        .ok,
    ).toBe(false);
    expect(
      guardChatParticipant({ state: member, eventStatus: 'published', rsvpStatus: undefined }).ok,
    ).toBe(false);
  });

  it('ADMITS non-members while member gating is disabled (memberGating.ts)', () => {
    // Was: rejected. Re-locking (MEMBER_GATING_ENABLED = true) restores the
    // rejection; the RSVP and event-status requirements below are unaffected.
    expect(
      guardChatParticipant({
        state: { ...member, activeMember: false },
        eventStatus: 'published',
        rsvpStatus: 'going',
      }).ok,
    ).toBe(true);
  });

  it('STILL rejects suspended and deleted callers, member or not', () => {
    // Teeth: the unlock must never open chat to a suspended account.
    for (const restricted of [
      { ...member, suspended: true },
      { ...member, deleted: true },
      { ...member, activeMember: false, suspended: true },
    ]) {
      expect(
        guardChatParticipant({
          state: restricted,
          eventStatus: 'published',
          rsvpStatus: 'going',
        }).ok,
      ).toBe(false);
    }
  });

  it('rejects chat on non-published events', () => {
    for (const status of ['draft', 'cancelled', 'completed'] as const) {
      expect(
        guardChatParticipant({ state: member, eventStatus: status, rsvpStatus: 'going' }).ok,
      ).toBe(false);
    }
  });
});

describe('chat-core document builders', () => {
  it('builds a visible message with denormalized author and trimmed text', () => {
    const docData = buildChatMessageDocument(
      { authorUserId: 'u1', authorDisplayName: 'Kalle', message: '  hej  ' },
      serverTimestamp,
    );
    expect(docData.message).toBe('hej');
    expect(docData.authorDisplayName).toBe('Kalle');
    expect(docData.moderationState).toBe('visible');
    expect(docData.removedAt).toBeNull();
  });

  it('seeds the auto-hide + allow bookkeeping fields on a fresh message', () => {
    const docData = buildChatMessageDocument(
      { authorUserId: 'u1', authorDisplayName: 'Kalle', message: 'hej' },
      serverTimestamp,
    );
    expect(docData.hiddenAt).toBeNull();
    expect(docData.reportCount).toBe(0);
    expect(docData.allowedAt).toBeNull();
    expect(docData.allowedByUserId).toBeNull();
  });

  it('blanks the body on removal and never carries the removal reason', () => {
    const removal = buildChatMessageRemoval('admin-1', serverTimestamp);
    expect(removal.message).toBe('');
    expect(removal.moderationState).toBe('removed');
    expect(removal.removedByUserId).toBe('admin-1');
    expect(removal).not.toHaveProperty('removalReason');
  });

  it('auto-hide PRESERVES the body (no message key) and records the distinct count', () => {
    const hide = buildChatMessageAutoHide(4, serverTimestamp);
    expect(hide.moderationState).toBe('auto_hidden');
    expect(hide.reportCount).toBe(4);
    expect(hide.hiddenAt).toBe('SERVER_TS');
    // Auto-hide must never blank the body — it is reversible via Allow.
    expect(hide).not.toHaveProperty('message');
  });

  it('allow marks the terminal allowed state, keeps the body, and stamps the admin', () => {
    const allow = buildChatMessageAllow('admin-2', serverTimestamp);
    expect(allow.moderationState).toBe('allowed');
    expect(allow.allowedByUserId).toBe('admin-2');
    expect(allow.allowedAt).toBe('SERVER_TS');
    expect(allow).not.toHaveProperty('message');
  });
});

describe('distinct-reporter auto-hide rule', () => {
  it('counts DISTINCT reporterUserId, not report documents', () => {
    // One user, three reasons → three report docs but ONE reporter.
    const oneUserThreeReasons = [
      { reporterUserId: 'u1' },
      { reporterUserId: 'u1' },
      { reporterUserId: 'u1' },
    ];
    expect(countDistinctReporters(oneUserThreeReasons)).toBe(1);
    expect(shouldAutoHide(countDistinctReporters(oneUserThreeReasons))).toBe(false);

    const threeUsers = [
      { reporterUserId: 'u1' },
      { reporterUserId: 'u2' },
      { reporterUserId: 'u3' },
    ];
    expect(countDistinctReporters(threeUsers)).toBe(3);
    expect(shouldAutoHide(countDistinctReporters(threeUsers))).toBe(true);
  });

  it('ignores malformed reporter ids', () => {
    expect(
      countDistinctReporters([{ reporterUserId: '' }, { reporterUserId: undefined }, { other: 1 } as never]),
    ).toBe(0);
  });

  it('crosses exactly at the threshold constant', () => {
    expect(shouldAutoHide(CHAT_AUTO_HIDE_REPORTER_THRESHOLD - 1)).toBe(false);
    expect(shouldAutoHide(CHAT_AUTO_HIDE_REPORTER_THRESHOLD)).toBe(true);
  });
});

describe('parseAllowChatMessageInput', () => {
  it('accepts { eventId, messageId } and rejects extras / missing fields', () => {
    expect(parseAllowChatMessageInput({ eventId: 'e1', messageId: 'm1' }).ok).toBe(true);
    expect(parseAllowChatMessageInput({ eventId: 'e1' }).ok).toBe(false);
    expect(parseAllowChatMessageInput({ eventId: 'e1', messageId: 'm1', reason: 'x' }).ok).toBe(false);
    expect(parseAllowChatMessageInput({ eventId: '', messageId: 'm1' }).ok).toBe(false);
  });
});

describe('chat-core report document builder', () => {
  it('derives a deterministic report ID and clamps details', () => {
    expect(chatReportDocId('m1', 'u1', 'spam')).toBe('m1_u1_spam');
    const report = buildChatReportDocument(
      { messageId: 'm1', reporterUserId: 'u1', reason: 'other', details: `  ${'d'.repeat(600)}  ` },
      serverTimestamp,
    );
    expect((report.details as string).length).toBe(500);
    expect(report.status).toBe('new');
    const noDetails = buildChatReportDocument(
      { messageId: 'm1', reporterUserId: 'u1', reason: 'spam', details: '   ' },
      serverTimestamp,
    );
    expect(noDetails.details).toBeNull();
  });
});
