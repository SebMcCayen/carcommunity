/**
 * Unit tests for the Phase 11 pure logic (subscription-core,
 * groupdrive-core). No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildSubscriptionDocument,
  hashPurchaseToken,
  isSubscriptionActiveStatus,
  parseGrantEntitlementInput,
  parseVerifySubscriptionInput,
} from '../subscription/subscription-core';
import {
  buildParticipantDocument,
  guardJoinableEvent,
  parseJoinGroupDriveInput,
  parseUpdateDriveStatusInput,
} from '../groupDrive/groupdrive-core';

const NOW = new Date('2026-07-05T12:00:00Z');

describe('subscription-core', () => {
  it('treats active and grace_period as access-granting (legacy)', () => {
    expect(isSubscriptionActiveStatus('active')).toBe(true);
    expect(isSubscriptionActiveStatus('grace_period')).toBe(true);
    for (const status of ['inactive', 'expired', 'revoked', 'cancelled'] as const) {
      expect(isSubscriptionActiveStatus(status)).toBe(false);
    }
  });

  it('hashes purchase tokens and never stores the raw token', () => {
    const hash = hashPurchaseToken('raw-receipt-token');
    expect(hash).toMatch(/^[a-f0-9]{64}$/);
    const docData = buildSubscriptionDocument(
      {
        userId: 'u1',
        platform: 'apple',
        status: 'active',
        entitlement: 'member_monthly',
        purchaseTokenHash: hash,
        expiresAt: null,
      },
      () => 'SERVER_TS',
    );
    expect(JSON.stringify(docData)).not.toContain('raw-receipt-token');
    expect(docData.purchaseTokenHash).toBe(hash);
  });

  it('validates verify and grant inputs', () => {
    expect(parseVerifySubscriptionInput({ platform: 'apple', purchaseToken: 't' }).ok).toBe(true);
    // manual is NOT a client-verifiable platform.
    expect(parseVerifySubscriptionInput({ platform: 'manual', purchaseToken: 't' }).ok).toBe(
      false,
    );
    expect(
      parseGrantEntitlementInput({
        targetUid: 'u1',
        entitlement: 'member_monthly',
        reason: 'x',
      }).ok,
    ).toBe(true);
    expect(
      parseGrantEntitlementInput({ targetUid: 'u1', entitlement: 'vip', reason: 'x' }).ok,
    ).toBe(false);
  });
});

describe('groupdrive-core', () => {
  it('guards the legacy join preconditions', () => {
    const base = { eventStatus: 'published', endsAt: null, rsvpStatus: 'going', now: NOW };
    expect(guardJoinableEvent(base).ok).toBe(true);
    expect(guardJoinableEvent({ ...base, rsvpStatus: 'maybe' }).ok).toBe(true);
    expect(guardJoinableEvent({ ...base, eventStatus: 'draft' }).ok).toBe(false);
    expect(guardJoinableEvent({ ...base, rsvpStatus: 'not_going' }).ok).toBe(false);
    expect(guardJoinableEvent({ ...base, rsvpStatus: null }).ok).toBe(false);
    expect(
      guardJoinableEvent({ ...base, endsAt: new Date('2026-07-05T11:00:00Z') }).ok,
    ).toBe(false);
  });

  it('never accepts left as an updatable status', () => {
    expect(parseUpdateDriveStatusInput({ eventId: 'e1', status: 'arrived' }).ok).toBe(true);
    expect(parseUpdateDriveStatusInput({ eventId: 'e1', status: 'left' }).ok).toBe(false);
    expect(parseJoinGroupDriveInput({ eventId: 'e1' }).ok).toBe(true);
  });

  it('builds fresh roster documents', () => {
    const docData = buildParticipantDocument('Seb', () => 'SERVER_TS');
    expect(docData).toEqual({
      displayName: 'Seb',
      status: 'joined',
      joinedAt: 'SERVER_TS',
      leftAt: null,
      updatedAt: 'SERVER_TS',
    });
  });
});
