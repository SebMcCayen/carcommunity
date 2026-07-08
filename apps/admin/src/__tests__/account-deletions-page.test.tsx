/**
 * Component tests for the account-deletions page — the load()/mark-processed
 * error-propagation contract (Phase 13o 6th-pass review):
 *  - a failed post-action reload must reach handleMarkProcessed's catch (no
 *    misleading success banner), because load() rethrows after setting error
 *    state; and
 *  - an initial/tab load failure still surfaces the error banner without an
 *    unhandled promise rejection (the effect swallows the rethrow).
 *
 * Rendered with react-dom/client + React.act (no testing-library dependency,
 * matching the app's zero-extra-deps test setup).
 */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const listMock = vi.fn();
const markMock = vi.fn();

vi.mock('@/features/account-deletions', () => ({
  adminListAccountDeletionRequests: (...args: unknown[]) => listMock(...args),
  markAccountDeletionProcessed: (...args: unknown[]) => markMock(...args),
  // Stable countdown — this suite is about error flow, not purge math.
  daysUntilPurge: () => 10,
}));

import AccountDeletionsPage from '@/app/account-deletions/page';
import { translate } from '@/i18n';

const t = (key: string) => translate('sv', key);

// React requires this flag for act() outside a test renderer.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// Node's process, reached without depending on @types/node in this workspace's
// typecheck config (which doesn't include the node types).
type RejectionHooks = {
  on(event: 'unhandledRejection', listener: (...args: unknown[]) => void): void;
  off(event: 'unhandledRejection', listener: (...args: unknown[]) => void): void;
};
const nodeProcess = (globalThis as { process?: RejectionHooks }).process;

const pendingRow = {
  userId: 'uid-1',
  reason: null,
  status: 'pending' as const,
  createdAt: '2026-07-01T00:00:00.000Z',
  processedAt: null,
  purgeDueAt: '2026-07-31T00:00:00.000Z',
};

let container: HTMLDivElement;
let root: Root;

/** Lets queued microtasks (state updates from resolved promises) flush. */
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

beforeEach(() => {
  listMock.mockReset();
  markMock.mockReset();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe('AccountDeletionsPage error propagation', () => {
  it('surfaces the error banner and no unhandled rejection when the initial load fails', async () => {
    const onUnhandled = vi.fn();
    nodeProcess?.on('unhandledRejection', onUnhandled);
    try {
      listMock.mockRejectedValue(new Error('initial-load-failed'));
      await act(async () => {
        root.render(<AccountDeletionsPage />);
      });
      await flush();
      // Give any escaped rejection a macrotask to fire.
      await new Promise((resolve) => setTimeout(resolve, 0));

      // The error is reflected in state (banner shows the failure) even though
      // load() rethrows — and the effect's .catch keeps it from escaping.
      expect(container.textContent).toContain('initial-load-failed');
      expect(onUnhandled).not.toHaveBeenCalled();
    } finally {
      nodeProcess?.off('unhandledRejection', onUnhandled);
    }
  });

  it('goes to the action catch (no success banner) when the post-action reload fails', async () => {
    // Initial load: one actionable pending request.
    listMock.mockResolvedValueOnce([pendingRow]);
    await act(async () => {
      root.render(<AccountDeletionsPage />);
    });
    await flush();
    expect(container.textContent).toContain(t('accountDeletions.markProcessed'));

    // The mark succeeds, but the refresh that follows it rejects.
    markMock.mockResolvedValue({ userId: 'uid-1', status: 'processed', alreadyProcessed: false });
    listMock.mockRejectedValueOnce(new Error('reload-failed'));

    await act(async () => {
      button(t('accountDeletions.markProcessed')).click();
    });
    await flush();

    // Because load() rethrows, the success banner is never shown; the failure
    // lands in handleMarkProcessed's catch, which surfaces the error instead.
    expect(container.textContent).not.toContain(t('accountDeletions.markProcessedSuccess'));
    expect(container.textContent).toContain('reload-failed');
    expect(markMock).toHaveBeenCalledWith('uid-1');
  });

  it('shows the success banner when the mark and its reload both succeed', async () => {
    listMock.mockResolvedValueOnce([pendingRow]);
    await act(async () => {
      root.render(<AccountDeletionsPage />);
    });
    await flush();

    markMock.mockResolvedValue({ userId: 'uid-1', status: 'processed', alreadyProcessed: false });
    // Reload succeeds — the request is now processed, so the list is empty.
    listMock.mockResolvedValueOnce([]);

    await act(async () => {
      button(t('accountDeletions.markProcessed')).click();
    });
    await flush();

    expect(container.textContent).toContain(t('accountDeletions.markProcessedSuccess'));
    expect(container.textContent).not.toContain(t('accountDeletions.markProcessedError'));
  });
});
