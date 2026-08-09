'use client';

/**
 * Kronjakt AUTO-SPAWN marked-AREAS admin tab ("Områden" / "Areas").
 *
 * The wider half of the auto-spawn safety model, alongside the "Spawn cells"
 * tab: admins DRAW polygon / circle / rectangle areas the scheduled spawner may
 * place crowns inside. Self-contained — it loads its own areas, owns the draw
 * state, and calls the safety-gated crownHunt.* area callables directly, so
 * page.tsx only has to mount it for the active tab.
 *
 * SAFETY GATE (mirrors the spawn-cell approval UX): an area only spawns while
 * active AND safeAreaConfirmed, and ACTIVATING one requires an explicit confirm
 * checkbox + warning in the same request. Deactivating / deleting drains the
 * area's live crowns (backend-side), surfaced with a clear confirm.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  adminListSpawnAreas,
  adminCreateSpawnArea,
  adminUpdateSpawnArea,
  adminDeleteSpawnArea,
  buildCreateAreaRequest,
  buildActivateAreaRequest,
  buildDeactivateAreaRequest,
  validateAreaShape,
  describeShape,
  shapeCenter,
  areaPoiCount,
  ApiError,
  MIN_AREA_RADIUS_METERS,
  MAX_AREA_RADIUS_METERS,
  type AdminCrownSpawnArea,
  type CrownSpawnAreaShape,
  type AreaValidationCode,
} from '@/features/crown-hunt';
import { AreaDrawMap, type AreaDrawTool } from '@/components/map/AreaDrawMap';
import { translate } from '@/i18n';

import { SpawnDiagnosticsPanel } from './SpawnDiagnosticsPanel';
import styles from './page.module.css';

const t = (key: string) => translate('sv', key);
const fmt = (key: string, params: Record<string, string | number>): string =>
  Object.entries(params).reduce((acc, [k, v]) => acc.replace(`{${k}}`, String(v)), t(key));

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('sv-SE');
}

/** Map a caught error to a localised, friendly message. */
function areaErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const code = error.code;
    if (code === 'unauthenticated' || code === 'permission-denied') {
      return t('crownHunt.errorAuth');
    }
    const areaCode = (error.details as { areaCode?: AreaValidationCode } | undefined)?.areaCode;
    if (areaCode) return t(`crownHunt.areaError.${areaCode}`);
  }
  return t('crownHunt.error');
}

function shapeSummary(shape: CrownSpawnAreaShape): string {
  const d = describeShape(shape);
  if (d.type === 'polygon') return fmt('crownHunt.areaShapePolygon', { count: d.detail });
  if (d.type === 'circle') return fmt('crownHunt.areaShapeCircle', { meters: d.detail });
  return t('crownHunt.areaShapeRectangle');
}

interface AreasTabProps {
  onFlash: (message: string) => void;
}

export function AreasTab({ onFlash }: AreasTabProps): React.ReactElement {
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const [areas, setAreas] = useState<AdminCrownSpawnArea[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Draw panel state
  const [drawing, setDrawing] = useState(false);
  const [editingAreaId, setEditingAreaId] = useState<string | null>(null);
  const [tool, setTool] = useState<AreaDrawTool>('polygon');
  const [shape, setShape] = useState<CrownSpawnAreaShape | null>(null);
  const [name, setName] = useState('');
  const [circleRadius, setCircleRadius] = useState(250);
  const [circleCenter, setCircleCenter] = useState<{ lat: number; lon: number } | null>(null);
  const [activateNow, setActivateNow] = useState(false);
  const [safeConfirm, setSafeConfirm] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // Activate existing modal
  const [activateTarget, setActivateTarget] = useState<AdminCrownSpawnArea | null>(null);
  const [activateConfirm, setActivateConfirm] = useState(false);
  const [isActivating, setIsActivating] = useState(false);
  const [activateError, setActivateError] = useState<string | null>(null);

  // Delete modal
  const [deleteTarget, setDeleteTarget] = useState<AdminCrownSpawnArea | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // Auto-spawn diagnostics panel (read-only troubleshooting for one area)
  const [diagnosticsTarget, setDiagnosticsTarget] = useState<AdminCrownSpawnArea | null>(null);

  const load = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const list = await adminListSpawnAreas();
      if (!mountedRef.current) return;
      setAreas(list);
    } catch {
      if (!mountedRef.current) return;
      setLoadError(t('crownHunt.error'));
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const resetDraw = () => {
    setDrawing(false);
    setEditingAreaId(null);
    setShape(null);
    setName('');
    setActivateNow(false);
    setSafeConfirm(false);
    setCircleCenter(null);
    setCircleRadius(250);
    setTool('polygon');
    setSaveError(null);
  };

  const validation = shape ? validateAreaShape(shape) : null;
  const shapeValid = validation?.ok === true;
  const shapeErrorText =
    shape && validation && !validation.ok ? t(`crownHunt.areaError.${validation.code}`) : null;
  // Activation may only be requested with the safety confirmation ticked.
  const canActivateFromForm = activateNow && safeConfirm;

  const handleSave = useCallback(async () => {
    if (!shape || !shapeValid) return;
    setIsSaving(true);
    setSaveError(null);
    try {
      if (editingAreaId) {
        await adminUpdateSpawnArea({
          areaId: editingAreaId,
          shape,
          name: name.trim() ? name.trim() : null,
          // Re-confirmation is required to (re)activate on the same request.
          ...(canActivateFromForm ? { active: true, safeAreaConfirmed: true } : {}),
        });
        onFlash(t('crownHunt.areaUpdateSuccess'));
      } else {
        await adminCreateSpawnArea(buildCreateAreaRequest(shape, name, activateNow, safeConfirm));
        onFlash(t('crownHunt.areaCreateSuccess'));
      }
      if (!mountedRef.current) return;
      resetDraw();
      void load();
    } catch (error) {
      if (!mountedRef.current) return;
      setSaveError(areaErrorMessage(error));
    } finally {
      if (mountedRef.current) setIsSaving(false);
    }
  }, [
    shape,
    shapeValid,
    editingAreaId,
    name,
    activateNow,
    safeConfirm,
    canActivateFromForm,
    load,
    onFlash,
  ]);

  const handleActivate = useCallback(async () => {
    if (!activateTarget) return;
    // The gate: a null request means "not confirmed" — never send activation.
    const request = buildActivateAreaRequest(activateTarget.areaId, activateConfirm);
    if (!request) return;
    setIsActivating(true);
    setActivateError(null);
    try {
      await adminUpdateSpawnArea(request);
      if (!mountedRef.current) return;
      setActivateTarget(null);
      setActivateConfirm(false);
      onFlash(t('crownHunt.areaActivateSuccess'));
      void load();
    } catch (error) {
      if (!mountedRef.current) return;
      setActivateError(areaErrorMessage(error));
    } finally {
      if (mountedRef.current) setIsActivating(false);
    }
  }, [activateTarget, activateConfirm, load, onFlash]);

  const handleDeactivate = useCallback(
    async (area: AdminCrownSpawnArea) => {
      try {
        await adminUpdateSpawnArea(buildDeactivateAreaRequest(area.areaId));
        if (!mountedRef.current) return;
        onFlash(t('crownHunt.areaDeactivateSuccess'));
        void load();
      } catch {
        // Non-critical; operator can retry.
      }
    },
    [load, onFlash],
  );

  const handleDelete = useCallback(async () => {
    if (!deleteTarget) return;
    setIsDeleting(true);
    setDeleteError(null);
    try {
      await adminDeleteSpawnArea({ areaId: deleteTarget.areaId });
      if (!mountedRef.current) return;
      setDeleteTarget(null);
      onFlash(t('crownHunt.areaDeleteSuccess'));
      void load();
    } catch (error) {
      if (!mountedRef.current) return;
      setDeleteError(areaErrorMessage(error));
    } finally {
      if (mountedRef.current) setIsDeleting(false);
    }
  }, [deleteTarget, load, onFlash]);

  const startEdit = (area: AdminCrownSpawnArea) => {
    resetDraw();
    setDrawing(true);
    setEditingAreaId(area.areaId);
    setName(area.name ?? '');
    setTool(area.shape.type);
    if (area.shape.type === 'circle') {
      setCircleCenter({ lat: area.shape.center.lat, lon: area.shape.center.lon });
      setCircleRadius(Math.round(area.shape.radiusMeters));
    }
    // Keep the existing shape as the current value until a new one is drawn.
    setShape(area.shape);
  };

  // Frame the map on the area being edited. When the id isn't in the current
  // list (stale/deleted), leave focusCenter null rather than jumping to (0,0).
  const editingArea = editingAreaId ? areas.find((a) => a.areaId === editingAreaId) : null;
  const focusCenter = editingArea ? shapeCenter(editingArea.shape) : null;

  return (
    <section>
      <p className={styles.introText}>{t('crownHunt.areasIntro')}</p>
      <div className={styles.noticeBanner}>{t('crownHunt.autoSpawnFlagNotice')}</div>

      <div className={styles.tabHeader}>
        {!drawing && (
          <button className={styles.btnPrimary} onClick={() => setDrawing(true)}>
            {t('crownHunt.areaDrawButton')}
          </button>
        )}
      </div>

      {drawing && (
        <div className={styles.formContainer}>
          <div className={styles.form}>
            <h3 className={styles.modalTitle}>
              {editingAreaId ? t('crownHunt.areaEditTitle') : t('crownHunt.areaDrawTitle')}
            </h3>

            {/* Shape tool selector */}
            <div
              role="radiogroup"
              aria-label={t('crownHunt.areaToolLabel')}
              className={styles.tabHeader}
            >
              {(['polygon', 'rectangle', 'circle'] as const).map((toolOption) => (
                <button
                  key={toolOption}
                  type="button"
                  role="radio"
                  aria-checked={tool === toolOption}
                  className={tool === toolOption ? styles.btnSmallPrimary : styles.btnSmall}
                  onClick={() => {
                    setTool(toolOption);
                    setShape(null);
                    if (toolOption !== 'circle') setCircleCenter(null);
                  }}
                >
                  {t(`crownHunt.areaTool_${toolOption}`)}
                </button>
              ))}
            </div>

            <AreaDrawMap
              tool={tool}
              circleRadiusMeters={circleRadius}
              circleCenter={circleCenter}
              onCircleCenterChange={setCircleCenter}
              onShapeDrawn={setShape}
              existingAreas={areas}
              focusCenter={focusCenter}
              labels={{
                attribution: t('crownHunt.osmAttribution'),
                unavailable: t('map.unavailable'),
                loadError: t('map.loadError'),
                hint: t(`crownHunt.areaHint_${tool}`),
              }}
            />

            {tool === 'circle' && (
              <label className={styles.label}>
                {t('crownHunt.areaRadiusLabel')}
                <input
                  className={styles.input}
                  type="number"
                  min={MIN_AREA_RADIUS_METERS}
                  max={MAX_AREA_RADIUS_METERS}
                  step={10}
                  value={circleRadius}
                  onChange={(e) => setCircleRadius(Number(e.target.value))}
                />
              </label>
            )}

            <label className={styles.label}>
              {t('crownHunt.areaNameLabel')}
              <input
                className={styles.input}
                type="text"
                maxLength={120}
                value={name}
                placeholder={t('crownHunt.areaNamePlaceholder')}
                onChange={(e) => setName(e.target.value)}
              />
            </label>

            {shape && !shapeValid && shapeErrorText && (
              <p className={styles.errorText} role="alert">
                {shapeErrorText}
              </p>
            )}
            {shape && shapeValid && <p className={styles.introText}>{shapeSummary(shape)}</p>}

            {/* Activation safety gate */}
            <label className={styles.checkLabel}>
              <input
                type="checkbox"
                checked={activateNow}
                onChange={(e) => {
                  setActivateNow(e.target.checked);
                  if (!e.target.checked) setSafeConfirm(false);
                }}
              />{' '}
              {t('crownHunt.areaActivateNow')}
            </label>
            {activateNow && (
              <>
                <p className={styles.safetyWarning}>⚠️ {t('crownHunt.areaSafetyWarning')}</p>
                <label className={styles.checkLabel}>
                  <input
                    type="checkbox"
                    checked={safeConfirm}
                    onChange={(e) => setSafeConfirm(e.target.checked)}
                  />{' '}
                  {t('crownHunt.areaSafeConfirm')}
                </label>
              </>
            )}

            {saveError !== null && <p className={styles.errorText}>{saveError}</p>}

            <div className={styles.formActions}>
              <button className={styles.btnSecondary} onClick={resetDraw} disabled={isSaving}>
                {t('crownHunt.cancel')}
              </button>
              <button
                className={styles.btnPrimary}
                onClick={() => void handleSave()}
                disabled={isSaving || !shapeValid || (activateNow && !safeConfirm)}
              >
                {isSaving
                  ? t('crownHunt.loading')
                  : editingAreaId
                    ? t('crownHunt.areaSaveEdit')
                    : t('crownHunt.areaSaveCreate')}
              </button>
            </div>
          </div>
        </div>
      )}

      {isLoading && <p className={styles.loadingText}>{t('crownHunt.loading')}</p>}
      {loadError !== null && (
        <p className={styles.errorText}>
          {loadError}{' '}
          <button className={styles.linkButton} onClick={() => void load()}>
            {t('crownHunt.retry')}
          </button>
        </p>
      )}

      {!isLoading && !loadError && areas.length === 0 && (
        <p className={styles.emptyText}>{t('crownHunt.areaNoAreas')}</p>
      )}

      {areas.length > 0 && (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>{t('crownHunt.areaColumnName')}</th>
              <th>{t('crownHunt.areaColumnShape')}</th>
              <th>{t('crownHunt.areaColumnState')}</th>
              <th>{t('crownHunt.areaColumnPoi')}</th>
              <th>{t('crownHunt.areaColumnChangedBy')}</th>
              <th>{t('crownHunt.areaColumnChangedAt')}</th>
              <th>{t('crownHunt.areaColumnActions')}</th>
            </tr>
          </thead>
          <tbody>
            {areas.map((area) => {
              const poi = areaPoiCount(area);
              const active = area.active && area.safeAreaConfirmed;
              return (
                <tr key={area.areaId}>
                  <td>{area.name?.trim() ? area.name : t('crownHunt.areaUnnamed')}</td>
                  <td>{shapeSummary(area.shape)}</td>
                  <td>
                    <span className={styles.badge}>
                      {active ? t('crownHunt.areaStateActive') : t('crownHunt.areaStateInactive')}
                    </span>
                  </td>
                  <td>{poi !== null ? fmt('crownHunt.areaPoiCount', { count: poi }) : '—'}</td>
                  <td title={area.approvedByUserId ?? area.createdByUserId}>
                    {(area.approvedByUserId ?? area.createdByUserId ?? '').slice(0, 8)
                      ? `${(area.approvedByUserId ?? area.createdByUserId ?? '').slice(0, 8)}…`
                      : '—'}
                  </td>
                  <td>{formatDate(area.updatedAt ?? area.createdAt)}</td>
                  <td className={styles.actions}>
                    {active ? (
                      <button
                        className={styles.btnSmallWarning}
                        onClick={() => void handleDeactivate(area)}
                      >
                        {t('crownHunt.areaDeactivate')}
                      </button>
                    ) : (
                      <button
                        className={styles.btnSmallPrimary}
                        onClick={() => {
                          setActivateTarget(area);
                          setActivateConfirm(false);
                          setActivateError(null);
                        }}
                      >
                        {t('crownHunt.areaActivate')}
                      </button>
                    )}
                    <button className={styles.btnSmall} onClick={() => startEdit(area)}>
                      {t('crownHunt.areaEdit')}
                    </button>
                    <button className={styles.btnSmall} onClick={() => setDiagnosticsTarget(area)}>
                      {t('crownHunt.diagButton')}
                    </button>
                    <button
                      className={styles.btnSmallWarning}
                      onClick={() => {
                        setDeleteTarget(area);
                        setDeleteError(null);
                      }}
                    >
                      {t('crownHunt.areaDelete')}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {/* Activate existing-area modal (safety gate) */}
      {activateTarget !== null && (
        <div className={styles.modalOverlay} role="dialog" aria-modal="true">
          <div className={styles.modal}>
            <h2 className={styles.modalTitle}>{t('crownHunt.areaActivateTitle')}</h2>
            <p className={styles.modalBody}>{t('crownHunt.areaActivateBody')}</p>
            <p className={styles.safetyWarning}>⚠️ {t('crownHunt.areaSafetyWarning')}</p>
            <label className={styles.checkLabel}>
              <input
                type="checkbox"
                checked={activateConfirm}
                onChange={(e) => setActivateConfirm(e.target.checked)}
              />{' '}
              {t('crownHunt.areaSafeConfirm')}
            </label>
            {activateError !== null && <p className={styles.errorText}>{activateError}</p>}
            <div className={styles.formActions}>
              <button
                className={styles.btnSecondary}
                onClick={() => {
                  setActivateTarget(null);
                  setActivateConfirm(false);
                }}
                disabled={isActivating}
              >
                {t('crownHunt.cancel')}
              </button>
              <button
                className={styles.btnPrimary}
                onClick={() => void handleActivate()}
                disabled={isActivating || !activateConfirm}
              >
                {isActivating ? t('crownHunt.loading') : t('crownHunt.areaActivate')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete modal */}
      {deleteTarget !== null && (
        <div className={styles.modalOverlay} role="dialog" aria-modal="true">
          <div className={styles.modal}>
            <h2 className={styles.modalTitle}>{t('crownHunt.areaDeleteTitle')}</h2>
            <p className={styles.modalBody}>{t('crownHunt.areaDeleteBody')}</p>
            {deleteError !== null && <p className={styles.errorText}>{deleteError}</p>}
            <div className={styles.formActions}>
              <button
                className={styles.btnSecondary}
                onClick={() => setDeleteTarget(null)}
                disabled={isDeleting}
              >
                {t('crownHunt.cancel')}
              </button>
              <button
                className={styles.btnSmallWarning}
                onClick={() => void handleDelete()}
                disabled={isDeleting}
              >
                {isDeleting ? t('crownHunt.loading') : t('crownHunt.areaDelete')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Auto-spawn diagnostics panel */}
      {diagnosticsTarget !== null && (
        <SpawnDiagnosticsPanel
          areaId={diagnosticsTarget.areaId}
          areaName={diagnosticsTarget.name ?? ''}
          onClose={() => setDiagnosticsTarget(null)}
        />
      )}
    </section>
  );
}

export default AreasTab;
