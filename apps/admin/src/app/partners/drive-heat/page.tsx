import { useCallback, useEffect, useRef, useState } from 'react';

import { DriveHeatMap } from '@/components/map/DriveHeatMap';
import { loadDriveHeat, type DriveHeatCell } from '@/features/partner-drive-heat';
import { translate } from '@/i18n';

import styles from '../../kronjakt/page.module.css';

const t = (key: string) => translate('sv', key);
const fmt = (key: string, params: Record<string, string | number>): string =>
  Object.entries(params).reduce((acc, [k, v]) => acc.replace(`{${k}}`, String(v)), t(key));

/** Band-label lookup passed to the map legend. */
const BAND_LABELS: Record<string, string> = {
  'driveHeat.bandLow': t('driveHeat.bandLow'),
  'driveHeat.bandModerate': t('driveHeat.bandModerate'),
  'driveHeat.bandBusy': t('driveHeat.bandBusy'),
  'driveHeat.bandHigh': t('driveHeat.bandHigh'),
  'driveHeat.bandVeryHigh': t('driveHeat.bandVeryHigh'),
};

function formatGeneratedAt(iso: string | null): string {
  if (!iso) return t('driveHeat.neverRun');
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return t('driveHeat.neverRun');
  return date.toLocaleString('sv-SE');
}

export default function PartnerDriveHeatPage() {
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const [cells, setCells] = useState<DriveHeatCell[]>([]);
  const [windowDays, setWindowDays] = useState<number>(90);
  const [generatedAt, setGeneratedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await loadDriveHeat();
      if (!mountedRef.current) return;
      setCells(result.cells);
      setWindowDays(result.windowDays);
      setGeneratedAt(result.generatedAt);
    } catch {
      if (!mountedRef.current) return;
      setError(t('driveHeat.error'));
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{t('driveHeat.pageTitle')}</h1>
        <p className={styles.description}>{t('driveHeat.pageDescription')}</p>
        <p className={styles.description}>{t('driveHeat.privacyNote')}</p>
      </div>

      {loading ? <p className={styles.loadingText}>{t('driveHeat.loading')}</p> : null}

      {!loading && error ? (
        <p className={styles.errorText}>
          {error}{' '}
          <button type="button" className={styles.linkButton} onClick={() => void load()}>
            {t('driveHeat.retry')}
          </button>
        </p>
      ) : null}

      {!loading && !error ? (
        <>
          <p className={styles.introText}>
            {fmt('driveHeat.meta', {
              windowDays,
              generatedAt: formatGeneratedAt(generatedAt),
              cells: cells.length,
            })}
          </p>

          {cells.length === 0 ? (
            <p className={styles.emptyText}>{t('driveHeat.sparse')}</p>
          ) : (
            <DriveHeatMap
              cells={cells}
              labels={{
                attribution: t('driveHeat.attribution'),
                unavailable: t('map.unavailable'),
                loadError: t('map.loadError'),
                legendTitle: t('driveHeat.legendTitle'),
                legendNote: t('driveHeat.legendNote'),
                bands: BAND_LABELS,
              }}
            />
          )}
        </>
      ) : null}
    </div>
  );
}
