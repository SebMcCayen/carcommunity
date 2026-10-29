import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, MessageSquare, Clock } from 'lucide-react';
import { useAdminAuth } from '@/components/auth/FirebaseAuthProvider';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { callAdmin } from '@/lib/callables';
import { pendingOperation } from '@/lib/pendingOperation';
import {
  type Target,
  type Case,
  sourceName,
  stateName,
  reasonName,
  date,
} from '@/features/public-moderation';
import styles from './page.module.css';
const actions = {
  review: 'Ta under granskning',
  resolve: 'Avsluta anmälan',
  dismiss: 'Avvisa anmälan',
  allow: 'Tillåt meddelandet',
  remove: 'Ta bort meddelandet',
  cleanup: 'Rensa kvarvarande citat',
};
type Action = keyof typeof actions;
export function ModerationCase({ target, onChanged }: { target: Target; onChanged: () => void }) {
  const { user } = useAdminAuth();
  const [detail, setDetail] = useState<Case | null>(null),
    [error, setError] = useState<string | null>(null),
    [loading, setLoading] = useState(true),
    [reason, setReason] = useState(''),
    [action, setAction] = useState<Action | null>(null),
    [busy, setBusy] = useState(false),
    [success, setSuccess] = useState('');
  const [cleanup, setCleanup] = useState<{ payload: Record<string, unknown>; key: string } | null>(
    null,
  );
  const [attempt, setAttempt] = useState(0);
  const alive = useRef(true),
    working = useRef(false);
  const scope = JSON.stringify([target, user?.uid]);
  useEffect(() => {
    alive.current = true;
    let current = true;
    setDetail(null);
    setError(null);
    setLoading(true);
    void callAdmin<Case>('admin-getPublicModerationCase', target)
      .then((d) => {
        if (current) setDetail(d);
      })
      .catch((e) => {
        if (current) setError(e instanceof Error ? e.message : 'Kunde inte läsa underlaget.');
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
      alive.current = false;
    };
    // target and auth form the immutable scope of this keyed component.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope, attempt]);
  const heading = useRef<HTMLHeadingElement>(null),
    focused = useRef(false);
  useEffect(() => {
    if (!loading && detail && !focused.current && heading.current) {
      focused.current = true;
      heading.current.focus({
        preventScroll: window.matchMedia?.('(min-width: 761px)').matches ?? true,
      });
    }
  }, [loading, detail]);
  async function decide(retry = false) {
    if (!detail || working.current || user?.uid !== detail.actorUid) return;
    working.current = true;
    setBusy(true);
    setError(null);
    try {
      const payload =
        retry && cleanup
          ? cleanup.payload
          : {
              target,
              expectedActorUid: detail.actorUid,
              expectedVersion: detail.version,
              action,
              reason: reason.trim(),
            };
      const operation = pendingOperation(`public-moderation:${detail.actorUid}`, payload);
      const result = await callAdmin<{ cleanup: { hasMore: boolean } }>(
        'admin-decidePublicModeration',
        { ...payload, idempotencyKey: operation.key },
      );
      if (!alive.current) return;
      if (result.cleanup.hasMore) {
        setCleanup({ payload, key: operation.key });
        setSuccess('Meddelandet är borttaget. Fler citat återstår att rensa.');
      } else {
        operation.clear();
        setCleanup(null);
        setSuccess('Beslutet har sparats.');
      }
      setAction(null);
      setReason('');
      const updated = await callAdmin<Case>('admin-getPublicModerationCase', target);
      if (alive.current) setDetail(updated);
      if (alive.current) onChanged();
    } catch (e) {
      if (alive.current)
        setError(
          e instanceof Error
            ? e.message
            : 'Beslutet kunde inte bekräftas. Försök samma beslut igen.',
        );
    } finally {
      working.current = false;
      if (alive.current) setBusy(false);
    }
  }
  if (loading)
    return (
      <section className={styles.detail} aria-busy="true">
        Läser granskningsunderlag…
      </section>
    );
  if (!detail)
    return (
      <section className={styles.detail}>
        <p role="alert">{error}</p>
        <button onClick={() => setAttempt((v) => v + 1)}>Försök läsa underlaget igen</button>
      </section>
    );
  const message = detail.message,
    removed = !message.exists || message.state === 'removed';
  return (
    <section className={styles.detail} aria-label="Granskningsunderlag">
      <div className={styles.caseHeading}>
        <div>
          <span className={styles.eyebrow}>{sourceName(target.source)}</span>
          <h2 ref={heading} tabIndex={-1}>
            {target.source === 'event'
              ? `Eventchatt · ${detail.eventTitle || target.eventId}`
              : 'Community · Publik kanal'}
          </h2>
        </div>
        <ShieldCheck size={24} />
      </div>
      <div className={styles.messageHead}>
        <span className={styles.avatar}>
          {message.authorName.slice(0, 2).toLocaleUpperCase('sv-SE') || '?'}
        </span>
        <div>
          {message.authorUid ? (
            <Link to={`/users/${encodeURIComponent(message.authorUid)}?section=messages`}>
              {message.authorName || 'Medlem'}
            </Link>
          ) : (
            <strong>Avsändare saknas</strong>
          )}
          <small>{date(message.createdAt)}</small>
        </div>
        <span className={styles.badge}>{stateName(message.state)}</span>
      </div>
      <blockquote className={styles.message}>
        {removed ? 'Meddelandet är borttaget.' : message.text || 'Innehåll saknas.'}
      </blockquote>
      {detail.report && (
        <div className={styles.reportInfo}>
          <strong>{reasonName(detail.report.reason)}</strong>
          <p>{detail.report.details || 'Ingen ytterligare beskrivning.'}</p>
          <small>
            Anmälan: {stateName(detail.report.status)} · {date(detail.report.createdAt)}
          </small>
        </div>
      )}
      <section className={styles.context}>
        <h3>
          <MessageSquare size={16} /> Sammanhang i samma chatt
        </h3>
        {detail.context.length ? (
          detail.context.map((row) => (
            <div key={row.messageId}>
              <small>
                {row.authorName || 'Medlem'} · {date(row.createdAt)}
              </small>
              <p>{row.state === 'removed' ? 'Meddelandet är borttaget.' : row.text}</p>
            </div>
          ))
        ) : (
          <p className={styles.muted}>Inget tidigare meddelande finns tillgängligt.</p>
        )}
      </section>
      <section className={styles.decision}>
        <h3>Beslut</h3>
        <p className={styles.muted}>
          Meddelandets synlighet och anmälans status visas separat. Andra anmälningar avslutas var
          för sig.
        </p>
        <label htmlFor="moderation-reason">Orsak till beslutet *</label>
        <textarea
          id="moderation-reason"
          maxLength={500}
          rows={3}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={busy || !!cleanup}
        />
        <div className={styles.actions}>
          {((detail.report ? ['review', 'dismiss', 'resolve'] : []) as Action[]).map((a) => (
            <button
              key={a}
              disabled={!reason.trim() || busy || !!cleanup}
              onClick={() => setAction(a)}
            >
              {actions[a]}
            </button>
          ))}
          {!removed && target.source === 'event' && (
            <button
              disabled={!reason.trim() || busy || !!cleanup}
              onClick={() => setAction('allow')}
            >
              {actions.allow}
            </button>
          )}
          {!removed && (
            <button
              className={styles.danger}
              disabled={!reason.trim() || busy || !!cleanup}
              onClick={() => setAction('remove')}
            >
              {actions.remove}
            </button>
          )}
          {removed && target.source === 'community' && (
            <button
              disabled={!reason.trim() || busy || !!cleanup}
              onClick={() => setAction('cleanup')}
            >
              {actions.cleanup}
            </button>
          )}
        </div>
        {success && <p role="status">{success}</p>}
        {cleanup && (
          <button disabled={busy} onClick={() => void decide(true)}>
            {busy ? 'Rensar…' : 'Fortsätt rensa citat'}
          </button>
        )}
        {error && (
          <p role="alert" className={styles.error}>
            {error}
          </p>
        )}
      </section>
      <section className={styles.history}>
        <h3>
          <Clock size={16} /> Beslutshistorik
        </h3>
        {detail.history.length ? (
          detail.history.map((row) => (
            <p key={row.id}>
              <strong>
                {actions[row.action.replace('publicChat.', '') as Action] ?? 'Modereringsbeslut'}
              </strong>
              <br />
              {row.reason}
              <small>{date(row.createdAt)}</small>
            </p>
          ))
        ) : (
          <p className={styles.muted}>Historik saknas.</p>
        )}
      </section>
      {action && (
        <ActionDialog
          title={actions[action]}
          onCancel={() => setAction(null)}
          cancelDisabled={busy}
        >
          <p>
            {message.authorName || 'Medlem'} · {sourceName(target.source)}
          </p>
          <blockquote>{removed ? 'Borttaget meddelande' : message.text}</blockquote>
          <p>
            <strong>Orsak:</strong> {reason.trim()}
          </p>
          <p>
            {action === 'remove'
              ? 'Texten tas bort från chatten. Åtgärden kan inte ångras. Publika citat rensas i begränsade omgångar.'
              : `Anmälan och meddelandet uppdateras enligt beslutet ”${actions[action]}”.`}
          </p>
          <button
            className={action === 'remove' ? styles.danger : styles.primary}
            disabled={busy || user?.uid !== detail.actorUid}
            onClick={() => void decide()}
          >
            {busy ? 'Sparar…' : 'Bekräfta beslut'}
          </button>
        </ActionDialog>
      )}
    </section>
  );
}
