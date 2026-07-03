/**
 * Unit tests for the badges pure logic (badge-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  BADGE_CATALOG,
  BADGE_CATALOG_ORDER,
  BADGE_KEYS,
  buildBadgeDocument,
  parseAwardHelpfulMemberInput,
  qualifiedEventBadges,
} from '../badges/badge-core';

describe('badge catalog (ported from legacy badge-catalog.ts)', () => {
  it('covers every key exactly once and keeps the display order complete', () => {
    expect(Object.keys(BADGE_CATALOG).sort()).toEqual([...BADGE_KEYS].sort());
    expect([...BADGE_CATALOG_ORDER].sort()).toEqual([...BADGE_KEYS].sort());
    for (const key of BADGE_KEYS) {
      expect(BADGE_CATALOG[key].key).toBe(key);
      expect(BADGE_CATALOG[key].name.length).toBeGreaterThan(0);
      expect(BADGE_CATALOG[key].iconIdentifier.startsWith('badge_')).toBe(true);
    }
  });

  it('keeps helpful_member as the only manual badge', () => {
    const manual = BADGE_KEYS.filter((key) => !BADGE_CATALOG[key].isAutomatic);
    expect(manual).toEqual(['helpful_member']);
  });
});

describe('badge-core inputs and builders', () => {
  it('requires targetUid and a non-empty reason for manual awards', () => {
    expect(parseAwardHelpfulMemberInput({ targetUid: 'u1', reason: 'Great help' }).ok).toBe(true);
    expect(parseAwardHelpfulMemberInput({ targetUid: 'u1', reason: '  ' }).ok).toBe(false);
    expect(parseAwardHelpfulMemberInput({ targetUid: 'u1' }).ok).toBe(false);
    expect(
      parseAwardHelpfulMemberInput({ targetUid: 'u1', reason: 'ok', extra: 1 }).ok,
    ).toBe(false);
  });

  it('denormalizes the catalog definition onto the award document', () => {
    const docData = buildBadgeDocument(
      'garage_created',
      { source: 'automatic', awardedByUserId: null },
      () => 'SERVER_TS',
    );
    expect(docData.badgeKey).toBe('garage_created');
    expect(docData.name).toBe('Garageprofil skapad');
    expect(docData.iconIdentifier).toBe('badge_garage_created');
    expect(docData.source).toBe('automatic');
    expect(docData.awardedByUserId).toBeNull();
    expect(docData.awardedAt).toBe('SERVER_TS');
  });

  it('qualifies event badges at the legacy thresholds', () => {
    expect(qualifiedEventBadges(0)).toEqual([]);
    expect(qualifiedEventBadges(1)).toEqual(['first_event']);
    expect(qualifiedEventBadges(4)).toEqual(['first_event']);
    expect(qualifiedEventBadges(5)).toEqual(['first_event', 'five_events']);
    expect(qualifiedEventBadges(50)).toEqual(['first_event', 'five_events']);
  });
});
