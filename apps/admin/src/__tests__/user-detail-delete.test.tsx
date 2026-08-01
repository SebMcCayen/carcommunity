/**
 * Component tests for the User detail page's "Delete user" danger zone.
 *
 * Proves the destructive-deletion UX contract:
 *  - the Delete user button reveals a confirmation dialog that names the user;
 *  - Confirm stays disabled until BOTH a reason is given AND the admin re-types
 *    the user's display name (the type-to-confirm speed-bump); and
 *  - Confirm calls adminDeleteUser(uid, reason) and navigates back to the list.
 *
 * Rendered with react-dom/client + React.act (no testing-library dependency,
 * matching the app's zero-extra-deps test setup). Firestore-backed siblings and
 * the router are mocked so the page renders without runtime Firebase env.
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const deleteMock = vi.fn();
const navigateMock = vi.fn();

vi.mock('react-router-dom', () => ({
  useParams: () => ({ id: 'uid-target' }),
  useNavigate: () => navigateMock,
  Navigate: () => null,
  Link: ({ children }: { children: unknown }) => children as never,
}));

vi.mock('@/features/users', () => ({
  adminGetUser: vi.fn(async () => ({
    uid: 'uid-target',
    displayName: 'Speedy',
    role: 'user',
    activeMember: false,
    suspended: false,
    deleted: false,
    bio: null,
    createdAt: null,
    updatedAt: null,
  })),
  adminDeleteUser: (...args: unknown[]) => deleteMock(...args),
  adminRestoreAccess: vi.fn(),
  adminSetAdminRole: vi.fn(),
  adminSuspendUser: vi.fn(),
  adminWarnUser: vi.fn(),
}));

// The points section pulls in Firestore-backed callables; stub it out.
vi.mock('@/app/users/[id]/PointsSection', () => ({ UserPointsSection: () => null }));

import UserDetailPage from '@/app/users/[id]/page';
import { translate } from '@/i18n';

const t = (key: string) => translate('sv', key);

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

function button(label: string): HTMLButtonElement {
  const match = Array.from(container.querySelectorAll('button')).find(
    (b) => b.textContent === label,
  );
  if (!match) throw new Error(`button not found: ${label}`);
  return match as HTMLButtonElement;
}

function setInput(id: string, value: string) {
  const el = container.querySelector(`#${id}`) as HTMLInputElement;
  if (!el) throw new Error(`input not found: ${id}`);
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
  act(() => {
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

beforeEach(() => {
  deleteMock.mockReset();
  navigateMock.mockReset();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

async function render() {
  await act(async () => {
    root.render(<UserDetailPage />);
  });
  await flush();
}

describe('User detail — delete danger zone', () => {
  it('reveals a confirm dialog naming the user only after pressing Delete user', async () => {
    await render();

    // The danger zone button is present; the confirm inputs are not yet shown.
    expect(button(t('users.detail.deleteUser'))).toBeTruthy();
    expect(container.querySelector('#user-delete-confirm')).toBeNull();

    act(() => button(t('users.detail.deleteUser')).click());

    // Dialog now shown and names the user (display name + uid).
    const dialog = container.querySelector('[role="alertdialog"]')!;
    expect(dialog).toBeTruthy();
    expect(dialog.textContent).toContain('Speedy');
    expect(dialog.textContent).toContain('uid-target');
    expect(container.querySelector('#user-delete-confirm')).toBeTruthy();
  });

  it('gates Confirm on both a reason and the typed display name, then calls the callable', async () => {
    deleteMock.mockResolvedValue({ targetUid: 'uid-target', deleted: true });
    await render();
    act(() => button(t('users.detail.deleteUser')).click());

    const confirm = () => button(t('users.detail.deleteConfirm'));

    // Disabled with nothing filled in.
    expect(confirm().disabled).toBe(true);

    // Reason only — still disabled (type-to-confirm not satisfied).
    setInput('user-delete-reason', 'GDPR erasure');
    expect(confirm().disabled).toBe(true);

    // Wrong confirmation phrase — still disabled.
    setInput('user-delete-confirm', 'speedy'); // case-sensitive: not "Speedy"
    expect(confirm().disabled).toBe(true);

    // Correct phrase — armed.
    setInput('user-delete-confirm', 'Speedy');
    expect(confirm().disabled).toBe(false);

    await act(async () => {
      confirm().click();
    });
    await flush();

    expect(deleteMock).toHaveBeenCalledTimes(1);
    expect(deleteMock).toHaveBeenCalledWith('uid-target', 'GDPR erasure');
    // Navigates back to the users list with the deleted-user notice.
    expect(navigateMock).toHaveBeenCalledWith(
      '/users',
      expect.objectContaining({
        replace: true,
        state: { deletedUser: { uid: 'uid-target', displayName: 'Speedy' } },
      }),
    );
  });
});
