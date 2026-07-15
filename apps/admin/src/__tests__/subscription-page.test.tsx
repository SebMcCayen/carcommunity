/**
 * Component tests for the subscription (Prenumeration) page's query-param flow.
 *
 * This behaviour is security-sensitive: the page drives destructive manual
 * grant/revoke, and the target UID can come from a profile link
 * (/subscription?uid=…). The tests lock in that:
 *  - a preset uid prefills the field, locks it read-only, shows the hint, and
 *    auto-runs the lookup on mount;
 *  - clearing the preset (back to the standalone route) resets the field and
 *    lookup state and re-enables manual entry;
 *  - switching the preset from one UID to another while the component stays
 *    mounted fully re-scopes to the new user — new lookup, and no carried-over
 *    reason that could be applied to the wrong UID.
 *
 * Rendered with react-dom/client + React.act inside a MemoryRouter (no
 * testing-library dependency, matching the app's zero-extra-deps test setup).
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, Routes, Route, useNavigate, type NavigateFunction } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getMock = vi.fn();
const grantMock = vi.fn();
const revokeMock = vi.fn();

vi.mock('@/features/subscription', () => ({
  adminGetUserSubscription: (...args: unknown[]) => getMock(...args),
  adminGrantMembership: (...args: unknown[]) => grantMock(...args),
  adminRevokeMembership: (...args: unknown[]) => revokeMock(...args),
}));

import SubscriptionPage from '@/app/subscription/page';
import { translate } from '@/i18n';

const t = (key: string) => translate('sv', key);

// React requires this flag for act() outside a test renderer.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function summaryFor(userId: string) {
  return {
    userId,
    entitlement: 'none' as const,
    subscription: null,
    isSuspendedWithActiveSubscription: false,
  };
}

let container: HTMLDivElement;
let root: Root;
// Captured from inside the router so tests can change the URL while the page
// stays mounted (mirrors navigating between profile-scoped routes).
let navigate: NavigateFunction;

function NavCapture() {
  navigate = useNavigate();
  return null;
}

/** Lets queued microtasks (state updates from resolved promises) flush. */
async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

function userIdInput(): HTMLInputElement {
  const el = container.querySelector<HTMLInputElement>('#sub-user-id');
  if (!el) throw new Error('user id input not found');
  return el;
}

function reasonInput(): HTMLInputElement | null {
  return container.querySelector<HTMLInputElement>('#sub-reason');
}

async function renderAt(url: string) {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[url]}>
        <NavCapture />
        <Routes>
          <Route path="/subscription" element={<SubscriptionPage />} />
        </Routes>
      </MemoryRouter>,
    );
  });
  await flush();
}

beforeEach(() => {
  getMock.mockReset();
  grantMock.mockReset();
  revokeMock.mockReset();
  getMock.mockImplementation((uid: string) => Promise.resolve(summaryFor(uid)));
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('SubscriptionPage query-param flow', () => {
  it('prefills, locks read-only, shows the hint, and auto-looks-up a preset uid', async () => {
    await renderAt('/subscription?uid=uid-1');

    const input = userIdInput();
    expect(input.value).toBe('uid-1');
    expect(input.readOnly).toBe(true);
    expect(container.textContent).toContain(t('subscription.fromProfileHint'));
    expect(getMock).toHaveBeenCalledWith('uid-1');
    expect(getMock).toHaveBeenCalledTimes(1);
  });

  it('resets the field and lookup state and re-enables editing when the preset clears', async () => {
    await renderAt('/subscription?uid=uid-1');
    expect(reasonInput()).not.toBeNull(); // summary loaded → action form present

    await act(async () => {
      navigate('/subscription');
    });
    await flush();

    const input = userIdInput();
    expect(input.value).toBe('');
    expect(input.readOnly).toBe(false);
    // Prior user's summary/action form is gone; hint no longer shown.
    expect(reasonInput()).toBeNull();
    expect(container.textContent).not.toContain(t('subscription.fromProfileHint'));
  });

  it('fully re-scopes to the new user when the preset switches non-empty→non-empty', async () => {
    await renderAt('/subscription?uid=uid-1');

    // Admin types a reason against user A.
    const reason = reasonInput();
    expect(reason).not.toBeNull();
    await act(async () => {
      const el = reason as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(el, 'granting for A');
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await flush();
    expect((reasonInput() as HTMLInputElement).value).toBe('granting for A');

    // Navigate to a different profile-scoped route while still mounted.
    await act(async () => {
      navigate('/subscription?uid=uid-2');
    });
    await flush();

    const input = userIdInput();
    expect(input.value).toBe('uid-2');
    expect(input.readOnly).toBe(true);
    // Lookup re-ran for the new UID.
    expect(getMock).toHaveBeenLastCalledWith('uid-2');
    // Reason from user A did NOT carry over to user B.
    expect((reasonInput() as HTMLInputElement).value).toBe('');
  });
});
