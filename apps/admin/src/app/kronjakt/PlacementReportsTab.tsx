import { useEffect, useMemo, useState } from 'react';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { MapLocationPicker, type MapLocationChoice } from '@/components/map/MapLocationPicker';
import { parseLatLng } from '@/components/map/coordinates';
import { ApiError } from '@/lib/errors';
import { translate } from '@/i18n';
import {
  getPlacementCase,
  listPlacementCases,
  listPlacementDestinations,
  resolvePlacementCase,
  type PlacementCase,
  type PlacementCaseDetail,
  type PlacementDecision,
  type PlacementDestination,
  type PlacementPosition,
  type PlacementReason,
} from '@/features/crown-hunt/placement-reports';
import styles from './PlacementReportsTab.module.css';
import pageStyles from './page.module.css';

const t = (key: string) => translate('sv', `placementReports.${key}`);

const REASONS: Record<PlacementReason, string> = {
  unsafe: t('unsafe'),
  hard_to_reach: t('hard_to_reach'),
  private_or_closed: t('private_or_closed'),
  other: t('other'),
};
const ACTIONS: Record<PlacementDecision, string> = {
  ignore: t('ignore'),
  move: t('move'),
  remove: t('remove'),
};
const DECISIONS: Record<PlacementDecision, string> = {
  ignore: t('ignored'),
  move: t('moved'),
  remove: t('removed'),
};
const STOP_CATEGORIES = { parking: t('parking'), fuel: t('fuel'), charging: t('charging') };
const dates = new Intl.DateTimeFormat('sv-SE', { dateStyle: 'short', timeStyle: 'short' });

type PlacementPage = Awaited<ReturnType<typeof listPlacementCases>>;
type Cursor = NonNullable<PlacementPage['nextCursor']>;

function crownLabel(report: PlacementCase) {
  return report.source === 'automatic' ? t('automatic') : t('handPlaced');
}

function coordinates(point: PlacementPosition) {
  return `${point.latitude.toFixed(5)}, ${point.longitude.toFixed(5)}`;
}

function PlacementMap({
  point,
  label,
  choices,
  onChoose,
}: {
  point: PlacementPosition;
  label: string;
  choices?: readonly MapLocationChoice[];
  onChoose?: (id: string) => void;
}) {
  return (
    <MapLocationPicker
      latitude={String(point.latitude)}
      longitude={String(point.longitude)}
      onChange={() => {}}
      labelLat={t('latitude')}
      labelLng={t('longitude')}
      helpText={label}
      choices={choices}
      onChoose={onChoose}
      unavailableText={t('readOnlyMapUnavailable')}
      loadErrorText={t('readOnlyMapError')}
      inputClassName={styles.input}
      disabled
    />
  );
}

export function PlacementReportsTab({ onCount }: { onCount: (count: number) => void }) {
  const [closed, setClosed] = useState(false);
  const [cursor, setCursor] = useState<Cursor>();
  const [page, setPage] = useState<PlacementPage | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState(false);
  const [notice, setNotice] = useState('');

  useEffect(() => {
    let current = true;
    void listPlacementCases(closed, cursor)
      .then((value) => {
        if (!current) return;
        setPage(value);
        onCount(value.openCount);
        setSelected((previous) =>
          value.cases.some((report) => report.caseId === previous)
            ? previous
            : (value.cases[0]?.caseId ?? null),
        );
      })
      .catch(() => {
        if (current) setError(true);
      });
    return () => {
      current = false;
    };
  }, [closed, cursor, attempt, onCount]);

  function changePage(nextClosed: boolean, nextCursor?: Cursor) {
    setClosed(nextClosed);
    setCursor(nextCursor);
    setPage(null);
    setSelected(null);
    setError(false);
    setAttempt((value) => value + 1);
  }

  return (
    <section aria-label={t('tab')}>
      <div className={styles.toolbar}>
        <button
          type="button"
          className={pageStyles.btnSecondary}
          aria-pressed={!closed}
          onClick={() => changePage(false)}
        >
          {t('open')}
        </button>
        <button
          type="button"
          className={pageStyles.btnSecondary}
          aria-pressed={closed}
          onClick={() => changePage(true)}
        >
          {t('closed')}
        </button>
        <button
          type="button"
          className={pageStyles.btnSecondary}
          onClick={() => changePage(closed)}
        >
          {t('refresh')}
        </button>
      </div>
      {notice && (
        <p className={pageStyles.successBanner} role="status">
          {notice}
        </p>
      )}
      {error && <p role="alert">{t('listError')}</p>}
      {!page && !error && <p role="status">{t('loading')}</p>}
      {page && page.cases.length === 0 && <p>{t(closed ? 'emptyClosed' : 'emptyOpen')}</p>}
      {page && page.cases.length > 0 && (
        <div className={styles.workspace}>
          <div className={styles.panel}>
            <h2>{closed ? t('closedCases') : t('open')}</h2>
            <ul className={styles.queue}>
              {page.cases.map((report) => (
                <li key={report.caseId}>
                  <button
                    type="button"
                    aria-pressed={report.caseId === selected}
                    onClick={() => {
                      setSelected(report.caseId);
                      setNotice('');
                    }}
                  >
                    <strong>{report.areaName || crownLabel(report)}</strong>
                    <span>
                      {report.reportCount}{' '}
                      {report.reportCount === 1 ? t('report') : t('reportPlural')}
                    </span>
                    <small>
                      {coordinates(report)} · {dates.format(new Date(report.lastReportedAt))}
                    </small>
                  </button>
                </li>
              ))}
            </ul>
            <div className={styles.toolbar}>
              {cursor && (
                <button
                  type="button"
                  className={pageStyles.btnSecondary}
                  onClick={() => changePage(closed)}
                >
                  {t('firstPage')}
                </button>
              )}
              {page.nextCursor && (
                <button
                  type="button"
                  className={pageStyles.btnSecondary}
                  onClick={() => changePage(closed, page.nextCursor ?? undefined)}
                >
                  {t('nextPage')}
                </button>
              )}
            </div>
          </div>
          {selected && (
            <CaseDetail
              key={selected}
              caseId={selected}
              onResolved={(action) => {
                setNotice(DECISIONS[action]);
                changePage(closed);
              }}
            />
          )}
        </div>
      )}
    </section>
  );
}

function CaseDetail({
  caseId,
  onResolved,
}: {
  caseId: string;
  onResolved: (action: PlacementDecision) => void;
}) {
  const [report, setReport] = useState<PlacementCaseDetail | null>(null);
  const [error, setError] = useState(false);
  const [action, setAction] = useState<PlacementDecision | null>(null);
  useEffect(() => {
    let current = true;
    void getPlacementCase(caseId)
      .then((value) => {
        if (current) setReport(value);
      })
      .catch(() => {
        if (current) setError(true);
      });
    return () => {
      current = false;
    };
  }, [caseId]);

  if (error) return <p role="alert">{t('detailError')}</p>;
  if (!report) return <p role="status">{t('detailLoading')}</p>;
  return (
    <article className={styles.panel}>
      <h2>{report.areaName || crownLabel(report)}</h2>
      <p>
        {crownLabel(report)} · {report.active ? t('active') : t('inactive')} · {report.reportCount}{' '}
        {report.reportCount === 1 ? t('report') : t('reportPlural')}
      </p>
      <PlacementMap point={report} label={t('reportedLocation')} />
      <h3>{t('reasons')}</h3>
      <ul>
        {Object.entries(report.reasons).map(([reason, count]) => (
          <li key={reason}>
            {REASONS[reason as PlacementReason]} · {count}
          </li>
        ))}
      </ul>
      <h3>{report.reportCount > report.reports.length ? t('latestReports') : t('reports')}</h3>
      <ul className={styles.reportList}>
        {report.reports.map((entry) => (
          <li key={entry.id}>
            <strong>{REASONS[entry.reason]}</strong> · {dates.format(new Date(entry.createdAt))}
            {entry.comment && <p>{entry.comment}</p>}
          </li>
        ))}
      </ul>
      {report.decision ? (
        <div>
          <strong>{DECISIONS[report.decision]}</strong>
          {report.replacement && <p>Vald ny plats · {coordinates(report.replacement)}</p>}
        </div>
      ) : (
        <div className={styles.actions}>
          <button
            type="button"
            className={pageStyles.btnSecondary}
            onClick={() => setAction('ignore')}
          >
            {ACTIONS.ignore}
          </button>
          <button
            type="button"
            className={pageStyles.btnPrimary}
            disabled={!report.canMove}
            onClick={() => setAction('move')}
          >
            {report.source === 'automatic' ? ACTIONS.move : t('moveManual')}
          </button>
          <button type="button" className={styles.danger} onClick={() => setAction('remove')}>
            {ACTIONS.remove}
          </button>
          {!report.canMove && <small>{t('moveUnavailable')}</small>}
        </div>
      )}
      {action && (
        <ResolveDialog
          report={report}
          action={action}
          onCancel={() => setAction(null)}
          onResolved={onResolved}
        />
      )}
    </article>
  );
}

function ResolveDialog({
  report,
  action,
  onCancel,
  onResolved,
}: {
  report: PlacementCaseDetail;
  action: PlacementDecision;
  onCancel: () => void;
  onResolved: (action: PlacementDecision) => void;
}) {
  const automatic = report.source === 'automatic';
  const automaticMove = action === 'move' && automatic;
  const [stops, setStops] = useState<PlacementDestination[] | null>(null);
  const [poiId, setPoiId] = useState('');
  const [lat, setLat] = useState('');
  const [lon, setLon] = useState('');
  const [safe, setSafe] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const choices = useMemo(
    () =>
      stops?.map((stop, index) => ({
        id: stop.poiId,
        lat: stop.latitude,
        lng: stop.longitude,
        label: `${index + 1}. ${STOP_CATEGORIES[stop.category]} · ${stop.distanceMeters} m`,
      })),
    [stops],
  );
  const selected = stops?.find((stop) => stop.poiId === poiId);
  const manual = parseLatLng(lat, lon);

  useEffect(() => {
    if (!automaticMove) return;
    let current = true;
    void listPlacementDestinations(report.caseId)
      .then(({ destinations }) => {
        if (current) setStops(destinations);
      })
      .catch(() => {
        if (current) setError(t('stopsError'));
      });
    return () => {
      current = false;
    };
  }, [automaticMove, report.caseId]);

  async function confirm() {
    setBusy(true);
    setError('');
    try {
      if (action !== 'move') {
        await resolvePlacementCase(report, { action });
      } else if (automatic && selected) {
        await resolvePlacementCase(report, { action, destination: { poiId: selected.poiId } });
      } else if (!automatic && manual && safe) {
        await resolvePlacementCase(report, {
          action,
          destination: { latitude: manual.lat, longitude: manual.lng, safeLocationConfirmed: true },
        });
      } else {
        return;
      }
      onResolved(action);
    } catch (err) {
      setError(
        err instanceof ApiError && err.code === 'failed-precondition'
          ? t('actionChanged')
          : t('actionError'),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <ActionDialog title={ACTIONS[action]} onCancel={onCancel} cancelDisabled={busy}>
      {action === 'ignore' && <p>{t('ignoreExplanation')}</p>}
      {action === 'remove' && <p>{t('removeExplanation')}</p>}
      {action === 'move' && <p>{t('moveExplanation')}</p>}
      {automaticMove && (
        <>
          <p>{t('chooseApprovedExplanation')}</p>
          {!stops && !error && <p role="status">{t('stopsLoading')}</p>}
          {stops?.length === 0 && <p>{t('noStops')}</p>}
          {!!stops?.length && (
            <label>
              {t('approvedStop')}
              <select
                className={styles.input}
                value={poiId}
                onChange={(event) => setPoiId(event.target.value)}
                disabled={busy}
              >
                <option value="">{t('chooseStop')}</option>
                {stops.map((stop, index) => (
                  <option key={stop.poiId} value={stop.poiId}>
                    {index + 1}. {STOP_CATEGORIES[stop.category]} · {stop.distanceMeters} m ·{' '}
                    {coordinates(stop)}
                  </option>
                ))}
              </select>
            </label>
          )}
          {!!choices?.length && (
            <PlacementMap
              point={selected ?? report}
              label={t('chooseMapOrList')}
              choices={choices}
              onChoose={busy ? undefined : setPoiId}
            />
          )}
          <small>
            <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>
          </small>
        </>
      )}
      {action === 'move' && !automatic && (
        <>
          <p>{t('moveManualExplanation')}</p>
          <MapLocationPicker
            latitude={lat}
            longitude={lon}
            onChange={(latitude, longitude) => {
              setLat(latitude);
              setLon(longitude);
              setSafe(false);
            }}
            initialCenter={{ lat: report.latitude, lng: report.longitude }}
            labelLat={t('newLatitude')}
            labelLng={t('newLongitude')}
            helpText={t('chooseOnMap')}
            unavailableText={t('editableMapUnavailable')}
            loadErrorText={t('editableMapError')}
            inputClassName={styles.input}
            disabled={busy}
          />
          <label>
            <input
              type="checkbox"
              checked={safe}
              onChange={(event) => setSafe(event.target.checked)}
              disabled={busy}
            />{' '}
            {t('safeConfirmed')}
          </label>
        </>
      )}
      {error && <p role="alert">{error}</p>}
      <button
        type="button"
        className={action === 'remove' ? styles.danger : pageStyles.btnPrimary}
        disabled={busy || (action === 'move' && (automatic ? !selected : !manual || !safe))}
        onClick={() => void confirm()}
      >
        {busy ? t('saving') : action === 'move' ? t('save') : ACTIONS[action]}
      </button>
    </ActionDialog>
  );
}
