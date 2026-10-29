import { useState } from 'react';
import { Search, RefreshCw, MessageSquare, ChevronRight } from 'lucide-react';
import { useAdminAuth } from '@/components/auth/FirebaseAuthProvider';
import {
  usePublicPages,
  type Report,
  type Target,
  targetOf,
  sourceName,
  reasonName,
  date,
} from '@/features/public-moderation';
import { ModerationCase } from './ModerationCase';
import styles from './page.module.css';
export default function ModerationPage() {
  const { user } = useAdminAuth();
  const [status, setStatus] = useState('open'),
    [source, setSource] = useState('all'),
    [search, setSearch] = useState(''),
    [target, setTarget] = useState<Target | null>(() => {
      const params = new URLSearchParams(window.location.search),
        source = params.get('caseSource'),
        messageId = params.get('messageId'),
        reportId = params.get('reportId'),
        eventId = params.get('eventId');
      return (source === 'event' || source === 'community') && messageId
        ? {
            source,
            messageId,
            ...(source === 'event' && eventId ? { eventId } : {}),
            ...(reportId ? { reportId } : {}),
          }
        : null;
    });
  const page = usePublicPages<Report>('admin-listPublicReports', { status, source });
  const rows = page.rows.filter((r) =>
    `${r.messageId} ${r.details} ${reasonName(r.reason)} ${r.eventId ?? ''} ${r.authorName} ${r.eventTitle ?? ''}`
      .toLocaleLowerCase('sv-SE')
      .includes(search.toLocaleLowerCase('sv-SE')),
  );
  const clear = () => {
    setTarget(null);
    setSearch('');
  };
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1>Modereringskö</h1>
          <p>Granska anmälningar från event och publika kanaler.</p>
        </div>
        <button
          onClick={() => {
            clear();
            void page.refresh();
          }}
          disabled={page.loading}
        >
          <RefreshCw size={16} /> Uppdatera
        </button>
      </header>
      <div className={styles.tabs} aria-label="Anmälningsstatus">
        {(
          [
            ['open', 'Öppna'],
            ['review', 'Under granskning'],
            ['closed', 'Avslutade'],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            aria-pressed={status === value}
            className={status === value ? styles.selected : ''}
            onClick={() => {
              clear();
              setStatus(value);
            }}
          >
            {label}
          </button>
        ))}
      </div>
      <div className={styles.workspace}>
        <section className={styles.queue} aria-label="Anmälningar">
          <div className={styles.filters}>
            <label>
              <Search size={16} /> Sök bland inlästa anmälningar
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Meddelande-id, event eller beskrivning"
              />
            </label>
            <label>
              Chatt
              <select
                value={source}
                onChange={(e) => {
                  clear();
                  setSource(e.target.value);
                }}
              >
                <option value="all">Alla publika chatter</option>
                <option value="event">Eventchatt</option>
                <option value="community">Publik communitychatt</option>
              </select>
            </label>
          </div>
          {rows.map((row) => (
            <button
              key={row.id}
              aria-pressed={JSON.stringify(target) === JSON.stringify(targetOf(row))}
              className={styles.caseRow}
              onClick={() => setTarget(targetOf(row))}
            >
              <span className={styles.eyebrow}>{sourceName(row.source)}</span>
              <strong>{row.authorName || 'Avsändare saknas'}</strong>
              <span>
                {row.eventTitle || 'Publik communitychatt'} · {reasonName(row.reason)}
              </span>
              <span>{row.details || 'Ingen beskrivning'}</span>
              <small>
                {date(row.createdAt)} <ChevronRight size={14} />
              </small>
            </button>
          ))}
          {!page.loading && !rows.length && !page.error && (
            <p className={styles.empty}>
              {search
                ? 'Ingen träff bland inlästa anmälningar.'
                : 'Inga anmälningar med vald status.'}
            </p>
          )}
          {page.loading && <p role="status">Läser anmälningar…</p>}
          {page.error && (
            <p role="alert" className={styles.error}>
              {page.error}
            </p>
          )}
          <div className={styles.pager}>
            <small>{page.rows.length} anmälningar inlästa</small>
            {page.cursor && (
              <button disabled={page.loading} onClick={() => void page.more()}>
                {page.error ? 'Försök läsa fler igen' : 'Visa fler'}
              </button>
            )}
            {page.error && !page.cursor && (
              <button onClick={() => void page.refresh()}>Försök igen</button>
            )}
          </div>
        </section>
        {target ? (
          <ModerationCase
            key={`${JSON.stringify(target)}:${user?.uid}`}
            target={target}
            onChanged={() => {
              void page.refresh();
            }}
          />
        ) : (
          <section className={`${styles.detail} ${styles.placeholder}`}>
            <MessageSquare size={32} />
            <h2>Välj en anmälan</h2>
            <p>Här visas meddelandet, sammanhanget och tidigare beslut.</p>
          </section>
        )}
      </div>
    </div>
  );
}
