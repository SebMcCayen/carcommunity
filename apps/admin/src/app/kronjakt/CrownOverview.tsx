import { useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  Clock,
  Database,
  Activity,
  Settings,
  RefreshCw,
  Crown,
  Map,
} from 'lucide-react';
import type {
  AdminCrownHuntPointSummary,
  AdminCrownSpawnDiagnosticsResponse,
} from '@carcommunity/shared/crown-hunt';
import { useAdminAuth } from '@/components/auth/FirebaseAuthProvider';
import {
  loadCrownOverview,
  visibleCrowns,
  type CrownOverviewSnapshot,
} from '@/features/crown-hunt/overview';
import { adminSpawnDiagnostics, estimateAreaService, areaPoiCount } from '@/features/crown-hunt';
import { CrownOverviewMap } from '@/components/map/CrownOverviewMap';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { SpawnDiagnosticsPanel } from './SpawnDiagnosticsPanel';
import styles from './CrownOverview.module.css';
const date = (value: string | null | undefined) =>
  value ? new Date(value).toLocaleString('sv-SE') : 'Historik saknas';
export function CrownOverview({
  onManageArea,
  onPoint,
}: {
  onManageArea: (id: string) => void;
  onPoint: (point: AdminCrownHuntPointSummary) => void;
}) {
  const { user } = useAdminAuth();
  const [data, setData] = useState<CrownOverviewSnapshot | null>(null),
    [loading, setLoading] = useState(true),
    [error, setError] = useState<string | null>(null),
    [attempt, setAttempt] = useState(0),
    [areaId, setAreaId] = useState<string | null>(null),
    [diagnostics, setDiagnostics] = useState<AdminCrownSpawnDiagnosticsResponse | null>(null),
    [diagError, setDiagError] = useState(false),
    [diagLoading, setDiagLoading] = useState(false),
    [showDiagnostics, setShowDiagnostics] = useState(false);
  const [layers, setLayers] = useState({ automatic: true, manual: true, areas: true }),
    [now, setNow] = useState(() => Date.now());
  const clock = useRef<{ server: number; received: number } | null>(null);
  useEffect(() => {
    const timer = setInterval(
      () =>
        setNow(
          clock.current
            ? clock.current.server + performance.now() - clock.current.received
            : Date.now(),
        ),
      1000,
    );
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    let current = true;
    setLoading(true);
    setError(null);
    setData(null);
    void loadCrownOverview()
      .then((value) => {
        if (current) {
          setData(value);
          setAreaId((previous) =>
            value.areas.some((a) => a.areaId === previous)
              ? previous
              : (value.areas[0]?.areaId ?? null),
          );
        }
      })
      .catch(() => {
        if (current) setError('Översikten kunde inte läsas från servern. Försök igen.');
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [attempt, user?.uid]);
  useEffect(() => {
    let current = true;
    setDiagnostics(null);
    setDiagError(false);
    if (!areaId) {
      setDiagLoading(false);
      return;
    }
    setDiagLoading(true);
    void adminSpawnDiagnostics(areaId)
      .then((value) => {
        if (current) {
          setDiagnostics(value);
          clock.current = { server: Date.parse(value.serverTime), received: performance.now() };
        }
      })
      .catch(() => {
        if (current) setDiagError(true);
      })
      .finally(() => {
        if (current) setDiagLoading(false);
      });
    return () => {
      current = false;
    };
  }, [areaId, attempt, user?.uid]);
  if (loading) return <p role="status">Läser Kronjaktens översikt…</p>;
  if (!data)
    return (
      <div className={styles.error} role="alert">
        <p>{error}</p>
        <button onClick={() => setAttempt((v) => v + 1)}>Försök igen</button>
      </div>
    );
  const area = data.areas.find((a) => a.areaId === areaId),
    crowns = visibleCrowns(data.crowns, now),
    eta =
      diagnostics &&
      area &&
      diagnostics.flagEnabled &&
      diagnostics.active &&
      diagnostics.safeAreaConfirmed
        ? estimateAreaService({
            areasAhead: diagnostics.areasAhead,
            maxAreasPerRun: diagnostics.maxAreasPerRun,
            nextRunAtMs: Date.parse(diagnostics.nextRunAt),
            runIntervalMs: diagnostics.runIntervalSeconds * 1000,
          })
        : null;
  const spawnEnabled = diagnostics?.flagEnabled ?? data.spawnEnabled;
  const blockers = diagnostics?.blockers ?? [],
    areaCount = area ? crowns.filter((c) => c.areaId === area.areaId).length : 0;
  return (
    <section className={styles.overview} aria-label="Kronjaktens driftöversikt">
      <div className={styles.statusBar}>
        <span>
          <Activity size={17} /> Automatisk spawn <strong>{spawnEnabled ? 'På' : 'Av'}</strong>
        </span>
        <span>
          <Clock size={17} /> Valt område · senast kört{' '}
          <strong>
            {diagLoading
              ? 'Läser…'
              : diagError
                ? 'Kunde inte läsas'
                : date(diagnostics?.lastSpawnPassAt)}
          </strong>
        </span>
        <span>
          <Database size={17} /> Data läst <strong>{date(data.readAt)}</strong>
        </span>
        <button onClick={() => setAttempt((v) => v + 1)} aria-label="Uppdatera översikten">
          <RefreshCw size={17} />
        </button>
      </div>
      <div className={styles.workspace}>
        <div className={styles.mapWrap}>
          <CrownOverviewMap
            areas={layers.areas ? data.areas : []}
            crowns={layers.automatic ? crowns : []}
            points={layers.manual ? data.points : []}
            selectedArea={areaId}
            onArea={(id) => {
              if (data.areas.some((a) => a.areaId === id)) setAreaId(id);
            }}
            onPoint={onPoint}
          />
          <fieldset className={styles.layers}>
            <legend>Kartlager</legend>
            {(
              [
                ['automatic', 'Automatiska'],
                ['manual', 'Handplacerade'],
                ['areas', 'Områden'],
              ] as const
            ).map(([key, label]) => (
              <label key={key}>
                <input
                  type="checkbox"
                  checked={layers[key]}
                  onChange={(e) => setLayers({ ...layers, [key]: e.target.checked })}
                />
                {key === 'areas' ? (
                  <Map size={16} />
                ) : (
                  <Crown size={16} fill={key === 'automatic' ? 'currentColor' : 'none'} />
                )}{' '}
                {label}
              </label>
            ))}
          </fieldset>
        </div>
        <aside className={styles.panel}>
          <label htmlFor="crown-area">Område</label>
          <select id="crown-area" value={areaId ?? ''} onChange={(e) => setAreaId(e.target.value)}>
            {!data.areas.length && <option value="">Inga områden</option>}
            {data.areas.map((a) => (
              <option key={a.areaId} value={a.areaId}>
                {a.name || 'Namnlöst område'}
              </option>
            ))}
          </select>
          {area ? (
            <>
              <div className={styles.areaHeading}>
                <h2>{area.name || 'Namnlöst område'}</h2>
                <span
                  className={area.active && area.safeAreaConfirmed ? styles.active : styles.muted}
                >
                  {area.active && area.safeAreaConfirmed
                    ? 'Aktivt'
                    : 'Pausat / ej säkerhetsgodkänt'}
                </span>
              </div>
              <p className={styles.muted}>
                Områdestyp ·{' '}
                {
                  ({ polygon: 'Polygon', circle: 'Cirkel', rectangle: 'Rektangel' } as const)[
                    area.shape.type
                  ]
                }
              </p>
              <dl className={styles.facts}>
                <div>
                  <dt>Automatiska kronor</dt>
                  <dd>
                    {data.crownsTruncated ? 'Minst ' : ''}
                    {areaCount}
                    {data.crownsTruncated ? ' inlästa' : ''}
                  </dd>
                </div>
                <div>
                  <dt>Säkra platser</dt>
                  <dd>
                    {area.poisRefreshedAt
                      ? (areaPoiCount(area) ?? 'Uppgift saknas')
                      : 'Platsdata saknas'}
                  </dd>
                </div>
                <div>
                  <dt>Senast kört</dt>
                  <dd>{diagError ? 'Kunde inte läsas' : date(diagnostics?.lastSpawnPassAt)}</dd>
                </div>
                <div>
                  <dt>Nästa besök</dt>
                  <dd>
                    {diagError
                      ? 'Kunde inte läsas'
                      : eta
                        ? eta.serviceAtMs > now
                          ? `Ca ${new Date(eta.serviceAtMs).toLocaleTimeString('sv-SE', { hour: '2-digit', minute: '2-digit' })}`
                          : 'Väntar på nästa körning'
                        : diagnostics
                          ? 'Ej planerat'
                          : 'Läser…'}
                  </dd>
                </div>
              </dl>
              <small className={styles.muted}>
                Nästa besök är en uppskattning. © OpenStreetMap contributors
              </small>
              {(!area.poisRefreshedAt || blockers.length > 0 || diagError) && (
                <div className={styles.warning}>
                  <AlertTriangle size={19} />
                  <div>
                    <strong>
                      {diagError
                        ? 'Diagnostik saknas'
                        : !area.poisRefreshedAt
                          ? 'Platsdata saknas'
                          : 'Området behöver ses över'}
                    </strong>
                    <p>Platsdata uppdaterad: {date(area.poisRefreshedAt)}</p>
                    {blockers.length > 0 && (
                      <p>
                        {blockers.length} hinder har registrerats. Se diagnostiken för detaljer.
                      </p>
                    )}
                  </div>
                </div>
              )}
              <button className={styles.primary} onClick={() => setShowDiagnostics(true)}>
                <Activity size={17} /> Visa diagnostik
              </button>
              <button onClick={() => onManageArea(area.areaId)}>
                <Settings size={17} /> Hantera område
              </button>
              <p className={styles.muted}>
                Pausa eller ändra området i områdesverktygen. En paus tar bort områdets aktiva
                automatiska kronor.
              </p>
            </>
          ) : (
            <p>Inga områden är registrerade. Skapa ett område under Områden och spawn.</p>
          )}
        </aside>
      </div>
      <p className={styles.coverage}>
        {crowns.length}
        {data.crownsTruncated ? '+' : ''} automatiska kronor · {data.points.length} av{' '}
        {data.pointTotal} aktiva handplacerade · {data.areas.length}
        {data.areasPossiblyTruncated ? '+' : ''} områden inlästa.{' '}
        {data.crownsTruncated || data.areasPossiblyTruncated || data.points.length < data.pointTotal
          ? 'Kartan visar ett begränsat urval. Använd respektive lista för fortsatt hantering.'
          : ''}
      </p>
      {showDiagnostics && area && (
        <ActionDialog
          title={`Diagnostik · ${area.name || 'Område'}`}
          onCancel={() => setShowDiagnostics(false)}
        >
          <SpawnDiagnosticsPanel
            key={area.areaId}
            areaId={area.areaId}
            areaName={area.name || 'Område'}
            onClose={() => setShowDiagnostics(false)}
            hideClose
          />
        </ActionDialog>
      )}
    </section>
  );
}
