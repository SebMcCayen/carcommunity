/**
 * Unit tests for the event chat pure logic (chat-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import type { UserAccessState } from '../shared/access';
import {
  CHAT_MESSAGE_MAX_LENGTH,
  buildChatMessageDocument,
  buildChatMessageRemoval,
  buildChatReportDocument,
  chatReportDocId,
  guardChatParticipant,
  parsePostChatMessageInput,
  parseRemoveChatMessageInput,
  parseReportChatMessageInput,
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

  it('blanks the body on removal and never carries the removal reason', () => {
    const removal = buildChatMessageRemoval('admin-1', serverTimestamp);
    expect(removal.message).toBe('');
    expect(removal.moderationState).toBe('removed');
    expect(removal.removedByUserId).toBe('admin-1');
    expect(removal).not.toHaveProperty('removalReason');
  });

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
