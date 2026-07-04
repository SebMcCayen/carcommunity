/**
 * Unit tests for the partner domain pure logic (partners-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildApplicationDocument,
  buildOfferDocuments,
  buildOfferUpdates,
  guardAvailabilityWindow,
  guardEditableStatus,
  guardStatusTransition,
  statusActionPastTense,
  parseCreateCompanyInput,
  parseCreateOfferInput,
  parseSubmitApplicationInput,
  parseUpdateOfferInput,
} from '../partners/partners-core';

const serverTimestamp = () => 'SERVER_TS';

const validOffer = {
  companyId: 'c1',
  title: '10% på service',
  teaserText: 'Medlemsrabatt hos verkstan.',
  offerType: 'percentage_discount',
  description: 'Full servicerabatt för medlemmar.',
  percentageDiscount: 10,
  discountCode: 'KCC10',
};

describe('partners-core inputs', () => {
  it('validates companies per the legacy limits', () => {
    const valid = { name: 'Verkstan AB', category: 'workshop' };
    expect(parseCreateCompanyInput(valid).ok).toBe(true);
    expect(parseCreateCompanyInput({ ...valid, name: 'x'.repeat(151) }).ok).toBe(false);
    expect(parseCreateCompanyInput({ ...valid, category: 'casino' }).ok).toBe(false);
    expect(parseCreateCompanyInput({ ...valid, website: 'not-a-url' }).ok).toBe(false);
    expect(
      parseCreateCompanyInput({ ...valid, logoPath: 'profileImages/u1/x.jpg' }).ok,
    ).toBe(false);
    expect(
      parseCreateCompanyInput({ ...valid, logoPath: 'companyImages/c1/logo.png' }).ok,
    ).toBe(true);
  });

  it('validates offers and applications strictly', () => {
    expect(parseCreateOfferInput(validOffer).ok).toBe(true);
    expect(parseCreateOfferInput({ ...validOffer, percentageDiscount: 101 }).ok).toBe(false);
    expect(parseCreateOfferInput({ ...validOffer, currencyCode: 'sek' }).ok).toBe(false);
    expect(parseCreateOfferInput({ ...validOffer, teaserText: 'x'.repeat(251) }).ok).toBe(false);
    expect(parseUpdateOfferInput({ offerId: 'o1', companyId: 'c2' }).ok).toBe(false); // company immutable

    const app = {
      companyName: 'Däckfirman',
      category: 'tires',
      contactName: 'Anna',
      contactEmail: 'anna@dack.se',
    };
    expect(parseSubmitApplicationInput(app).ok).toBe(true);
    expect(parseSubmitApplicationInput({ ...app, contactEmail: 'nope' }).ok).toBe(false);
    expect(parseSubmitApplicationInput({ ...app, extra: 1 }).ok).toBe(false);
  });
});

describe('partners-core lifecycle guards', () => {
  it('shares the draft → active ⇄ paused → ended lifecycle', () => {
    expect(guardStatusTransition('draft', 'activate')).toEqual({
      ok: true,
      nextStatus: 'active',
    });
    expect(guardStatusTransition('active', 'pause')).toEqual({ ok: true, nextStatus: 'paused' });
    expect(guardStatusTransition('paused', 'end')).toEqual({ ok: true, nextStatus: 'ended' });
    expect(guardStatusTransition('ended', 'activate').ok).toBe(false);
    expect(guardStatusTransition('expired', 'activate').ok).toBe(false);
    // Unknown/corrupted statuses never silently heal into live records.
    expect(guardStatusTransition('foo', 'activate').ok).toBe(false);
    expect(guardStatusTransition('', 'end').ok).toBe(false);
  });

  it('produces grammatical audit fallbacks ("end" → "ended")', () => {
    expect(statusActionPastTense('activate')).toBe('activated');
    expect(statusActionPastTense('pause')).toBe('paused');
    expect(statusActionPastTense('end')).toBe('ended');
  });

  it('allows edits only in draft or paused', () => {
    expect(guardEditableStatus('draft').ok).toBe(true);
    expect(guardEditableStatus('paused').ok).toBe(true);
    expect(guardEditableStatus('active').ok).toBe(false);
    expect(guardEditableStatus('ended').ok).toBe(false);
  });

  it('validates the availability window ordering', () => {
    expect(
      guardAvailabilityWindow('2026-07-01T00:00:00Z', '2026-08-01T00:00:00Z').ok,
    ).toBe(true);
    expect(
      guardAvailabilityWindow('2026-08-01T00:00:00Z', '2026-07-01T00:00:00Z').ok,
    ).toBe(false);
    expect(guardAvailabilityWindow(null, '2026-08-01T00:00:00Z').ok).toBe(true);
  });
});

describe('partners-core builders — the three-tier offer split', () => {
  it('keeps the code and member fields off the teaser document', () => {
    const parsed = parseCreateOfferInput(validOffer);
    if (!parsed.ok) throw new Error('expected ok');
    const { offerDoc, memberDoc, secretDoc } = buildOfferDocuments(
      parsed.input,
      'Verkstan AB',
      serverTimestamp,
    );
    expect(offerDoc.title).toBe(validOffer.title);
    expect(offerDoc.partnerCompanyName).toBe('Verkstan AB');
    expect(offerDoc.status).toBe('draft');
    expect(offerDoc).not.toHaveProperty('description');
    expect(offerDoc).not.toHaveProperty('discountCode');
    expect(offerDoc).not.toHaveProperty('percentageDiscount');

    expect(memberDoc.description).toBe(validOffer.description);
    expect(memberDoc.percentageDiscount).toBe(10);
    expect(memberDoc).not.toHaveProperty('discountCode');

    expect(secretDoc.discountCode).toBe('KCC10');
  });

  it('routes partial updates to the correct tier', () => {
    const parsed = parseUpdateOfferInput({
      offerId: 'o1',
      teaserText: 'Ny teaser',
      terms: 'Nya villkor',
      discountCode: 'NY10',
    });
    if (!parsed.ok) throw new Error('expected ok');
    const { offerDoc, memberDoc, secretDoc, changedFields } = buildOfferUpdates(
      parsed.input,
      serverTimestamp,
    );
    expect(offerDoc.teaserText).toBe('Ny teaser');
    expect(memberDoc.terms).toBe('Nya villkor');
    expect(secretDoc.discountCode).toBe('NY10');
    expect(offerDoc).not.toHaveProperty('discountCode');
    expect(changedFields.sort()).toEqual(['discountCode', 'teaserText', 'terms']);
  });

  it('normalizes application contact email to lowercase', () => {
    const parsed = parseSubmitApplicationInput({
      companyName: 'Däckfirman',
      category: 'tires',
      contactName: 'Anna',
      contactEmail: 'Anna@Dack.SE',
    });
    if (!parsed.ok) throw new Error('expected ok');
    const docData = buildApplicationDocument(parsed.input, 'u1', serverTimestamp);
    expect(docData.contactEmail).toBe('anna@dack.se');
    expect(docData.status).toBe('submitted');
    expect(docData.partnerCompanyId).toBeNull();
  });
});
