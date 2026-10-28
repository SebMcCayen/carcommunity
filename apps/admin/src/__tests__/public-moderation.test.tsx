import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
const mock = vi.hoisted(() => ({ api: vi.fn(), uid: 'actor' }));
vi.mock('@/lib/callables', () => ({ callAdmin: mock.api }));
vi.mock('@/components/auth/FirebaseAuthProvider', () => ({
  useAdminAuth: () => ({ user: { uid: mock.uid } }),
}));
import { ModerationCase } from '@/app/moderation/ModerationCase';
import { usePublicPages } from '@/features/public-moderation';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement, root: Root;
beforeEach(() => {
  mock.uid = 'actor';
  mock.api.mockReset();
  sessionStorage.clear();
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});
const detail = {
  actorUid: 'actor',
  version: 'a'.repeat(64),
  message: {
    source: 'community',
    eventId: null,
    messageId: 'message',
    authorUid: 'member',
    authorName: 'AlexNord',
    text: 'Reported text',
    state: 'visible',
    exists: true,
    createdAt: null,
  },
  report: { id: 'report', reason: 'spam', details: 'Note', status: 'pending', createdAt: null },
  context: [],
  history: [],
};
const target = { source: 'community' as const, messageId: 'message', reportId: 'report' };
const button = (text: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent === text)!;
const render = () =>
  act(async () =>
    root.render(
      <MemoryRouter>
        <ModerationCase target={target} onChanged={() => undefined} />
      </MemoryRouter>,
    ),
  );
function reason(value: string) {
  act(() => {
    const el = host.querySelector('textarea')!;
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
it('requires a reason and a reviewed dialog; an uncertain decision retries the identical receipt', async () => {
  mock.api.mockResolvedValue(detail);
  await render();
  expect(button('Ta bort meddelandet').disabled).toBe(true);
  reason('Reviewed reason');
  act(() => button('Ta bort meddelandet').click());
  expect(host.querySelector('dialog')?.textContent).toContain('Reported text');
  mock.api.mockRejectedValueOnce(new Error('offline'));
  await act(async () => button('Bekräfta beslut').click());
  const first = mock.api.mock.calls.find(([name]) => name === 'admin-decidePublicModeration')?.[1];
  expect(first).toMatchObject({
    target,
    expectedActorUid: 'actor',
    expectedVersion: 'a'.repeat(64),
    reason: 'Reviewed reason',
    action: 'remove',
  });
  mock.api
    .mockResolvedValueOnce({ cleanup: { hasMore: false } })
    .mockResolvedValueOnce({
      ...detail,
      message: { ...detail.message, exists: false, state: 'removed', text: '' },
    });
  await act(async () => button('Bekräfta beslut').click());
  const writes = mock.api.mock.calls.filter(([name]) => name === 'admin-decidePublicModeration');
  expect(writes[1]?.[1]).toEqual(first);
  expect(host.textContent).toContain('Meddelandet är borttaget.');
  expect(host.textContent).not.toContain('Reported text');
});
it('does not submit a decision from a different current admin session', async () => {
  mock.api.mockResolvedValue(detail);
  await render();
  reason('Reason');
  act(() => button('Ta bort meddelandet').click());
  mock.uid = 'other';
  await render();
  expect(button('Bekräfta beslut').disabled).toBe(true);
  expect(
    mock.api.mock.calls.filter(([name]) => name === 'admin-decidePublicModeration'),
  ).toHaveLength(0);
});
function Screen({ uid }: { uid: string }) {
  const p = usePublicPages<{ id: string }>('admin-listMemberMessages', { targetUid: uid });
  return (
    <>
      <p>{p.rows.map((r) => r.id).join(',')}</p>
      <button onClick={() => void p.more()}>More</button>
      <span>{p.error}</span>
    </>
  );
}
it('fences a late member page and retries a failed continuation without dropping rows', async () => {
  let finish: ((value: unknown) => void) | undefined;
  mock.api
    .mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    )
    .mockResolvedValueOnce({ items: [{ id: 'new' }], meta: { nextCursor: 'next' } })
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ items: [{ id: 'newer' }], meta: { nextCursor: null } });
  await act(async () => root.render(<Screen uid="old" />));
  await act(async () => root.render(<Screen uid="new" />));
  await act(async () => finish?.({ items: [{ id: 'old-secret' }], meta: { nextCursor: null } }));
  expect(host.textContent).not.toContain('old-secret');
  await act(async () => button('More').click());
  expect(host.textContent).toContain('new');
  await act(async () => button('More').click());
  expect(host.textContent).toContain('new,newer');
  expect(mock.api.mock.calls.at(-1)?.[1]).toMatchObject({ targetUid: 'new', cursor: 'next' });
});
