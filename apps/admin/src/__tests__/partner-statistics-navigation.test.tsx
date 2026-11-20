import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({ statistics: vi.fn() }));
vi.mock('@/features/partners/portal', () => ({
  getStatistics: api.statistics,
  statisticsPeriods: () => ['all', '2026-10'],
  readLogo: vi.fn(),
}));
import PartnerStatisticsPage from '@/app/partners/statistics/page';
import { translate } from '@/i18n';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const p = (key: string) => translate('sv', `partnerPortal.${key}`);
const companies = [
  {
    companyId: 'company-a',
    companyName: 'Workshop A',
    totalRedeemed: 10,
    offers: [{ offerId: 'offer-a', title: 'Wash A', totalRedeemed: 10 }],
  },
  {
    companyId: 'company-b',
    companyName: 'Workshop B',
    totalRedeemed: 20,
    offers: [{ offerId: 'offer-b', title: 'Wash B', totalRedeemed: 20 }],
  },
];
let host: HTMLDivElement;
let root: ReturnType<typeof createRoot>;
let router: ReturnType<typeof createMemoryRouter>;
beforeEach(() => {
  vi.clearAllMocks();
  api.statistics.mockImplementation(async (companyId: string | undefined, period: string) => {
    const partners = companies.filter((company) => !companyId || company.companyId === companyId);
    return {
      updatedAt: '2026-11-20T03:00:00Z',
      period,
      totalRedeemed: partners.reduce((total, company) => total + company.totalRedeemed, 0),
      partners,
    };
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  router = createMemoryRouter(
    [{ path: '/partners/statistics', element: <PartnerStatisticsPage /> }],
    { initialEntries: ['/partners/statistics?partnerId=company-a'] },
  );
});
afterEach(() => {
  act(() => root.unmount());
  router.dispose();
  host.remove();
});
const input = () => host.querySelector('input')!;
const select = (label: string) => {
  const result = [...host.querySelectorAll('label')]
    .find((element) => element.textContent?.startsWith(label))
    ?.querySelector('select');
  if (!result) throw Error(label);
  return result;
};
function editInput(value: string) {
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input(), value);
    input().dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function choose(label: string, value: string) {
  await act(async () => {
    const element = select(label);
    element.value = value;
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
const render = () => act(async () => root.render(<RouterProvider router={router} />));

it('resets company and offer filters on All partners and browser back/forward navigation', async () => {
  await render();
  expect(input().value).toBe('company-a');
  await choose(p('filterOffer'), 'offer-a');
  expect(host.querySelector('strong')?.textContent).toBe('10');

  await act(async () =>
    (host.querySelector('a[href="/partners/statistics"]') as HTMLAnchorElement).click(),
  );
  expect(api.statistics).toHaveBeenLastCalledWith(undefined, 'all');
  expect(input().value).toBe('');
  expect(select(p('filterOffer')).value).toBe('');
  expect(host.querySelector('strong')?.textContent).toBe('30');
  expect(host.querySelector('tbody')?.textContent).toContain('Workshop A');
  expect(host.querySelector('tbody')?.textContent).toContain('Workshop B');

  await act(async () => router.navigate(-1));
  expect(input().value).toBe('company-a');
  expect(api.statistics).toHaveBeenLastCalledWith('company-a', 'all');
  await choose(p('filterOffer'), 'offer-a');
  await act(async () => router.navigate(1));
  expect(input().value).toBe('');
  expect(select(p('filterOffer')).value).toBe('');
  expect(host.querySelector('strong')?.textContent).toBe('30');

  await choose(p('filterOffer'), 'offer-a');
  await act(async () => router.navigate('/partners/statistics?partnerId=company-b'));
  expect(input().value).toBe('company-b');
  expect(select(p('filterOffer')).value).toBe('');
  expect(host.querySelector('tbody')?.textContent).toContain('Workshop B');
  expect(host.querySelector('strong')?.textContent).toBe('20');
});

it('keeps unsaved company text through period/report updates and retains the period across scopes', async () => {
  await render();
  editInput('company-b');
  await choose(p('period'), '2026-10');
  expect(input().value).toBe('company-b');
  expect(api.statistics).toHaveBeenLastCalledWith('company-a', '2026-10');

  await act(async () =>
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })),
  );
  expect(router.state.location.search).toBe('?partnerId=company-b');
  expect(api.statistics).toHaveBeenLastCalledWith('company-b', '2026-10');
  expect(input().value).toBe('company-b');
  await choose(p('filterOffer'), 'offer-b');
  await act(async () => router.navigate('/partners/statistics'));
  expect(input().value).toBe('');
  expect(select(p('period')).value).toBe('2026-10');
  expect(select(p('filterOffer')).value).toBe('');
  expect(host.querySelector('strong')?.textContent).toBe('30');
});
