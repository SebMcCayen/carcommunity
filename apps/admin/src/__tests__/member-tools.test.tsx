import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
const callMock = vi.fn();
vi.mock('@/lib/callables', () => ({ callAdmin: (...args: unknown[]) => callMock(...args) }));
import { MemberTools } from '@/app/users/[id]/MemberTools';
import { pendingOperation } from '@/lib/pendingOperation';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let container: HTMLDivElement, root: Root;
const member = {
  uid: 'member-1',
  actorUid: 'admin-1',
  displayName: 'Alice',
  role: 'user' as const,
  activeMember: true,
  suspended: false,
  deleted: false,
  bio: null,
  createdAt: null,
  updatedAt: null,
  permissions: {
    canRename: true,
    canWarn: true,
    canAdjustPoints: true,
    canReversePoints: true,
    canSuspend: true,
    canRestore: false,
  },
};
async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}
async function click(label: string) {
  const button = Array.from(container.querySelectorAll('button')).find(
    (b) => b.textContent === label,
  );
  expect(button, label).toBeTruthy();
  await act(async () => button!.click());
  await flush();
}
async function input(id: string, value: string) {
  const field = container.querySelector(`#${id}`) as HTMLInputElement;
  await act(async () => {
    Object.getOwnPropertyDescriptor(
      field.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype,
      'value',
    )!.set!.call(field, value);
    field.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
beforeEach(async () => {
  sessionStorage.clear();
  callMock.mockReset();
  callMock.mockImplementation((name: string) =>
    name === 'admin-listWarnings'
      ? Promise.resolve({ warnings: [], meta: { total: 0, hasNext: false, nextCursor: null } })
      : Promise.resolve({}),
  );
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  await act(async () => root.render(<MemberTools member={member} onChanged={() => {}} />));
  await flush();
});
afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
});
describe('member decisions', () => {
  it('keeps the same retry identity for an uncertain decision and changes it for a different actor or intent', () => {
    const first = pendingOperation('admin-1:member-1:points', { amount: 10 });
    expect(pendingOperation('admin-1:member-1:points', { amount: 10 }).key).toBe(first.key);
    expect(pendingOperation('admin-2:member-1:points', { amount: 10 }).key).not.toBe(first.key);
    expect(pendingOperation('admin-1:member-1:points', { amount: 20 }).key).not.toBe(first.key);
  });
  it('names the reviewed target and validity before warning; retries an uncertain result with the same key', async () => {
    await input('member-decision-reason', 'Respektera andra');
    await input('warning-note', 'Intern');
    await click('Granska varning');
    expect(container.querySelector('dialog')?.textContent).toContain('Alice · member-1');
    expect(container.querySelector('dialog')?.textContent).toContain('7 dagar');
    callMock.mockRejectedValueOnce(new Error('Nätverket avbröts'));
    await click('Bekräfta beslut');
    const first = callMock.mock.calls.find((c) => c[0] === 'admin-warnUser')![1];
    expect(container.querySelector('[role="alert"]')?.textContent).toContain('Nätverket');
    callMock.mockImplementation((name: string) =>
      name === 'admin-listWarnings'
        ? Promise.resolve({ warnings: [], meta: { total: 0, hasNext: false, nextCursor: null } })
        : Promise.resolve({ notificationStatus: 'delivered' }),
    );
    await click('Bekräfta beslut');
    const last = callMock.mock.calls.filter((c) => c[0] === 'admin-warnUser').at(-1)![1];
    expect(last).toEqual(first);
    expect(last).toMatchObject({
      expectedActorUid: 'admin-1',
      durationDays: 7,
      reason: 'Respektera andra',
      internalNote: 'Intern',
    });
  });
});
