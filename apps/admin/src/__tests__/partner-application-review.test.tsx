import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const api = vi.hoisted(() => ({ reviewApplication: vi.fn(), reject: vi.fn() }));
vi.mock('@/features/partners/portal', () => ({
  reviewApplication: api.reviewApplication,
  listApplications: vi.fn(),
  getApplication: vi.fn(),
  readLogo: vi.fn(),
}));
vi.mock('@/features/partners', () => ({ adminRejectApplication: api.reject }));
vi.mock('@/components/map/MapLocationPicker', () => ({
  MapLocationPicker: ({ onChange }: { onChange: (lat: string, lng: string) => void }) => (
    <button onClick={() => onChange('57.5', '12.1')}>Välj plats</button>
  ),
}));
import { ApplicationReview } from '@/app/partners/applications/page';
import type { PortalApplication } from '@/features/partners/portal';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const application: PortalApplication = {
  applicationId: 'application',
  companyName: 'Nordisk Bilvård',
  organizationNumber: '123456-7890',
  category: 'car_care',
  contactName: 'Private contact',
  contactEmail: 'private@example.test',
  contactPhone: '070-private',
  websiteUrl: 'https://example.test',
  proposedDescription: 'Professionell bilvård.',
  proposedAddress: 'Industrivägen 14',
  message: null,
  status: 'submitted',
  revision: 4,
  submittedByUserId: 'applicant',
  reviewedByUserId: null,
  reviewedAt: null,
  reviewReason: null,
  createdAt: '2026-11-17T10:00:00Z',
  updatedAt: '2026-11-17T10:00:00Z',
  partnerCompanyId: null,
  selfServiceRequested: true,
  billboardRequested: true,
  billboardPlacementRequest: 'Vid infarten',
  logoPath: null,
  logoPreviewBase64: null,
  publicPhone: '0300-public',
};
let host: HTMLDivElement;
let root: ReturnType<typeof createRoot>;
beforeEach(() => {
  vi.clearAllMocks();
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});
const button = (text: string) => {
  const element = [...host.querySelectorAll('button')].find((item) => item.textContent === text);
  if (!element) throw new Error(text);
  return element;
};
const render = async () =>
  act(async () =>
    root.render(
      <MemoryRouter>
        <ApplicationReview
          application={application}
          onRefresh={vi.fn().mockResolvedValue(undefined)}
        />
      </MemoryRouter>,
    ),
  );
it('shows private contact data to reviewers while excluding it from the embedded public preview', async () => {
  await render();
  expect(host.textContent).toContain('private@example.test');
  const preview = host.querySelector('aside')!;
  expect(preview.textContent).not.toContain('private@example.test');
  expect(preview.textContent).not.toContain('070-private');
  expect(preview.textContent).toContain('0300-public');
  expect(preview.textContent).not.toContain('Private contact');
});
it('requires an explicit publication confirmation and prevents a double submit while publishing', async () => {
  let finish: (value: unknown) => void = () => {};
  api.reviewApplication.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  await render();
  act(() => button('Godkänn och publicera').click());
  act(() => button('Välj plats').click());
  const dialog = host.querySelector('dialog')!;
  const publish = dialog.querySelector('button[type=submit]') as HTMLButtonElement;
  expect(publish.disabled).toBe(true);
  act(() => (dialog.querySelector('input[type=checkbox]') as HTMLInputElement).click());
  expect(publish.disabled).toBe(false);
  act(() => {
    dialog
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    dialog
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
  expect(api.reviewApplication).toHaveBeenCalledTimes(1);
  expect(api.reviewApplication).toHaveBeenCalledWith('application', 'publish', {
    expectedRevision: 4,
    grantSelfService: true,
    latitude: 57.5,
    longitude: 12.1,
  });
  await act(async () => finish({ companyId: 'company', status: 'approved' }));
  expect(host.querySelector('dialog')).toBeNull();
  expect(host.textContent).toContain('Partnern har godkänts och publicerats.');
});
it('retains a rejected publication for correction and does not show success', async () => {
  api.reviewApplication.mockRejectedValue(new Error('Company representative must be verified.'));
  await render();
  act(() => button('Godkänn och publicera').click());
  act(() => button('Välj plats').click());
  act(() => (host.querySelector('dialog input[type=checkbox]') as HTMLInputElement).click());
  await act(async () =>
    host
      .querySelector('dialog form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })),
  );
  expect(host.querySelector('[role=alert]')?.textContent).toContain(
    'Company representative must be verified.',
  );
  expect(host.textContent).not.toContain('Partnern har godkänts och publicerats.');
  expect(host.querySelector('dialog')).not.toBeNull();
});
it('sends a concrete request for changes, instead of implicitly approving the company', async () => {
  api.reviewApplication.mockResolvedValue({ status: 'changes_requested' });
  await render();
  act(() => button('Begär komplettering').click());
  const field = host.querySelector('dialog textarea') as HTMLTextAreaElement;
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(
      field,
      'Ange korrekt besöksadress.',
    );
    field.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await act(async () =>
    host
      .querySelector('dialog form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })),
  );
  expect(api.reviewApplication).toHaveBeenCalledWith('application', 'request_changes', {
    reason: 'Ange korrekt besöksadress.',
  });
});
