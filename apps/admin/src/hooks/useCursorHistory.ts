import { useCallback, useEffect, useRef, useState } from 'react';

export interface CursorHistoryPage<T> {
  items: T[];
  meta: { total: number; nextCursor?: string | null; asOf?: string };
}
/** Keeps failed pages retryable and ignores responses from older filters/users. */
export function useCursorHistory<T>(
  read: (cursor: string | null) => Promise<CursorHistoryPage<T>>,
  idKey: keyof T,
) {
  const [items, setItems] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [asOf, setAsOf] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const control = useRef({ sequence: 0, busy: false });
  const request = useCallback(
    async (cursor: string | null, append: boolean) => {
      if (append && control.current.busy) return;
      const generation = ++control.current.sequence;
      control.current.busy = true;
      setLoading(true);
      setError(null);
      if (!append) {
        setItems([]);
        setNextCursor(null);
        setTotal(0);
        setAsOf(null);
      }
      try {
        const result = await read(cursor);
        if (generation !== control.current.sequence) return;
        setItems((previous) =>
          append
            ? [...new Map([...previous, ...result.items].map((row) => [row[idKey], row])).values()]
            : result.items,
        );
        setTotal(result.meta.total);
        setNextCursor(result.meta.nextCursor ?? null);
        setAsOf(result.meta.asOf ?? null);
      } catch {
        if (generation === control.current.sequence)
          setError('Historiken kunde inte läsas. Försök igen.');
      } finally {
        if (generation === control.current.sequence) {
          control.current.busy = false;
          setLoading(false);
        }
      }
    },
    [read, idKey],
  );
  const refresh = useCallback(() => request(null, false), [request]);
  const loadMore = useCallback(
    () => (nextCursor ? request(nextCursor, true) : Promise.resolve()),
    [request, nextCursor],
  );
  useEffect(() => {
    const current = control.current;
    void refresh();
    return () => {
      current.sequence++;
      current.busy = false;
    };
  }, [refresh]);
  return { items, total, nextCursor, asOf, loading, error, refresh, loadMore };
}
