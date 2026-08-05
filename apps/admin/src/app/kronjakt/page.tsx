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
// Crown rarity tiers
//
// A Crown is a map COLLECTABLE (Pokémon GO–style), not a titled document. Its
// reward is chosen from a rarity TIER preset rather than a free-typed number.
// This is a FRONTEND-ONLY concept: the backend point model only stores
// `rewardPoints` (+ a collect radius via geofenceRadiusMeters), so a tier
// resolves to its preset numbers on submit. The KP values, the fixed 75 m
// collect radius and the per-tier lifespans are the canonical figures from the
// gamification spec's CROWN_RARITY_TABLE (docs/gamification-system.md).
// ---------------------------------------------------------------------------

type CrownTier = 'common' | 'rare' | 'epic' | 'legendary';

interface CrownTierSpec {
  /** Kronpoäng reward the tier resolves to. */
  points: number;
  /**
   * Spec lifespan in hours from the gamification doc's CROWN_RARITY_TABLE.
   * Informational only here: the BACKEND owns crown TTL; hand-placed admin
   * points expose availability via the optional availableFrom/until window, so
   * nothing on this form reads ttlHours.
   */
  ttlHours: number;
  /** Distinct colour cue so rarity reads at a glance (rare vs legendary). */
  color: string;
}

const CROWN_TIER_ORDER: readonly CrownTier[] = ['common', 'rare', 'epic', 'legendary'];

const CROWN_TIER_TABLE: Record<CrownTier, CrownTierSpec> = {
  common: { points: 10, ttlHours: 6, color: '#9aa0a6' },
  rare: { points: 25, ttlHours: 12, color: '#3b82f6' },
  epic: { points: 100, ttlHours: 24, color: '#a855f7' },
  legendary: { points: 500, ttlHours: 48, color: '#f5b301' },
};

const DEFAULT_CROWN_TIER: CrownTier = 'common';

/** Fixed collect radius for every Crown (spec COLLECT_RADIUS_METERS). */
const CROWN_COLLECT_RADIUS_METERS = 75;

function tierLabel(tier: CrownTier): string {
  return t(`crownHunt.tier_${tier}`);
}

/**
 * The tier whose preset reward EXACTLY equals `points`, or null when it matches
 * none (a legacy/custom reward from the old free-number UI, which allowed
 * 1–1000 KP). Returning null — rather than defaulting to Common — is what stops
 * an edit from silently rewriting a custom reward down to 10 KP.
 */
function matchTier(points: number): CrownTier | null {
  return CROWN_TIER_ORDER.find((tier) => CROWN_TIER_TABLE[tier].points === points) ?? null;
}

/** Display label + colour for any stored reward, incl. legacy custom values. */
function tierDisplay(points: number): { label: string; color: string } {
  const tier = matchTier(points);
  if (tier) return { label: tierLabel(tier), color: CROWN_TIER_TABLE[tier].color };
  return { label: t('crownHunt.tier_custom'), color: 'var(--text-secondary)' };
}

// ---------------------------------------------------------------------------
// Point form
// ---------------------------------------------------------------------------

interface PointFormState {
  latitude: string;
  longitude: string;
  /**
   * The tier the admin has selected, or 'custom' when editing a legacy point
   * whose stored reward maps to no tier. `rewardPoints` is the source of truth
   * that is actually saved; picking a tier button overwrites it with the
   * preset. While 'custom' is held the original reward is preserved untouched.
   */
  tier: CrownTier | 'custom';
  rewardPoints: number;
  /**
   * The collect radius that will be saved. New crowns default to the fixed
   * 75 m; editing an existing point keeps its stored radius verbatim (the form
   * has no geofence control, so an unrelated edit must not change it).
   */
  geofenceRadiusMeters: number;
  repeatRule: 'once' | 'daily' | 'weekly';
  /**
   * Headcount mode, INDEPENDENT of the rarity tier: 'everyone' = unlimited
   * distinct collectors (default, best for events), 'limited' = only the first
   * N distinct collectors, then the crown is done. `maxCollectors` holds the N
   * input (as a string) and is read only while mode is 'limited'.
   */
  collectorMode: 'everyone' | 'limited';
  maxCollectors: string;
  availableFrom: string;
  availableUntil: string;
}

const EMPTY_FORM: PointFormState = {
  latitude: '',
  longitude: '',
  tier: DEFAULT_CROWN_TIER,
  rewardPoints: CROWN_TIER_TABLE[DEFAULT_CROWN_TIER].points,
  geofenceRadiusMeters: CROWN_COLLECT_RADIUS_METERS,
  repeatRule: 'once',
  collectorMode: 'everyone',
  maxCollectors: '',
  availableFrom: '',
  availableUntil: '',
};

function pointToForm(point: AdminCrownHuntPointSummary): PointFormState {
  return {
    latitude: String(point.latitude),
    longitude: String(point.longitude),
    // Keep the stored reward verbatim; only mark it 'custom' when it maps to no
    // tier so saving without touching the selector never changes it.
    tier: matchTier(point.rewardPoints) ?? 'custom',
    rewardPoints: point.rewardPoints,
    // Preserve the existing collect radius (there is no geofence UI to change
    // it) so an edit never silently rewrites it to the 75 m new-point default.
    geofenceRadiusMeters: point.geofenceRadiusMeters,
    repeatRule: point.repeatRule,
    // null maxCollectors (incl. legacy points with no field) = unlimited.
    collectorMode: point.maxCollectors != null ? 'limited' : 'everyone',
    maxCollectors: point.maxCollectors != null ? String(point.maxCollectors) : '',
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

  // Picking a tier leaves any 'custom' state and overwrites the saved reward
  // with that tier's preset — the only path that changes rewardPoints.
  const selectTier = (tier: CrownTier) =>
    setForm((prev) => ({ ...prev, tier, rewardPoints: CROWN_TIER_TABLE[tier].points }));

  const selectCollectorMode = (collectorMode: PointFormState['collectorMode']) =>
    setForm((prev) => ({ ...prev, collectorMode }));

  // Only meaningful in 'limited' mode: N must be an integer >= 1. Parse with
  // Number (not parseInt) so "1.5"/"2.9" are NOT truncated to a valid integer —
  // Number.isInteger then rejects any decimal; empty → 0, rejected by the >= 1
  // check. This blocks submit and shows an inline message; the backend
  // re-validates.
  const parsedMaxCollectors = Number(form.maxCollectors);
  const maxCollectorsInvalid =
    form.collectorMode === 'limited' &&
    !(Number.isInteger(parsedMaxCollectors) && parsedMaxCollectors >= 1);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        // Defense-in-depth: the submit button is disabled while the N is
        // invalid, but Enter-key submit can bypass a disabled button, so guard
        // here too (the backend re-validates regardless).
        if (maxCollectorsInvalid) return;
        void onSave(form);
      }}
      className={styles.form}
    >
      <p className={styles.introText}>{t('crownHunt.formCollectableHint')}</p>

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
        radiusMeters={form.geofenceRadiusMeters}
        required
        labelClassName={styles.label}
        inputClassName={styles.input}
      />

      <fieldset className={styles.tierFieldset}>
        <legend className={styles.label}>{t('crownHunt.formTierLabel')}</legend>
        {/* Toggle-button group: the <fieldset>/<legend> labels the set and each
            button reports its state via aria-pressed. This deliberately avoids
            role="radiogroup"/"radio", which would also require roving-tabindex
            + arrow-key navigation to be a faithful ARIA radio pattern. */}
        <div className={styles.tierGrid}>
          {CROWN_TIER_ORDER.map((tier) => {
            const spec = CROWN_TIER_TABLE[tier];
            const selected = form.tier === tier;
            return (
              <button
                type="button"
                key={tier}
                aria-pressed={selected}
                className={`${styles.tierOption} ${selected ? styles.tierOptionSelected : ''}`}
                style={{ ['--tier-color' as string]: spec.color }}
                onClick={() => selectTier(tier)}
              >
                <span className={styles.tierCrown} style={{ color: spec.color }} aria-hidden="true">
                  ♛
                </span>
                <span className={styles.tierName}>{tierLabel(tier)}</span>
                <span className={styles.tierPoints}>{spec.points} KP</span>
              </button>
            );
          })}
        </div>
        {/* Legacy point whose stored reward maps to no tier: show the real value
            read-only so saving without picking a tier keeps it (no silent
            coercion to Common). Picking any tier above replaces it. */}
        {form.tier === 'custom' && (
          <p className={styles.introText}>
            {t('crownHunt.tierCustomNotice').replace('{points}', String(form.rewardPoints))}
          </p>
        )}
      </fieldset>

      <fieldset className={styles.tierFieldset}>
        <legend className={styles.label}>{t('crownHunt.formCollectorsLabel')}</legend>
        {/* Same toggle-button a11y pattern as the tier selector: the
            <fieldset>/<legend> labels the set, each button reports aria-pressed.
            Headcount is INDEPENDENT of the rarity tier above. */}
        <div className={styles.collectorModeGrid}>
          <button
            type="button"
            aria-pressed={form.collectorMode === 'everyone'}
            className={`${styles.collectorOption} ${form.collectorMode === 'everyone' ? styles.collectorOptionSelected : ''}`}
            onClick={() => selectCollectorMode('everyone')}
          >
            <span className={styles.collectorOptionName}>{t('crownHunt.collectorsEveryone')}</span>
            <span className={styles.collectorOptionDesc}>{t('crownHunt.collectorsEveryoneHint')}</span>
          </button>
          <button
            type="button"
            aria-pressed={form.collectorMode === 'limited'}
            className={`${styles.collectorOption} ${form.collectorMode === 'limited' ? styles.collectorOptionSelected : ''}`}
            onClick={() => selectCollectorMode('limited')}
          >
            <span className={styles.collectorOptionName}>{t('crownHunt.collectorsLimited')}</span>
            <span className={styles.collectorOptionDesc}>{t('crownHunt.collectorsLimitedHint')}</span>
          </button>
        </div>
        {form.collectorMode === 'limited' && (
          <label className={`${styles.label} ${styles.collectorLimitRow}`}>
            {t('crownHunt.collectorsLimitLabel')}
            <input
              className={styles.input}
              type="number"
              min={1}
              step={1}
              inputMode="numeric"
              value={form.maxCollectors}
              onChange={(e) => set('maxCollectors', e.target.value)}
              aria-invalid={maxCollectorsInvalid}
            />
            {maxCollectorsInvalid && (
              <span className={styles.errorText}>{t('crownHunt.collectorsLimitInvalid')}</span>
            )}
          </label>
        )}
      </fieldset>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('crownHunt.formRepeatRuleLabel')}
          <select
            className={styles.input}
            value={form.repeatRule}
            onChange={(e) => set('repeatRule', e.target.value as PointFormState['repeatRule'])}
          >
            <option value="once">{t('crownHunt.repeatOnce')}</option>
            <option value="daily">{t('crownHunt.repeatDaily')}</option>
            <option value="weekly">{t('crownHunt.repeatWeekly')}</option>
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
        <button type="submit" className={styles.btnPrimary} disabled={isSaving || maxCollectorsInvalid}>
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
  point: AdminCrownHuntPointSummary;
  onConfirm: (note: string) => Promise<void>;
  onCancel: () => void;
  isConfirming: boolean;
  error: string | null;
}

const ActivateModal = ({ point, onConfirm, onCancel, isConfirming, error }: ActivateModalProps) => {
  const [safetyNote, setSafetyNote] = useState('');
  const [checked, setChecked] = useState(false);
  const display = tierDisplay(point.rewardPoints);

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{t('crownHunt.activateConfirmTitle')}</h2>
        {/* A Crown is a textless collectable — identify it by rarity + reward,
            not a title. */}
        <p className={styles.modalBody}>
          <span className={styles.tierCrown} style={{ color: display.color }} aria-hidden="true">
            ♛
          </span>{' '}
          {display.label} · {point.rewardPoints} KP · {point.latitude.toFixed(5)}, {point.longitude.toFixed(5)}
        </p>
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
            <th>{t('crownHunt.columnRarity')}</th>
            <th>{t('crownHunt.columnStatus')}</th>
            <th>{t('crownHunt.columnReward')}</th>
            <th>{t('crownHunt.columnCollectors')}</th>
            <th>{t('crownHunt.columnRepeatRule')}</th>
            <th>{t('crownHunt.columnCreatedAt')}</th>
            <th>{t('crownHunt.columnActions')}</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => {
            const display = tierDisplay(point.rewardPoints);
            return (
            <tr key={point.pointId}>
              <td>
                <span
                  className={styles.tierChip}
                  style={{ ['--tier-color' as string]: display.color }}
                >
                  <span className={styles.tierCrown} style={{ color: display.color }} aria-hidden="true">
                    ♛
                  </span>
                  {display.label}
                </span>
              </td>
              <td>
                <span className={`${styles.badge} ${styles[`badge_${point.status}`] ?? ''}`}>
                  {statusLabel(point.status)}
                </span>
              </td>
              <td>{point.rewardPoints}</td>
              <td>
                {point.maxCollectors == null
                  ? t('crownHunt.collectorsEveryone')
                  : `${point.collectorCount} / ${point.maxCollectors}`}
              </td>
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
            );
          })}
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
          // A Crown is a map COLLECTABLE (Pokémon GO–style), not a titled
          // document — no title/description is sent. rewardPoints is the source
          // of truth: a tier button sets it to that tier's preset, while a
          // legacy 'custom' reward is preserved verbatim so an edit never
          // silently coerces it. The 75 m collect radius is fixed.
          latitude: parseFloat(form.latitude),
          longitude: parseFloat(form.longitude),
          geofenceRadiusMeters: form.geofenceRadiusMeters,
          rewardPoints: form.rewardPoints,
          repeatRule: form.repeatRule,
          // Headcount is independent of the rarity tier: 'everyone' → null
          // (unlimited); 'limited' → the entered N (validated >= 1 in the form,
          // re-validated server-side). Sending null on edit reverts a limited
          // crown to unlimited.
          maxCollectors:
            form.collectorMode === 'limited' ? Number(form.maxCollectors) : null,
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
          point={activatingPoint}
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
