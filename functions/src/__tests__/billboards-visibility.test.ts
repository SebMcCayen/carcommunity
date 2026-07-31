/**
 * Unit tests for the billboard MAP VISIBILITY rule — the pure half of "an
 * inactive, expired or unscheduled billboard must not render".
 *
 * The enforcement itself is not here and cannot be: it is the read rule on
 * `billboards` requiring `status == 'active' && mapVisible == true`, exercised
 * by the emulator suite. What these tests pin is the DECISION that rule depends
 * on — the one the lifecycle callables and the scheduled sweep both write from,
 * where a boundary off by one comparison is the difference between a sponsor's
 * placement expiring on time and running for free.
 *
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildBillboardDocument,
  decideVisibility,
  isBillboardMapVisible,
} from '../billboards/billboards-core';

const NOW = new Date('2026-07-31T12:00:00.000Z');
const at = (iso: string) => new Date(iso);

describe('isBillboardMapVisible', () => {
  it('only an ACTIVE billboard can be drawn', () => {
    for (const status of ['draft', 'paused', 'ended', '', 'ACTIVE', 'unknown']) {
      expect(isBillboardMapVisible(status, null, null, NOW)).toBe(false);
    }
    expect(isBillboardMapVisible('active', null, null, NOW)).toBe(true);
  });

  it('an unbounded window means "from activation until I stop it"', () => {
    expect(isBillboardMapVisible('active', null, null, NOW)).toBe(true);
    expect(isBillboardMapVisible('active', at('2020-01-01T00:00:00Z'), null, NOW)).toBe(true);
    expect(isBillboardMapVisible('active', null, at('2030-01-01T00:00:00Z'), NOW)).toBe(true);
  });

  it('a null bound is not a licence to ignore the other bound', () => {
    expect(isBillboardMapVisible('active', at('2030-01-01T00:00:00Z'), null, NOW)).toBe(false);
    expect(isBillboardMapVisible('active', null, at('2020-01-01T00:00:00Z'), NOW)).toBe(false);
  });

  it('the window is half-open: start inclusive, end exclusive', () => {
    // Exactly at the start it IS visible — an admin scheduling a placement for
    // 09:00 means it runs at 09:00.
    expect(isBillboardMapVisible('active', NOW, null, NOW)).toBe(true);
    // Exactly at the end it is NOT — by the admin's own definition it has
    // finished. Getting this backwards runs every placement one tick long.
    expect(isBillboardMapVisible('active', null, NOW, NOW)).toBe(false);
    expect(isBillboardMapVisible('active', null, at('2026-07-31T12:00:00.001Z'), NOW)).toBe(true);
  });

  it('a scheduled window must be entered AND not yet left', () => {
    const from = at('2026-07-31T10:00:00Z');
    const until = at('2026-07-31T14:00:00Z');
    expect(isBillboardMapVisible('active', from, until, at('2026-07-31T09:59:59Z'))).toBe(false);
    expect(isBillboardMapVisible('active', from, until, at('2026-07-31T12:00:00Z'))).toBe(true);
    expect(isBillboardMapVisible('active', from, until, at('2026-07-31T14:00:01Z'))).toBe(false);
  });

  it('an unparseable stored bound fails CLOSED rather than reading as unbounded', () => {
    // Every comparison against NaN is false, so a naive implementation would
    // treat a corrupt date as "no limit" and run the placement forever.
    expect(isBillboardMapVisible('active', new Date('nonsense'), null, NOW)).toBe(false);
    expect(isBillboardMapVisible('active', null, new Date('nonsense'), NOW)).toBe(false);
  });
});

describe('buildBillboardDocument', () => {
  it('creates every billboard invisible — a draft has not passed the safety gate', () => {
    const doc = buildBillboardDocument(
      {
        partnerCompanyId: 'company-1',
        headline: 'Headline',
        message: 'Message',
        placementType: 'map_billboard',
        latitude: 57.49,
        longitude: 12.07,
      },
      'admin-1',
      () => 'SERVER_TIMESTAMP',
    );
    expect(doc.status).toBe('draft');
    // Written EXPLICITLY, not merely absent: the member query filters on
    // `mapVisible == true` and the read rule evaluates it, so both need a value
    // from the very first write.
    expect(doc.mapVisible).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(doc, 'mapVisible')).toBe(true);
  });
});

describe('decideVisibility (the sweep decision)', () => {
  it('returns null when the stored flag is already correct — the common case costs no write', () => {
    expect(decideVisibility('b1', 'active', null, null, true, NOW)).toBeNull();
    expect(decideVisibility('b1', 'paused', null, null, false, NOW)).toBeNull();
  });

  it('turns a scheduled billboard ON once its window opens', () => {
    expect(
      decideVisibility('b1', 'active', at('2026-07-31T11:00:00Z'), null, false, NOW),
    ).toEqual({ id: 'b1', mapVisible: true });
  });

  it('turns an expired billboard OFF', () => {
    expect(
      decideVisibility('b1', 'active', null, at('2026-07-31T11:00:00Z'), true, NOW),
    ).toEqual({ id: 'b1', mapVisible: false });
  });

  it('repairs a document written before the field existed', () => {
    // `undefined` is not `false` — an active, in-window legacy billboard must be
    // switched on rather than left permanently invisible, which is what makes
    // the sweep a backfill and removes the need for a manual migration.
    expect(decideVisibility('b1', 'active', null, null, undefined, NOW)).toEqual({
      id: 'b1',
      mapVisible: true,
    });
    // ...and a legacy non-active one stays off without a write.
    expect(decideVisibility('b1', 'ended', null, null, undefined, NOW)).toBeNull();
  });

  it('never leaves a non-active billboard flagged visible', () => {
    for (const status of ['draft', 'paused', 'ended']) {
      expect(decideVisibility('b1', status, null, null, true, NOW)).toEqual({
        id: 'b1',
        mapVisible: false,
      });
    }
  });
});
