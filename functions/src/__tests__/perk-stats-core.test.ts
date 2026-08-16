/**
 * Kronjakt PERK-USAGE STATISTICS — pure logic (admin-stats PR-A) unit tests.
 *
 * Everything the perk-stats layer decides without Firestore: the fixed-key count
 * shape, the empty aggregate document, the two scopes an event folds into
 * (all-time + its Europe/Stockholm season), and the fold-marker id. The emulator
 * integration (crownhunt-perk-stats.emulator.test.ts) proves the trigger wiring;
 * this proves the maths.
 */

import { describe, expect, it } from 'vitest';
import { ALL_TIME_SCOPE, seasonIdForInstant } from '../crownHunt/crown-hunt-stats-core';
import { PERK_IDS } from '../crownHunt/perks-core';
import {
  CROWN_PERK_STATS_COLLECTION,
  CROWN_PERK_STAT_FOLDS_COLLECTION,
  PERK_STAT_KEYS,
  buildEmptyPerkStats,
  isPerkId,
  perkStatFoldId,
  perkStatScopesFor,
  zeroPerkCounts,
} from '../crownHunt/perk-stats-core';

describe('collection names', () => {
  it('are the documented, distinct collections', () => {
    expect(CROWN_PERK_STATS_COLLECTION).toBe('crownHuntPerkStats');
    expect(CROWN_PERK_STAT_FOLDS_COLLECTION).toBe('crownHuntPerkStatFolds');
  });
});

describe('perk-keyed counts', () => {
  it('keys by the full perk catalog', () => {
    expect([...PERK_STAT_KEYS]).toEqual([...PERK_IDS]);
    expect([...PERK_STAT_KEYS]).toEqual(['spike_strip', 'shield', 'boost']);
  });

  it('zeroPerkCounts is a full fixed-key map of zeros', () => {
    const counts = zeroPerkCounts();
    expect(counts).toEqual({ spike_strip: 0, shield: 0, boost: 0 });
    // Present for every catalog perk, never more.
    expect(Object.keys(counts).sort()).toEqual([...PERK_IDS].sort());
  });

  it('returns a fresh object each call (no shared mutable state)', () => {
    const a = zeroPerkCounts();
    a.boost = 5;
    expect(zeroPerkCounts().boost).toBe(0);
  });
});

describe('empty aggregate document', () => {
  it('has the full documented shape for a scope', () => {
    expect(buildEmptyPerkStats('alltime')).toEqual({
      scope: 'alltime',
      usedByPerk: { spike_strip: 0, shield: 0, boost: 0 },
      purchasedByPerk: { spike_strip: 0, shield: 0, boost: 0 },
      trapTriggers: 0,
    });
  });

  it('carries whatever scope id it is built for', () => {
    expect(buildEmptyPerkStats('2026-08').scope).toBe('2026-08');
  });
});

describe('scope derivation', () => {
  it('folds an event into all-time plus its Stockholm season', () => {
    const instant = new Date('2026-08-16T10:00:00Z');
    expect(perkStatScopesFor(instant)).toEqual([ALL_TIME_SCOPE, '2026-08']);
  });

  it('derives the season from the event instant (agreeing with seasonIdForInstant)', () => {
    // 22:30 UTC on Dec 31 is 23:30 local (Stockholm winter is UTC+1), so it is
    // still December — the season is bucketed in Stockholm civil time.
    const instant = new Date('2025-12-31T22:30:00Z');
    const [, season] = perkStatScopesFor(instant);
    expect(season).toBe(seasonIdForInstant(instant));
    expect(season).toBe('2025-12');
  });

  it('always puts all-time first', () => {
    expect(perkStatScopesFor(new Date())[0]).toBe(ALL_TIME_SCOPE);
  });
});

describe('fold ids', () => {
  it('namespace by source so the id spaces cannot collide', () => {
    expect(perkStatFoldId('deploy', 'deploy_abc')).toBe('deploy__deploy_abc');
    expect(perkStatFoldId('drain', 'xyz')).toBe('drain__xyz');
    expect(perkStatFoldId('purchase', 'uid1__entry1')).toBe('purchase__uid1__entry1');
  });

  it('a given (source, docId) is stable', () => {
    expect(perkStatFoldId('deploy', 'd1')).toBe(perkStatFoldId('deploy', 'd1'));
  });

  it('the same doc id under two sources yields two distinct markers', () => {
    expect(perkStatFoldId('deploy', 'same')).not.toBe(perkStatFoldId('drain', 'same'));
  });
});

describe('isPerkId (re-exported guard)', () => {
  it('accepts the catalog perks and rejects everything else', () => {
    for (const id of PERK_IDS) {
      expect(isPerkId(id)).toBe(true);
    }
    expect(isPerkId('perk_trap')).toBe(false);
    expect(isPerkId('unknown')).toBe(false);
    expect(isPerkId(null)).toBe(false);
    expect(isPerkId(42)).toBe(false);
  });
});
