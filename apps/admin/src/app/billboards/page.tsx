'use client';

/**
 * Admin Digital Billboards management page.
 *
 * Allows admins to:
 *  - List all sponsored billboard placements (all statuses)
 *  - Create draft billboards
 *  - Edit draft/paused billboards
 *  - Activate billboards (with 6 mandatory safety confirmations)
 *  - Pause active billboards (with reason)
 *  - End billboards (with reason)
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  AdminActivateBillboardRequest,
  AdminBillboardSummary,
  AdminCreateBillboardRequest,
  AdminUpdateBillboardRequest,
  BillboardCtaType,
  BillboardPlacementType,
} from '@/features/digital-billboards';
import {
  adminActivateBillboard,
  adminCreateBillboard,
  adminEndBillboard,
  adminListBillboards,
  adminPauseBillboard,
  adminUpdateBillboard,
  ApiError,
} from '@/features/digital-billboards';
import { DateTimeField } from '@/components/ui/DateTimeField';
import { MapLocationPicker } from '@/components/map/MapLocationPicker';
import { translate } from '@/i18n';
import { localToIso, toLocalDateTimeValue } from '@/lib/datetime';
import { formatDateOnly } from '@/lib/format';

import styles from '../kronjakt/page.module.css';

const t = (key: string) => translate('sv', key);

function formatDate(iso: string | undefined | null): string {
  return formatDateOnly(iso);
}

function statusLabel(status: string): string {
  switch (status) {
    case 'draft':
      return t('billboards.statusDraft');
    case 'active':
      return t('billboards.statusActive');
    case 'paused':
      return t('billboards.statusPaused');
    case 'ended':
      return t('billboards.statusEnded');
    default:
      return status;
  }
}

function statusBadgeClass(status: string): string {
  switch (status) {
    case 'active':
      return styles.badge_active ?? '';
    case 'paused':
      return styles.badge_paused ?? '';
    case 'ended':
      return styles.badge_ended ?? '';
    default:
      return styles.badge_draft ?? '';
  }
}

interface BillboardFormState {
  partnerCompanyId: string;
  headline: string;
  message: string;
  placementType: BillboardPlacementType;
  latitude: string;
  longitude: string;
  availableFrom: string;
  availableUntil: string;
  callToActionType: BillboardCtaType | '';
  callToActionValue: string;
  safetyNote: string;
}

const EMPTY_FORM: BillboardFormState = {
  partnerCompanyId: '',
  headline: '',
  message: '',
  placementType: 'map_billboard',
  latitude: '',
  longitude: '',
  availableFrom: '',
  availableUntil: '',
  callToActionType: '',
  callToActionValue: '',
  safetyNote: '',
};

function billboardToForm(billboard: AdminBillboardSummary): BillboardFormState {
  return {
    partnerCompanyId: billboard.partnerId,
    headline: billboard.headline,
    message: billboard.message,
    placementType: billboard.placementType,
    latitude: String(billboard.latitude),
    longitude: String(billboard.longitude),
    availableFrom: toLocalDateTimeValue(billboard.availableFrom),
    availableUntil: toLocalDateTimeValue(billboard.availableUntil),
    callToActionType: billboard.callToActionType ?? '',
    callToActionValue: billboard.callToActionValue ?? '',
    safetyNote: billboard.safetyNote ?? '',
  };
}

interface BillboardFormProps {
  initial: BillboardFormState;
  isEdit: boolean;
  onSave: (form: BillboardFormState) => Promise<void>;
  onCancel: () => void;
  isSaving: boolean;
  saveError: string | null;
}

const PLACEMENT_OPTIONS: BillboardPlacementType[] = [
  'map_billboard',
  'event_area',
  'partner_area',
  'other_approved_location',
];
const CTA_OPTIONS: (BillboardCtaType | '')[] = [
  '',
  'navigate',
  'phone',
  'website',
  'offer_view',
  'partner_profile',
];

const BillboardForm = ({ initial, isEdit, onSave, onCancel, isSaving, saveError }: BillboardFormProps) => {
  const [form, setForm] = useState<BillboardFormState>(initial);
  const set = (field: keyof BillboardFormState, value: string) =>
    setForm((prev) => ({ ...prev, [field]: value }));

  const requiresCtaValue =
    form.callToActionType !== '' &&
    form.callToActionType !== 'navigate' &&
    form.callToActionType !== 'partner_profile' &&
    form.callToActionType !== 'offer_view';

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void onSave(form);
      }}
      className={styles.form}
    >
      {!isEdit && (
        <div className={styles.formRow}>
          <label className={styles.label}>
            {t('billboards.formPartnerIdLabel')} *
            <input
              className={styles.input}
              value={form.partnerCompanyId}
              onChange={(event) => set('partnerCompanyId', event.target.value)}
              required
              placeholder="UUID"
            />
          </label>
        </div>
      )}

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('billboards.formHeadlineLabel')} *
          <input
            className={styles.input}
            value={form.headline}
            onChange={(event) => set('headline', event.target.value)}
            maxLength={100}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('billboards.formMessageLabel')} *
          <textarea
            className={styles.input}
            value={form.message}
            onChange={(event) => set('message', event.target.value)}
            maxLength={300}
            rows={4}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('billboards.formPlacementTypeLabel')}
          <select
            className={styles.input}
            value={form.placementType}
            onChange={(event) => set('placementType', event.target.value as BillboardPlacementType)}
          >
            {PLACEMENT_OPTIONS.map((placement) => (
              <option key={placement} value={placement}>
                {placement}
              </option>
            ))}
          </select>
        </label>
      </div>

      <MapLocationPicker
        latitude={form.latitude}
        longitude={form.longitude}
        onChange={(latitude, longitude) =>
          setForm((prev) => ({ ...prev, latitude, longitude }))
        }
        labelLat={t('billboards.formLatitudeLabel')}
        labelLng={t('billboards.formLongitudeLabel')}
        helpText={t('map.dragHint')}
        unavailableText={t('map.unavailable')}
        loadErrorText={t('map.loadError')}
        required
        labelClassName={styles.label}
        inputClassName={styles.input}
      />

      <div className={styles.formRowGrid}>
        <DateTimeField
          id="bb-available-from"
          label={t('billboards.formAvailableFromLabel')}
          labelClassName={styles.label}
          inputClassName={styles.input}
          value={form.availableFrom}
          onChange={(next) => set('availableFrom', next)}
        />
        <DateTimeField
          id="bb-available-until"
          label={t('billboards.formAvailableUntilLabel')}
          labelClassName={styles.label}
          inputClassName={styles.input}
          value={form.availableUntil}
          onChange={(next) => set('availableUntil', next)}
        />
      </div>

      <div className={styles.formRowGrid}>
        <label className={styles.label}>
          {t('billboards.formCtaTypeLabel')}
          <select
            className={styles.input}
            value={form.callToActionType}
            onChange={(event) => set('callToActionType', event.target.value as BillboardCtaType | '')}
          >
            {CTA_OPTIONS.map((cta) => (
              <option key={cta} value={cta}>
                {cta || '—'}
              </option>
            ))}
          </select>
        </label>
        {requiresCtaValue && (
          <label className={styles.label}>
            {t('billboards.formCtaValueLabel')}
            <input
              className={styles.input}
              value={form.callToActionValue}
              onChange={(event) => set('callToActionValue', event.target.value)}
              maxLength={500}
            />
          </label>
        )}
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('billboards.formSafetyNoteLabel')}
          <textarea
            className={styles.input}
            value={form.safetyNote}
            onChange={(event) => set('safetyNote', event.target.value)}
            maxLength={500}
            rows={2}
          />
        </label>
      </div>

      {saveError && <p className={styles.errorText}>{saveError}</p>}

      <div className={styles.formActions}>
        <button type="button" className={styles.btnSecondary} onClick={onCancel} disabled={isSaving}>
          {t('billboards.cancel')}
        </button>
        <button type="submit" className={styles.btnPrimary} disabled={isSaving}>
          {isSaving ? t('billboards.saving') : t('billboards.save')}
        </button>
      </div>
    </form>
  );
};

interface ActivateModalProps {
  billboard: AdminBillboardSummary;
  onConfirm: (request: AdminActivateBillboardRequest) => Promise<void>;
  onCancel: () => void;
  isSaving: boolean;
  saveError: string | null;
}

interface SafetyChecks {
  notBusinessLocationConfirmed: boolean;
  notRoadLaneConfirmed: boolean;
  notRoadSignConfirmed: boolean;
  notObstructingMapConfirmed: boolean;
  markedAsAdvertisingConfirmed: boolean;
  suitableForMapConfirmed: boolean;
}

const EMPTY_CHECKS: SafetyChecks = {
  notBusinessLocationConfirmed: false,
  notRoadLaneConfirmed: false,
  notRoadSignConfirmed: false,
  notObstructingMapConfirmed: false,
  markedAsAdvertisingConfirmed: false,
  suitableForMapConfirmed: false,
};

const ActivateModal = ({ billboard, onConfirm, onCancel, isSaving, saveError }: ActivateModalProps) => {
  const [checks, setChecks] = useState<SafetyChecks>(EMPTY_CHECKS);
  const [approvalReason, setApprovalReason] = useState('');

  const allChecked = Object.values(checks).every(Boolean);
  const canSubmit = allChecked && approvalReason.trim().length >= 3;

  const toggle = (key: keyof SafetyChecks) => {
    setChecks((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{t('billboards.activateConfirmTitle')}</h2>
        <p className={styles.modalBody}>{billboard.headline}</p>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!canSubmit) return;
            void onConfirm({
              notBusinessLocationConfirmed: checks.notBusinessLocationConfirmed,
              notRoadLaneConfirmed: checks.notRoadLaneConfirmed,
              notRoadSignConfirmed: checks.notRoadSignConfirmed,
              notObstructingMapConfirmed: checks.notObstructingMapConfirmed,
              markedAsAdvertisingConfirmed: checks.markedAsAdvertisingConfirmed,
              suitableForMapConfirmed: checks.suitableForMapConfirmed,
              approvalReason: approvalReason.trim(),
            });
          }}
        >
          <p className={styles.label}>{t('billboards.safetyCheckTitle')}</p>
          <div className={styles.form}>
            {([
              ['notBusinessLocationConfirmed', t('billboards.safetyCheck1')],
              ['notRoadLaneConfirmed', t('billboards.safetyCheck2')],
              ['notRoadSignConfirmed', t('billboards.safetyCheck3')],
              ['notObstructingMapConfirmed', t('billboards.safetyCheck4')],
              ['markedAsAdvertisingConfirmed', t('billboards.safetyCheck5')],
              ['suitableForMapConfirmed', t('billboards.safetyCheck6')],
            ] as [keyof SafetyChecks, string][]).map(([key, label]) => (
              <label key={key} className={styles.checkLabel}>
                <input type="checkbox" checked={checks[key]} onChange={() => toggle(key)} />
                <span>{label}</span>
              </label>
            ))}
          </div>

          <label className={styles.label} style={{ marginTop: '12px' }}>
            {t('billboards.approvalReasonLabel')} *
            <textarea
              className={styles.input}
              value={approvalReason}
              onChange={(event) => setApprovalReason(event.target.value)}
              rows={3}
              minLength={3}
              maxLength={1000}
              required
            />
          </label>

          {saveError && <p className={styles.errorText}>{saveError}</p>}

          <div className={styles.formActions}>
            <button type="button" className={styles.btnSecondary} onClick={onCancel} disabled={isSaving}>
              {t('billboards.cancel')}
            </button>
            <button type="submit" className={styles.btnPrimary} disabled={!canSubmit || isSaving}>
              {isSaving ? t('billboards.activating') : t('billboards.confirmActivate')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

interface ReasonModalProps {
  title: string;
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
  isSaving: boolean;
  saveError: string | null;
}

const ReasonModal = ({ title, onConfirm, onCancel, isSaving, saveError }: ReasonModalProps) => {
  const [reason, setReason] = useState('');

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{title}</h2>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void onConfirm(reason);
          }}
          className={styles.form}
        >
          <label className={styles.label}>
            {t('billboards.reasonLabel')} *
            <textarea
              className={styles.input}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={3}
              required
              minLength={1}
              maxLength={500}
            />
          </label>

          {saveError && <p className={styles.errorText}>{saveError}</p>}

          <div className={styles.formActions}>
            <button type="button" className={styles.btnSecondary} onClick={onCancel} disabled={isSaving}>
              {t('billboards.cancel')}
            </button>
            <button type="submit" className={styles.btnPrimary} disabled={!reason.trim() || isSaving}>
              {isSaving ? t('billboards.saving') : t('billboards.confirm')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

type View = 'list' | 'create' | 'edit' | 'activate' | 'pause' | 'end';

export default function BillboardsPage() {
  const [billboards, setBillboards] = useState<AdminBillboardSummary[]>([]);
  const [page, setPage] = useState(1);
  const [hasNext, setHasNext] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [view, setView] = useState<View>('list');
  const [selected, setSelected] = useState<AdminBillboardSummary | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const isMounted = useRef(true);

  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const loadBillboards = useCallback(async (nextPage: number) => {
    if (!isMounted.current) return;

    setIsLoading(true);
    setLoadError(null);
    try {
      const response = await adminListBillboards(nextPage);
      if (isMounted.current) {
        setBillboards(response.data.billboards);
        setHasNext(response.meta.hasNext);
        setPage(nextPage);
      }
    } catch (error) {
      if (isMounted.current) {
        setLoadError(error instanceof ApiError ? error.message : t('billboards.loadError'));
      }
    } finally {
      if (isMounted.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadBillboards(1);
  }, [loadBillboards]);

  const resetView = () => {
    setView('list');
    setSelected(null);
    setSaveError(null);
  };

  const handleCreate = async (form: BillboardFormState) => {
    setIsSaving(true);
    setSaveError(null);

    try {
      const request: AdminCreateBillboardRequest = {
        partnerCompanyId: form.partnerCompanyId,
        headline: form.headline,
        message: form.message,
        placementType: form.placementType,
        latitude: parseFloat(form.latitude),
        longitude: parseFloat(form.longitude),
        availableFrom: localToIso(form.availableFrom),
        availableUntil: localToIso(form.availableUntil),
        callToActionType: (form.callToActionType as BillboardCtaType) || null,
        callToActionValue:
          (form.callToActionType === 'phone' || form.callToActionType === 'website') && form.callToActionValue.trim()
            ? form.callToActionValue.trim()
            : null,
        safetyNote: form.safetyNote || null,
      };
      await adminCreateBillboard(request);
      resetView();
      await loadBillboards(1);
    } catch (error) {
      if (isMounted.current) {
        setSaveError(error instanceof ApiError ? error.message : t('billboards.saveError'));
      }
    } finally {
      if (isMounted.current) setIsSaving(false);
    }
  };

  const handleUpdate = async (form: BillboardFormState) => {
    if (!selected) return;

    setIsSaving(true);
    setSaveError(null);

    try {
      const request: AdminUpdateBillboardRequest = {
        headline: form.headline,
        message: form.message,
        placementType: form.placementType,
        latitude: parseFloat(form.latitude),
        longitude: parseFloat(form.longitude),
        availableFrom: localToIso(form.availableFrom),
        availableUntil: localToIso(form.availableUntil),
        callToActionType: (form.callToActionType as BillboardCtaType) || null,
        callToActionValue:
          (form.callToActionType === 'phone' || form.callToActionType === 'website') && form.callToActionValue.trim()
            ? form.callToActionValue.trim()
            : null,
        safetyNote: form.safetyNote || null,
      };

      await adminUpdateBillboard(selected.billboardId, request);
      resetView();
      await loadBillboards(page);
    } catch (error) {
      if (isMounted.current) {
        setSaveError(error instanceof ApiError ? error.message : t('billboards.saveError'));
      }
    } finally {
      if (isMounted.current) setIsSaving(false);
    }
  };

  const handleActivate = async (request: AdminActivateBillboardRequest) => {
    if (!selected) return;

    setIsSaving(true);
    setSaveError(null);
    try {
      await adminActivateBillboard(selected.billboardId, request);
      resetView();
      await loadBillboards(page);
    } catch (error) {
      if (isMounted.current) {
        setSaveError(error instanceof ApiError ? error.message : t('billboards.saveError'));
      }
    } finally {
      if (isMounted.current) setIsSaving(false);
    }
  };

  const handlePause = async (reason: string) => {
    if (!selected) return;

    setIsSaving(true);
    setSaveError(null);
    try {
      await adminPauseBillboard(selected.billboardId, reason.trim());
      resetView();
      await loadBillboards(page);
    } catch (error) {
      if (isMounted.current) {
        setSaveError(error instanceof ApiError ? error.message : t('billboards.saveError'));
      }
    } finally {
      if (isMounted.current) setIsSaving(false);
    }
  };

  const handleEnd = async (reason: string) => {
    if (!selected) return;

    setIsSaving(true);
    setSaveError(null);
    try {
      await adminEndBillboard(selected.billboardId, reason.trim());
      resetView();
      await loadBillboards(page);
    } catch (error) {
      if (isMounted.current) {
        setSaveError(error instanceof ApiError ? error.message : t('billboards.saveError'));
      }
    } finally {
      if (isMounted.current) setIsSaving(false);
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{t('billboards.pageTitle')}</h1>
        <p className={styles.description}>{t('billboards.pageDescription')}</p>
      </div>

      {view === 'list' && (
        <>
          <div className={styles.tabHeader}>
            <button className={styles.btnPrimary} onClick={() => setView('create')}>
              {t('billboards.createBillboard')}
            </button>
          </div>

          {isLoading && <p className={styles.loadingText}>{t('billboards.loading')}</p>}
          {loadError && <p className={styles.errorText}>{loadError}</p>}

          {!isLoading && !loadError && (
            <>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>{t('billboards.columnHeadline')}</th>
                    <th>{t('billboards.columnCompany')}</th>
                    <th>{t('billboards.columnPlacement')}</th>
                    <th>{t('billboards.columnStatus')}</th>
                    <th>{t('billboards.columnOnMap')}</th>
                    <th>{t('billboards.columnAvailableUntil')}</th>
                    <th>{t('billboards.columnActions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {billboards.map((billboard) => (
                    <tr key={billboard.billboardId}>
                      <td>{billboard.headline}</td>
                      <td>{billboard.partnerCompanyName}</td>
                      <td>{billboard.placementType}</td>
                      <td>
                        <span className={`${styles.badge} ${statusBadgeClass(billboard.status)}`}>
                          {statusLabel(billboard.status)}
                        </span>
                      </td>
                      {/*
                        Distinct from the status badge on purpose. "Aktiv" is
                        the admin's decision; this is whether members can
                        actually see it at this instant, which the availability
                        window can veto. Without this column an operator who
                        schedules a placement for next week sees "Aktiv", looks
                        at the map, and files a bug.
                      */}
                      <td>
                        {billboard.mapVisible
                          ? t('billboards.onMapYes')
                          : t('billboards.onMapNo')}
                      </td>
                      <td>{formatDate(billboard.availableUntil)}</td>
                      <td>
                        <div className={styles.actions}>
                          {(billboard.status === 'draft' || billboard.status === 'paused') && (
                            <button
                              className={styles.btnSmall}
                              onClick={() => {
                                setSelected(billboard);
                                setView('edit');
                              }}
                            >
                              {t('billboards.edit')}
                            </button>
                          )}
                          {(billboard.status === 'draft' || billboard.status === 'paused') && (
                            <button
                              className={styles.btnSmallPrimary}
                              onClick={() => {
                                setSelected(billboard);
                                setView('activate');
                              }}
                            >
                              {t('billboards.activate')}
                            </button>
                          )}
                          {billboard.status === 'active' && (
                            <button
                              className={styles.btnSmallWarning}
                              onClick={() => {
                                setSelected(billboard);
                                setView('pause');
                              }}
                            >
                              {t('billboards.pause')}
                            </button>
                          )}
                          {billboard.status !== 'ended' && (
                            <button
                              className={styles.btnSmallWarning}
                              onClick={() => {
                                setSelected(billboard);
                                setView('end');
                              }}
                            >
                              {t('billboards.end')}
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                  {billboards.length === 0 && (
                    <tr>
                      <td colSpan={7}>{t('billboards.noBillboards')}</td>
                    </tr>
                  )}
                </tbody>
              </table>

              <div className={styles.actions} style={{ justifyContent: 'space-between', marginTop: '16px' }}>
                <div>
                  {page > 1 && (
                    <button className={styles.btnSecondary} onClick={() => void loadBillboards(page - 1)}>
                      ← {t('billboards.prevPage')}
                    </button>
                  )}
                </div>
                <div>
                  {hasNext && (
                    <button className={styles.btnSecondary} onClick={() => void loadBillboards(page + 1)}>
                      {t('billboards.nextPage')} →
                    </button>
                  )}
                </div>
              </div>
            </>
          )}
        </>
      )}

      {view === 'create' && (
        <div className={styles.formContainer}>
          <h2 className={styles.modalTitle}>{t('billboards.createBillboard')}</h2>
          <BillboardForm
            initial={EMPTY_FORM}
            isEdit={false}
            onSave={handleCreate}
            onCancel={resetView}
            isSaving={isSaving}
            saveError={saveError}
          />
        </div>
      )}

      {view === 'edit' && selected && (
        <div className={styles.formContainer}>
          <h2 className={styles.modalTitle}>{t('billboards.editBillboard')}</h2>
          <BillboardForm
            initial={billboardToForm(selected)}
            isEdit={true}
            onSave={handleUpdate}
            onCancel={resetView}
            isSaving={isSaving}
            saveError={saveError}
          />
        </div>
      )}

      {view === 'activate' && selected && (
        <ActivateModal
          billboard={selected}
          onConfirm={handleActivate}
          onCancel={resetView}
          isSaving={isSaving}
          saveError={saveError}
        />
      )}

      {view === 'pause' && selected && (
        <ReasonModal
          title={t('billboards.pauseTitle')}
          onConfirm={handlePause}
          onCancel={resetView}
          isSaving={isSaving}
          saveError={saveError}
        />
      )}

      {view === 'end' && selected && (
        <ReasonModal
          title={t('billboards.endTitle')}
          onConfirm={handleEnd}
          onCancel={resetView}
          isSaving={isSaving}
          saveError={saveError}
        />
      )}
    </div>
  );
}
