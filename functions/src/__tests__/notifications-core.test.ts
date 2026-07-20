/**
 * Unit tests for the notifications pure logic (notifications-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  ESSENTIAL_NOTIFICATION_CATEGORIES,
  MAX_NOTIFICATION_BODY_LENGTH,
  MAX_NOTIFICATION_PREVIEW_LENGTH,
  MAX_NOTIFICATION_TITLE_LENGTH,
  NOTIFICATION_CATEGORIES,
  SOCIAL_NOTIFICATION_CATEGORIES,
  buildNotificationDocument,
  buildPushTokenDocument,
  decideInAppDelivery,
  hashPushToken,
  isEssentialCategory,
  parseMarkNotificationReadInput,
  parseRegisterPushTokenInput,
  parseUnregisterPushTokenInput,
  readRetentionCutoff,
  unreadRetentionCutoff,
} from '../notifications/notifications-core';
import type { UserAccessState } from '../shared/access';

const activeUser: UserAccessState = {
  role: 'user',
  activeMember: false,
  suspended: false,
  deleted: false,
};

describe('notifications-core inputs', () => {
  it('validates markRead and token inputs', () => {
    expect(parseMarkNotificationReadInput({ notificationId: 'n1' }).ok).toBe(true);
    expect(parseMarkNotificationReadInput({ notificationId: '' }).ok).toBe(false);
    expect(parseMarkNotificationReadInput({ notificationId: 'n1', extra: 1 }).ok).toBe(false);

    expect(parseRegisterPushTokenInput({ token: 'fcm-abc', platform: 'android' }).ok).toBe(true);
    expect(parseRegisterPushTokenInput({ token: 'fcm-abc', platform: 'web' }).ok).toBe(false);
    expect(parseRegisterPushTokenInput({ token: '', platform: 'android' }).ok).toBe(false);

    const tokenId = hashPushToken('fcm-abc');
    expect(parseUnregisterPushTokenInput({ tokenId }).ok).toBe(true);
    expect(parseUnregisterPushTokenInput({ tokenId: 'not-a-hash' }).ok).toBe(false);
  });

  it('hashes push tokens to stable 64-hex ids', () => {
    const hash = hashPushToken('fcm-token-123');
    expect(hash).toMatch(/^[a-f0-9]{64}$/);
    expect(hashPushToken('fcm-token-123')).toBe(hash);
    expect(hashPushToken('fcm-token-124')).not.toBe(hash);
    // The raw token is never part of the id.
    expect(hash).not.toContain('fcm');
  });
});

describe('notifications-core delivery eligibility', () => {
  it('never delivers to deleted users, essential or not', () => {
    const deleted = { ...activeUser, deleted: true };
    for (const category of NOTIFICATION_CATEGORIES) {
      expect(decideInAppDelivery(category, deleted, undefined).deliver).toBe(false);
    }
  });

  it('delivers only the essential categories to suspended users', () => {
    const suspended = { ...activeUser, suspended: true };
    for (const category of NOTIFICATION_CATEGORIES) {
      const expected = ESSENTIAL_NOTIFICATION_CATEGORIES.includes(category);
      expect(decideInAppDelivery(category, suspended, undefined).deliver).toBe(expected);
    }
  });

  it('honors per-category opt-outs except for essential categories', () => {
    const prefs = {
      system_notice: { inApp: false },
      account_warning: { inApp: false },
    };
    expect(decideInAppDelivery('system_notice', activeUser, prefs)).toEqual({
      deliver: false,
      reason: 'opted_out',
    });
    // Essential account notices cannot be disabled (legacy invariant).
    expect(decideInAppDelivery('account_warning', activeUser, prefs).deliver).toBe(true);
    // Missing entries and malformed maps default to enabled.
    expect(decideInAppDelivery('event_reminder', activeUser, prefs).deliver).toBe(true);
    expect(decideInAppDelivery('event_reminder', activeUser, 'garbage').deliver).toBe(true);
    expect(
      decideInAppDelivery('event_reminder', activeUser, { event_reminder: true }).deliver,
    ).toBe(true);
  });
});

describe('notifications-core social categories', () => {
  it('exposes the five chat/social categories as active', () => {
    expect(SOCIAL_NOTIFICATION_CATEGORIES).toEqual([
      'direct_message',
      'community_chat',
      'convoy_chat',
      'friend_request',
      'convoy_invite',
    ]);
    for (const category of SOCIAL_NOTIFICATION_CATEGORIES) {
      expect(NOTIFICATION_CATEGORIES).toContain(category);
    }
  });

  it('never marks a social category essential — members can always be silenced', () => {
    for (const category of SOCIAL_NOTIFICATION_CATEGORIES) {
      expect(isEssentialCategory(category)).toBe(false);
      expect(ESSENTIAL_NOTIFICATION_CATEGORIES).not.toContain(category);
    }
  });

  it('honors an opt-out for every social category independently', () => {
    for (const category of SOCIAL_NOTIFICATION_CATEGORIES) {
      expect(decideInAppDelivery(category, activeUser, { [category]: { inApp: false } })).toEqual({
        deliver: false,
        reason: 'opted_out',
      });
      // Opting out of one social category leaves the others delivering.
      const others = SOCIAL_NOTIFICATION_CATEGORIES.filter((c) => c !== category);
      for (const other of others) {
        expect(decideInAppDelivery(other, activeUser, { [category]: { inApp: false } }).deliver).toBe(
          true,
        );
      }
      // Default (no entry) is enabled.
      expect(decideInAppDelivery(category, activeUser, undefined).deliver).toBe(true);
    }
  });

  it('no longer couples convoy invites to system_notice', () => {
    // Silencing system notices must not silence convoy invites, and vice versa.
    const noSystem = { system_notice: { inApp: false } };
    expect(decideInAppDelivery('convoy_invite', activeUser, noSystem).deliver).toBe(true);
    const noInvites = { convoy_invite: { inApp: false } };
    expect(decideInAppDelivery('system_notice', activeUser, noInvites).deliver).toBe(true);
  });
});

describe('notifications-core builders', () => {
  it('builds unread documents truncated to the legacy plain-text limits', () => {
    const docData = buildNotificationDocument(
      {
        category: 'event_cancelled',
        title: 'T'.repeat(500),
        previewText: 'P'.repeat(500),
        body: 'B'.repeat(5000),
      },
      () => 'SERVER_TS',
    );
    expect(docData.read).toBe(false);
    expect(docData.readAt).toBeNull();
    expect(docData.actionType).toBe('none');
    expect(docData.relatedEntityId).toBeNull();
    expect((docData.title as string).length).toBe(MAX_NOTIFICATION_TITLE_LENGTH);
    expect((docData.previewText as string).length).toBe(MAX_NOTIFICATION_PREVIEW_LENGTH);
    expect((docData.body as string).length).toBe(MAX_NOTIFICATION_BODY_LENGTH);
    expect(docData.createdAt).toBe('SERVER_TS');
  });

  // The registry deliberately DOES store the raw token now. FCM addresses a
  // device by the token itself, so the previous hash-only document could never
  // be sent to. The protection moved from the storage format to the security
  // rules: userPrivate/{uid}/pushTokens denies all client access (see
  // firebase/firestore.rules), the callable still returns only the hash, and
  // the subcollection is erased with userPrivate on account deletion.
  it('builds token documents holding the raw token, keyed by its hash', () => {
    const docData = buildPushTokenDocument(
      { token: 'fcm-secret-token', platform: 'android', appVersion: '1.2.3' },
      () => 'SERVER_TS',
    );
    expect(docData.token).toBe('fcm-secret-token');
    expect(docData.platform).toBe('android');
    expect(docData.appVersion).toBe('1.2.3');
    expect(docData.buildNumber).toBeNull();
    // The document ID stays the hash, which is what keeps re-registration
    // idempotent.
    expect(hashPushToken('fcm-secret-token')).toMatch(/^[a-f0-9]{64}$/);
  });
});

describe('notifications-core retention cutoffs', () => {
  it('computes the 7-day read and 30-day unread windows', () => {
    const now = new Date('2026-07-31T12:00:00Z');
    expect(readRetentionCutoff(now).toISOString()).toBe('2026-07-24T12:00:00.000Z');
    expect(unreadRetentionCutoff(now).toISOString()).toBe('2026-07-01T12:00:00.000Z');
  });
});
