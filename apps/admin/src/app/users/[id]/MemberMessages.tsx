import { useState } from 'react';
import { useAdminAuth } from '@/components/auth/FirebaseAuthProvider';
import {
  usePublicPages,
  type Message,
  type Target,
  targetOf,
  sourceName,
  stateName,
  date,
} from '@/features/public-moderation';
import { ModerationCase } from '@/app/moderation/ModerationCase';
import styles from '@/app/moderation/page.module.css';
export function MemberMessages({ uid }: { uid: string }) {
  const { user } = useAdminAuth();
  const [source, setSource] = useState('all'),
    [target, setTarget] = useState<Target | null>(null);
  const page = usePublicPages<Message>('admin-listMemberMessages', { targetUid: uid, source });
  return (
    <section className={styles.page}>
      <h2>Publika meddelanden</h2>
      <p className={styles.muted}>
        Medlemmens meddelanden i eventchatt och den publika communitychatten, även utan anmälan.
      </p>
      <label>
        Chatt{' '}
        <select
          value={source}
          onChange={(e) => {
            setTarget(null);
            setSource(e.target.value);
          }}
        >
          <option value="all">Alla publika chatter</option>
          <option value="event">Eventchatt</option>
          <option value="community">Publik communitychatt</option>
        </select>
      </label>
      {target ? (
        <>
          <button onClick={() => setTarget(null)}>Tillbaka till medlemmens meddelanden</button>
          <ModerationCase
            key={`${uid}:${JSON.stringify(target)}:${user?.uid}`}
            target={target}
            onChanged={() => void page.refresh()}
          />
        </>
      ) : (
        <>
          {page.rows.map((row) => (
            <button
              className={styles.caseRow}
              key={row.id}
              onClick={() => setTarget(targetOf(row))}
            >
              <strong>
                {sourceName(row.source)} · {stateName(row.state)}
              </strong>
              <span>{row.state === 'removed' ? 'Meddelandet är borttaget.' : row.text}</span>
              <small>{date(row.createdAt)}</small>
            </button>
          ))}
          {!page.rows.length && !page.loading && !page.error && (
            <p>Inga publika meddelanden finns tillgängliga.</p>
          )}
          <div className={styles.pager}>
            <small>{page.rows.length} meddelanden inlästa</small>
            {page.cursor && (
              <button disabled={page.loading} onClick={() => void page.more()}>
                Visa fler
              </button>
            )}
            <button disabled={page.loading} onClick={() => void page.refresh()}>
              Uppdatera
            </button>
          </div>
        </>
      )}
      {page.loading && <p role="status">Läser meddelanden…</p>}
      {page.error && <p role="alert">{page.error}</p>}
    </section>
  );
}
