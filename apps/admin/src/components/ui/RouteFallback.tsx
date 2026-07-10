import { criticalStrings } from '@/i18n/criticalStrings';
import styles from './RouteFallback.module.css';

/**
 * Suspense fallback shown while a lazy-loaded route chunk is being fetched.
 *
 * Mirrors the loading paragraph the pages themselves render while fetching
 * data (secondary text with aria-live/aria-busy), so a chunk load and a data
 * load look identical to the user.
 *
 * This component sits in the eager entry graph, so it deliberately avoids the
 * i18n `translate` helper: importing the dictionaries here would pull them back
 * into the initial bundle and defeat route-level code splitting. Instead it
 * sources its copy from `criticalStrings` — a tiny, dictionary-free module of
 * strings safe to ship eagerly — whose value mirrors the app's `*.loading`
 * i18n key ("Laddar...").
 */
export function RouteFallback() {
  return (
    <p className={styles.meta} aria-live="polite" aria-busy="true">
      {criticalStrings.routeLoading}
    </p>
  );
}
