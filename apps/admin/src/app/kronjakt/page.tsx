'use client';

/**
 * Admin Kronjakt (Crown Hunt) management page.
 *
 * Allows admins to:
 *  - List all Kronjakt points (all statuses)
 *  - Create draft points
 *  - Edit draft/paused points
 *  - Activate points (with safety confirmation)
 *  - Pause active points
 *  - Review claim summaries including high-risk claims
 *
 * Safety and security rules:
 *  - New points start as draft; activation requires explicit safety confirmation.
 *  - Backend enforces all validation, approval, and audit logging.
 *  - No exact user claim coordinates are shown.
 *  - No user movement routes are exposed.
 *  - High-risk claims are shown for review only (no manual award in this step).
 *  - Do not hard-delete points; prefer pause/end.
 *  - Kronjakt must never encourage speeding, risky driving, or unsafe stops.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  AdminCrownHuntClaimSummary,
  AdminCrownHuntPointSummary,
  AdminCreateCrownHuntPointRequest,
  AdminUpdateCrownHuntPointRequest,
  CrownHuntClaimResult,
  AdminSpawnCellSummary,
  SpawnCellTarget,
} from '@/features/crown-hunt';
import {
  adminActivateCrownHuntPoint,
  adminCreateCrownHuntPoint,
  adminListCrownHuntClaims,
  adminListCrownHuntPoints,
  adminPauseCrownHuntPoint,
  adminUpdateCrownHuntPoint,
  adminListSpawnCells,
  adminApproveSpawnCell,
  adminRevokeSpawnCell,
  cellKeyForCoords,
  formatCellCenter,
} from '@/features/crown-hunt';
import { DateTimePicker } from '@/components/ui/DateTimePicker';
import { MapLocationPicker } from '@/components/map/MapLocationPicker';
import { translate } from '@/i18n';
import { localToIso, toLocalDateTimeValue } from '@/lib/datetime';
import { formatDateOnly } from '@/lib/format';

import styles from './page.module.css';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatDate(iso: string | undefined | null): string {
  return formatDateOnly(iso);
}

function statusLabel(status: string): string {
  switch (status) {
    case 'draft':
      return t('crownHunt.statusDraft');
    case 'active':
      return t('crownHunt.statusActive');
    case 'paused':
      return t('crownHunt.statusPaused');
    case 'ended':
      return t('crownHunt.statusEnded');
    default:
      return status;
  }
}

// ---------------------------------------------------------------------------
// Point form
// ---------------------------------------------------------------------------

interface PointFormState {
  title: string;
  description: string;
  latitude: string;
  longitude: string;
  geofenceRadiusMeters: string;
  rewardPoints: string;
  repeatRule: 'once' | 'daily' | 'weekly';
  availableFrom: string;
  availableUntil: string;
}

const EMPTY_FORM: PointFormState = {
  title: '',
  description: '',
  latitude: '',
  longitude: '',
  geofenceRadiusMeters: '100',
  rewardPoints: '10',
  repeatRule: 'once',
  availableFrom: '',
  availableUntil: '',
};

function pointToForm(point: AdminCrownHuntPointSummary): PointFormState {
  return {
    title: point.title,
    description: point.description ?? '',
    latitude: String(point.latitude),
    longitude: String(point.longitude),
    geofenceRadiusMeters: String(point.geofenceRadiusMeters),
    rewardPoints: String(point.rewardPoints),
    repeatRule: point.repeatRule,
    availableFrom: toLocalDateTimeValue(point.availableFrom),
    availableUntil: toLocalDateTimeValue(point.availableUntil),
  };
}

interface PointFormProps {
  initial: PointFormState;
  onSave: (form: PointFormState) => Promise<void>;
  onCancel: () => void;
  isSaving: boolean;
  saveError: string | null;
}

const PointForm = ({ initial, onSave, onCancel, isSaving, saveError }: PointFormProps) => {
  const [form, setForm] = useState<PointFormState>(initial);

  const set = (field: keyof PointFormState, value: string) =>
    setForm((prev) => ({ ...prev, [field]: value }));

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void onSave(form);
      }}
      className={styles.form}
    >
      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('crownHunt.formTitleLabel')}
          <input
            className={styles.input}
            value={form.title}
            onChange={(e) => set('title', e.target.value)}
            maxLength={100}
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('crownHunt.formDescriptionLabel')}
          <textarea
            className={styles.input}
            value={form.description}
            onChange={(e) => set('description', e.target.value)}
            maxLength={500}
            rows={3}
          />
        </label>
      </div>

      <MapLocationPicker
        latitude={form.latitude}
        longitude={form.longitude}
        onChange={(latitude, longitude) =>
          setForm((prev) => ({ ...prev, latitude, longitude }))
        }
        labelLat={t('crownHunt.formLatitudeLabel')}
        labelLng={t('crownHunt.formLongitudeLabel')}
        helpText={t('map.dragHint')}
        unavailableText={t('map.unavailable')}
        loadErrorText={t('map.loadError')}
        radiusMeters={Number.parseInt(form.geofenceRadiusMeters, 10) || undefined}
        required
        labelClassName={styles.label}
        inputClassName={styles.input}
      />

      <div className={styles.formRowGrid}>
        <label className={styles.label}>
          {t('crownHunt.formGeofenceLabel')} *
          <input
            className={styles.input}
            type="number"
            min="20"
            max="150"
            value={form.geofenceRadiusMeters}
            onChange={(e) => set('geofenceRadiusMeters', e.target.value)}
            required
          />
        </label>
        <label className={styles.label}>
          {t('crownHunt.formRewardLabel')} *
          <input
            className={styles.input}
            type="number"
            min="1"
            max="1000"
            value={form.rewardPoints}
            onChange={(e) => set('rewardPoints', e.target.value)}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('crownHunt.formRepeatRuleLabel')} *
          <select
            className={styles.input}
            value={form.repeatRule}
            onChange={(e) => set('repeatRule', e.target.value as PointFormState['repeatRule'])}
          >
            <option value="once">once</option>
            <option value="daily">daily</option>
            <option value="weekly">weekly</option>
          </select>
        </label>
      </div>

      <div className={styles.formRowGrid}>
        <DateTimePicker
          id="ch-available-from"
          label={t('crownHunt.formAvailableFromLabel')}
          labelClassName={styles.label}
          inputClassName={styles.input}
          value={form.availableFrom}
          onChange={(next) => set('availableFrom', next)}
        />
        <DateTimePicker
          id="ch-available-until"
          label={t('crownHunt.formAvailableUntilLabel')}
          labelClassName={styles.label}
          inputClassName={styles.input}
          value={form.availableUntil}
          onChange={(next) => set('availableUntil', next)}
        />
      </div>

      {saveError !== null && <p className={styles.errorText}>{saveError}</p>}

      <div className={styles.formActions}>
        <button type="button" className={styles.btnSecondary} onClick={onCancel} disabled={isSaving}>
          {t('crownHunt.cancel')}
        </button>
        <button type="submit" className={styles.btnPrimary} disabled={isSaving}>
          {isSaving ? t('crownHunt.loading') : t('crownHunt.savePoint')}
        </button>
      </div>
    </form>
  );
};

// ---------------------------------------------------------------------------
// Activate confirmation modal
// ---------------------------------------------------------------------------

interface ActivateModalProps {
  pointTitle: string;
  onConfirm: (note: string) => Promise<void>;
  onCancel: () => void;
  isConfirming: boolean;
  error: string | null;
}

const ActivateModal = ({ pointTitle, onConfirm, onCancel, isConfirming, error }: ActivateModalProps) => {
  const [safetyNote, setSafetyNote] = useState('');
  const [checked, setChecked] = useState(false);

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{t('crownHunt.activateConfirmTitle')}</h2>
        <p className={styles.modalBody}>{pointTitle}</p>
        <p className={styles.safetyWarning}>⚠️ {t('crownHunt.safetyWarning')}</p>

        <label className={styles.label}>
          <input
            type="checkbox"
            checked={checked}
            onChange={(e) => setChecked(e.target.checked)}
          />
          {' '}{t('crownHunt.safetyConfirmed')}
        </label>

        <label className={styles.label} style={{ marginTop: '12px' }}>
          {t('crownHunt.safetyNoteLabel')} *
          <textarea
            className={styles.input}
            value={safetyNote}
            onChange={(e) => setSafetyNote(e.target.value)}
            placeholder={t('crownHunt.safetyNotePlaceholder')}
            rows={3}
            required
          />
        </label>

        {error !== null && <p className={styles.errorText}>{error}</p>}

        <div className={styles.formActions}>
          <button className={styles.btnSecondary} onClick={onCancel} disabled={isConfirming}>
            {t('crownHunt.cancel')}
          </button>
          <button
            className={styles.btnPrimary}
            onClick={() => void onConfirm(safetyNote)}
            disabled={isConfirming || !checked || safetyNote.trim().length < 3}
          >
            {isConfirming ? t('crownHunt.loading') : t('crownHunt.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------
// Points tab
// ---------------------------------------------------------------------------

interface PointsTabProps {
  points: AdminCrownHuntPointSummary[];
  isLoading: boolean;
  error: string | null;
  onRefresh: () => void;
  onActivate: (point: AdminCrownHuntPointSummary) => void;
  onPause: (point: AdminCrownHuntPointSummary) => void;
  onEdit: (point: AdminCrownHuntPointSummary) => void;
  onCreateNew: () => void;
}

const PointsTab = ({
  points,
  isLoading,
  error,
  onRefresh,
  onActivate,
  onPause,
  onEdit,
  onCreateNew,
}: PointsTabProps) => (
  <section>
    <div className={styles.tabHeader}>
      <button className={styles.btnPrimary} onClick={onCreateNew}>
        {t('crownHunt.createPoint')}
      </button>
    </div>

    {isLoading && <p className={styles.loadingText}>{t('crownHunt.loading')}</p>}
    {error !== null && (
      <p className={styles.errorText}>
        {t('crownHunt.error')}{' '}
        <button className={styles.linkButton} onClick={onRefresh}>
          {t('crownHunt.retry')}
        </button>
      </p>
    )}

    {!isLoading && !error && points.length === 0 && (
      <p className={styles.emptyText}>{t('crownHunt.noPoints')}</p>
    )}

    {points.length > 0 && (
      <table className={styles.table}>
        <thead>
          <tr>
            <th>{t('crownHunt.columnTitle')}</th>
            <th>{t('crownHunt.columnStatus')}</th>
            <th>{t('crownHunt.columnReward')}</th>
            <th>{t('crownHunt.columnRepeatRule')}</th>
            <th>{t('crownHunt.columnCreatedAt')}</th>
            <th>{t('crownHunt.columnActions')}</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.pointId}>
              <td>{point.title}</td>
              <td>
                <span className={`${styles.badge} ${styles[`badge_${point.status}`] ?? ''}`}>
                  {statusLabel(point.status)}
                </span>
              </td>
              <td>{point.rewardPoints}</td>
              <td>{point.repeatRule}</td>
              <td>{formatDate(point.createdAt)}</td>
              <td className={styles.actions}>
                {(point.status === 'draft' || point.status === 'paused') && (
                  <button className={styles.btnSmall} onClick={() => onEdit(point)}>
                    {t('crownHunt.editPoint')}
                  </button>
                )}
                {point.status === 'draft' && (
                  <button className={styles.btnSmallPrimary} onClick={() => onActivate(point)}>
                    {t('crownHunt.activatePoint')}
                  </button>
                )}
                {point.status === 'active' && (
                  <button className={styles.btnSmallWarning} onClick={() => onPause(point)}>
                    {t('crownHunt.pausePoint')}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    )}
  </section>
);

// ---------------------------------------------------------------------------
// Claims tab
// ---------------------------------------------------------------------------

interface ClaimsTabProps {
  claims: AdminCrownHuntClaimSummary[];
  isLoading: boolean;
  error: string | null;
  onRefresh: () => void;
  filterRiskReview: boolean;
  onToggleRiskFilter: () => void;
}

const ClaimsTab = ({
  claims,
  isLoading,
  error,
  onRefresh,
  filterRiskReview,
  onToggleRiskFilter,
}: ClaimsTabProps) => (
  <section>
    <div className={styles.tabHeader}>
      <label className={styles.checkLabel}>
        <input type="checkbox" checked={filterRiskReview} onChange={onToggleRiskFilter} />
        {' '}{t('crownHunt.suspiciousClaims')}
      </label>
    </div>

    {isLoading && <p className={styles.loadingText}>{t('crownHunt.loading')}</p>}
    {error !== null && (
      <p className={styles.errorText}>
        {t('crownHunt.error')}{' '}
        <button className={styles.linkButton} onClick={onRefresh}>
          {t('crownHunt.retry')}
        </button>
      </p>
    )}

    {!isLoading && !error && claims.length === 0 && (
      <p className={styles.emptyText}>{t('crownHunt.noClaims')}</p>
    )}

    {claims.length > 0 && (
      <table className={styles.table}>
        <thead>
          <tr>
            <th>{t('crownHunt.columnPoint')}</th>
            <th>{t('crownHunt.columnUser')}</th>
            <th>{t('crownHunt.columnResult')}</th>
            <th>{t('crownHunt.columnClaimedAt')}</th>
            <th>{t('crownHunt.columnRiskReasons')}</th>
          </tr>
        </thead>
        <tbody>
          {claims.map((claim) => (
            <tr key={claim.claimId}>
              <td>{claim.pointTitle}</td>
              <td title={claim.userId}>{claim.userId.slice(0, 8)}…</td>
              <td>
                <span
                  className={`${styles.badge} ${claim.result === 'risk_review' ? styles.badge_risk : claim.result === 'awarded' ? styles.badge_active : styles.badge_draft}`}
                >
                  {claim.result}
                </span>
              </td>
              <td>{formatDate(claim.claimedAt)}</td>
              <td>
                {/* Only show risk reason categories — no raw signals or thresholds */}
                {claim.riskReasonCategories.length > 0
                  ? claim.riskReasonCategories.join(', ')
                  : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    )}
  </section>
);

// ---------------------------------------------------------------------------
// Spawn cells tab (auto-spawn area allow-list)
// ---------------------------------------------------------------------------

/** Fully-resolved target for an approval action + display strings. */
interface ApproveTarget {
  cellKey: string;
  center: string;
  source: SpawnCellTarget;
}

function spawnCellStateLabel(approved: boolean): string {
  return approved ? t('crownHunt.spawnCellStateApproved') : t('crownHunt.spawnCellStateRevoked');
}

interface AddAreaFormProps {
  onApprove: (target: ApproveTarget) => void;
  onCancel: () => void;
}

/** Pick a point on the map; the whole ~1.1 km grid cell it lands in is approved. */
const AddAreaForm = ({ onApprove, onCancel }: AddAreaFormProps) => {
  const [lat, setLat] = useState('');
  const [lng, setLng] = useState('');

  const latNum = Number.parseFloat(lat);
  const lngNum = Number.parseFloat(lng);
  const hasPoint =
    Number.isFinite(latNum) &&
    Number.isFinite(lngNum) &&
    latNum >= -90 &&
    latNum <= 90 &&
    lngNum >= -180 &&
    lngNum <= 180;
  const cellKey = hasPoint ? cellKeyForCoords(latNum, lngNum) : null;

  return (
    <div className={styles.form}>
      <MapLocationPicker
        latitude={lat}
        longitude={lng}
        onChange={(nextLat, nextLng) => {
          setLat(nextLat);
          setLng(nextLng);
        }}
        labelLat={t('crownHunt.formLatitudeLabel')}
        labelLng={t('crownHunt.formLongitudeLabel')}
        helpText={t('crownHunt.spawnCellPickLabel')}
        unavailableText={t('map.unavailable')}
        loadErrorText={t('map.loadError')}
        required
        labelClassName={styles.label}
        inputClassName={styles.input}
      />

      {cellKey !== null ? (
        <p className={styles.introText}>
          {t('crownHunt.spawnCellResolvedLabel')}:{' '}
          <span className={styles.cellKey}>{cellKey}</span> — {formatCellCenter(cellKey)}
          <br />
          {t('crownHunt.spawnCellResolvedHint')}
        </p>
      ) : (
        <p className={styles.introText}>{t('crownHunt.spawnCellInvalidPoint')}</p>
      )}

      <div className={styles.formActions}>
        <button type="button" className={styles.btnSecondary} onClick={onCancel}>
          {t('crownHunt.cancel')}
        </button>
        <button
          type="button"
          className={styles.btnPrimary}
          disabled={cellKey === null}
          onClick={() => {
            if (cellKey === null) return;
            onApprove({
              cellKey,
              center: formatCellCenter(cellKey),
              source: { latitude: latNum, longitude: lngNum },
            });
          }}
        >
          {t('crownHunt.spawnCellApprove')}
        </button>
      </div>
    </div>
  );
};

interface ApproveCellModalProps {
  target: ApproveTarget;
  onConfirm: (note: string) => Promise<void>;
  onCancel: () => void;
  isConfirming: boolean;
  error: string | null;
}

const ApproveCellModal = ({ target, onConfirm, onCancel, isConfirming, error }: ApproveCellModalProps) => {
  const [note, setNote] = useState('');
  const [checked, setChecked] = useState(false);

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{t('crownHunt.spawnCellApproveConfirmTitle')}</h2>
        <p className={styles.modalBody}>
          {t('crownHunt.spawnCellApproveConfirmBody')
            .replace('{cell}', target.cellKey)
            .replace('{center}', target.center)}
        </p>
        <p className={styles.safetyWarning}>⚠️ {t('crownHunt.spawnCellSafetyWarning')}</p>

        <label className={styles.label}>
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />{' '}
          {t('crownHunt.spawnCellSafeAreaConfirm')}
        </label>

        <label className={styles.label} style={{ marginTop: '12px' }}>
          {t('crownHunt.spawnCellApproveNoteLabel')} *
          <textarea
            className={styles.input}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder={t('crownHunt.spawnCellApproveNotePlaceholder')}
            rows={3}
            required
          />
        </label>

        {error !== null && <p className={styles.errorText}>{error}</p>}

        <div className={styles.formActions}>
          <button className={styles.btnSecondary} onClick={onCancel} disabled={isConfirming}>
            {t('crownHunt.cancel')}
          </button>
          <button
            className={styles.btnPrimary}
            onClick={() => void onConfirm(note)}
            disabled={isConfirming || !checked || note.trim().length < 3}
          >
            {isConfirming ? t('crownHunt.loading') : t('crownHunt.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
};

interface RevokeCellModalProps {
  cellKey: string;
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
  isConfirming: boolean;
  error: string | null;
}

const RevokeCellModal = ({ cellKey, onConfirm, onCancel, isConfirming, error }: RevokeCellModalProps) => {
  const [reason, setReason] = useState('');

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{t('crownHunt.spawnCellRevokeConfirmTitle')}</h2>
        <p className={styles.modalBody}>
          {t('crownHunt.spawnCellRevokeConfirmBody').replace('{cell}', cellKey)}
        </p>

        <label className={styles.label}>
          {t('crownHunt.spawnCellRevokeReasonLabel')}
          <textarea
            className={styles.input}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={t('crownHunt.spawnCellRevokeReasonPlaceholder')}
            rows={3}
          />
        </label>

        {error !== null && <p className={styles.errorText}>{error}</p>}

        <div className={styles.formActions}>
          <button className={styles.btnSecondary} onClick={onCancel} disabled={isConfirming}>
            {t('crownHunt.cancel')}
          </button>
          <button
            className={styles.btnSmallWarning}
            onClick={() => void onConfirm(reason)}
            disabled={isConfirming}
          >
            {isConfirming ? t('crownHunt.loading') : t('crownHunt.spawnCellRevoke')}
          </button>
        </div>
      </div>
    </div>
  );
};

interface SpawnCellsTabProps {
  cells: AdminSpawnCellSummary[];
  isLoading: boolean;
  error: string | null;
  onRefresh: () => void;
  showAddForm: boolean;
  onToggleAddForm: (show: boolean) => void;
  onApproveTarget: (target: ApproveTarget) => void;
  onRevoke: (cellKey: string) => void;
}

const SpawnCellsTab = ({
  cells,
  isLoading,
  error,
  onRefresh,
  showAddForm,
  onToggleAddForm,
  onApproveTarget,
  onRevoke,
}: SpawnCellsTabProps) => (
  <section>
    <p className={styles.introText}>{t('crownHunt.spawnCellsIntro')}</p>
    <div className={styles.noticeBanner}>{t('crownHunt.spawnCellsFlagNotice')}</div>

    <div className={styles.tabHeader}>
      {!showAddForm && (
        <button className={styles.btnPrimary} onClick={() => onToggleAddForm(true)}>
          {t('crownHunt.spawnCellAddButton')}
        </button>
      )}
    </div>

    {showAddForm && (
      <div className={styles.formContainer}>
        <AddAreaForm onApprove={onApproveTarget} onCancel={() => onToggleAddForm(false)} />
      </div>
    )}

    {isLoading && <p className={styles.loadingText}>{t('crownHunt.loading')}</p>}
    {error !== null && (
      <p className={styles.errorText}>
        {t('crownHunt.error')}{' '}
        <button className={styles.linkButton} onClick={onRefresh}>
          {t('crownHunt.retry')}
        </button>
      </p>
    )}

    {!isLoading && !error && cells.length === 0 && (
      <p className={styles.emptyText}>{t('crownHunt.spawnCellNoCells')}</p>
    )}

    {cells.length > 0 && (
      <table className={styles.table}>
        <thead>
          <tr>
            <th>{t('crownHunt.spawnCellColumnCell')}</th>
            <th>{t('crownHunt.spawnCellColumnCenter')}</th>
            <th>{t('crownHunt.spawnCellColumnState')}</th>
            <th>{t('crownHunt.spawnCellColumnApprovedBy')}</th>
            <th>{t('crownHunt.spawnCellColumnApprovedAt')}</th>
            <th>{t('crownHunt.spawnCellColumnActions')}</th>
          </tr>
        </thead>
        <tbody>
          {cells.map((cell) => (
            <tr key={cell.cellKey}>
              <td className={styles.cellKey}>{cell.cellKey}</td>
              <td>{formatCellCenter(cell.cellKey)}</td>
              <td>
                <span
                  className={`${styles.badge} ${cell.approved ? styles.badge_active : styles.badge_ended}`}
                >
                  {spawnCellStateLabel(cell.approved)}
                </span>
              </td>
              <td title={cell.approved ? cell.approvedByUserId ?? '' : cell.revokedByUserId ?? ''}>
                {(() => {
                  const who = cell.approved ? cell.approvedByUserId : cell.revokedByUserId;
                  return who ? `${who.slice(0, 8)}…` : '—';
                })()}
              </td>
              <td>{formatDate(cell.approved ? cell.approvedAt : cell.revokedAt)}</td>
              <td className={styles.actions}>
                {cell.approved ? (
                  <button className={styles.btnSmallWarning} onClick={() => onRevoke(cell.cellKey)}>
                    {t('crownHunt.spawnCellRevoke')}
                  </button>
                ) : (
                  <button
                    className={styles.btnSmallPrimary}
                    onClick={() =>
                      onApproveTarget({
                        cellKey: cell.cellKey,
                        center: formatCellCenter(cell.cellKey),
                        source: { cellKey: cell.cellKey },
                      })
                    }
                  >
                    {t('crownHunt.spawnCellReapprove')}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    )}
  </section>
);

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

type Tab = 'points' | 'claims' | 'spawnCells';

export default function KronjaktPage() {
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const [activeTab, setActiveTab] = useState<Tab>('points');

  // Points state
  const [points, setPoints] = useState<AdminCrownHuntPointSummary[]>([]);
  const [pointsLoading, setPointsLoading] = useState(false);
  const [pointsError, setPointsError] = useState<string | null>(null);

  // Claims state
  const [claims, setClaims] = useState<AdminCrownHuntClaimSummary[]>([]);
  const [claimsLoading, setClaimsLoading] = useState(false);
  const [claimsError, setClaimsError] = useState<string | null>(null);
  const [filterRiskReview, setFilterRiskReview] = useState(false);

  // Form state
  const [showForm, setShowForm] = useState(false);
  const [editingPoint, setEditingPoint] = useState<AdminCrownHuntPointSummary | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // Activate modal state
  const [activatingPoint, setActivatingPoint] = useState<AdminCrownHuntPointSummary | null>(null);
  const [isActivating, setIsActivating] = useState(false);
  const [activateError, setActivateError] = useState<string | null>(null);

  // Spawn cells state
  const [spawnCells, setSpawnCells] = useState<AdminSpawnCellSummary[]>([]);
  const [spawnCellsLoading, setSpawnCellsLoading] = useState(false);
  const [spawnCellsError, setSpawnCellsError] = useState<string | null>(null);
  const [showAddArea, setShowAddArea] = useState(false);
  const [approveTarget, setApproveTarget] = useState<ApproveTarget | null>(null);
  const [isApprovingCell, setIsApprovingCell] = useState(false);
  const [approveCellError, setApproveCellError] = useState<string | null>(null);
  const [revokeCellKey, setRevokeCellKey] = useState<string | null>(null);
  const [isRevokingCell, setIsRevokingCell] = useState(false);
  const [revokeCellError, setRevokeCellError] = useState<string | null>(null);

  // Flash messages
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // ---------------------------------------------------------------------------
  // Data loading
  // ---------------------------------------------------------------------------

  const loadPoints = useCallback(async () => {
    setPointsLoading(true);
    setPointsError(null);
    try {
      const res = await adminListCrownHuntPoints();
      if (!mountedRef.current) return;
      setPoints(res.data.points);
    } catch {
      if (!mountedRef.current) return;
      setPointsError(t('crownHunt.error'));
    } finally {
      if (mountedRef.current) setPointsLoading(false);
    }
  }, []);

  const loadClaims = useCallback(async (riskOnly: boolean) => {
    setClaimsLoading(true);
    setClaimsError(null);
    try {
      const filter: CrownHuntClaimResult | undefined = riskOnly ? 'risk_review' : undefined;
      const res = await adminListCrownHuntClaims(1, filter);
      if (!mountedRef.current) return;
      setClaims(res.data.claims);
    } catch {
      if (!mountedRef.current) return;
      setClaimsError(t('crownHunt.error'));
    } finally {
      if (mountedRef.current) setClaimsLoading(false);
    }
  }, []);

  const loadSpawnCells = useCallback(async () => {
    setSpawnCellsLoading(true);
    setSpawnCellsError(null);
    try {
      const cells = await adminListSpawnCells();
      if (!mountedRef.current) return;
      setSpawnCells(cells);
    } catch {
      if (!mountedRef.current) return;
      setSpawnCellsError(t('crownHunt.error'));
    } finally {
      if (mountedRef.current) setSpawnCellsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadPoints();
  }, [loadPoints]);

  useEffect(() => {
    if (activeTab === 'claims') {
      void loadClaims(filterRiskReview);
    }
  }, [activeTab, filterRiskReview, loadClaims]);

  useEffect(() => {
    if (activeTab === 'spawnCells') {
      void loadSpawnCells();
    }
  }, [activeTab, loadSpawnCells]);

  // ---------------------------------------------------------------------------
  // Form handlers
  // ---------------------------------------------------------------------------

  const showSuccess = (msg: string) => {
    setSuccessMsg(msg);
    setTimeout(() => { if (mountedRef.current) setSuccessMsg(null); }, 3000);
  };

  const handleSave = useCallback(
    async (form: PointFormState) => {
      setIsSaving(true);
      setSaveError(null);
      try {
        const base = {
          // A Crown point is just a collectable on the map — a title is
          // optional, so an empty box is sent as "no title" rather than ''.
          title: form.title.trim() || undefined,
          description: form.description || undefined,
          latitude: parseFloat(form.latitude),
          longitude: parseFloat(form.longitude),
          geofenceRadiusMeters: parseInt(form.geofenceRadiusMeters, 10),
          rewardPoints: parseInt(form.rewardPoints, 10),
          repeatRule: form.repeatRule,
          availableFrom: localToIso(form.availableFrom) ?? undefined,
          availableUntil: localToIso(form.availableUntil) ?? undefined,
        };

        if (editingPoint !== null) {
          await adminUpdateCrownHuntPoint(editingPoint.pointId, base as AdminUpdateCrownHuntPointRequest);
          showSuccess(t('crownHunt.updateSuccess'));
        } else {
          await adminCreateCrownHuntPoint(base as AdminCreateCrownHuntPointRequest);
          showSuccess(t('crownHunt.createSuccess'));
        }

        if (!mountedRef.current) return;
        setShowForm(false);
        setEditingPoint(null);
        void loadPoints();
      } catch {
        if (!mountedRef.current) return;
        setSaveError(t('crownHunt.error'));
      } finally {
        if (mountedRef.current) setIsSaving(false);
      }
    },
    [editingPoint, loadPoints],
  );

  const handleActivateConfirm = useCallback(
    async (safetyNote: string) => {
      if (!activatingPoint) return;
      setIsActivating(true);
      setActivateError(null);
      try {
        await adminActivateCrownHuntPoint(activatingPoint.pointId, safetyNote);
        if (!mountedRef.current) return;
        setActivatingPoint(null);
        showSuccess(t('crownHunt.activateSuccess'));
        void loadPoints();
      } catch {
        if (!mountedRef.current) return;
        setActivateError(t('crownHunt.error'));
      } finally {
        if (mountedRef.current) setIsActivating(false);
      }
    },
    [activatingPoint, loadPoints],
  );

  const handlePause = useCallback(
    async (point: AdminCrownHuntPointSummary) => {
      try {
        await adminPauseCrownHuntPoint(point.pointId);
        if (!mountedRef.current) return;
        showSuccess(t('crownHunt.pauseSuccess'));
        void loadPoints();
      } catch {
        // Non-critical; user can retry.
      }
    },
    [loadPoints],
  );

  // ---------------------------------------------------------------------------
  // Spawn cell handlers
  // ---------------------------------------------------------------------------

  const handleApproveCell = useCallback(
    async (note: string) => {
      if (!approveTarget) return;
      setIsApprovingCell(true);
      setApproveCellError(null);
      try {
        await adminApproveSpawnCell(approveTarget.source, note);
        if (!mountedRef.current) return;
        setApproveTarget(null);
        setShowAddArea(false);
        showSuccess(t('crownHunt.spawnCellApproveSuccess'));
        void loadSpawnCells();
      } catch {
        if (!mountedRef.current) return;
        setApproveCellError(t('crownHunt.error'));
      } finally {
        if (mountedRef.current) setIsApprovingCell(false);
      }
    },
    [approveTarget, loadSpawnCells],
  );

  const handleRevokeCell = useCallback(
    async (reason: string) => {
      if (!revokeCellKey) return;
      setIsRevokingCell(true);
      setRevokeCellError(null);
      try {
        await adminRevokeSpawnCell({ cellKey: revokeCellKey }, reason);
        if (!mountedRef.current) return;
        setRevokeCellKey(null);
        showSuccess(t('crownHunt.spawnCellRevokeSuccess'));
        void loadSpawnCells();
      } catch {
        if (!mountedRef.current) return;
        setRevokeCellError(t('crownHunt.error'));
      } finally {
        if (mountedRef.current) setIsRevokingCell(false);
      }
    },
    [revokeCellKey, loadSpawnCells],
  );

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{t('crownHunt.pageTitle')}</h1>
        <p className={styles.description}>{t('crownHunt.pageDescription')}</p>
      </div>

      {/* Success flash */}
      {successMsg !== null && (
        <div className={styles.successBanner} role="status" aria-live="polite">
          {successMsg}
        </div>
      )}

      {/* Tab bar */}
      <div className={styles.tabs} role="tablist">
        <button
          role="tab"
          aria-selected={activeTab === 'points'}
          className={activeTab === 'points' ? styles.tabActive : styles.tab}
          onClick={() => setActiveTab('points')}
        >
          {t('crownHunt.pointsTab')}
        </button>
        <button
          role="tab"
          aria-selected={activeTab === 'claims'}
          className={activeTab === 'claims' ? styles.tabActive : styles.tab}
          onClick={() => setActiveTab('claims')}
        >
          {t('crownHunt.claimsTab')}
        </button>
        <button
          role="tab"
          aria-selected={activeTab === 'spawnCells'}
          className={activeTab === 'spawnCells' ? styles.tabActive : styles.tab}
          onClick={() => setActiveTab('spawnCells')}
        >
          {t('crownHunt.spawnCellsTab')}
        </button>
      </div>

      {/* Point create/edit form */}
      {showForm && (
        <div className={styles.formContainer}>
          <PointForm
            initial={editingPoint !== null ? pointToForm(editingPoint) : EMPTY_FORM}
            onSave={handleSave}
            onCancel={() => {
              setShowForm(false);
              setEditingPoint(null);
              setSaveError(null);
            }}
            isSaving={isSaving}
            saveError={saveError}
          />
        </div>
      )}

      {/* Active tab content */}
      {activeTab === 'points' && !showForm && (
        <PointsTab
          points={points}
          isLoading={pointsLoading}
          error={pointsError}
          onRefresh={() => void loadPoints()}
          onActivate={(p) => {
            setActivatingPoint(p);
            setActivateError(null);
          }}
          onPause={(p) => void handlePause(p)}
          onEdit={(p) => {
            setEditingPoint(p);
            setShowForm(true);
            setSaveError(null);
          }}
          onCreateNew={() => {
            setEditingPoint(null);
            setShowForm(true);
            setSaveError(null);
          }}
        />
      )}

      {activeTab === 'claims' && (
        <ClaimsTab
          claims={claims}
          isLoading={claimsLoading}
          error={claimsError}
          onRefresh={() => void loadClaims(filterRiskReview)}
          filterRiskReview={filterRiskReview}
          onToggleRiskFilter={() => setFilterRiskReview((v) => !v)}
        />
      )}

      {activeTab === 'spawnCells' && (
        <SpawnCellsTab
          cells={spawnCells}
          isLoading={spawnCellsLoading}
          error={spawnCellsError}
          onRefresh={() => void loadSpawnCells()}
          showAddForm={showAddArea}
          onToggleAddForm={(show) => {
            setShowAddArea(show);
            if (!show) setApproveTarget(null);
          }}
          onApproveTarget={(target) => {
            setApproveTarget(target);
            setApproveCellError(null);
          }}
          onRevoke={(cellKey) => {
            setRevokeCellKey(cellKey);
            setRevokeCellError(null);
          }}
        />
      )}

      {/* Spawn cell approve confirmation modal */}
      {approveTarget !== null && (
        <ApproveCellModal
          target={approveTarget}
          onConfirm={handleApproveCell}
          onCancel={() => {
            setApproveTarget(null);
            setApproveCellError(null);
          }}
          isConfirming={isApprovingCell}
          error={approveCellError}
        />
      )}

      {/* Spawn cell revoke confirmation modal */}
      {revokeCellKey !== null && (
        <RevokeCellModal
          cellKey={revokeCellKey}
          onConfirm={handleRevokeCell}
          onCancel={() => {
            setRevokeCellKey(null);
            setRevokeCellError(null);
          }}
          isConfirming={isRevokingCell}
          error={revokeCellError}
        />
      )}

      {/* Activate confirmation modal */}
      {activatingPoint !== null && (
        <ActivateModal
          pointTitle={activatingPoint.title}
          onConfirm={handleActivateConfirm}
          onCancel={() => {
            setActivatingPoint(null);
            setActivateError(null);
          }}
          isConfirming={isActivating}
          error={activateError}
        />
      )}
    </div>
  );
}
