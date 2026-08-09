'use client';

/**
 * Auto-spawn DIAGNOSTICS panel — the read-only troubleshooting view for one
 * MARKED auto-spawn area, opened from the Areas tab.
 *
 * Shows, for the selected area:
 *  1. a LIVE countdown to the next scheduled spawn run + an ESTIMATE of when this
 *     area is next visited given its round-robin queue position;
 *  2. WHERE crowns would land — the area's scanned grid cells, which are spawn
 *     candidates (below target, with a cached safe-stop POI), each with its
 *     centroid, live-vs-target count and POI count;
 *  3. WHY nothing is spawning — the area-level blockers the engine checks.
 *
 * Every time-relative number is framed as an ESTIMATE: the spawner is round-robin
 * over active areas, shares a per-run cell budget, and skips at-target cells, so
 * a specific cell's next top-up can only be bracketed. The countdown ticks off
 * the server-provided `nextRunAt` against the local clock (recomputed each
 * second). All spawn state is backend-only, so everything here comes from the
 * admin-gated crownHunt.spawnDiagnostics callable.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  adminSpawnDiagnostics,
  adminReingestSpawnAreaPois,
  countdownSeconds,
  estimateAreaService,
  type AdminCrownSpawnDiagnosticsResponse,
  type CrownSpawnDiagnosticCell,
} from '@/features/crown-hunt';
import { translate } from '@/i18n';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);
const fmt = (key: string, params: Record<string, string | number>): string =>
  Object.entries(params).reduce((acc, [k, v]) => acc.replace(`{${k}}`, String(v)), t(key));

function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('sv-SE');
}

/** Whole seconds → "Mm Ss" (or "Ss"), for the countdown. */
function formatCountdown(totalSeconds: number): string {
  if (totalSeconds <= 0) return t('crownHunt.diagCountdownAny');
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

function reasonBadgeClass(reason: CrownSpawnDiagnosticCell['reason']): string {
  return reason === 'would_spawn'
    ? `${styles.diagReasonBadge} ${styles.diagReasonWould}`
    : `${styles.diagReasonBadge}`;
}

interface SpawnDiagnosticsPanelProps {
  areaId: string;
  areaName: string;
  onClose: () => void;
}

export function SpawnDiagnosticsPanel({
  areaId,
  areaName,
  onClose,
}: SpawnDiagnosticsPanelProps): React.ReactElement {
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const [data, setData] = useState<AdminCrownSpawnDiagnosticsResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [candidatesOnly, setCandidatesOnly] = useState(false);
  const [nowMs, setNowMs] = useState(() => Date.now());
  // Server clock minus local clock, captured at load. The countdown ticks off
  // the local clock but corrects by this offset, so an admin whose machine clock
  // is skewed still sees a countdown/ETA aligned to backend time (the callable's
  // nextRunAt is a server timestamp). Defaults to 0 until the first load lands.
  const [serverSkewMs, setServerSkewMs] = useState(0);

  // On-demand "Retry POIs" state. The re-ingestion is a distinct action from the
  // diagnostics reload, so it has its own in-flight flag and its own success /
  // error line (a transient Overpass timeout comes back as a message, not a throw).
  const [isReingesting, setIsReingesting] = useState(false);
  const [reingestOk, setReingestOk] = useState<boolean | null>(null);
  const [reingestMessage, setReingestMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const res = await adminSpawnDiagnostics(areaId);
      if (!mountedRef.current) return;
      const serverMs = new Date(res.serverTime).getTime();
      setServerSkewMs(Number.isFinite(serverMs) ? serverMs - Date.now() : 0);
      setData(res);
      setNowMs(Date.now());
    } catch {
      if (!mountedRef.current) return;
      setLoadError(t('crownHunt.diagError'));
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, [areaId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Re-run this area's OSM safe-stop POI ingestion on demand. The callable never
  // throws on an Overpass failure — it returns { ok:false, message } — so a
  // timeout shows the "try again" message rather than a hard error; only a real
  // transport/auth error (a thrown ApiError) falls through to the catch. On
  // success the diagnostics are reloaded so the new poiCount / blockers show.
  const handleReingest = useCallback(async () => {
    setIsReingesting(true);
    setReingestOk(null);
    setReingestMessage(null);
    try {
      const res = await adminReingestSpawnAreaPois(areaId);
      if (!mountedRef.current) return;
      setReingestOk(res.ok);
      // Success shows the new cached count; a failed Overpass run is surfaced with
      // the localised "timed out, cache kept, try again" line (the callable's
      // structured `message` carries the same meaning and is logged server-side).
      setReingestMessage(
        res.ok
          ? fmt('crownHunt.diagReingestSuccess', { count: res.poiCount })
          : t('crownHunt.diagReingestFailed'),
      );
      if (res.ok) await load();
    } catch {
      if (!mountedRef.current) return;
      setReingestOk(false);
      setReingestMessage(t('crownHunt.diagReingestError'));
    } finally {
      if (mountedRef.current) setIsReingesting(false);
    }
  }, [areaId, load]);

  // Tick the local clock once a second so the countdown recomputes live.
  useEffect(() => {
    const id = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const intervalMinutes = data ? Math.round(data.runIntervalSeconds / 60) : 10;

  const renderBody = () => {
    if (isLoading && !data) return <p className={styles.loadingText}>{t('crownHunt.loading')}</p>;
    if (loadError) {
      return (
        <p className={styles.errorText}>
          {loadError}{' '}
          <button className={styles.linkButton} onClick={() => void load()}>
            {t('crownHunt.retry')}
          </button>
        </p>
      );
    }
    if (!data) return null;

    // Correct the local clock to server time so skew doesn't distort the count.
    const serverNowMs = nowMs + serverSkewMs;
    const secondsToNextRun = countdownSeconds(data.nextRunAt, serverNowMs);
    const service = estimateAreaService({
      areasAhead: data.areasAhead,
      maxAreasPerRun: data.maxAreasPerRun,
      nextRunAtMs: new Date(data.nextRunAt).getTime(),
      runIntervalMs: data.runIntervalSeconds * 1000,
    });
    const serviceText = !data.active
      ? t('crownHunt.diagServiceInactive')
      : service.runsUntilServed === 0
        ? t('crownHunt.diagServiceNextRun')
        : fmt('crownHunt.diagServiceRuns', {
            runs: service.runsUntilServed,
            minutes: service.runsUntilServed * intervalMinutes,
          });

    const cells = candidatesOnly ? data.cells.filter((c) => c.eligible) : data.cells;

    return (
      <>
        <p className={styles.diagMuted}>
          {fmt('crownHunt.diagEstimateNote', { minutes: intervalMinutes })}
        </p>

        {/* Countdown + key facts */}
        <div className={styles.diagGrid}>
          <div className={styles.diagStat}>
            <span className={styles.diagStatLabel}>{t('crownHunt.diagNextRunLabel')}</span>
            <span className={styles.diagCountdown}>{formatCountdown(secondsToNextRun)}</span>
            <span className={styles.diagMuted}>
              {fmt('crownHunt.diagNextRunAt', { time: formatTime(data.nextRunAt) })}
            </span>
          </div>
          <div className={styles.diagStat}>
            <span className={styles.diagStatLabel}>{t('crownHunt.diagServiceLabel')}</span>
            <span className={styles.diagStatValue}>{serviceText}</span>
            <span className={styles.diagMuted}>
              {fmt('crownHunt.diagQueueValue', {
                ahead: data.areasAhead,
                total: data.activeAreaCount,
              })}
            </span>
          </div>
          <div className={styles.diagStat}>
            <span className={styles.diagStatLabel}>{t('crownHunt.diagFlagLabel')}</span>
            <span className={styles.diagStatValue}>
              {data.flagEnabled ? t('crownHunt.diagFlagOn') : t('crownHunt.diagFlagOff')}
            </span>
          </div>
          <div className={styles.diagStat}>
            <span className={styles.diagStatLabel}>{t('crownHunt.diagStateLabel')}</span>
            <span className={styles.diagStatValue}>
              {data.active && data.safeAreaConfirmed
                ? t('crownHunt.diagStateActive')
                : t('crownHunt.diagStateInactive')}
            </span>
          </div>
          <div className={styles.diagStat}>
            <span className={styles.diagStatLabel}>{t('crownHunt.diagPoiLabel')}</span>
            <span className={styles.diagStatValue}>
              {fmt('crownHunt.diagPoiValue', { count: data.areaPoiCount })}
            </span>
            <span className={styles.diagMuted}>
              {data.poisRefreshedAt
                ? fmt('crownHunt.diagPoisRefreshed', { time: formatTime(data.poisRefreshedAt) })
                : t('crownHunt.diagPoisNever')}
            </span>
            {/* On-demand re-ingestion — most prominent when the cache is empty
                (poiCount 0), which is exactly the transient-Overpass-failure case. */}
            <button
              type="button"
              className={data.areaPoiCount <= 0 ? styles.btnPrimary : styles.btnSecondary}
              onClick={() => void handleReingest()}
              disabled={isReingesting}
            >
              {isReingesting
                ? t('crownHunt.diagReingestLoading')
                : t('crownHunt.diagReingestButton')}
            </button>
            {reingestMessage !== null && (
              <span
                className={reingestOk ? styles.diagMuted : styles.errorText}
                role={reingestOk ? undefined : 'alert'}
              >
                {reingestMessage}
              </span>
            )}
          </div>
          <div className={styles.diagStat}>
            <span className={styles.diagStatLabel}>{t('crownHunt.diagLastServedLabel')}</span>
            <span className={styles.diagStatValue}>
              {data.lastSpawnPassAt ? formatTime(data.lastSpawnPassAt) : t('crownHunt.diagNever')}
            </span>
          </div>
        </div>

        {/* Why nothing is spawning */}
        <h3 className={styles.diagSectionTitle}>{t('crownHunt.diagBlockersTitle')}</h3>
        {data.blockers.length === 0 ? (
          <p className={styles.diagNoBlockers}>{t('crownHunt.diagNoBlockers')}</p>
        ) : (
          <ul className={styles.diagBlockerList}>
            {data.blockers.map((code) => (
              <li key={code} className={styles.diagBlockerItem}>
                {t(`crownHunt.diagBlocker.${code}`)}
              </li>
            ))}
          </ul>
        )}

        {/* Where it would spawn */}
        <h3 className={styles.diagSectionTitle}>{t('crownHunt.diagWhereTitle')}</h3>
        <p className={styles.diagMuted}>
          {fmt('crownHunt.diagCandidatesSummary', {
            candidates: data.candidateCellCount,
            scanned: data.cellsScanned,
            total: data.totalCells,
          })}
        </p>
        <label className={styles.checkLabel}>
          <input
            type="checkbox"
            checked={candidatesOnly}
            onChange={(e) => setCandidatesOnly(e.target.checked)}
          />{' '}
          {t('crownHunt.diagShowCandidatesOnly')}
        </label>

        {cells.length === 0 ? (
          <p className={styles.emptyText}>{t('crownHunt.diagCellsEmpty')}</p>
        ) : (
          <div className={styles.diagTableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>{t('crownHunt.diagColCell')}</th>
                  <th>{t('crownHunt.diagColStatus')}</th>
                  <th>{t('crownHunt.diagColActivity')}</th>
                  <th>{t('crownHunt.diagColLiveTarget')}</th>
                  <th>{t('crownHunt.diagColPois')}</th>
                  <th>{t('crownHunt.diagColCentre')}</th>
                </tr>
              </thead>
              <tbody>
                {cells.map((cell) => (
                  <tr key={cell.cellKey}>
                    <td className={styles.cellKey}>{cell.cellKey}</td>
                    <td>
                      <span className={reasonBadgeClass(cell.reason)}>
                        {t(`crownHunt.diagCellReason.${cell.reason}`)}
                      </span>
                    </td>
                    <td>{cell.activityScore.toFixed(2)}</td>
                    <td>
                      {cell.liveCount} / {cell.target}
                    </td>
                    <td>
                      {cell.poiCountCapped
                        ? fmt('crownHunt.diagPoiPlus', { count: cell.poiCount })
                        : cell.poiCount}
                    </td>
                    <td>
                      {cell.center.lat.toFixed(4)}, {cell.center.lon.toFixed(4)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className={styles.diagAttribution}>{t('crownHunt.osmAttribution')}</p>
      </>
    );
  };

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={`${styles.modal} ${styles.modalWide}`}>
        <div className={styles.tabHeader}>
          <h2 className={styles.modalTitle}>{t('crownHunt.diagTitle')}</h2>
        </div>
        <p className={styles.introText}>
          {fmt('crownHunt.diagFor', { name: areaName || t('crownHunt.areaUnnamed') })}
        </p>

        {renderBody()}

        <div className={styles.formActions}>
          <button className={styles.btnSecondary} onClick={() => void load()} disabled={isLoading}>
            {t('crownHunt.diagRefresh')}
          </button>
          <button className={styles.btnPrimary} onClick={onClose}>
            {t('crownHunt.diagClose')}
          </button>
        </div>
      </div>
    </div>
  );
}

export default SpawnDiagnosticsPanel;
