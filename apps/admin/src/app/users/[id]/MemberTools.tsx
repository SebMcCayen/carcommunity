import { useCallback, useRef, useState } from 'react';
import { callAdmin } from '@/lib/callables';
import { pendingOperation } from '@/lib/pendingOperation';
import { useCursorHistory } from '@/hooks/useCursorHistory';
import { HistoryPager } from '@/components/ui/HistoryPager';
import { ActionDialog } from '@/components/ui/ActionDialog';
import type { AdminUserDetail } from '@/features/users';
import styles from './page.module.css';
type Warning = {
  id: string;
  reason: string;
  internalNote: string;
  createdAt: string;
  expiresAt: string | null;
  status: 'active' | 'expired' | 'revoked';
  version: number;
};
const date = (value: string | null) =>
  value ? new Date(value).toLocaleString('sv-SE') : 'Tills den återkallas';
export function MemberTools({
  member,
  onChanged,
}: {
  member: AdminUserDetail;
  onChanged: () => void;
}) {
  const [name, setName] = useState(member.displayName),
    [reason, setReason] = useState(''),
    [note, setNote] = useState(''),
    [days, setDays] = useState('7');
  const [customDuration, setCustomDuration] = useState(false);
  const validDuration =
    days === '' ||
    (Number.isSafeInteger(Number(days)) && Number(days) >= 1 && Number(days) <= 3650);
  const [error, setError] = useState<string | null>(null),
    [success, setSuccess] = useState<string | null>(null),
    [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<{
    title: string;
    name: string;
    payload: Record<string, unknown>;
  } | null>(null);
  const working = useRef(false);
  const read = useCallback(
    async (cursor: string | null) => {
      const result = await callAdmin<{
        warnings: Warning[];
        meta: { total: number; hasNext: boolean; nextCursor: string | null };
      }>('admin-listWarnings', { targetUid: member.uid, cursor });
      return { items: result.warnings, meta: result.meta };
    },
    [member.uid],
  );
  const history = useCursorHistory(read, 'id');
  function review(action: string, title: string, payload: Record<string, unknown>) {
    if (!reason.trim()) return;
    setError(null);
    setPending({
      name: action,
      title,
      payload: {
        targetUid: member.uid,
        expectedActorUid: member.actorUid,
        reason: reason.trim(),
        ...payload,
      },
    });
  }
  async function confirm() {
    if (!pending || working.current || !member.actorUid) return;
    working.current = true;
    setBusy(true);
    setError(null);
    let saved = false;
    try {
      const receipt = pendingOperation(
        `${member.actorUid}:${member.uid}:${pending.name}`,
        pending.payload,
      );
      const result = await callAdmin<{ notificationStatus?: string }>(pending.name, {
        ...pending.payload,
        idempotencyKey: receipt.key,
      });
      saved = true;
      if (result.notificationStatus === 'failed') {
        setSuccess(
          'Varningen är sparad. Aviseringen misslyckades; bekräfta samma beslut igen för att försöka avisera på nytt.',
        );
      } else {
        receipt.clear();
        setPending(null);
        setReason('');
        setNote('');
        setSuccess('Beslutet är sparat.');
      }
      await history.refresh();
      if (pending.name === 'admin-updateDisplayName') onChanged();
    } catch (e) {
      setError(
        saved
          ? 'Beslutet är sparat, men vyn kunde inte uppdateras. Läs in historiken igen.'
          : (e as Error).message,
      );
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  return (
    <section className={styles.card} aria-labelledby="member-tools-title">
      <h2 id="member-tools-title" className={styles.cardTitle}>
        Profil och varningar
      </h2>
      <p className={styles.meta}>
        Varningens orsak och giltighet visas endast i medlemmens egen profil. Interna anteckningar
        visas bara för administratörer.
      </p>
      <label className={styles.label} htmlFor="member-name">
        Visningsnamn
      </label>
      <input
        id="member-name"
        className={styles.input}
        value={name}
        maxLength={120}
        onChange={(e) => setName(e.target.value)}
        disabled={busy}
      />
      <label className={styles.label} htmlFor="member-decision-reason">
        Orsak till beslut / varning till medlemmen
      </label>
      <textarea
        id="member-decision-reason"
        className={styles.input}
        maxLength={500}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        disabled={busy}
      />
      <button
        className={styles.secondaryButton}
        disabled={
          busy ||
          !member.permissions?.canRename ||
          !reason.trim() ||
          !name.trim() ||
          name.trim() === member.displayName
        }
        onClick={() =>
          review('admin-updateDisplayName', 'Ändra visningsnamn', {
            displayName: name.trim(),
            previousDisplayName: member.displayName,
            expectedActorUid: member.actorUid,
          })
        }
      >
        Granska namnbyte
      </button>
      <label className={styles.label} htmlFor="warning-note">
        Intern anteckning (visas inte för medlemmen)
      </label>
      <textarea
        id="warning-note"
        className={styles.input}
        maxLength={1000}
        value={note}
        onChange={(e) => setNote(e.target.value)}
        disabled={busy}
      />
      <label className={styles.label} htmlFor="warning-days">
        Varningens giltighet
      </label>
      <select
        id="warning-days"
        className={styles.input}
        value={customDuration ? 'custom' : days}
        onChange={(e) => {
          const custom = e.target.value === 'custom';
          setCustomDuration(custom);
          setDays(custom ? '14' : e.target.value);
        }}
        disabled={busy}
      >
        <option value="7">7 dagar</option>
        <option value="30">30 dagar</option>
        <option value="90">90 dagar</option>
        <option value="">Tills den återkallas</option>
        <option value="custom">Annan giltighetstid</option>
      </select>
      {customDuration && (
        <>
          <label className={styles.label} htmlFor="warning-custom-days">
            Antal dagar (1–3650)
          </label>
          <input
            id="warning-custom-days"
            className={styles.input}
            type="number"
            min={1}
            max={3650}
            step={1}
            value={days}
            onChange={(e) => setDays(e.target.value)}
            disabled={busy}
          />
        </>
      )}
      <button
        className={styles.primaryButton}
        disabled={busy || !validDuration || !member.permissions?.canWarn || !reason.trim()}
        onClick={() =>
          review('admin-warnUser', 'Utfärda varning', {
            internalNote: note.trim(),
            ...(days ? { durationDays: Number(days) } : { expiresAt: null }),
          })
        }
      >
        Granska varning
      </button>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      {success && (
        <p className={styles.success} role="status">
          {success}
        </p>
      )}
      {pending && (
        <ActionDialog
          title={pending.title}
          onCancel={() => {
            if (!busy) setPending(null);
          }}
        >
          <p>
            {member.displayName} · {member.uid}
          </p>
          <p>{String(pending.payload.reason)}</p>
          {pending.name === 'admin-updateDisplayName' ? (
            <p>
              {member.displayName} → {String(pending.payload.displayName)}
            </p>
          ) : (
            <p>
              Giltighet:{' '}
              {pending.payload.durationDays
                ? `${pending.payload.durationDays} dagar`
                : pending.name === 'admin-revokeWarning'
                  ? 'Återkallas nu'
                  : 'Tills den återkallas'}
            </p>
          )}
          <button className={styles.primaryButton} onClick={() => void confirm()} disabled={busy}>
            {busy ? 'Sparar…' : 'Bekräfta beslut'}
          </button>
        </ActionDialog>
      )}
      <h3>Varningshistorik</h3>
      <p className={styles.meta}>
        Äldre modereringsbeslut utan giltighet finns i den befintliga granskningsloggen. De
        aktiveras inte retroaktivt.
      </p>
      {history.items.map((w) => (
        <article key={w.id} className={styles.card}>
          <strong>
            {w.status === 'active'
              ? 'Aktiv varning'
              : w.status === 'expired'
                ? 'Utgången varning'
                : 'Återkallad varning'}
          </strong>
          <p>{w.reason}</p>
          <p>
            Utfärdad: {date(w.createdAt)} · Giltig till: {date(w.expiresAt)}
          </p>
          {w.internalNote && <p>Intern anteckning: {w.internalNote}</p>}
          {w.status !== 'revoked' && (
            <div className={styles.actions}>
              <button
                className={styles.secondaryButton}
                disabled={busy || !reason.trim() || !member.permissions?.canWarn}
                onClick={() =>
                  review('admin-setWarningExpiry', 'Ändra varningens giltighet', {
                    warningId: w.id,
                    expectedVersion: w.version,
                    ...(days ? { durationDays: Number(days) } : { expiresAt: null }),
                  })
                }
              >
                Ändra giltighet enligt valet ovan
              </button>
              <button
                className={styles.dangerButton}
                disabled={busy || !reason.trim() || !member.permissions?.canWarn}
                onClick={() =>
                  review('admin-revokeWarning', 'Återkalla varning', {
                    warningId: w.id,
                    expectedVersion: w.version,
                  })
                }
              >
                Återkalla
              </button>
            </div>
          )}
        </article>
      ))}
      {!history.loading && !history.error && history.items.length === 0 && (
        <p>Inga varningar har registrerats.</p>
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
