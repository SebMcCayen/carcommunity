import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
const api = vi.hoisted(() => vi.fn());
vi.mock('@/lib/callables', () => ({ callAdmin: api }));
import { AccessTools } from '@/app/users/[id]/AccessTools';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let root: Root, host: HTMLDivElement;
const view = {
  uid: 'member',
  actorUid: 'actor',
  displayName: 'Medlem',
  version: 7,
  blacklistIds: ['reviewed'],
  ready: true,
  canManage: true,
  suspended: true,
  blacklisted: true,
  sharedRestricted: false,
  persistentIdentitySupported: true,
  reason: 'Beteende',
};
beforeEach(() => {
  api.mockReset();
  sessionStorage.clear();
  api.mockResolvedValue(view);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});
async function render() {
  await act(async () => root.render(<AccessTools uid="member" />));
}
function button(text: string) {
  const value = [...host.querySelectorAll('button')].find((el) => el.textContent === text);
  if (!value) throw Error(text);
  return value;
}
function reason(text: string) {
  const input = host.querySelector('textarea')!;
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(input, text);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
it('reviews independent unlisting and reuses the same actor-bound intent after uncertain delivery', async () => {
  await render();
  reason('Godkänd överklagan');
  act(() => button('Ta bort från svartlistan').click());
  expect(host.querySelector('dialog')?.textContent).toContain('En separat avstängning behålls.');
  api.mockRejectedValueOnce(Error('lost response'));
  await act(async () => button('Bekräfta beslut').click());
  const first = api.mock.calls.find((row) => row[0] === 'admin-updateAccess')?.[1];
  expect(first).toMatchObject({
    action: 'unlist',
    expectedActorUid: 'actor',
    expectedVersion: 7,
    expectedBlacklistIds: ['reviewed'],
    reason: 'Godkänd överklagan',
  });
  expect(first.idempotencyKey).toBeTruthy();
  api.mockResolvedValue(view);
  await act(async () => button('Bekräfta beslut').click());
  const calls = api.mock.calls.filter((row) => row[0] === 'admin-updateAccess');
  expect(calls).toHaveLength(2);
  expect(calls[1]![1]).toEqual(first);
  expect(host.querySelector('dialog')).toBeNull();
});
it('keeps mutations disabled until the migration is ready and capabilities permit them', async () => {
  api.mockResolvedValue({ ...view, ready: false });
  await render();
  reason('Motivering');
  expect(button('Häv avstängning').disabled).toBe(true);
  expect(button('Ta bort från svartlistan').disabled).toBe(true);
  expect(api.mock.calls.every((row) => row[0] === 'admin-getAccessTarget')).toBe(true);
});
