import { describe, expect, it } from 'vitest';

import { isFirestoreSafeId } from '../points/points-core';
import {
  awardGuardDocId,
  awardGuardWindowKey,
  dailyClaimCounterDocId,
  pointCollectorDocId,
  utcDayKey,
} from './crownhunt-core';

describe('crownhunt-core award-guard identifiers', () => {
  const uid = 'user-ABC123';
  const pointId = 'point.42_slug-x';

  describe('utcDayKey', () => {
    it('returns the UTC calendar day regardless of time of day', () => {
      expect(utcDayKey(new Date('2026-07-09T00:00:00.000Z'))).toBe('2026-07-09');
      expect(utcDayKey(new Date('2026-07-09T23:59:59.999Z'))).toBe('2026-07-09');
    });

    it('rolls to the next day at the UTC boundary, not local midnight', () => {
      // 23:30 UTC is still the 9th in UTC even though it is the 10th in +02:00.
      expect(utcDayKey(new Date('2026-07-09T23:30:00.000Z'))).toBe('2026-07-09');
      expect(utcDayKey(new Date('2026-07-10T00:00:00.000Z'))).toBe('2026-07-10');
    });
  });

  describe('awardGuardWindowKey', () => {
    const now = new Date('2026-07-09T12:00:00.000Z'); // a Thursday

    it("is the constant 'once' for once rules (all history is one window)", () => {
      expect(awardGuardWindowKey('once', now)).toBe('once');
    });

    it('is the UTC day for daily rules', () => {
      expect(awardGuardWindowKey('daily', now)).toBe('2026-07-09');
    });

    it('is the UTC ISO-week Monday for weekly rules', () => {
      // Monday of the week containing Thu 2026-07-09 is 2026-07-06.
      expect(awardGuardWindowKey('weekly', now)).toBe('2026-07-06');
    });
  });

  describe('awardGuardDocId', () => {
    const now = new Date('2026-07-09T12:00:00.000Z');

    it('is deterministic for the same (uid, point, window)', () => {
      const a = awardGuardDocId(uid, pointId, 'daily', now);
      const b = awardGuardDocId(uid, pointId, 'daily', new Date('2026-07-09T20:00:00.000Z'));
      expect(a).toBe(b);
    });

    it('differs across users, points, rules, and windows', () => {
      const base = awardGuardDocId(uid, pointId, 'daily', now);
      expect(awardGuardDocId('other-user', pointId, 'daily', now)).not.toBe(base);
      expect(awardGuardDocId(uid, 'other-point', 'daily', now)).not.toBe(base);
      expect(awardGuardDocId(uid, pointId, 'once', now)).not.toBe(base);
      // Next UTC day → a fresh daily window.
      expect(awardGuardDocId(uid, pointId, 'daily', new Date('2026-07-10T12:00:00.000Z'))).not.toBe(
        base,
      );
    });

    it('never derives from the client idempotency key (no key input at all)', () => {
      // The function signature carries no idempotency key — a compile-time and
      // runtime guarantee that concurrent distinct-key claims collide here.
      expect(awardGuardDocId.length).toBe(4);
    });

    it('cannot collide when a uid or pointId contains the separator', () => {
      // Length-prefixed hashing is injective: these tuples would collide under
      // naive `${uid}__${pointId}` concatenation.
      expect(awardGuardDocId('a', 'b__c', 'once', now)).not.toBe(
        awardGuardDocId('a__b', 'c', 'once', now),
      );
      expect(awardGuardDocId('u_', '_p', 'once', now)).not.toBe(
        awardGuardDocId('u', '__p', 'once', now),
      );
    });

    it('produces a Firestore-safe document id', () => {
      for (const rule of ['once', 'daily', 'weekly'] as const) {
        expect(isFirestoreSafeId(awardGuardDocId(uid, pointId, rule, now))).toBe(true);
      }
    });
  });

  describe('dailyClaimCounterDocId', () => {
    const now = new Date('2026-07-09T12:00:00.000Z');

    it('is one bucket per user per UTC day', () => {
      const bucket = dailyClaimCounterDocId(uid, now);
      // Same UTC day → same bucket, regardless of time of day.
      expect(dailyClaimCounterDocId(uid, new Date('2026-07-09T23:00:00.000Z'))).toBe(bucket);
      // Next UTC day → a different bucket.
      expect(dailyClaimCounterDocId(uid, new Date('2026-07-10T01:00:00.000Z'))).not.toBe(bucket);
      // Different user → a different bucket.
      expect(dailyClaimCounterDocId('other-user', now)).not.toBe(bucket);
    });

    it('produces a Firestore-safe document id', () => {
      expect(isFirestoreSafeId(dailyClaimCounterDocId(uid, now))).toBe(true);
    });
  });

  describe('pointCollectorDocId', () => {
    it('is one marker per (point, user) and order-sensitive', () => {
      const marker = pointCollectorDocId(pointId, uid);
      // Stable for the same pair.
      expect(pointCollectorDocId(pointId, uid)).toBe(marker);
      // Distinct point or distinct user → a distinct marker.
      expect(pointCollectorDocId('other-point', uid)).not.toBe(marker);
      expect(pointCollectorDocId(pointId, 'other-user')).not.toBe(marker);
      // Length-prefixed hashing: swapping the fields cannot collide.
      expect(pointCollectorDocId('a', 'b__c')).not.toBe(pointCollectorDocId('a__b', 'c'));
    });

    it('produces a Firestore-safe document id', () => {
      expect(isFirestoreSafeId(pointCollectorDocId(pointId, uid))).toBe(true);
    });
  });
});
