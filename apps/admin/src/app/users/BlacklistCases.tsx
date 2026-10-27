import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { callAdmin } from '@/lib/callables';
import { useCursorHistory } from '@/hooks/useCursorHistory';
import { HistoryPager } from '@/components/ui/HistoryPager';
import { AccessTools } from './[id]/AccessTools';
type Case = {
  id: string;
  targetUid: string;
  displayName: string;
  reason: string;
  createdAt: string;
};
export function BlacklistCases() {
  const [open, setOpen] = useState(false);
  return (
    <section>
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>
        Svartlistning för CarCommunity
      </button>
      {open && <Cases />}
    </section>
  );
}
function Cases() {
  const [target, setTarget] = useState<string | null>(null);
  const read = useCallback(async (cursor: string | null) => {
    const result = await callAdmin<{
      cases: Case[];
      meta: { total: number; nextCursor?: string | null };
    }>('admin-listAccessBlocks', { pageSize: 20, ...(cursor ? { cursor } : {}) });
    return { items: result.cases, meta: result.meta };
  }, []);
  const history = useCursorHistory(read, 'id');
  return (
    <>
      <p>
        Aktiva beslut visas en gång per svartlistning. Identitetsspärren behålls efter
        kontoborttagning tills den uttryckligen tas bort.
      </p>
      <ul>
        {history.items.map((row) => (
          <li key={row.id}>
            <Link to={`/users/${encodeURIComponent(row.targetUid)}`}>
              {row.displayName || row.targetUid}
            </Link>{' '}
            · {new Date(row.createdAt).toLocaleString('sv-SE')}
            <p>{row.reason}</p>
            <button type="button" onClick={() => setTarget(row.targetUid)}>
              Granska åtkomst / ta bort svartlistning
            </button>
          </li>
        ))}
      </ul>
      {!history.loading && !history.error && history.items.length === 0 && (
        <p>Inga aktiva svartlistningar.</p>
      )}
      <HistoryPager
        shown={history.items.length}
        total={history.total}
        hasNext={!!history.nextCursor}
        loading={history.loading}
        error={history.error}
        onMore={() => void history.loadMore()}
        onRefresh={() => void history.refresh()}
      />
      {target && (
        <>
          <AccessTools key={target} uid={target} onChanged={() => void history.refresh()} />
          <button type="button" onClick={() => setTarget(null)}>
            Stäng granskning
          </button>
        </>
      )}
    </>
  );
}
