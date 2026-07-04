import { useCallback, useEffect, useState } from 'react';
import {
  getFeatureFlagRows,
  loadFeatureFlagRows,
  setFeatureFlag,
  type FeatureFlagRow,
} from '@/features/feature-flags';
import styles from './page.module.css';

export default function FeatureFlagsPage() {
  const [rows, setRows] = useState<FeatureFlagRow[]>(getFeatureFlagRows());
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setRows(await loadFeatureFlagRows());
      setLoadError(null);
    } catch (error) {
      // Contract defaults stay on screen; flag the staleness.
      setLoadError(error instanceof Error ? error.message : 'Kunde inte läsa flaggorna.');
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const toggle = useCallback(
    async (row: FeatureFlagRow) => {
      const reason = window.prompt(
        `Ange orsak för att ${row.enabled ? 'stänga av' : 'aktivera'} "${row.key}" (audit-loggas):`,
      );
      if (reason === null) return; // cancelled
      setBusyKey(row.key);
      try {
        await setFeatureFlag(row.key, !row.enabled, reason.trim() || undefined);
        await refresh();
      } catch (error) {
        setLoadError(error instanceof Error ? error.message : 'Ändringen misslyckades.');
      } finally {
        setBusyKey(null);
      }
    },
    [refresh],
  );

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Feature Flags</h1>
        <p className={styles.subtitle}>
          Live values from <code>config/featureFlags</code>; unset flags show their contract
          defaults. Every change is audit-logged server-side.
        </p>
      </div>

      {loadError ? (
        <div className={styles.notice} role="alert">
          <span aria-hidden="true">⚠</span>
          <p>
            <strong>Kunde inte läsa/ändra flaggor:</strong> {loadError}
          </p>
        </div>
      ) : null}

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Current flags</h2>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">Flag key</th>
              <th scope="col">Status</th>
              <th scope="col">Source</th>
              <th scope="col">Edit</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key}>
                <td>
                  <span className={styles.flagKey}>{row.key}</span>
                </td>
                <td>
                  <span
                    className={`${styles.badge} ${row.enabled ? styles.badgeEnabled : styles.badgeDisabled}`}
                  >
                    {row.enabled ? 'Enabled' : 'Disabled'}
                  </span>
                </td>
                <td>{row.overridden ? 'Firestore' : 'Contract default'}</td>
                <td>
                  <button
                    type="button"
                    onClick={() => void toggle(row)}
                    disabled={busyKey !== null}
                  >
                    {busyKey === row.key
                      ? 'Sparar…'
                      : row.enabled
                        ? 'Stäng av'
                        : 'Aktivera'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
