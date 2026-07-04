/**
 * Unit tests for the digital billboards pure logic (billboards-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  BILLBOARD_INTERACTION_TYPES,
  BILLBOARD_TO_INSIGHTS_TYPE,
  buildBillboardDocument,
  guardAvailabilityWindow,
  guardCallToActionPair,
  guardEditableBillboard,
  parseActivateBillboardInput,
  parseCreateBillboardInput,
  parseUpdateBillboardInput,
} from '../billboards/billboards-core';
import { PARTNER_INTERACTION_TYPES } from '../partnerInsights/insights-core';

const validCreate = {
  partnerCompanyId: 'co-1',
  headline: 'Fika hos Verkstan',
  message: 'Stanna till och säg hej — kaffe till alla medlemmar.',
  placementType: 'map_billboard',
  latitude: 59.33,
  longitude: 18.07,
};

const allConfirmations = {
  billboardId: 'b1',
  notBusinessLocationConfirmed: true,
  notRoadLaneConfirmed: true,
  notRoadSignConfirmed: true,
  notObstructingMapConfirmed: true,
  markedAsAdvertisingConfirmed: true,
  suitableForMapConfirmed: true,
  approvalReason: 'Placering granskad på karta och foto.',
};

describe('billboards-core inputs', () => {
  it('validates fields per the legacy limits', () => {
    expect(parseCreateBillboardInput(validCreate).ok).toBe(true);
    expect(parseCreateBillboardInput({ ...validCreate, headline: 'x'.repeat(101) }).ok).toBe(
      false,
    );
    expect(parseCreateBillboardInput({ ...validCreate, message: 'x'.repeat(301) }).ok).toBe(false);
    expect(parseCreateBillboardInput({ ...validCreate, placementType: 'highway' }).ok).toBe(false);
    expect(parseCreateBillboardInput({ ...validCreate, latitude: 91 }).ok).toBe(false);
    expect(
      parseCreateBillboardInput({ ...validCreate, imagePath: 'profileImages/u/x.png' }).ok,
    ).toBe(false);
    expect(
      parseCreateBillboardInput({ ...validCreate, imagePath: 'billboardImages/b1/hero.png' }).ok,
    ).toBe(true);
    // partnerCompanyId is immutable on update.
    expect(parseUpdateBillboardInput({ billboardId: 'b1', partnerCompanyId: 'co-2' }).ok).toBe(
      false,
    );
  });

  it('requires ALL six safety confirmations to activate', () => {
    expect(parseActivateBillboardInput(allConfirmations).ok).toBe(true);
    for (const key of [
      'notBusinessLocationConfirmed',
      'notRoadLaneConfirmed',
      'notRoadSignConfirmed',
      'notObstructingMapConfirmed',
      'markedAsAdvertisingConfirmed',
      'suitableForMapConfirmed',
    ]) {
      expect(parseActivateBillboardInput({ ...allConfirmations, [key]: false }).ok).toBe(false);
    }
    expect(parseActivateBillboardInput({ ...allConfirmations, approvalReason: ' ' }).ok).toBe(
      false,
    );
  });
});

describe('billboards-core guards and mapping', () => {
  it('allows edits only in draft or paused', () => {
    expect(guardEditableBillboard('draft').ok).toBe(true);
    expect(guardEditableBillboard('paused').ok).toBe(true);
    expect(guardEditableBillboard('active').ok).toBe(false);
    expect(guardEditableBillboard('ended').ok).toBe(false);
    expect(guardEditableBillboard('bogus').ok).toBe(false);
  });

  it('requires the CTA type and value as a pair, and window ordering', () => {
    expect(guardCallToActionPair('website', 'https://a.se').ok).toBe(true);
    expect(guardCallToActionPair(null, null).ok).toBe(true);
    expect(guardCallToActionPair('website', null).ok).toBe(false);
    expect(guardCallToActionPair(null, 'https://a.se').ok).toBe(false);
    expect(
      guardAvailabilityWindow('2026-08-01T00:00:00Z', '2026-07-01T00:00:00Z').ok,
    ).toBe(false);
  });

  it('maps every billboard interaction onto a valid insights type', () => {
    for (const type of BILLBOARD_INTERACTION_TYPES) {
      expect(PARTNER_INTERACTION_TYPES).toContain(BILLBOARD_TO_INSIGHTS_TYPE[type]);
    }
    expect(BILLBOARD_TO_INSIGHTS_TYPE.impression).toBe('map_view');
    expect(BILLBOARD_TO_INSIGHTS_TYPE.open).toBe('profile_view');
    // Never maps to the opt-in-gated pass-by type.
    expect(Object.values(BILLBOARD_TO_INSIGHTS_TYPE)).not.toContain('anonymous_pass_by');
  });

  it('builds draft documents with stamped nulls', () => {
    const parsed = parseCreateBillboardInput(validCreate);
    if (!parsed.ok) throw new Error('expected ok');
    const docData = buildBillboardDocument(parsed.input, 'admin-1', () => 'SERVER_TS');
    expect(docData.status).toBe('draft');
    expect(docData.approvedAt).toBeNull();
    expect(docData.callToActionType).toBeNull();
    expect(docData.createdByUserId).toBe('admin-1');
  });
});
