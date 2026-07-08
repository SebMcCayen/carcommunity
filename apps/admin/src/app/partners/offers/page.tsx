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

import type {
  AdminPartnerOfferSummary,
  AdminPartnerOfferDetail,
  CreatePartnerOfferRequest,
  UpdatePartnerOfferRequest,
  PartnerOfferStatus,
  PartnerOfferType,
} from '@/features/partners';
import {
  adminListPartnerOffers,
  adminGetPartnerOffer,
  adminCreatePartnerOffer,
  adminUpdatePartnerOffer,
  adminActivatePartnerOffer,
  adminPausePartnerOffer,
  adminEndPartnerOffer,
  PARTNER_OFFER_STATUSES,
  PARTNER_OFFER_TYPES,
  ApiError,
} from '@/features/partners';
import { translate } from '@/i18n';
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

function offerDetailToForm(offer: AdminPartnerOfferDetail): OfferFormState {
  return {
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
    availableFrom: offer.availableFrom
      ? new Date(offer.availableFrom).toISOString().slice(0, 16)
      : '',
    availableUntil: offer.availableUntil
      ? new Date(offer.availableUntil).toISOString().slice(0, 16)
      : '',
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
    availableFrom: form.availableFrom ? new Date(form.availableFrom).toISOString() : null,
    availableUntil: form.availableUntil ? new Date(form.availableUntil).toISOString() : null,
  };
}

// ---------------------------------------------------------------------------
// Offer form component
// ---------------------------------------------------------------------------

interface OfferFormProps {
  initial: OfferFormState;
  onSave: (form: OfferFormState) => Promise<void>;
  onCancel: () => void;
  isSaving: boolean;
  saveError: string | null;
  isEdit?: boolean;
}

const OfferForm = ({ initial, onSave, onCancel, isSaving, saveError, isEdit = false }: OfferFormProps) => {
  const [form, setForm] = useState<OfferFormState>(initial);

  const set = (field: keyof OfferFormState, value: string) =>
    setForm((prev) => ({ ...prev, [field]: value }));

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void onSave(form);
      }}
      className={styles.form}
    >
      <p className={styles.safetyWarning}>{t('partnerOffers.codeNeverShownInList')}</p>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partnerOffers.formTitleLabel')}
          <input
            className={styles.input}
            value={form.title}
            onChange={(e) => set('title', e.target.value)}
            maxLength={150}
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
            {PARTNER_OFFER_TYPES.map((type) => (
              <option key={type} value={type}>
                {offerTypeLabel(type)}
              </option>
            ))}
          </select>
        </label>
      </div>

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

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partnerOffers.formTermsLabel')}
          <textarea
            className={styles.input}
            value={form.terms}
            onChange={(e) => set('terms', e.target.value)}
            maxLength={2000}
            rows={3}
          />
        </label>
      </div>

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

      <div className={styles.formRowGrid}>
        <label className={styles.label}>
          {t('partnerOffers.formPercentageDiscountLabel')}
          <input
            className={styles.input}
            type="number"
            min="0"
            max="100"
            step="0.1"
            value={form.percentageDiscount}
            onChange={(e) => set('percentageDiscount', e.target.value)}
          />
        </label>
        <label className={styles.label}>
          {t('partnerOffers.formFixedDiscountLabel')}
          <input
            className={styles.input}
            type="number"
            min="0"
            step="1"
            value={form.fixedDiscountMinorUnits}
            onChange={(e) => set('fixedDiscountMinorUnits', e.target.value)}
          />
        </label>
        <label className={styles.label}>
          {t('partnerOffers.formCurrencyCodeLabel')}
          <input
            className={styles.input}
            type="text"
            value={form.currencyCode}
            onChange={(e) => set('currencyCode', e.target.value.toUpperCase())}
            maxLength={3}
          />
        </label>
      </div>

      <div className={styles.formRowGrid}>
        <label className={styles.label}>
          {t('partnerOffers.formAvailableFromLabel')}
          <input
            className={styles.input}
            type="datetime-local"
            value={form.availableFrom}
            onChange={(e) => set('availableFrom', e.target.value)}
          />
        </label>
        <label className={styles.label}>
          {t('partnerOffers.formAvailableUntilLabel')}
          <input
            className={styles.input}
            type="datetime-local"
            value={form.availableUntil}
            onChange={(e) => set('availableUntil', e.target.value)}
          />
        </label>
      </div>

      {saveError !== null && <p className={styles.errorText}>{saveError}</p>}

      <div className={styles.formActions}>
        <button type="button" className={styles.btnSecondary} onClick={onCancel} disabled={isSaving}>
          {t('partnerOffers.cancel')}
        </button>
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
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{title}</h2>

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
          <button className={styles.btnSecondary} onClick={onCancel} disabled={isConfirming}>
            {t('partnerOffers.cancel')}
          </button>
          <button
            className={styles.btnPrimary}
            onClick={() => void onConfirm(reason)}
            disabled={isConfirming || reason.trim().length < 1}
          >
            {isConfirming ? t('partnerOffers.loading') : t('partnerOffers.confirm')}
          </button>
        </div>
      </div>
    </div>
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

const ActivateModal = ({ offerTitle, onConfirm, onCancel, isConfirming, error }: ActivateModalProps) => {
  const [checked, setChecked] = useState(false);

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{t('partnerOffers.activateConfirmTitle')}</h2>
        <p className={styles.modalBody}>{offerTitle}</p>
        <p className={styles.safetyWarning}>{t('partnerOffers.activateConfirmBody')}</p>

        <label className={styles.label}>
          <input
            type="checkbox"
            checked={checked}
            onChange={(e) => setChecked(e.target.checked)}
          />
          {' '}{t('partnerOffers.confirm')}
        </label>

        {error !== null && <p className={styles.errorText}>{error}</p>}

        <div className={styles.formActions}>
          <button className={styles.btnSecondary} onClick={onCancel} disabled={isConfirming}>
            {t('partnerOffers.cancel')}
          </button>
          <button
            className={styles.btnPrimary}
            onClick={() => void onConfirm()}
            disabled={isConfirming || !checked}
          >
            {isConfirming ? t('partnerOffers.loading') : t('partnerOffers.activateOffer')}
          </button>
        </div>
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------
// Main page component
// ---------------------------------------------------------------------------

type ModalState =
  | { type: 'none' }
  | { type: 'create'; partnerId: string }
  | { type: 'edit'; offer: AdminPartnerOfferDetail }
  | { type: 'activate'; offer: AdminPartnerOfferSummary }
  | { type: 'pause'; offer: AdminPartnerOfferSummary }
  | { type: 'end'; offer: AdminPartnerOfferSummary };

export default function PartnerOffersPage() {
  const [offers, setOffers] = useState<AdminPartnerOfferSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modal, setModal] = useState<ModalState>({ type: 'none' });
  const [modalError, setModalError] = useState<string | null>(null);
  const [isActing, setIsActing] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Client-side filters
  const [filterPartnerId, setFilterPartnerId] = useState('');
  const [filterStatus, setFilterStatus] = useState<PartnerOfferStatus | ''>('');
  const [newPartnerIdInput, setNewPartnerIdInput] = useState('');

  const isMounted = useRef(true);
  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const result = await adminListPartnerOffers({
        partnerId: filterPartnerId.trim() || undefined,
        status: filterStatus || undefined,
      });
      if (isMounted.current) setOffers(result.data.offers);
    } catch {
      if (isMounted.current) setError(t('partnerOffers.error'));
    } finally {
      if (isMounted.current) setIsLoading(false);
    }
  }, [filterPartnerId, filterStatus]);

  useEffect(() => {
    void load();
  }, [load]);

  const showSuccess = (message: string) => {
    setSuccessMessage(message);
    setTimeout(() => {
      if (isMounted.current) setSuccessMessage(null);
    }, 3000);
  };

  const handleCreate = async (form: OfferFormState) => {
    if (modal.type !== 'create') return;
    setIsActing(true);
    setModalError(null);
    try {
      await adminCreatePartnerOffer(modal.partnerId, formToRequest(form));
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.createSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      setIsActing(false);
    }
  };

  const handleUpdate = async (form: OfferFormState) => {
    if (modal.type !== 'edit') return;
    setIsActing(true);
    setModalError(null);
    try {
      await adminUpdatePartnerOffer(modal.offer.offerId, formToRequest(form));
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.updateSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      setIsActing(false);
    }
  };

  const handleEdit = async (offer: AdminPartnerOfferSummary) => {
    try {
      const detail = await adminGetPartnerOffer(offer.offerId);
      setModalError(null);
      setModal({ type: 'edit', offer: detail });
    } catch {
      setError(t('partnerOffers.error'));
    }
  };

  const handleActivate = async () => {
    if (modal.type !== 'activate') return;
    setIsActing(true);
    setModalError(null);
    try {
      await adminActivatePartnerOffer(modal.offer.offerId);
      setModal({ type: 'none' });
      showSuccess(t('partnerOffers.activateSuccess'));
      void load();
    } catch (err) {
      setModalError(err instanceof ApiError ? err.message : t('partnerOffers.error'));
    } finally {
      setIsActing(false);
    }
  };

  const handlePause = async (reason: string) => {
    if (modal.type !== 'pause') return;
    if (!reason.trim()) {
      setModalError(t('partnerOffers.reasonRequired'));
      return;
    }
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
      setIsActing(false);
    }
  };

  const handleEnd = async (reason: string) => {
    if (modal.type !== 'end') return;
    if (!reason.trim()) {
      setModalError(t('partnerOffers.reasonRequired'));
      return;
    }
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

      {successMessage !== null && (
        <p className={offerStyles.successText}>{successMessage}</p>
      )}

      {/* Filters */}
      <div className={offerStyles.filterRow}>
        <label className={offerStyles.filterLabel}>
          {t('partnerOffers.filterByStatus')}:
          <select
            className={`${styles.input} ${offerStyles.filterInput}`}
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value as PartnerOfferStatus | '')}
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
            onChange={(e) => setFilterPartnerId(e.target.value)}
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
                <td>{offer.title}</td>
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
                        setModal({ type: 'activate', offer });
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

      {/* Create modal */}
      {modal.type === 'create' && (
        <div className={styles.modalOverlay} role="dialog" aria-modal="true">
          <div className={styles.modal}>
            <h2 className={styles.modalTitle}>{t('partnerOffers.createOffer')}</h2>
            <OfferForm
              initial={EMPTY_FORM}
              onSave={handleCreate}
              onCancel={closeModal}
              isSaving={isActing}
              saveError={modalError}
            />
          </div>
        </div>
      )}

      {/* Edit modal */}
      {modal.type === 'edit' && (
        <div className={styles.modalOverlay} role="dialog" aria-modal="true">
          <div className={styles.modal}>
            <h2 className={styles.modalTitle}>{t('partnerOffers.editOffer')}</h2>
            <OfferForm
              initial={offerDetailToForm(modal.offer)}
              onSave={handleUpdate}
              onCancel={closeModal}
              isSaving={isActing}
              saveError={modalError}
              isEdit
            />
          </div>
        </div>
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
