import { useCallback, useEffect, useRef, useState } from 'react';
import { callAdmin } from '@/lib/callables';
import { useAdminAuth } from '@/components/auth/FirebaseAuthProvider';
export type Source = 'event' | 'community';
export type Target = { source: Source; messageId: string; eventId?: string; reportId?: string };
export type Message = {
  id?: string;
  source: Source;
  eventId: string | null;
  messageId: string;
  authorUid: string;
  authorName: string;
  text: string;
  state: string;
  exists: boolean;
  createdAt: string | null;
};
export type Report = {
  authorName: string;
  eventTitle: string | null;
  id: string;
  source: Source;
  eventId: string | null;
  messageId: string;
  reportId: string;
  reason: string;
  details: string;
  status: string;
  createdAt: string | null;
};
export type Case = {
  eventTitle: string | null;
  actorUid: string;
  version: string;
  message: Message;
  report: {
    id: string;
    reason: string;
    details: string;
    status: string;
    createdAt: string | null;
  } | null;
  context: Message[];
  history: { id: string; action: string; reason: string; createdAt: string | null }[];
};
export const targetOf = (row: Message | Report): Target => ({
  source: row.source,
  messageId: row.messageId,
  ...(row.eventId ? { eventId: row.eventId } : {}),
  ...('reportId' in row ? { reportId: row.reportId } : {}),
});
export const sourceName = (source: Source) =>
  source === 'event' ? 'Eventchatt' : 'Publik communitychatt';
export const stateName = (state: string) =>
  ({
    new: 'Öppen',
    pending: 'Öppen',
    under_review: 'Under granskning',
    resolved: 'Avslutad',
    reviewed: 'Avslutad',
    dismissed: 'Avvisad',
    visible: 'Synligt',
    auto_hidden: 'Automatiskt dolt',
    allowed: 'Tillåtet',
    removed: 'Borttaget',
  })[state] ?? 'Okänd status';
export const reasonName = (reason: string) =>
  ({
    harassment: 'Trakasserier',
    hate_or_abuse: 'Hat eller kränkningar',
    spam: 'Spam',
    unsafe_driving: 'Osäker körning',
    privacy: 'Integritetsintrång',
    other: 'Övrigt',
    inappropriate: 'Olämpligt innehåll',
  })[reason] ?? 'Anmälan';
export const date = (value: string | null) =>
  value ? new Date(value).toLocaleString('sv-SE') : 'Tidpunkt saknas';
export function usePublicPages<T extends { id?: string }>(
  name: string,
  filters: Record<string, unknown>,
) {
  const { user } = useAdminAuth();
  const scope = JSON.stringify([filters, user?.uid]);
  const [rows, setRows] = useState<T[]>([]),
    [cursor, setCursor] = useState<string | null>(null),
    [loading, setLoading] = useState(true),
    [error, setError] = useState<string | null>(null);
  const ref = useRef({ sequence: 0, busy: false });
  const read = useCallback(
    async (next: string | null) => {
      if (next && ref.current.busy) return;
      const n = ++ref.current.sequence;
      ref.current.busy = true;
      setLoading(true);
      setError(null);
      if (!next) {
        setRows([]);
        setCursor(null);
      }
      try {
        const [values] = JSON.parse(scope) as [Record<string, unknown>, string | null];
        const result = await callAdmin<{ items: T[]; meta: { nextCursor: string | null } }>(name, {
          ...values,
          pageSize: 20,
          cursor: next,
        });
        if (n === ref.current.sequence) {
          setRows((previous) =>
            next
              ? [...new Map([...previous, ...result.items].map((row) => [row.id, row])).values()]
              : result.items,
          );
          setCursor(result.meta.nextCursor);
        }
      } catch (e) {
        if (n === ref.current.sequence)
          setError(e instanceof Error ? e.message : 'Kunde inte läsa kön. Försök igen.');
      } finally {
        if (n === ref.current.sequence) {
          ref.current.busy = false;
          setLoading(false);
        }
      }
    },
    [name, scope],
  );
  useEffect(() => {
    const c = ref.current;
    void read(null);
    return () => {
      c.sequence++;
      c.busy = false;
    };
  }, [read]);
  return { rows, cursor, loading, error, refresh: () => read(null), more: () => read(cursor) };
}
