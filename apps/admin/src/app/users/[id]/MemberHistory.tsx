import { useCallback } from 'react';
import { callAdmin } from '@/lib/callables';
import { useCursorHistory } from '@/hooks/useCursorHistory';
import { HistoryPager } from '@/components/ui/HistoryPager';
import { formatDateTimeStockholm } from '@/lib/format';
import styles from './page.module.css';
export type AuditRow = {
  id: string;
  action: string;
  reason: string | null;
  actorName: string;
  createdAt: string | null;
};
export const actionLabel = (action: string) =>
  (
    ({
      'user.warn': 'Varning utfärdad',
      'user.warning.revoke': 'Varning återkallad',
      'user.warning.expiry': 'Varningens giltighet ändrad',
      'user.displayName.update': 'Visningsnamn ändrat',
      'user.carCommunity.suspend': 'Avstängd i CarCommunity',
      'user.carCommunity.restore': 'Avstängning hävd',
      'user.carCommunity.blacklist': 'Svartlistad i CarCommunity',
      'user.carCommunity.unlist': 'Borttagen från svartlistan',
      'user.setAdminRole': 'Administratörsroll ändrad',
      'points.adminAdjust': 'Poäng justerade',
      'points.adminReverse': 'Poängpost återförd',
    }) as Record<string, string>
  )[action] ?? 'Administrativ åtgärd';
export function MemberHistory({ uid }: { uid: string }) {
  const read = useCallback(
    async (cursor: string | null) =>
      callAdmin<{ items: AuditRow[]; meta: { total: number; nextCursor?: string | null } }>(
        'admin-listMemberHistory',
        { targetUid: uid, pageSize: 20, cursor },
      ),
    [uid],
  );
  const history = useCursorHistory(read, 'id');
  return (
    <section className={styles.card}>
      <h2 className={styles.cardTitle}>Åtgärdshistorik</h2>
      <p className={styles.meta}>
        Administrativa beslut som hör till detta konto. Poänghändelser finns under Poäng.
      </p>
      <div className={styles.tableScroll}>
        <table className={styles.auditTable}>
          <thead>
            <tr>
              <th>Datum</th>
              <th>Åtgärd och motivering</th>
              <th>Utförd av</th>
            </tr>
          </thead>
          <tbody>
            {history.items.map((row) => (
              <tr key={row.id}>
                <td>{formatDateTimeStockholm(row.createdAt)}</td>
                <td>
                  <strong>{actionLabel(row.action)}</strong>
                  {row.reason && <p>{row.reason}</p>}
                </td>
                <td>{row.actorName}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!history.loading && !history.error && history.items.length === 0 && (
        <p>Inga administrativa beslut har registrerats för kontot.</p>
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
    </section>
  );
}
