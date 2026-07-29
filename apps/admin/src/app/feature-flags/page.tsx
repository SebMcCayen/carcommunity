import { useCallback, useEffect, useState } from 'react';
import {
  getFeatureFlagRows,
  loadFeatureFlagRows,
  setFeatureFlag,
  type FeatureFlagRow,
} from '@/features/feature-flags';
import styles from './page.module.css';

const SENSITIVITY_LABEL: Record<FeatureFlagRow['sensitivity'], string | null> = {
  safety: 'Säkerhet',
  privacy: 'Integritet',
  standard: null,
};

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
      // Extra step only when ENABLING a safety-relevant flag. Turning one off
      // is the emergency action and stays a single click plus the reason
      // prompt — a kill switch behind a maze is not a kill switch.
      if (!row.enabled && row.sensitivity === 'safety') {
        const confirmed = window.confirm(
          `"${row.label}" (${row.key}) påverkar var medlemmar bjuds in att köra och stanna.\n\n${row.description}\n\nAktivera ändå?`,
        );
        if (!confirmed) return;
      }
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
          Every flag in <code>contracts/features/feature-flags.json</code> — the same list the
          backend honours. Live values come from <code>config/featureFlags</code>; unset flags show
          their contract defaults. Every change is audit-logged server-side.
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
        <h2 className={styles.sectionTitle}>Current flags ({rows.length})</h2>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">Flag</th>
              <th scope="col">Status</th>
              <th scope="col">Source</th>
              <th scope="col">Edit</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const sensitivityLabel = SENSITIVITY_LABEL[row.sensitivity];
              return (
                <tr
                  key={row.key}
                  className={row.sensitivity === 'safety' ? styles.rowSafety : undefined}
                >
                  <td>
                    <div className={styles.flagLabelRow}>
                      <strong className={styles.flagLabel}>{row.label}</strong>
                      {sensitivityLabel ? (
                        <span
                          className={`${styles.tag} ${
                            row.sensitivity === 'safety' ? styles.tagSafety : styles.tagPrivacy
                          }`}
                        >
                          {sensitivityLabel}
                        </span>
                      ) : null}
                    </div>
                    <span className={styles.flagKey}>{row.key}</span>
                    <p className={styles.flagDescription}>{row.description}</p>
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
                      {busyKey === row.key ? 'Sparar…' : row.enabled ? 'Stäng av' : 'Aktivera'}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>
    </div>
  );
}
