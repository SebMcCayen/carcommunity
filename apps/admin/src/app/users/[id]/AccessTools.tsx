import { useCallback, useEffect, useRef, useState } from 'react';
import { callAdmin } from '@/lib/callables';
import { pendingOperation } from '@/lib/pendingOperation';
import { ActionDialog } from '@/components/ui/ActionDialog';
import styles from './page.module.css';
type Action = 'suspend' | 'restore' | 'blacklist' | 'unlist';
type Access = {
  uid: string;
  actorUid: string;
  displayName: string | null;
  version: number;
  blacklistIds: string[];
  ready: boolean;
  canManage: boolean;
  suspended: boolean;
  blacklisted: boolean;
  sharedRestricted: boolean;
  persistentIdentitySupported: boolean;
  reason: string | null;
};
const labels: Record<Action, string> = {
  suspend: 'Stäng av i CarCommunity',
  restore: 'Häv avstängning',
  blacklist: 'Svartlista i CarCommunity',
  unlist: 'Ta bort från svartlistan',
};
export function AccessTools({ uid, onChanged }: { uid: string; onChanged?: () => void }) {
  const [access, setAccess] = useState<Access | null>(null);
  const [reason, setReason] = useState(''),
    [error, setError] = useState<string | null>(null),
    [success, setSuccess] = useState<string | null>(null);
  const [busy, setBusy] = useState(false),
    [pending, setPending] = useState<{ action: Action; view: Access; reason: string } | null>(null);
  const working = useRef(false);
  const load = useCallback(async () => {
    try {
      setAccess(await callAdmin<Access>('admin-getAccessTarget', { targetUid: uid }));
    } catch {
      setError('Åtkomsten kunde inte läsas. Uppdatera och försök igen.');
    }
  }, [uid]);
  useEffect(() => {
    void load();
  }, [load]);
  async function confirm() {
    if (!pending || working.current) return;
    working.current = true;
    setBusy(true);
    setError(null);
    setSuccess(null);
    const reviewed = pending;
    try {
      const payload = {
        targetUid: uid,
        action: reviewed.action,
        reason: reviewed.reason,
        expectedActorUid: reviewed.view.actorUid,
        expectedVersion: reviewed.view.version,
        expectedBlacklistIds: reviewed.view.blacklistIds,
      };
      const receipt = pendingOperation(`${reviewed.view.actorUid}:${uid}:access`, payload);
      await callAdmin('admin-updateAccess', { ...payload, idempotencyKey: receipt.key });
      receipt.clear();
      setPending(null);
      setReason('');
      setSuccess('Beslutet är sparat för CarCommunity.');
      await load();
      onChanged?.();
    } catch {
      setError(
        'Beslutet kunde inte bekräftas. Samma bekräftelse kan skickas igen. Om åtkomsten har ändrats: avbryt, uppdatera och granska på nytt.',
      );
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  return (
    <section className={styles.card} aria-label="Åtkomst till CarCommunity">
      <h2 className={styles.cardTitle}>Åtkomst till CarCommunity</h2>
      <p>
        Avstängning och svartlistning är separata beslut. Svartlistning gäller även vid
        återregistrering med samma kopplade Google- eller Apple-identitet.
      </p>
      <p>Den gemensamma Firebase-inloggningen och Driver påverkas inte av dessa verktyg.</p>
      {access ? (
        <>
          <p>
            <strong>
              {access.blacklisted ? 'Svartlistad' : 'Ej svartlistad'} ·{' '}
              {access.suspended ? 'Avstängd' : 'Ej avstängd'}
            </strong>
          </p>
          {access.reason && <p>{access.reason}</p>}
          {access.sharedRestricted && (
            <p className={styles.warning}>
              Kontot har även en äldre gemensam kontobegränsning. Verktygen här ändrar endast
              CarCommunity.
            </p>
          )}
          {!access.ready && (
            <p role="status">
              Åtkomstverktygen blir tillgängliga efter kontrollerad driftsättning och verifierad
              migrering.
            </p>
          )}
          {!access.canManage && <p>Du saknar behörighet att ändra detta kontos åtkomst.</p>}
          {!access.persistentIdentitySupported && !access.blacklisted && (
            <p>
              Ingen verifierad Google- eller Apple-identitet är kopplad. Kontot kan stängas av, men
              inte identitetssvartlistas.
            </p>
          )}
          <label className={styles.label} htmlFor={`access-reason-${uid}`}>
            Motivering som användaren kan se
          </label>
          <textarea
            id={`access-reason-${uid}`}
            className={styles.input}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <div className={styles.actions}>
            {(
              [
                access.suspended ? 'restore' : 'suspend',
                access.blacklisted ? 'unlist' : 'blacklist',
              ] as Action[]
            ).map((action) => (
              <button
                key={action}
                className={styles.secondaryButton}
                type="button"
                disabled={
                  busy ||
                  !reason.trim() ||
                  !access.ready ||
                  !access.canManage ||
                  (action === 'blacklist' && !access.persistentIdentitySupported)
                }
                onClick={() => setPending({ action, view: access, reason: reason.trim() })}
              >
                {labels[action]}
              </button>
            ))}
          </div>
        </>
      ) : (
        <p>Läser åtkomst…</p>
      )}
      <button
        className={styles.secondaryButton}
        type="button"
        disabled={busy}
        onClick={() => {
          setError(null);
          void load();
        }}
      >
        Uppdatera åtkomst
      </button>
      {success && <p role="status">{success}</p>}
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      {pending && (
        <ActionDialog
          cancelDisabled={busy}
          title={labels[pending.action]}
          onCancel={() => {
            if (!busy) setPending(null);
          }}
        >
          <p>{pending.view.displayName || uid}</p>
          <p>{pending.reason}</p>
          <p>
            {pending.action === 'restore'
              ? 'Svartlistningen behålls.'
              : pending.action === 'unlist'
                ? 'En separat avstängning behålls.'
                : pending.action === 'blacklist'
                  ? 'De kopplade Google- och Apple-identiteterna spärras i CarCommunity även efter kontoborttagning och återregistrering.'
                  : 'CarCommunity blir otillgängligt tills avstängningen hävs.'}
          </p>
          <button
            type="button"
            className={styles.dangerButton}
            disabled={busy}
            onClick={() => void confirm()}
          >
            {busy ? 'Sparar…' : 'Bekräfta beslut'}
          </button>
        </ActionDialog>
      )}
    </section>
  );
}
