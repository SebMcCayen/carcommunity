import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
const api = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  submit: vi.fn(),
  activate: vi.fn(),
  createLegacy: vi.fn(),
}));
vi.mock('@/features/partners/portal', () => ({
  listOffers: api.list,
  getOffer: api.get,
  submitManagedOffer: api.submit,
}));
vi.mock('@/features/partners', () => ({
  adminCreatePartnerOffer: api.createLegacy,
  adminUpdatePartnerOffer: vi.fn(),
  adminActivatePartnerOffer: api.activate,
  adminPausePartnerOffer: vi.fn(),
  adminEndPartnerOffer: vi.fn(),
  PARTNER_OFFER_STATUSES: ['draft', 'active', 'paused', 'ended', 'expired'],
  PARTNER_OFFER_TYPES: ['member_benefit', 'percentage_discount', 'discount_code'],
  ApiError: Error,
}));
import PartnerOffersPage from '@/app/partners/offers/page';
import { translate } from '@/i18n';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const offer = {
  offerId: 'offer',
  partnerId: 'company',
  partnerCompanyName: 'Business',
  title: 'Rekond',
  teaserText: 'Tre platser',
  description: 'Professionell rekond',
  terms: 'En gång per konto.',
  offerType: 'percentage_discount',
  status: 'draft',
  redemptionInstructions: null,
  percentageDiscount: 20,
  fixedDiscountMinorUnits: null,
  currencyCode: null,
  availableFrom: null,
  availableUntil: null,
  activatedAt: null,
  pausedAt: null,
  endedAt: null,
  createdByUserId: 'staff',
  updatedByUserId: null,
  createdAt: '2026-11-17T10:00:00Z',
  updatedAt: '2026-11-17T10:00:00Z',
  redemptionMode: 'reservation',
  capacity: 3,
  reservationDays: 7,
  reservedCount: 0,
  redeemedCount: 0,
  issuedCount: 0,
  version: 7,
};
let host: HTMLDivElement;
let root: ReturnType<typeof createRoot>;
beforeEach(() => {
  vi.clearAllMocks();
  api.list.mockResolvedValue({ items: [offer], nextCursor: null });
  api.get.mockResolvedValue(offer);
  api.submit.mockResolvedValue({ offerId: 'created', status: 'draft' });
  api.activate.mockResolvedValue(offer);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});
const button = (text: string) => {
  const result = [...(host.querySelector('dialog') ?? host).querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  );
  if (!result) throw Error(text);
  return result;
};
function field(label: string, value: string) {
  const target = [...host.querySelectorAll('label')]
    .find((item) => item.textContent?.trim() === label)
    ?.querySelector('input,textarea') as HTMLInputElement | HTMLTextAreaElement;
  if (!target) throw Error(label);
  const prototype =
    target.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  act(() => {
    Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(target, value);
    target.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
it('reviews the full terms before activation and submits the reviewed campaign version', async () => {
  await act(async () =>
    root.render(
      <MemoryRouter initialEntries={['/partners/offers?offerId=offer']}>
        <PartnerOffersPage />
      </MemoryRouter>,
    ),
  );
  expect(host.querySelector('dialog')?.textContent).toContain('En gång per konto.');
  expect(api.activate).not.toHaveBeenCalled();
  const activateLabel = translate('sv', 'partnerOffers.activateOffer');
  act(() => button(activateLabel).click());
  const dialog = host.querySelector('dialog')!;
  const confirmButton = [...dialog.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === activateLabel,
  ) as HTMLButtonElement;
  expect(confirmButton).toBeDefined();
  expect(confirmButton.disabled).toBe(true);
  act(() => (dialog.querySelector('input[type=checkbox]') as HTMLInputElement).click());
  await act(async () => button(activateLabel).click());
  expect(api.activate).toHaveBeenCalledWith('offer', undefined, 7);
});
it('creates a three-place personal QR campaign without invoking the legacy shared-code endpoint', async () => {
  await act(async () =>
    root.render(
      <MemoryRouter initialEntries={['/partners/offers?partnerId=company']}>
        <PartnerOffersPage />
      </MemoryRouter>,
    ),
  );
  act(() => button(translate('sv', 'partnerOffers.createOffer')).click());
  field(translate('sv', 'partnerOffers.formTitleLabel'), 'Bilvård');
  field(translate('sv', 'partnerOffers.formTeaserLabel'), 'Tre kunder får prova');
  field(translate('sv', 'partnerOffers.formDescriptionLabel'), 'Utförlig beskrivning');
  field(translate('sv', 'partnerOffers.formTermsLabel'), 'Gäller en gång per konto.');
  field(translate('sv', 'partnerPortal.offerCapacity'), '3');
  await act(async () =>
    host
      .querySelector('dialog form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })),
  );
  expect(api.submit).toHaveBeenCalledWith(
    expect.objectContaining({
      companyId: 'company',
      capacity: 3,
      reservationDays: 7,
      terms: 'Gäller en gång per konto.',
    }),
  );
  expect(api.createLegacy).not.toHaveBeenCalled();
});
