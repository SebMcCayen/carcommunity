import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
const api = vi.hoisted(() => ({ balance: vi.fn(), ledger: vi.fn(), adjust: vi.fn() }));
vi.mock('@/features/points', () => ({
  getAdminUserPointsBalance: api.balance,
  getAdminUserPointsLedger: api.ledger,
  applyAdminPointsAdjustment: api.adjust,
}));
import { UserPointsSection } from '@/app/users/[id]/PointsSection';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
it('keeps the server-confirmed adjustment balance and offers a single cancel control', async () => {
  sessionStorage.clear();
  api.balance.mockResolvedValue({ ok: true, data: { balance: 100 } });
  api.ledger.mockResolvedValueOnce({
    ok: true,
    data: { balance: 100, transactions: [] },
    meta: { total: 0 },
  });
  api.ledger.mockResolvedValue({
    ok: true,
    data: { balance: 117, transactions: [] },
    meta: { total: 0 },
  });
  api.adjust.mockResolvedValue({ ok: true, data: { balanceAfter: 117 } });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const button = (text: string) => {
    const element = [...host.querySelectorAll('button')].find((el) => el.textContent === text);
    if (!element) throw Error(text);
    return element;
  };
  const input = (id: string, value: string) => {
    const el = host.querySelector('#' + id) as HTMLInputElement | HTMLTextAreaElement;
    const proto =
      el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    act(() => {
      Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
  };
  try {
    await act(async () => root.render(<UserPointsSection userId="member" actorUid="admin" />));
    input('adjust-amount', '17');
    input('adjust-reason', 'Reviewed local adjustment');
    act(() => button('Justera').click());
    expect(
      [...host.querySelectorAll('dialog button')].filter((el) => el.textContent === 'Avbryt'),
    ).toHaveLength(1);
    await act(async () => button('Bekräfta justering').click());
    expect(api.adjust).toHaveBeenCalledWith(
      'member',
      expect.objectContaining({ amount: 17, expectedActorUid: 'admin' }),
    );
    expect(host.textContent).toContain('117 KP');
    expect(api.balance).not.toHaveBeenCalled();
  } finally {
    act(() => root.unmount());
    host.remove();
  }
});
