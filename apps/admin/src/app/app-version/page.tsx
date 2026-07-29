import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  loadAppVersionConfig,
  setAppVersion,
  type AppVersionConfig,
} from '@/features/app-version';
import styles from './page.module.css';

/**
 * Operator page for the in-app update prompt.
 *
 * The Android app compares its own versionCode against `latestVersionCode`
 * here, so this must be updated at every Play release or the prompt never
 * fires. `minimumSupportedVersionCode` is the separate, non-dismissible
 * block — leave it at 0 unless an old build genuinely must be stopped.
 */
export default function AppVersionPage() {
  const [config, setConfig] = useState<AppVersionConfig | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [latestVersionCode, setLatestVersionCode] = useState('');
  const [latestVersionName, setLatestVersionName] = useState('');
  const [minimumSupportedVersionCode, setMinimumSupportedVersionCode] = useState('');
  const [reason, setReason] = useState('');

  const refresh = useCallback(async () => {
    try {
      const next = await loadAppVersionConfig();
      setConfig(next);
      // Pre-fill from the published values so a partial edit cannot silently
      // reset a field the operator did not mean to touch.
      setLatestVersionCode(next ? String(next.latestVersionCode) : '');
      setLatestVersionName(next?.latestVersionName ?? '');
      setMinimumSupportedVersionCode(
        next && next.minimumSupportedVersionCode > 0
          ? String(next.minimumSupportedVersionCode)
          : '',
      );
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Kunde inte läsa appversionen.');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const submit = useCallback(
    async (event: FormEvent) => {
      event.preventDefault();
      const latest = Number(latestVersionCode);
      const minimum = minimumSupportedVersionCode ? Number(minimumSupportedVersionCode) : 0;
      if (!Number.isInteger(latest) || latest < 1) {
        setError('latestVersionCode måste vara ett heltal ≥ 1.');
        return;
      }
      if (minimum > 0 && !window.confirm(BLOCK_CONFIRMATION)) return;
      setSaving(true);
      try {
        await setAppVersion({
          latestVersionCode: latest,
          latestVersionName: latestVersionName.trim() || undefined,
          minimumSupportedVersionCode: minimum > 0 ? minimum : undefined,
          reason: reason.trim() || undefined,
        });
        setReason('');
        await refresh();
      } catch (saveError) {
        setError(saveError instanceof Error ? saveError.message : 'Publiceringen misslyckades.');
      } finally {
        setSaving(false);
      }
    },
    [latestVersionCode, latestVersionName, minimumSupportedVersionCode, reason, refresh],
  );

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>App version</h1>
        <p className={styles.subtitle}>
          Live values from <code>config/appVersion</code>. The Android app prompts to update when
          its own <code>versionCode</code> is BELOW <code>latestVersionCode</code>, so this must be
          set after every Play release — otherwise nobody is ever prompted. Every change is
          audit-logged server-side.
        </p>
      </div>

      {error ? (
        <div className={styles.notice} role="alert">
          <span aria-hidden="true">⚠</span>
          <p>
            <strong>Fel:</strong> {error}
          </p>
        </div>
      ) : null}

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Published</h2>
        {!loaded ? (
          <p>Läser…</p>
        ) : config ? (
          <dl className={styles.definitions}>
            <dt>latestVersionCode</dt>
            <dd>{config.latestVersionCode}</dd>
            <dt>latestVersionName</dt>
            <dd>{config.latestVersionName ?? '—'}</dd>
            <dt>minimumSupportedVersionCode</dt>
            <dd>
              {config.minimumSupportedVersionCode === 0
                ? '0 (inget blockeras)'
                : config.minimumSupportedVersionCode}
            </dd>
          </dl>
        ) : (
          <p>Ingen version publicerad ännu — appen visar ingen uppdateringsruta.</p>
        )}
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Publish</h2>
        <form className={styles.form} onSubmit={(event) => void submit(event)}>
          <label className={styles.field}>
            <span>latestVersionCode</span>
            <input
              type="number"
              min={1}
              step={1}
              required
              value={latestVersionCode}
              onChange={(event) => setLatestVersionCode(event.target.value)}
            />
            <small>Heltalet från apps/android/app/build.gradle.kts i den släppta builden.</small>
          </label>

          <label className={styles.field}>
            <span>latestVersionName</span>
            <input
              type="text"
              maxLength={32}
              placeholder="0.8.12"
              value={latestVersionName}
              onChange={(event) => setLatestVersionName(event.target.value)}
            />
            <small>Visas bara i dialogen. Jämförs aldrig.</small>
          </label>

          <label className={styles.field}>
            <span>minimumSupportedVersionCode</span>
            <input
              type="number"
              min={0}
              step={1}
              placeholder="0"
              value={minimumSupportedVersionCode}
              onChange={(event) => setMinimumSupportedVersionCode(event.target.value)}
            />
            <small>
              Lämna tomt (= 0) i normalfallet. Ett värde &gt; 0 låser äldre builds med en dialog
              som INTE går att stänga.
            </small>
          </label>

          <label className={styles.field}>
            <span>Orsak (audit-loggas)</span>
            <input
              type="text"
              maxLength={500}
              placeholder="Release 0.8.12"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>

          <button type="submit" disabled={saving}>
            {saving ? 'Sparar…' : 'Publicera'}
          </button>
        </form>
      </section>
    </div>
  );
}

const BLOCK_CONFIRMATION =
  'Ett minimum > 0 spärrar äldre installationer med en dialog som inte går att stänga. Fortsätta?';
