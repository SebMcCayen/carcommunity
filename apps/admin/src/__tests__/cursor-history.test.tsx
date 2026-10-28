import { act, useCallback } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCursorHistory, type CursorHistoryPage } from '@/hooks/useCursorHistory';
import { HistoryPager } from '@/components/ui/HistoryPager';
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
type Row = { id: string };
const read = vi.fn();
function Screen({ filter }: { filter: string }) {
  const load = useCallback((cursor: string | null) => read(filter, cursor), [filter]);
  const history = useCursorHistory<Row>(load, 'id');
  return (
    <>
      <ul>
        {history.items.map((row) => (
          <li key={row.id}>{row.id}</li>
        ))}
      </ul>
      <HistoryPager
        shown={history.items.length}
        total={history.total}
        hasNext={!!history.nextCursor}
        loading={history.loading}
        error={history.error}
        onMore={() => void history.loadMore()}
        onRefresh={() => void history.refresh()}
      />
    </>
  );
}
let container: HTMLDivElement, root: Root;
beforeEach(() => {
  read.mockReset();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});
afterEach(() => {
  act(() => root.unmount());
  container.remove();
});
const page = (ids: string[], cursor: string | null): CursorHistoryPage<Row> => ({
  items: ids.map((id) => ({ id })),
  meta: { total: 3, nextCursor: cursor },
});
const button = (text: string) =>
  [...container.querySelectorAll('button')].find((button) => button.textContent === text)!;
describe('cursor history interaction', () => {
  it('keeps rows and cursor after a failed continuation and retries exactly that page', async () => {
    read
      .mockResolvedValueOnce(page(['a', 'b'], 'next'))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(page(['c'], null));
    await act(async () => root.render(<Screen filter="all" />));
    await act(async () => button('Visa fler').click());
    expect([...container.querySelectorAll('li')].map((row) => row.textContent)).toEqual(['a', 'b']);
    expect(container.querySelector('[role="alert"]')).not.toBeNull();
    await act(async () => button('Försök läsa fler igen').click());
    expect(read.mock.calls).toEqual([
      ['all', null],
      ['all', 'next'],
      ['all', 'next'],
    ]);
    expect([...container.querySelectorAll('li')].map((row) => row.textContent)).toEqual([
      'a',
      'b',
      'c',
    ]);
    expect(container.textContent).toContain('Visar 3 av 3');
    expect(button('Visa fler')).toBeUndefined();
  });
  it('ignores an old filter response and never appends it to the new selection', async () => {
    let complete!: (page: CursorHistoryPage<Row>) => void;
    read
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            complete = resolve;
          }),
      )
      .mockResolvedValueOnce(page(['risk'], null));
    await act(async () => root.render(<Screen filter="all" />));
    await act(async () => root.render(<Screen filter="risk" />));
    await act(async () => complete(page(['old-all-result'], 'old-cursor')));
    expect(container.textContent).toContain('risk');
    expect(container.textContent).not.toContain('old-all-result');
    expect(button('Visa fler')).toBeUndefined();
  });
  it('blocks repeated load-more clicks and refresh starts from a new first page', async () => {
    let complete!: (page: CursorHistoryPage<Row>) => void;
    read
      .mockResolvedValueOnce(page(['a'], 'next'))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            complete = resolve;
          }),
      )
      .mockResolvedValueOnce(page(['new'], null));
    await act(async () => root.render(<Screen filter="all" />));
    await act(async () => {
      const more = button('Visa fler');
      more.click();
      more.click();
    });
    expect(read).toHaveBeenCalledTimes(2);
    await act(async () => complete(page(['b'], null)));
    await act(async () => button('Uppdatera historiken').click());
    expect(read.mock.calls.at(-1)).toEqual(['all', null]);
    expect([...container.querySelectorAll('li')].map((row) => row.textContent)).toEqual(['new']);
  });
});
