import styles from './HistoryPager.module.css';
interface HistoryPagerProps {
  shown: number;
  total: number;
  hasNext: boolean;
  loading: boolean;
  error?: string | null;
  onMore: () => void;
  onRefresh: () => void;
}
/** A failed continuation keeps its rows and cursor; refresh starts a fresh history. */
export function HistoryPager({
  shown,
  total,
  hasNext,
  loading,
  error,
  onMore,
  onRefresh,
}: HistoryPagerProps) {
  return (
    <div aria-label="Sidindelning" className={styles.pager}>
      <span className={styles.count} aria-live="polite">
        {loading && shown === 0
          ? 'Läser historik…'
          : error && shown === 0
            ? 'Historiken kunde inte läsas.'
            : `Visar ${shown} av ${total} poster`}
      </span>
      {error && (
        <span className={styles.error} role="alert">
          {error}
        </span>
      )}
      {hasNext && (
        <button
          className={styles.button}
          type="button"
          onClick={onMore}
          disabled={loading}
          aria-busy={loading}
        >
          {loading ? 'Läser…' : error ? 'Försök läsa fler igen' : 'Visa fler'}
        </button>
      )}
      <button className={styles.button} type="button" onClick={onRefresh} disabled={loading}>
        Uppdatera historiken
      </button>
    </div>
  );
}
