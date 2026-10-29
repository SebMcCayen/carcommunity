import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
const api = vi.hoisted(() => vi.fn());
vi.mock('@/lib/callables', () => ({ callAdmin: api }));
import { MemberTools } from '@/app/users/[id]/MemberTools';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let root: Root, host: HTMLDivElement;
const member = {
  uid: 'member',
  actorUid: 'actor',
  displayName: 'AlexNord',
  role: 'user' as const,
  activeMember: false,
  suspended: false,
  deleted: false,
  bio: null,
  createdAt: null,
  updatedAt: null,
  permissions: {
    canWarn: true,
    canRename: true,
    canAdjustPoints: true,
    canReversePoints: true,
    canSuspend: true,
    canRestore: false,
  },
};
beforeEach(() => {
  sessionStorage.clear();
  api.mockReset();
  api.mockImplementation(async (name: string) =>
    name === 'admin-listWarnings'
      ? {
          warnings: [
            {
              id: 'warning-one',
              reason: 'Visad varning',
              internalNote: 'Intern anteckning',
              status: 'active',
              version: 3,
              createdAt: '2026-10-27T12:00:00Z',
              expiresAt: '2026-10-28T12:00:00Z',
              remainingMillis: 1000,
            },
          ],
          meta: { total: 1, nextCursor: null },
        }
      : {},
  );
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});
async function render(section: 'profile' | 'warnings') {
  await act(async () =>
    root.render(<MemberTools member={member} section={section} onChanged={() => undefined} />),
  );
}
function button(text: string) {
  const match = [...host.querySelectorAll('button')].find((el) => el.textContent === text);
  if (!match) throw Error(text);
  return match;
}
function input(id: string, value: string) {
  const el = host.querySelector('#' + id) as HTMLInputElement | HTMLTextAreaElement;
  const prototype =
    el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  act(() => {
    Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
it('profile editing excludes warning creation and binds the reviewed name to actor and target', async () => {
  await render('profile');
  expect(api).not.toHaveBeenCalled();
  expect(host.querySelector('#warning-note')).toBeNull();
  input('member-name', 'Alex Nytt');
  input('member-decision-reason', 'Rättat visningsnamn');
  act(() => button('Granska namnbyte').click());
  expect(host.querySelector('dialog')?.textContent).toContain('AlexNord → Alex Nytt');
  await act(async () => button('Bekräfta beslut').click());
  expect(api).toHaveBeenCalledWith(
    'admin-updateDisplayName',
    expect.objectContaining({
      targetUid: 'member',
      expectedActorUid: 'actor',
      displayName: 'Alex Nytt',
      previousDisplayName: 'AlexNord',
      reason: 'Rättat visningsnamn',
      idempotencyKey: expect.any(String),
    }),
  );
});
it('changes existing warning expiry through its own reviewed controls without a new-warning reason', async () => {
  await render('warnings');
  expect(button('Ändra giltighet').disabled).toBe(false);
  act(() => button('Ändra giltighet').click());
  expect(button('Bekräfta beslut').disabled).toBe(true);
  input('warning-decision-reason', 'Överklagan granskad');
  input('warning-new-days', '5');
  await act(async () => button('Bekräfta beslut').click());
  expect(api).toHaveBeenCalledWith(
    'admin-setWarningExpiry',
    expect.objectContaining({
      warningId: 'warning-one',
      expectedVersion: 3,
      durationDays: 5,
      reason: 'Överklagan granskad',
      targetUid: 'member',
      expectedActorUid: 'actor',
      idempotencyKey: expect.any(String),
    }),
  );
  expect(api.mock.calls.some((row) => row[0] === 'admin-warnUser')).toBe(false);
});
it('expires the displayed active status from the server duration without another backend read', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date', 'performance'] });
  await render('warnings');
  expect(host.textContent).toContain('Aktiv varning');
  const count = api.mock.calls.length;
  await act(async () => vi.advanceTimersByTime(2000));
  expect(host.textContent).toContain('Utgången varning');
  expect(api).toHaveBeenCalledTimes(count);
});
