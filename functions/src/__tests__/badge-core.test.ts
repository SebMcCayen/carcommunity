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
  parseGrantEarlyTesterInput,
  parseEarlyMemberCutoff,
  qualifiedEventBadges,
  qualifiesAsEarlyMember,
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

  it('keeps helpful_member and early_tester as the only manual badges', () => {
    const manual = BADGE_KEYS.filter((key) => !BADGE_CATALOG[key].isAutomatic);
    expect(manual).toEqual(['helpful_member', 'early_tester']);
  });

  it('defines early_tester as a criteria-free, non-ladder, admin-granted badge', () => {
    const founder = BADGE_CATALOG.early_tester;
    expect(founder.isAutomatic).toBe(false);
    expect(founder.ladder).toBeNull();
    expect(founder.tier).toBeNull();
    expect(founder.metric).toBeNull();
    expect(founder.threshold).toBeNull();
    expect(founder.pointsReward).toBe(0);
    expect(founder.name).toBe('Grundare');
    expect(founder.iconIdentifier).toBe('badge_early_tester');
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

  it('parses, de-duplicates and defaults the early-tester grant input', () => {
    const ok = parseGrantEarlyTesterInput({ uids: ['a', 'b', 'a'] });
    expect(ok.ok).toBe(true);
    if (ok.ok) {
      // Duplicates collapsed to first-seen order, default reason applied.
      expect(ok.input.uids).toEqual(['a', 'b']);
      expect(ok.input.reason.length).toBeGreaterThan(0);
    }
    const withReason = parseGrantEarlyTesterInput({ uids: ['a'], reason: 'Beta cohort' });
    expect(withReason.ok && withReason.input.reason).toBe('Beta cohort');
    // Rejections: empty list, non-array, unknown field, blank UID.
    expect(parseGrantEarlyTesterInput({ uids: [] }).ok).toBe(false);
    expect(parseGrantEarlyTesterInput({ uids: 'a' }).ok).toBe(false);
    expect(parseGrantEarlyTesterInput({ uids: ['a'], extra: 1 }).ok).toBe(false);
    expect(parseGrantEarlyTesterInput({ uids: ['  '] }).ok).toBe(false);
    expect(parseGrantEarlyTesterInput({}).ok).toBe(false);
  });

  it('denormalizes the catalog definition onto the award document', () => {
    const docData = buildBadgeDocument(
      'garage_created',
      { source: 'automatic' },
      () => 'SERVER_TS',
    );
    expect(docData.badgeKey).toBe('garage_created');
    expect(docData.name).toBe('Garageprofil skapad');
    expect(docData.iconIdentifier).toBe('badge_garage_created');
    expect(docData.source).toBe('automatic');
    // Never present — the badge document is publicly readable.
    expect(docData).not.toHaveProperty('awardedByUserId');
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

describe('early_member cutoff (legacy evaluateEarlyMember)', () => {
  it('treats an unset or invalid cutoff as never-award (safe default)', () => {
    expect(parseEarlyMemberCutoff(undefined)).toBeNull();
    expect(parseEarlyMemberCutoff('')).toBeNull();
    expect(parseEarlyMemberCutoff('   ')).toBeNull();
    expect(parseEarlyMemberCutoff('not-a-date')).toBeNull();
    expect(parseEarlyMemberCutoff('2026-01-01T00:00:00Z')).toEqual(
      new Date('2026-01-01T00:00:00Z'),
    );
  });

  it('qualifies accounts created strictly before the cutoff', () => {
    const cutoff = new Date('2026-01-01T00:00:00Z');
    expect(qualifiesAsEarlyMember(new Date('2025-12-31T23:59:59Z'), cutoff)).toBe(true);
    expect(qualifiesAsEarlyMember(cutoff, cutoff)).toBe(false);
    expect(qualifiesAsEarlyMember(new Date('2026-06-01T00:00:00Z'), cutoff)).toBe(false);
  });
});
