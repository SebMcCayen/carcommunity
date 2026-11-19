'use client';

/**
 * Admin Partner Offers management page.
 *
 * Allows admins to:
 *  - List all partner offers (all statuses)
 *  - Filter by partner company and status
 *  - Create draft offers
 *  - Edit draft/paused offers
 *  - Activate offers (with confirmation)
 *  - Pause offers (with reason)
 *  - End offers (with reason)
 *
 * Security rules:
 *  - discountCode is NEVER displayed in list view or detail view.
 *  - discountCode is only returned via the member-facing show-code endpoint.
 *  - All offer text is plain text — never rendered as HTML.
 *  - Backend enforces all validation, status transitions, and audit logging.
 *  - Status cannot be set via create/update form — only through dedicated action buttons.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  listOffers,
  getOffer,
  submitManagedOffer,
  type PortalCursor,
  type PortalOffer,
} from '@/features/partners/portal';
import { p } from '../portal-components';
import portalStyles from '../portal.module.css';

import type {
  AdminPartnerOfferSummary,
  CreatePartnerOfferRequest,
  PartnerOfferStatus,
  PartnerOfferType,
} from '@/features/partners';
import {
  adminCreatePartnerOffer,
  adminUpdatePartnerOffer,
  adminActivatePartnerOffer,
  adminPausePartnerOffer,
  adminEndPartnerOffer,
  PARTNER_OFFER_STATUSES,
  PARTNER_OFFER_TYPES,
  ApiError,
} from '@/features/partners';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { DateTimeField } from '@/components/ui/DateTimeField';
import { translate } from '@/i18n';
import { localToIso, toLocalDateTimeValue } from '@/lib/datetime';
import { formatDateOnly } from '@/lib/format';

import styles from '../../kronjakt/page.module.css';
import offerStyles from './page.module.css';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatDate(iso: string | undefined | null): string {
  return formatDateOnly(iso);
}

function offerStatusLabel(status: PartnerOfferStatus): string {
  const key = `partnerOffers.status${status.charAt(0).toUpperCase()}${status.slice(1)}`;
  return t(key);
}

function offerTypeLabel(type: PartnerOfferType): string {
  const map: Record<PartnerOfferType, string> = {
    discount_code: t('partnerOffers.offerTypeDiscountCode'),
    percentage_discount: t('partnerOffers.offerTypePercentageDiscount'),
    fixed_discount: t('partnerOffers.offerTypeFixedDiscount'),
    member_benefit: t('partnerOffers.offerTypeMemberBenefit'),
    special_offer: t('partnerOffers.offerTypeSpecialOffer'),
    other: t('partnerOffers.offerTypeOther'),
  };
  return map[type] ?? type;
}

// ---------------------------------------------------------------------------
// Offer form state
// ---------------------------------------------------------------------------

interface OfferFormState {
  personalQr: boolean;
  capacity: string;
  reservationDays: string;
  title: string;
  teaserText: string;
  description: string;
  offerType: PartnerOfferType;
  redemptionInstructions: string;
  terms: string;
  discountCode: string;
  percentageDiscount: string;
  fixedDiscountMinorUnits: string;
  currencyCode: string;
  availableFrom: string;
  availableUntil: string;
}

const EMPTY_FORM: OfferFormState = {
  personalQr: true,
  capacity: '',
  reservationDays: '7',
  title: '',
  teaserText: '',
  description: '',
  offerType: 'member_benefit',
  redemptionInstructions: '',
  terms: '',
  discountCode: '',
  percentageDiscount: '',
  fixedDiscountMinorUnits: '',
  currencyCode: '',
  availableFrom: '',
  availableUntil: '',
};

function offerDetailToForm(offer: PortalOffer): OfferFormState {
  return {
    personalQr: offer.redemptionMode === 'reservation',
    capacity: offer.capacity != null ? String(offer.capacity) : '',
    reservationDays: String(offer.reservationDays),
    title: offer.title,
    teaserText: offer.teaserText,
    description: offer.description ?? '',
    offerType: offer.offerType,
    redemptionInstructions: offer.redemptionInstructions ?? '',
    terms: offer.terms ?? '',
    discountCode: '',
    percentageDiscount: offer.percentageDiscount != null ? String(offer.percentageDiscount) : '',
    fixedDiscountMinorUnits:
      offer.fixedDiscountMinorUnits != null ? String(offer.fixedDiscountMinorUnits) : '',
    currencyCode: offer.currencyCode ?? '',
    availableFrom: toLocalDateTimeValue(offer.availableFrom),
    availableUntil: toLocalDateTimeValue(offer.availableUntil),
  };
}

function formToRequest(form: OfferFormState): CreatePartnerOfferRequest {
  return {
    title: form.title.trim(),
    teaserText: form.teaserText.trim(),
    description: form.description.trim(),
    offerType: form.offerType,
    redemptionInstructions: form.redemptionInstructions.trim() || null,
    terms: form.terms.trim() || null,
    discountCode: form.discountCode.trim() || null,
    percentageDiscount: form.percentageDiscount ? parseFloat(form.percentageDiscount) : null,
    fixedDiscountMinorUnits: form.fixedDiscountMinorUnits
      ? parseInt(form.fixedDiscountMinorUnits, 10)
      : null,
    currencyCode: form.currencyCode.trim().toUpperCase() || null,
    availableFrom: localToIso(form.availableFrom),
    availableUntil: localToIso(form.availableUntil),
  };
}

// ---------------------------------------------------------------------------
// Offer form component
// ---------------------------------------------------------------------------

interface OfferFormProps {
  initial: OfferFormState;
  onSave: (form: OfferFormState) => Promise<void>;
  isSaving: boolean;
  saveError: string | null;
  isEdit?: boolean;
}

const OfferForm = ({ initial, onSave, isSaving, saveError, isEdit = false }: OfferFormProps) => {
  const [form, setForm] = useState<OfferFormState>(initial);

  const set = (field: Exclude<keyof OfferFormState, 'personalQr'>, value: string) =>
    setForm((prev) => ({ ...prev, [field]: value }));

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void onSave(form);
      }}
      className={styles.form}
    >
      <p className={styles.safetyWarning}>{p('offerReviewNote')}</p>
      {!isEdit && (
        <label className={portalStyles.check}>
          <input
            type="checkbox"
            checked={form.personalQr}
            onChange={(event) =>
              setForm((previous) => ({
                ...previous,
                personalQr: event.target.checked,
                offerType: 'member_benefit',
              }))
            }
          />
          {p('personalQr')}
        </label>
      )}
      {form.personalQr && (
        <div className={styles.formRowGrid}>
          <label className={styles.label}>
            {p('offerCapacity')}
            <input
              className={styles.input}
              type="number"
              min="1"
              max="100000"
              step="1"
              placeholder={p('unlimited')}
              value={form.capacity}
              onChange={(event) => set('capacity', event.target.value)}
            />
          </label>
          <label className={styles.label}>
            {p('reservationDays')}
            <input
              className={styles.input}
              type="number"
              min="1"
              max="30"
              step="1"
              required
              value={form.reservationDays}
              onChange={(event) => set('reservationDays', event.target.value)}
            />
          </label>
          <p>{p('onePerPerson')}</p>
        </div>
      )}

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partnerOffers.formTitleLabel')}
          <input
            className={styles.input}
            value={form.title}
            onChange={(e) => set('title', e.target.value)}
            maxLength={form.personalQr ? 120 : 150}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partnerOffers.formTeaserLabel')}
          <input
            className={styles.input}
            value={form.teaserText}
            onChange={(e) => set('teaserText', e.target.value)}
            maxLength={250}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partnerOffers.formDescriptionLabel')}
          <textarea
            className={styles.input}
            value={form.description}
            onChange={(e) => set('description', e.target.value)}
            maxLength={2000}
            rows={4}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partnerOffers.formOfferTypeLabel')}
          <select
            className={styles.input}
            value={form.offerType}
            onChange={(e) => set('offerType', e.target.value as PartnerOfferType)}
          >
            {(form.personalQr
              ? (['member_benefit', 'percentage_discount'] as const)
              : PARTNER_OFFER_TYPES
            ).map((type) => (
              <option key={type} value={type}>
                {offerTypeLabel(type)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {!form.personalQr && (
        <div className={styles.formRow}>
          <label className={styles.label}>
            {t('partnerOffers.formRedemptionInstructionsLabel')}
            <textarea
              className={styles.input}
              value={form.redemptionInstructions}
              onChange={(e) => set('redemptionInstructions', e.target.value)}
              maxLength={1000}
              rows={3}
            />
          </label>
        </div>
      )}

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partnerOffers.formTermsLabel')}
          <textarea
            className={styles.input}
            value={form.terms}
            onChange={(e) => set('terms', e.target.value)}
            maxLength={2000}
            required={form.personalQr}
            rows={3}
          />
        </label>
      </div>

      {!form.personalQr && (
        <div className={styles.formRow}>
          <label className={styles.label}>
            {t('partnerOffers.formDiscountCodeLabel')}
            <input
              className={styles.input}
              type="text"
              value={form.discountCode}
              onChange={(e) => set('discountCode', e.target.value)}
              maxLength={100}
              autoComplete="off"
            />
          </label>
        </div>
      )}

      <div className={styles.formRowGrid}>
        {(!form.personalQr || form.offerType === 'percentage_discount') && (
          <label className={styles.label}>
            {t('partnerOffers.formPercentageDiscountLabel')}
            <input
              className={styles.input}
              type="number"
              min={form.personalQr ? '1' : '0'}
              max="100"
              step={form.personalQr ? '1' : '0.1'}
              required={form.personalQr}
              value={form.percentageDiscount}
              onChange={(event) => set('percentageDiscount', event.target.value)}
            />
          </label>
        )}
        {!form.personalQr && (
          <>
            <label className={styles.label}>
              {t('partnerOffers.formFixedDiscountLabel')}
              <input
                className={styles.input}
                type="number"
                min="0"
                step="1"
                value={form.fixedDiscountMinorUnits}
                onChange={(event) => set('fixedDiscountMinorUnits', event.target.value)}
              />
            </label>
            <label className={styles.label}>
              {t('partnerOffers.formCurrencyCodeLabel')}
              <input
                className={styles.input}
                value={form.currencyCode}
                maxLength={3}
                onChange={(event) => set('currencyCode', event.target.value.toUpperCase())}
              />
            </label>
          </>
        )}
      </div>

      <div className={styles.formRowGrid}>
        <DateTimeField
          id="po-available-from"
          label={t('partnerOffers.formAvailableFromLabel')}
          labelClassName={styles.label}
          inputClassName={styles.input}
          value={form.availableFrom}
          onChange={(next) => set('availableFrom', next)}
        />
        <DateTimeField
          id="po-available-until"
          label={t('partnerOffers.formAvailableUntilLabel')}
          labelClassName={styles.label}
          inputClassName={styles.input}
          value={form.availableUntil}
          onChange={(next) => set('availableUntil', next)}
        />
      </div>

      {saveError !== null && <p className={styles.errorText}>{saveError}</p>}

      <div className={styles.formActions}>
        <button type="submit" className={styles.btnPrimary} disabled={isSaving}>
          {isSaving ? t('partnerOffers.loading') : t('partnerOffers.saveOffer')}
        </button>
      </div>
    </form>
  );
};

// ---------------------------------------------------------------------------
// Pause/End modal
// ---------------------------------------------------------------------------

interface ReasonModalProps {
  title: string;
  reasonLabel: string;
  reasonPlaceholder: string;
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
  isConfirming: boolean;
  error: string | null;
}

const ReasonModal = ({
  title,
  reasonLabel,
  reasonPlaceholder,
  onConfirm,
  onCancel,
  isConfirming,
  error,
}: ReasonModalProps) => {
  const [reason, setReason] = useState('');

  return (
    <ActionDialog title={title} onCancel={onCancel} cancelDisabled={isConfirming}>
      <label className={styles.label}>
        {reasonLabel}
        <textarea
          className={styles.input}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder={reasonPlaceholder}
          rows={3}
          required
        />
      </label>

      {error !== null && <p className={styles.errorText}>{error}</p>}

      <div className={styles.formActions}>
        <button
          className={styles.btnPrimary}
          onClick={() => void onConfirm(reason)}
          disabled={isConfirming || reason.trim().length < 1}
        >
          {isConfirming ? t('partnerOffers.loading') : t('partnerOffers.confirm')}
        </button>
      </div>
    </ActionDialog>
  );
};

// ---------------------------------------------------------------------------
// Activate confirmation modal
// ---------------------------------------------------------------------------

interface ActivateModalProps {
  offerTitle: string;
  onConfirm: () => Promise<void>;
  onCancel: () => void;
  isConfirming: boolean;
  error: string | null;
}

const ActivateModal = ({
  offerTitle,
  onConfirm,
  onCancel,
  isConfirming,
  error,
}: ActivateModalProps) => {
  const [checked, setChecked] = useState(false);

  return (
    <ActionDialog
      title={t('partnerOffers.activateConfirmTitle')}
      onCancel={onCancel}
      cancelDisabled={isConfirming}
    >
      <p className={styles.modalBody}>{offerTitle}</p>
      <p className={styles.safetyWarning}>{t('partnerOffers.activateConfirmBody')}</p>

      <label className={styles.label}>
        <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />{' '}
        {t('partnerOffers.confirm')}
      </label>

      {error !== null && <p className={styles.errorText}>{error}</p>}

      <div className={styles.formActions}>
        <button
          className={styles.btnPrimary}
          onClick={() => void onConfirm()}
          disabled={isConfirming || !checked}
        >
          {isConfirming ? t('partnerOffers.loading') : t('partnerOffers.activateOffer')}
        </button>
      </div>
    </ActionDialog>
  );
};

// ---------------------------------------------------------------------------
// Main page component
// ---------------------------------------------------------------------------

type ModalState =
  | { type: 'none' }
  | { type: 'create'; partnerId: string }
  | { type: 'edit'; offer: PortalOffer }
  | { type: 'review'; offer: PortalOffer }
  | { type: 'activate'; offer: PortalOffer }
  | { type: 'pause'; offer: AdminPartnerOfferSummary }
  | { type: 'end'; offer: AdminPartnerOfferSummary };

export default function PartnerOffersPage() {
  const [params] = useSearchParams();
  const [cursors, setCursors] = useState<(PortalCursor | null)[]>([null]);
  const [pageIndex, setPageIndex] = useState(0);
  const [nextCursor, setNextCursor] = useState<PortalCursor | null>(null);
  const loadGeneration = useRef(0);
  const actionFlight = useRef(false);
  const [offers, setOffers] = useState<AdminPartnerOfferSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modal, setModal] = useState<ModalState>({ type: 'none' });
  const [modalError, setModalError] = useState<string | null>(null);
  const [isActing, setIsActing] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Filters run on Firestore before cursor pagination.
  const [filterPartnerId, setFilterPartnerId] = useState(params.get('partnerId') ?? '');
  const [filterStatus, setFilterStatus] = useState<PartnerOfferStatus | ''>('');
  const [newPartnerIdInput, setNewPartnerIdInput] = useState(params.get('partnerId') ?? '');

  const isMounted = useRef(true);
  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const load = useCallback(async () => {
    const generation = ++loadGeneration.current;
    setIsLoading(true);
    setError(null);
    try {
      const result = await listOffers(
        cursors[pageIndex],
        filterPartnerId.trim() || undefined,
        filterStatus || undefined,
      );
      if (isMounted.current && generation === loadGeneration.current) {
        setOffers(result.items);
        setNextCursor(result.nextCursor);
      }
    } catch {
      if (isMounted.current && generation === loadGeneration.current)
        setError(t('partnerOffers.error'));
    } finally {
      if (isMounted.current && generation === loadGeneration.current) setIsLoading(false);
    }
  }, [filterPartnerId, filterStatus, cursors, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const offerId = params.get('offerId');
    if (!offerId) return;
    let active = true;
    getOffer(offerId)
      .then((offer) => {
        if (active) setModal({ type: 'review', offer });
      })
      .catch(() => {
        if (active) setError(t('partnerOffers.error'));
      });
    return () => {
      active = false;
    };
  }, [params]);

  const showSuccess = (message: string) => {
    setSuccessMessage(message);
    setTimeout(() => {
      if (isMounted.current) setSuccessMessage(null);
    }, 3000);
  };

  const handleCreate = async (form: OfferFormState) => {
    if (actionFlight.current) return;
    if (modal.type !== 'create') return;
    actionFlight.current = true;
    setIsActing(true);
    setModalError(null);
    try {
      if (form.personalQr)
        await submitManagedOffer({
          companyId: modal.partnerId,
          title: form.title.trim(),
          teaserText: form.teaserText.trim(),
          description: form.description.trim(),
          terms: form.terms.trim(),
          percentageDiscount:
            form.offerType === 'percentage_discount' ? Number(form.percentageDiscount) : null,
          availableFrom: localToIso(form.availableFrom),
          availableUntil: localToIso(form.availableUntil),
          capacity: form.capacity ? Number(form.capacity) : null,
          reservationDays: Number(form.reservationDays),
        });
      else await adminCreatePartnerOffer(modal.partnerId, formToRequest(form));
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.createSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      actionFlight.current = false;
      setIsActing(false);
    }
  };

  const handleUpdate = async (form: OfferFormState) => {
    if (actionFlight.current) return;
    if (modal.type !== 'edit') return;
    actionFlight.current = true;
    setIsActing(true);
    setModalError(null);
    try {
      if (form.personalQr)
        await submitManagedOffer({
          companyId: modal.offer.partnerId,
          offerId: modal.offer.offerId,
          title: form.title.trim(),
          teaserText: form.teaserText.trim(),
          description: form.description.trim(),
          terms: form.terms.trim(),
          percentageDiscount:
            form.offerType === 'percentage_discount' ? Number(form.percentageDiscount) : null,
          availableFrom: localToIso(form.availableFrom),
          availableUntil: localToIso(form.availableUntil),
          capacity: form.capacity ? Number(form.capacity) : null,
          reservationDays: Number(form.reservationDays),
        });
      else await adminUpdatePartnerOffer(modal.offer.offerId, formToRequest(form));
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.updateSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      actionFlight.current = false;
      setIsActing(false);
    }
  };

  const handleEdit = async (offer: AdminPartnerOfferSummary) => {
    try {
      const detail = await getOffer(offer.offerId);
      if (detail.redemptionMode === 'reservation' && detail.issuedCount > 0) {
        setError(p('immutableOffer'));
        return;
      }
      setModalError(null);
      setModal({ type: 'edit', offer: detail });
    } catch {
      setError(t('partnerOffers.error'));
    }
  };

  const handleActivate = async () => {
    if (actionFlight.current) return;
    if (modal.type !== 'activate') return;
    actionFlight.current = true;
    setIsActing(true);
    setModalError(null);
    try {
      await adminActivatePartnerOffer(
        modal.offer.offerId,
        undefined,
        modal.offer.redemptionMode === 'reservation' ? modal.offer.version : undefined,
      );
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.activateSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      actionFlight.current = false;
      setIsActing(false);
    }
  };

  const handlePause = async (reason: string) => {
    if (actionFlight.current) return;
    if (modal.type !== 'pause') return;
    if (!reason.trim()) {
      setModalError(t('partnerOffers.reasonRequired'));
      return;
    }
    actionFlight.current = true;
    setIsActing(true);
    setModalError(null);
    try {
      await adminPausePartnerOffer(modal.offer.offerId, reason);
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.pauseSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      actionFlight.current = false;
      setIsActing(false);
    }
  };

  const handleEnd = async (reason: string) => {
    if (actionFlight.current) return;
    if (modal.type !== 'end') return;
    if (!reason.trim()) {
      setModalError(t('partnerOffers.reasonRequired'));
      return;
    }
    actionFlight.current = true;
    setIsActing(true);
    setModalError(null);
    try {
      await adminEndPartnerOffer(modal.offer.offerId, reason);
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.endSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      actionFlight.current = false;
      setIsActing(false);
    }
  };

  const closeModal = () => {
    setModal({ type: 'none' });
    setModalError(null);
    setIsActing(false);
  };

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{t('partnerOffers.pageTitle')}</h1>
        <p className={styles.description}>{t('partnerOffers.pageDescription')}</p>
      </div>

      {successMessage !== null && <p className={offerStyles.successText}>{successMessage}</p>}

      {/* Filters */}
      <div className={offerStyles.filterRow}>
        <label className={offerStyles.filterLabel}>
          {t('partnerOffers.filterByStatus')}:
          <select
            className={`${styles.input} ${offerStyles.filterInput}`}
            value={filterStatus}
            onChange={(e) => {
              setCursors([null]);
              setPageIndex(0);
              setFilterStatus(e.target.value as PartnerOfferStatus | '');
            }}
          >
            <option value="">{t('partnerOffers.allStatuses')}</option>
            {PARTNER_OFFER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {offerStatusLabel(s)}
              </option>
            ))}
          </select>
        </label>

        <label className={offerStyles.filterLabel}>
          {t('partnerOffers.filterByPartner')}:
          <input
            className={`${styles.input} ${offerStyles.filterInput}`}
            type="text"
            placeholder={t('partnerOffers.formPartnerLabel')}
            value={filterPartnerId}
            onChange={(e) => {
              setCursors([null]);
              setPageIndex(0);
              setFilterPartnerId(e.target.value);
            }}
          />
        </label>
      </div>

      {/* Create offer controls */}
      <div className={styles.tabHeader}>
        <div className={offerStyles.createRow}>
          <input
            className={`${styles.input} ${offerStyles.createInput}`}
            type="text"
            placeholder={t('partnerOffers.formPartnerLabel')}
            value={newPartnerIdInput}
            onChange={(e) => setNewPartnerIdInput(e.target.value)}
          />
          <button
            className={styles.btnPrimary}
            disabled={!newPartnerIdInput.trim()}
            onClick={() => {
              setModalError(null);
              setModal({ type: 'create', partnerId: newPartnerIdInput.trim() });
            }}
          >
            {t('partnerOffers.createOffer')}
          </button>
        </div>
      </div>

      {/* Loading / Error / Empty */}
      {isLoading && <p className={styles.loadingText}>{t('partnerOffers.loading')}</p>}
      {error !== null && (
        <p className={styles.errorText}>
          {t('partnerOffers.error')}{' '}
          <button className={styles.linkButton} onClick={() => void load()}>
            {t('partnerOffers.retry')}
          </button>
        </p>
      )}
      {!isLoading && !error && offers.length === 0 && (
        <p className={styles.emptyText}>{t('partnerOffers.noOffers')}</p>
      )}

      {/* Offers table */}
      {offers.length > 0 && (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>{t('partnerOffers.columnPartner')}</th>
              <th>{t('partnerOffers.columnTitle')}</th>
              <th>{t('partnerOffers.columnType')}</th>
              <th>{t('partnerOffers.columnStatus')}</th>
              <th>{t('partnerOffers.columnAvailableUntil')}</th>
              <th>{t('partnerOffers.columnActivatedAt')}</th>
              <th>{t('partnerOffers.columnCreatedAt')}</th>
              <th>{t('partnerOffers.columnActions')}</th>
            </tr>
          </thead>
          <tbody>
            {offers.map((offer) => (
              <tr key={offer.offerId}>
                <td title={offer.partnerId}>{offer.partnerCompanyName}</td>
                <td>
                  <button
                    className={styles.linkButton}
                    onClick={async () => {
                      try {
                        const detail = await getOffer(offer.offerId);
                        setModalError(null);
                        setModal({ type: 'review', offer: detail });
                      } catch {
                        setError(t('partnerOffers.error'));
                      }
                    }}
                  >
                    {offer.title}
                  </button>
                </td>
                <td>{offerTypeLabel(offer.offerType)}</td>
                <td>
                  <span className={`${styles.badge} ${styles[`badge_${offer.status}`] ?? ''}`}>
                    {offerStatusLabel(offer.status)}
                  </span>
                </td>
                <td>{formatDate(offer.availableUntil)}</td>
                <td>{formatDate(offer.activatedAt)}</td>
                <td>{formatDate(offer.createdAt)}</td>
                <td className={styles.actions}>
                  {(offer.status === 'draft' || offer.status === 'paused') && (
                    <button className={styles.btnSmall} onClick={() => void handleEdit(offer)}>
                      {t('partnerOffers.editOffer')}
                    </button>
                  )}
                  {(offer.status === 'draft' || offer.status === 'paused') && (
                    <button
                      className={styles.btnSmallPrimary}
                      onClick={() => {
                        setModalError(null);
                        void getOffer(offer.offerId)
                          .then((detail) => setModal({ type: 'review', offer: detail }))
                          .catch(() => setError(t('partnerOffers.error')));
                      }}
                    >
                      {t('partnerOffers.activateOffer')}
                    </button>
                  )}
                  {offer.status === 'active' && (
                    <button
                      className={styles.btnSmallWarning}
                      onClick={() => {
                        setModalError(null);
                        setModal({ type: 'pause', offer });
                      }}
                    >
                      {t('partnerOffers.pauseOffer')}
                    </button>
                  )}
                  {offer.status !== 'ended' && offer.status !== 'expired' && (
                    <button
                      className={styles.btnSmallDanger}
                      onClick={() => {
                        setModalError(null);
                        setModal({ type: 'end', offer });
                      }}
                    >
                      {t('partnerOffers.endOffer')}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className={portalStyles.pager}>
        <button
          className={portalStyles.secondary}
          disabled={isLoading || !pageIndex}
          onClick={() => setPageIndex((i) => i - 1)}
        >
          {p('previous')}
        </button>
        <span>{pageIndex + 1}</span>
        <button
          className={portalStyles.secondary}
          disabled={isLoading || !nextCursor}
          onClick={() => {
            setCursors((previous) => [...previous.slice(0, pageIndex + 1), nextCursor]);
            setPageIndex((i) => i + 1);
          }}
        >
          {p('next')}
        </button>
      </div>
      {modal.type === 'review' && (
        <ActionDialog title={modal.offer.title} onCancel={closeModal}>
          <p>{modal.offer.description}</p>
          <h3>{p('publishedTerms')}</h3>
          <p style={{ whiteSpace: 'pre-wrap' }}>{modal.offer.terms || '—'}</p>
          <dl>
            <dt>{p('offerCapacity')}</dt>
            <dd>{modal.offer.capacity ?? p('unlimited')}</dd>
            <dt>{p('reservationDays')}</dt>
            <dd>{modal.offer.reservationDays}</dd>
            <dt>{p('offerMode')}</dt>
            <dd>{p(modal.offer.redemptionMode === 'reservation' ? 'personalQr' : 'sharedCode')}</dd>
            <dt>{t('partnerOffers.formAvailableFromLabel')}</dt>
            <dd>{formatDate(modal.offer.availableFrom)}</dd>
            <dt>{t('partnerOffers.formAvailableUntilLabel')}</dt>
            <dd>{formatDate(modal.offer.availableUntil)}</dd>
          </dl>
          <p>{p('paymentNotice')}</p>
          <Link
            className={styles.linkButton}
            to={`/partners/statistics?partnerId=${encodeURIComponent(modal.offer.partnerId)}`}
          >
            {p('statistics')}
          </Link>
          <div className={styles.formActions}>
            {(modal.offer.status === 'draft' || modal.offer.status === 'paused') && (
              <button
                className={styles.btnPrimary}
                onClick={() => setModal({ type: 'activate', offer: modal.offer })}
              >
                {t('partnerOffers.activateOffer')}
              </button>
            )}
          </div>
        </ActionDialog>
      )}
      {/* Create modal */}
      {modal.type === 'create' && (
        <ActionDialog
          title={t('partnerOffers.createOffer')}
          onCancel={closeModal}
          cancelDisabled={isActing}
        >
          <OfferForm
            initial={EMPTY_FORM}
            onSave={handleCreate}
            isSaving={isActing}
            saveError={modalError}
          />
        </ActionDialog>
      )}

      {/* Edit modal */}
      {modal.type === 'edit' && (
        <ActionDialog
          title={t('partnerOffers.editOffer')}
          onCancel={closeModal}
          cancelDisabled={isActing}
        >
          <OfferForm
            initial={offerDetailToForm(modal.offer)}
            onSave={handleUpdate}
            isSaving={isActing}
            saveError={modalError}
            isEdit
          />
        </ActionDialog>
      )}

      {/* Activate modal */}
      {modal.type === 'activate' && (
        <ActivateModal
          offerTitle={modal.offer.title}
          onConfirm={handleActivate}
          onCancel={closeModal}
          isConfirming={isActing}
          error={modalError}
        />
      )}

      {/* Pause modal */}
      {modal.type === 'pause' && (
        <ReasonModal
          title={t('partnerOffers.pauseConfirmTitle')}
          reasonLabel={t('partnerOffers.pauseReasonLabel')}
          reasonPlaceholder={t('partnerOffers.pauseReasonPlaceholder')}
          onConfirm={handlePause}
          onCancel={closeModal}
          isConfirming={isActing}
          error={modalError}
        />
      )}

      {/* End modal */}
      {modal.type === 'end' && (
        <ReasonModal
          title={t('partnerOffers.endConfirmTitle')}
          reasonLabel={t('partnerOffers.endReasonLabel')}
          reasonPlaceholder={t('partnerOffers.endReasonPlaceholder')}
          onConfirm={handleEnd}
          onCancel={closeModal}
          isConfirming={isActing}
          error={modalError}
        />
      )}
    </div>
  );
}
