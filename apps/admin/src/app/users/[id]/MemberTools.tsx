import { useCallback, useEffect, useRef, useState } from 'react';
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
  remainingMillis?: number | null;
  deadline?: number;
};
const warningStatus = (warning: Warning) =>
  warning.status === 'active' &&
  warning.deadline !== undefined &&
  performance.now() >= warning.deadline
    ? 'expired'
    : warning.status;
const date = (value: string | null) =>
  value ? new Date(value).toLocaleString('sv-SE') : 'Tills den återkallas';
export function MemberTools({
  member,
  onChanged,
  section = 'all',
}: {
  member: AdminUserDetail;
  onChanged: () => void;
  section?: 'all' | 'profile' | 'warnings';
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
    context?: string;
  } | null>(null);
  const working = useRef(false);
  const read = useCallback(
    async (cursor: string | null) => {
      const started = performance.now();
      if (section === 'profile') return { items: [], meta: { total: 0 } };
      const result = await callAdmin<{
        warnings: Warning[];
        meta: { total: number; hasNext: boolean; nextCursor: string | null };
      }>('admin-listWarnings', { targetUid: member.uid, cursor });
      return {
        items: result.warnings.map((warning) => ({
          ...warning,
          deadline: warning.remainingMillis == null ? undefined : started + warning.remainingMillis,
        })),
        meta: result.meta,
      };
    },
    [member.uid, section],
  );
  const history = useCursorHistory(read, 'id');
  const [clockVersion, setClockVersion] = useState(0);
  useEffect(() => {
    const deadlines = history.items
      .filter(
        (warning) =>
          warning.status === 'active' &&
          warning.deadline !== undefined &&
          warning.deadline > performance.now(),
      )
      .map((warning) => warning.deadline!);
    if (!deadlines.length) return;
    const timer = window.setTimeout(
      () => setClockVersion((value) => value + 1),
      Math.min(2147483647, Math.max(1, Math.min(...deadlines) - performance.now() + 1)),
    );
    return () => window.clearTimeout(timer);
  }, [history.items, clockVersion]);
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
  function reviewWarning(warning: Warning, revoke: boolean) {
    setError(null);
    setPending({
      name: revoke ? 'admin-revokeWarning' : 'admin-setWarningExpiry',
      title: revoke ? 'Återkalla varning' : 'Ändra varningens giltighet',
      context: warning.reason,
      payload: {
        targetUid: member.uid,
        expectedActorUid: member.actorUid,
        warningId: warning.id,
        expectedVersion: warning.version,
        reason: '',
        ...(revoke ? {} : { durationDays: 7 }),
      },
    });
  }
  const lifecycleDecision =
    pending?.name === 'admin-revokeWarning' || pending?.name === 'admin-setWarningExpiry';
  const pendingDuration = pending?.payload.durationDays;
  const validPending =
    !!pending &&
    String(pending.payload.reason ?? '').trim().length > 0 &&
    (pending.name !== 'admin-setWarningExpiry' ||
      pending.payload.expiresAt === null ||
      (Number.isSafeInteger(pendingDuration) &&
        Number(pendingDuration) >= 1 &&
        Number(pendingDuration) <= 3650));
  async function confirm() {
    if (!pending || !validPending || working.current || !member.actorUid) return;
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
        {section === 'profile'
          ? 'Ändra visningsnamn'
          : section === 'warnings'
            ? 'Varningar'
            : 'Profil och varningar'}
      </h2>
      <p className={styles.meta}>
        {section === 'profile'
          ? 'Namnbytet uppdaterar profilen. Tidigare chattmeddelanden behåller sina sparade avsändarnamn.'
          : 'Varningens orsak och giltighet visas endast i medlemmens egen profil. Interna anteckningar visas bara för administratörer.'}
      </p>
      {section !== 'warnings' && (
        <>
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
        </>
      )}
      <label className={styles.label} htmlFor="member-decision-reason">
        {section === 'profile' ? 'Motivering till namnbytet' : 'Varning som medlemmen ska se'}
      </label>
      <textarea
        id="member-decision-reason"
        className={styles.input}
        maxLength={500}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        disabled={busy}
      />
      {section !== 'warnings' && (
        <>
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
        </>
      )}
      {section !== 'profile' && (
        <>
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
        </>
      )}
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
          cancelDisabled={busy}
          title={pending.title}
          onCancel={() => {
            if (!busy) setPending(null);
          }}
        >
          <p>
            {member.displayName} · {member.uid}
          </p>
          {lifecycleDecision ? (
            <>
              <p>{pending.context}</p>
              <label className={styles.label} htmlFor="warning-decision-reason">
                Motivering till beslutet (sparas i adminloggen)
              </label>
              <textarea
                id="warning-decision-reason"
                className={styles.input}
                maxLength={500}
                disabled={busy}
                value={String(pending.payload.reason ?? '')}
                onChange={(event) =>
                  setPending({
                    ...pending,
                    payload: { ...pending.payload, reason: event.target.value },
                  })
                }
              />
              {pending.name === 'admin-setWarningExpiry' && (
                <>
                  <label className={styles.label} htmlFor="warning-new-days">
                    Ny giltighet från beslutstidpunkten, antal dagar (1–3650)
                  </label>
                  <input
                    id="warning-new-days"
                    className={styles.input}
                    type="number"
                    min={1}
                    max={3650}
                    step={1}
                    disabled={busy || pending.payload.expiresAt === null}
                    value={pendingDuration === undefined ? '' : String(pendingDuration)}
                    onChange={(event) => {
                      const { expiresAt: _expiresAt, ...payload } = pending.payload;
                      void _expiresAt;
                      setPending({
                        ...pending,
                        payload: { ...payload, durationDays: Number(event.target.value) },
                      });
                    }}
                  />
                  <label>
                    <input
                      type="checkbox"
                      checked={pending.payload.expiresAt === null}
                      disabled={busy}
                      onChange={(event) => {
                        const {
                          expiresAt: _expiresAt,
                          durationDays: _durationDays,
                          ...payload
                        } = pending.payload;
                        void _expiresAt;
                        void _durationDays;
                        setPending({
                          ...pending,
                          payload: {
                            ...payload,
                            ...(event.target.checked ? { expiresAt: null } : { durationDays: 7 }),
                          },
                        });
                      }}
                    />{' '}
                    Tills den återkallas
                  </label>
                </>
              )}
            </>
          ) : (
            <p>{String(pending.payload.reason)}</p>
          )}
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
          <button
            className={styles.primaryButton}
            onClick={() => void confirm()}
            disabled={busy || !validPending}
          >
            {busy ? 'Sparar…' : 'Bekräfta beslut'}
          </button>
        </ActionDialog>
      )}
      {section !== 'profile' && (
        <>
          <h3>Varningshistorik</h3>
          <p className={styles.meta}>
            Äldre modereringsbeslut utan giltighet finns i den befintliga granskningsloggen. De
            aktiveras inte retroaktivt.
          </p>
          {history.items.map((w) => (
            <article key={w.id} className={styles.card}>
              <strong>
                {warningStatus(w) === 'active'
                  ? 'Aktiv varning'
                  : warningStatus(w) === 'expired'
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
                    disabled={busy || !member.permissions?.canWarn}
                    onClick={() => reviewWarning(w, false)}
                  >
                    Ändra giltighet
                  </button>
                  <button
                    className={styles.dangerButton}
                    disabled={busy || !member.permissions?.canWarn}
                    onClick={() => reviewWarning(w, true)}
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
          />{' '}
        </>
      )}
    </section>
  );
}
