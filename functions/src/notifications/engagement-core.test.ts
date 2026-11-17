import { describe, expect, it } from 'vitest';
import {
  engagementId,
  engagementPushRemainingMs,
  nextEngagementBudget,
  epicWithinRange,
  ENGAGEMENT_COOLDOWN_MS,
} from './engagement-core';
import { buildPushDeepLink, decideInAppDelivery, decidePushDelivery } from './notifications-core';
const active = { role: 'user' as const, activeMember: false, deleted: false, suspended: false };

describe('engagement notification policy', () => {
  it('does not queue stale engagement pushes for later delivery', () => {
    expect(engagementPushRemainingMs(1000, 1000)).toBe(300000);
    expect(engagementPushRemainingMs(1000, 301000)).toBe(0);
    expect(engagementPushRemainingMs(NaN, 1000)).toBe(0);
  });
  it('spaces deliveries across midnight and limits each category to two a day', () => {
    const now = Date.parse('2026-11-17T00:00:00Z');
    const first = nextEngagementBudget(undefined, now)!;
    expect(nextEngagementBudget(first, now + ENGAGEMENT_COOLDOWN_MS - 1)).toBeNull();
    const second = nextEngagementBudget(first, now + ENGAGEMENT_COOLDOWN_MS)!;
    expect(second.recentSentAtMs.length).toBe(2);
    expect(nextEngagementBudget(second, now + 2 * ENGAGEMENT_COOLDOWN_MS)).toBeNull();
    const late = nextEngagementBudget(undefined, now + 23 * 3600000)!;
    expect(nextEngagementBudget(late, now + 24 * 3600000)).toBeNull();
    expect(nextEngagementBudget(late, now + 29 * 3600000)?.recentSentAtMs.length).toBe(2);
  });
  it('uses exact 3 km distance and rejects invalid locations', () => {
    const pos = { latitude: 57.48, longitude: 12.08 };
    expect(epicWithinRange(pos, { latitude: 57.5, longitude: 12.08 })).toBe(true);
    expect(epicWithinRange(pos, { latitude: 57.51, longitude: 12.08 })).toBe(false);
    expect(epicWithinRange(pos, { latitude: NaN, longitude: 12.08 })).toBe(false);
    expect(epicWithinRange(pos, { latitude: 95, longitude: 12.08 })).toBe(false);
    expect(epicWithinRange(pos, {})).toBe(false);
  });
  it('keeps deterministic keys private and unambiguous', () => {
    expect(engagementId('ab', 'c')).not.toBe(engagementId('a', 'bc'));
    expect(engagementId('friendUid', 'sessionId')).toMatch(/^[a-f0-9]{64}$/);
  });
  for (const category of ['friend_live', 'epic_crown'] as const) {
    it(`honours ${category} opt-outs and restricted accounts`, () => {
      expect(decideInAppDelivery(category, active, {}).deliver).toBe(true);
      expect(decideInAppDelivery(category, active, { [category]: { inApp: false } }).deliver).toBe(
        false,
      );
      expect(decidePushDelivery(category, active, { [category]: { push: false } }).deliver).toBe(
        false,
      );
      expect(decideInAppDelivery(category, { ...active, suspended: true }, {}).deliver).toBe(false);
      expect(buildPushDeepLink(category, null, 'me').target).toBe('map');
    });
  }
});
