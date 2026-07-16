import { describe, expect, it } from 'vitest';
import {
  parseAdminSendInput,
  requiresConfirmation,
  validateAudienceRequirements,
  type AdminSendInput,
} from './adminSend-core';
import {
  ADMIN_SENDABLE_CATEGORIES,
  NOTIFICATION_CATEGORIES,
  SOCIAL_NOTIFICATION_CATEGORIES,
} from './notifications-core';

const base = {
  category: 'admin_message',
  audience: 'admins',
  title: 'Update',
  previewText: 'A short preview',
  body: 'The full announcement body.',
  reason: 'Community-wide notice',
  idempotencyKey: 'key-123',
} as const;

describe('parseAdminSendInput', () => {
  it('accepts a well-formed admin send', () => {
    const parsed = parseAdminSendInput({ ...base });
    expect(parsed.ok).toBe(true);
  });

  it('rejects empty required fields and unknown categories/audiences', () => {
    expect(parseAdminSendInput({ ...base, title: '' }).ok).toBe(false);
    expect(parseAdminSendInput({ ...base, reason: '' }).ok).toBe(false);
    expect(parseAdminSendInput({ ...base, category: 'nope' }).ok).toBe(false);
    expect(parseAdminSendInput({ ...base, audience: 'everyone' }).ok).toBe(false);
    expect(parseAdminSendInput({ ...base, extra: true }).ok).toBe(false);
  });

  it('accepts every operational category', () => {
    for (const category of ADMIN_SENDABLE_CATEGORIES) {
      expect(parseAdminSendInput({ ...base, category }).ok).toBe(true);
    }
  });

  it('rejects the social categories — a broadcast must not pose as member activity', () => {
    // These are valid NOTIFICATION_CATEGORIES, but producer-only: an admin
    // send must not be able to fake a DM / friend request / convoy invite.
    for (const category of SOCIAL_NOTIFICATION_CATEGORIES) {
      expect(NOTIFICATION_CATEGORIES).toContain(category);
      expect(parseAdminSendInput({ ...base, category }).ok).toBe(false);
    }
  });
});

describe('validateAudienceRequirements', () => {
  const validate = (over: Partial<AdminSendInput>) =>
    validateAudienceRequirements({ ...base, ...over } as AdminSendInput);

  it('requires eventId for event_participants and targetUserId for specific_user', () => {
    expect(validate({ audience: 'event_participants' }).ok).toBe(false);
    expect(validate({ audience: 'event_participants', eventId: 'e1' }).ok).toBe(true);
    expect(validate({ audience: 'specific_user' }).ok).toBe(false);
    expect(validate({ audience: 'specific_user', targetUserId: 'u1' }).ok).toBe(true);
  });

  it('requires confirmation for broad audiences only', () => {
    expect(requiresConfirmation('all_users')).toBe(true);
    expect(requiresConfirmation('free_users')).toBe(true);
    expect(requiresConfirmation('members')).toBe(false);

    expect(validate({ audience: 'all_users' }).ok).toBe(false);
    expect(validate({ audience: 'all_users', confirmed: true }).ok).toBe(true);
    expect(validate({ audience: 'free_users', confirmed: false }).ok).toBe(false);
    expect(validate({ audience: 'members' }).ok).toBe(true);
  });
});
