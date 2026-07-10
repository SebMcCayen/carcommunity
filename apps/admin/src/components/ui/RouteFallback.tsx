import styles from './RouteFallback.module.css';

/**
 * Suspense fallback shown while a lazy-loaded route chunk is being fetched.
 *
 * Mirrors the loading paragraph the pages themselves render while fetching
 * data (secondary text with aria-live/aria-busy), so a chunk load and a data
 * load look identical to the user.
 *
 * Deliberately does NOT use the i18n `translate` helper: this component sits
 * in the eager entry graph, and importing the dictionaries here would pull
 * them back into the initial bundle and defeat route-level code splitting.
 * The literal matches the Swedish loading copy used across the app.
 */
export function RouteFallback() {
  return (
    <p className={styles.meta} aria-live="polite" aria-busy="true">
      Laddar...
    </p>
  );
}
