import { AlertTriangle, Coins, Pencil, ArrowRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { callAdmin } from '@/lib/callables';
import { formatDateTimeStockholm } from '@/lib/format';
import type { AdminUserDetail } from '@/features/users';
import { actionLabel, type AuditRow } from './MemberHistory';
import styles from './page.module.css';
type Warning = {
  id: string;
  reason: string;
  createdAt: string;
  expiresAt: string | null;
  remainingMillis: number | null;
  status: string;
  deadline?: number;
};
type Entry = {
  transactionId: string;
  amount: number;
  description: string;
  createdAt: string;
  source: string;
};
export function MemberOverview({
  member,
  onSection,
  onRefresh,
}: {
  member: AdminUserDetail;
  onSection: (section: string) => void;
  onRefresh: () => void;
}) {
  const [warnings, setWarnings] = useState<Warning[] | null>(null),
    [moreWarnings, setMoreWarnings] = useState(false),
    [warningError, setWarningError] = useState(false);
  const [ledger, setLedger] = useState<{ balance: number; transactions: Entry[] } | null>(null),
    [pointsError, setPointsError] = useState(false);
  const [audit, setAudit] = useState<AuditRow[] | null>(null),
    [auditError, setAuditError] = useState(false);
  const [tick, setTick] = useState(0),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    let live = true;
    setWarnings(null);
    setLedger(null);
    setAudit(null);
    setWarningError(false);
    setPointsError(false);
    setAuditError(false);
    const started = performance.now();
    void callAdmin<{ warnings: Warning[]; meta: { nextCursor: string | null } }>(
      'admin-listWarnings',
      { targetUid: member.uid, pageSize: 20 },
    )
      .then((result) => {
        if (live) {
          setMoreWarnings(!!result.meta.nextCursor);
          setWarnings(
            result.warnings.map((row) => ({
              ...row,
              deadline: row.remainingMillis === null ? undefined : started + row.remainingMillis,
            })),
          );
        }
      })
      .catch(() => {
        if (live) setWarningError(true);
      });
    void callAdmin<{ data: { balance: number; transactions: Entry[] } }>('admin-listPointsLedger', {
      targetUid: member.uid,
      pageSize: 3,
    })
      .then((result) => {
        if (live) setLedger(result.data);
      })
      .catch(() => {
        if (live) setPointsError(true);
      });
    void callAdmin<{ items: AuditRow[] }>('admin-listMemberHistory', {
      targetUid: member.uid,
      pageSize: 3,
    })
      .then((result) => {
        if (live) setAudit(result.items);
      })
      .catch(() => {
        if (live) setAuditError(true);
      });
    return () => {
      live = false;
    };
  }, [member.uid, retry]);
  useEffect(() => {
    const deadlines =
      warnings
        ?.filter(
          (row) =>
            row.status === 'active' &&
            row.deadline !== undefined &&
            row.deadline > performance.now(),
        )
        .map((row) => row.deadline!) ?? [];
    if (!deadlines.length) return;
    const timer = window.setTimeout(
      () => setTick((value) => value + 1),
      Math.min(2147483647, Math.max(1, Math.min(...deadlines) - performance.now() + 1)),
    );
    return () => window.clearTimeout(timer);
  }, [warnings, tick]);
  const active = warnings?.filter(
    (row) =>
      row.status === 'active' && (row.deadline === undefined || performance.now() < row.deadline),
  );
  const warning = active?.[0];
  return (
    <div className={styles.overviewGrid}>
      <div className={styles.overviewMain}>
        <section className={styles.card}>
          <div className={styles.cardHeading}>
            <h2 className={styles.cardTitle}>Profil</h2>
            <button type="button" className={styles.textAction} onClick={onRefresh}>
              Uppdatera översikten
            </button>
          </div>
          <dl className={styles.overviewDetails}>
            <dt>Visningsnamn</dt>
            <dd>{member.displayName || 'Namn saknas'}</dd>
            <dd>
              <button
                type="button"
                className={styles.textAction}
                onClick={() => onSection('profile')}
              >
                <Pencil size={16} aria-hidden="true" /> Ändra namn
              </button>
            </dd>
            <dt>Medlemskap</dt>
            <dd>{member.activeMember ? 'Aktivt medlemskap' : 'Community'}</dd>
            <dd>
              <Link
                className={styles.link}
                to={`/subscription?uid=${encodeURIComponent(member.uid)}`}
              >
                Visa medlemskap
              </Link>
            </dd>
            <dt>Senast registrerade inloggning</dt>
            <dd>
              {member.lastLoginAt ? formatDateTimeStockholm(member.lastLoginAt) : 'Historik saknas'}
            </dd>
            <dd />
            <dt>Inloggningsfrekvens</dt>
            <dd>Historik saknas</dd>
            <dd />
          </dl>
          {warning ? (
            <article className={styles.activeWarning} aria-label="Aktiv varning">
              <div className={styles.cardHeading}>
                <h3>
                  <AlertTriangle size={24} aria-hidden="true" />
                  Aktiv varning
                  {active && active.length > 1 ? ` · ${active.length} på denna sida` : ''}
                </h3>
                <button
                  className={styles.textAction}
                  type="button"
                  onClick={() => onSection('warnings')}
                >
                  Hantera varningar
                </button>
              </div>
              <dl className={styles.warningDetails}>
                <dt>Orsak</dt>
                <dd>{warning.reason}</dd>
                <dt>Utfärdad</dt>
                <dd>{formatDateTimeStockholm(warning.createdAt)}</dd>
                <dt>Giltig till</dt>
                <dd>
                  {warning.expiresAt
                    ? formatDateTimeStockholm(warning.expiresAt)
                    : 'Tills den återkallas'}
                </dd>
              </dl>
              <p className={styles.meta}>Synlig endast för medlemmen och administratörer.</p>
            </article>
          ) : warningError ? (
            <p role="alert">
              Varningarna kunde inte läsas.{' '}
              <button
                type="button"
                className={styles.textAction}
                onClick={() => setRetry((v) => v + 1)}
              >
                Försök igen
              </button>
            </p>
          ) : warnings === null ? (
            <p className={styles.meta}>Läser varningar…</p>
          ) : (
            <p className={styles.meta}>
              {moreWarnings
                ? 'Äldre varningar finns. Öppna Varningar för fullständig historik.'
                : 'Inga aktiva varningar.'}
            </p>
          )}
        </section>
        <section className={styles.card}>
          <div className={styles.cardHeading}>
            <h2 className={styles.cardTitle}>Senaste åtgärder</h2>
            <button
              type="button"
              className={styles.textAction}
              onClick={() => onSection('history')}
            >
              Visa all historik <ArrowRight size={16} aria-hidden="true" />
            </button>
          </div>
          {auditError ? (
            <p role="alert">
              Åtgärderna kunde inte läsas.{' '}
              <button
                className={styles.textAction}
                type="button"
                onClick={() => setRetry((v) => v + 1)}
              >
                Försök igen
              </button>
            </p>
          ) : audit === null ? (
            <p className={styles.meta}>Läser åtgärder…</p>
          ) : audit.length === 0 ? (
            <p className={styles.meta}>Inga administrativa beslut har registrerats.</p>
          ) : (
            <div className={styles.tableScroll}>
              <table className={styles.auditTable}>
                <thead>
                  <tr>
                    <th>Datum</th>
                    <th>Åtgärd</th>
                    <th>Utförd av</th>
                  </tr>
                </thead>
                <tbody>
                  {audit.map((row) => (
                    <tr key={row.id}>
                      <td>{formatDateTimeStockholm(row.createdAt)}</td>
                      <td>{actionLabel(row.action)}</td>
                      <td>{row.actorName}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>
      <section className={`${styles.card} ${styles.pointsOverview}`}>
        <h2 className={styles.cardTitle}>Poängsaldo</h2>
        {pointsError ? (
          <p role="alert">
            Poängsaldot kunde inte läsas.{' '}
            <button
              className={styles.textAction}
              type="button"
              onClick={() => setRetry((v) => v + 1)}
            >
              Försök igen
            </button>
          </p>
        ) : (
          <p className={styles.pointsBalance}>
            {ledger ? new Intl.NumberFormat('sv-SE').format(ledger.balance) : '…'}
          </p>
        )}
        <p className={styles.meta}>Gemensamt saldo för XP och Kronpoäng.</p>
        <button
          type="button"
          className={styles.secondaryButton}
          onClick={() => onSection('points')}
        >
          <Coins size={18} aria-hidden="true" /> Justera saldo
        </button>
        <h3>Senaste poänghändelser</h3>
        {ledger?.transactions.length === 0 && (
          <p className={styles.meta}>Inga poänghändelser har registrerats.</p>
        )}
        <div>
          {ledger?.transactions.map((row) => (
            <article key={row.transactionId} className={styles.pointPreview}>
              <time dateTime={row.createdAt}>{formatDateTimeStockholm(row.createdAt)}</time>
              <strong className={row.amount < 0 ? styles.negativeAmount : styles.positiveAmount}>
                {row.amount > 0 ? '+' : ''}
                {row.amount}
              </strong>
              <div>
                {row.description || 'Poänghändelse'}
                <p className={styles.meta}>Kronpoäng</p>
              </div>
            </article>
          ))}
        </div>
        <button type="button" className={styles.textAction} onClick={() => onSection('points')}>
          Visa alla poänghändelser <ArrowRight size={16} aria-hidden="true" />
        </button>
      </section>
    </div>
  );
}
