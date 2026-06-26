'use client';

/**
 * Admin KCC Företagspartner management page.
 *
 * Two tabs:
 *  1. Partneransökningar — review and act on partner applications.
 *  2. Företagspartner — manage approved partner companies.
 *
 * Security and privacy rules:
 *  - Backend is the sole authority for all approval, publication, and audit logging.
 *  - Do not trust client-side admin state — all actions require server-side auth.
 *  - Application contact details are internal and never forwarded to public APIs.
 *  - New companies start as draft; public activation is a separate explicit action.
 *  - Activation requires confirmation that coordinates match the actual business location.
 *  - Partners are never hard-deleted; use pause or end.
 *  - Do not implement offers, analytics, digital billboards, or invoicing here.
 *  - Submitted application text is rendered as plain text (never raw HTML).
 *  - Do not allow duplicate simultaneous actions (disabled while pending).
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  AdminPartnerApplicationSummary,
  AdminPartnerApplicationDetail,
  AdminPartnerCompanySummary,
  AdminCreatePartnerRequest,
  PartnerApplicationStatus,
  PartnerCompanyStatus,
  PartnerCategory,
} from '@/features/partners';
import {
  adminListPartnerApplications,
  adminGetPartnerApplication,
  adminStartApplicationReview,
  adminApproveApplication,
  adminRejectApplication,
  adminListPartnerCompanies,
  adminCreatePartnerCompany,
  adminUpdatePartnerCompany,
  adminActivatePartner,
  adminPausePartner,
  adminEndPartnership,
  ApiError,
} from '@/features/partners';
import { translate } from '@/i18n';

import styles from '../kronjakt/page.module.css';

const t = (key: string) => translate('sv', key);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatDate(iso: string | undefined | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('sv-SE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

function appStatusLabel(status: PartnerApplicationStatus): string {
  switch (status) {
    case 'submitted':
      return t('partners.appStatusSubmitted');
    case 'under_review':
      return t('partners.appStatusUnderReview');
    case 'approved':
      return t('partners.appStatusApproved');
    case 'rejected':
      return t('partners.appStatusRejected');
    case 'withdrawn':
      return t('partners.appStatusWithdrawn');
    default:
      return status;
  }
}

function companyStatusLabel(status: PartnerCompanyStatus): string {
  switch (status) {
    case 'draft':
      return t('partners.statusDraft');
    case 'active':
      return t('partners.statusActive');
    case 'paused':
      return t('partners.statusPaused');
    case 'ended':
      return t('partners.statusEnded');
    default:
      return status;
  }
}

function appStatusBadgeClass(status: PartnerApplicationStatus): string {
  switch (status) {
    case 'approved':
      return styles.badge_active;
    case 'under_review':
      return styles.badge_paused;
    case 'rejected':
    case 'withdrawn':
      return styles.badge_ended;
    default:
      return styles.badge_draft;
  }
}

function companyStatusBadgeClass(status: PartnerCompanyStatus): string {
  switch (status) {
    case 'active':
      return styles.badge_active;
    case 'paused':
      return styles.badge_paused;
    case 'ended':
      return styles.badge_ended;
    default:
      return styles.badge_draft;
  }
}

const PARTNER_CATEGORIES: PartnerCategory[] = [
  'workshop',
  'car_care',
  'parts',
  'tires',
  'charging',
  'restaurant',
  'retail',
  'other',
];

// ---------------------------------------------------------------------------
// Company form
// ---------------------------------------------------------------------------

interface CompanyFormState {
  companyName: string;
  category: PartnerCategory;
  publicDescription: string;
  address: string;
  latitude: string;
  longitude: string;
  publicPhone: string;
  publicWebsiteUrl: string;
}

const EMPTY_COMPANY_FORM: CompanyFormState = {
  companyName: '',
  category: 'other',
  publicDescription: '',
  address: '',
  latitude: '',
  longitude: '',
  publicPhone: '',
  publicWebsiteUrl: '',
};

interface CompanyFormProps {
  initial: CompanyFormState;
  onSave: (form: CompanyFormState) => Promise<void>;
  onCancel: () => void;
  isSaving: boolean;
  saveError: string | null;
}

const CompanyForm = ({ initial, onSave, onCancel, isSaving, saveError }: CompanyFormProps) => {
  const [form, setForm] = useState<CompanyFormState>(initial);
  const set = (field: keyof CompanyFormState, value: string) =>
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
          {t('partners.formCompanyNameLabel')} *
          <input
            className={styles.input}
            value={form.companyName}
            onChange={(e) => set('companyName', e.target.value)}
            maxLength={150}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formCategoryLabel')} *
          <select
            className={styles.input}
            value={form.category}
            onChange={(e) => set('category', e.target.value as PartnerCategory)}
            required
          >
            {PARTNER_CATEGORIES.map((cat) => (
              <option key={cat} value={cat}>
                {cat}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formDescriptionLabel')} *
          <textarea
            className={styles.input}
            value={form.publicDescription}
            onChange={(e) => set('publicDescription', e.target.value)}
            maxLength={1000}
            rows={3}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formAddressLabel')} *
          <input
            className={styles.input}
            value={form.address}
            onChange={(e) => set('address', e.target.value)}
            maxLength={300}
            required
          />
        </label>
      </div>

      <div className={styles.formRowGrid}>
        <label className={styles.label}>
          {t('partners.formLatitudeLabel')} *
          <input
            className={styles.input}
            type="number"
            step="any"
            min="-90"
            max="90"
            value={form.latitude}
            onChange={(e) => set('latitude', e.target.value)}
            required
          />
        </label>
        <label className={styles.label}>
          {t('partners.formLongitudeLabel')} *
          <input
            className={styles.input}
            type="number"
            step="any"
            min="-180"
            max="180"
            value={form.longitude}
            onChange={(e) => set('longitude', e.target.value)}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formPhoneLabel')}
          <input
            className={styles.input}
            value={form.publicPhone}
            onChange={(e) => set('publicPhone', e.target.value)}
            maxLength={30}
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formWebsiteLabel')}
          <input
            className={styles.input}
            type="url"
            value={form.publicWebsiteUrl}
            onChange={(e) => set('publicWebsiteUrl', e.target.value)}
            maxLength={500}
          />
        </label>
      </div>

      {saveError !== null && <p className={styles.errorText}>{saveError}</p>}

      <div className={styles.formActions}>
        <button type="button" className={styles.btnSecondary} onClick={onCancel} disabled={isSaving}>
          {t('partners.cancel')}
        </button>
        <button type="submit" className={styles.btnPrimary} disabled={isSaving}>
          {isSaving ? t('partners.loading') : t('partners.saveCompany')}
        </button>
      </div>
    </form>
  );
};

// ---------------------------------------------------------------------------
// Reject modal
// ---------------------------------------------------------------------------

interface RejectModalProps {
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
  isSubmitting: boolean;
  error: string | null;
}

const RejectModal = ({ onConfirm, onCancel, isSubmitting, error }: RejectModalProps) => {
  const [reason, setReason] = useState('');
  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true">
      <div className={styles.modal}>
        <h2 className={styles.modalTitle}>{t('partners.rejectConfirmTitle')}</h2>
        <label className={styles.label}>
          {t('partners.rejectReasonLabel')}
          <textarea
            className={styles.input}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={2000}
            placeholder={t('partners.rejectReasonPlaceholder')}
            required
          />
        </label>
        {error !== null && <p className={styles.errorText}>{error}</p>}
        <div className={styles.formActions}>
          <button className={styles.btnSecondary} onClick={onCancel} disabled={isSubmitting}>
            {t('partners.cancel')}
          </button>
          <button
            className={styles.btnPrimary}
            onClick={() => void onConfirm(reason)}
            disabled={isSubmitting || reason.trim().length === 0}
          >
            {isSubmitting ? t('partners.loading') : t('partners.rejectApplication')}
          </button>
        </div>
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------
// Status action modal (pause / end) with optional reason
// ---------------------------------------------------------------------------

interface StatusActionModalProps {
  title: string;
  reasonLabel: string;
  reasonPlaceholder: string;
  confirmLabel: string;
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
  isSubmitting: boolean;
  error: string | null;
}

const StatusActionModal = ({
  title,
  reasonLabel,
  reasonPlaceholder,
  confirmLabel,
  onConfirm,
  onCancel,
  isSubmitting,
  error,
}: StatusActionModalProps) => {
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
            rows={2}
            maxLength={2000}
            placeholder={reasonPlaceholder}
          />
        </label>
        {error !== null && <p className={styles.errorText}>{error}</p>}
        <div className={styles.formActions}>
          <button className={styles.btnSecondary} onClick={onCancel} disabled={isSubmitting}>
            {t('partners.cancel')}
          </button>
          <button
            className={styles.btnPrimary}
            onClick={() => void onConfirm(reason)}
            disabled={isSubmitting}
          >
            {isSubmitting ? t('partners.loading') : confirmLabel}
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
  onConfirm: () => Promise<void>;
  onCancel: () => void;
  isSubmitting: boolean;
  error: string | null;
}

const ActivateModal = ({ onConfirm, onCancel, isSubmitting, error }: ActivateModalProps) => (
  <div className={styles.modalOverlay} role="dialog" aria-modal="true">
    <div className={styles.modal}>
      <h2 className={styles.modalTitle}>{t('partners.activateConfirmTitle')}</h2>
      <p className={styles.modalBody}>{t('partners.activateConfirmBody')}</p>
      {error !== null && <p className={styles.errorText}>{error}</p>}
      <div className={styles.formActions}>
        <button className={styles.btnSecondary} onClick={onCancel} disabled={isSubmitting}>
          {t('partners.cancel')}
        </button>
        <button
          className={styles.btnPrimary}
          onClick={() => void onConfirm()}
          disabled={isSubmitting}
        >
          {isSubmitting ? t('partners.loading') : t('partners.activatePartner')}
        </button>
      </div>
    </div>
  </div>
);

// ---------------------------------------------------------------------------
// Applications tab
// ---------------------------------------------------------------------------

const ApplicationsTab = () => {
  const [applications, setApplications] = useState<AdminPartnerApplicationSummary[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  // Selected application for detail view
  const [selectedApp, setSelectedApp] = useState<AdminPartnerApplicationDetail | null>(null);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);

  // Reject modal
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [isRejecting, setIsRejecting] = useState(false);
  const [rejectError, setRejectError] = useState<string | null>(null);

  const isMounted = useRef(true);
  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const load = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const res = await adminListPartnerApplications();
      if (isMounted.current) setApplications(res.data.applications);
    } catch {
      if (isMounted.current) setLoadError(t('partners.error'));
    } finally {
      if (isMounted.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const loadDetail = useCallback(async (id: string) => {
    setIsLoadingDetail(true);
    try {
      const detail = await adminGetPartnerApplication(id);
      if (isMounted.current) setSelectedApp(detail);
    } catch {
      // keep existing selection if detail fails
    } finally {
      if (isMounted.current) setIsLoadingDetail(false);
    }
  }, []);

  const handleStartReview = useCallback(
    async (id: string) => {
      if (pending !== null) return;
      setPending(id);
      setActionError(null);
      try {
        await adminStartApplicationReview(id);
        setSuccessMessage(t('partners.startReviewSuccess'));
        void load();
        if (selectedApp?.applicationId === id) void loadDetail(id);
      } catch (err) {
        setActionError(err instanceof ApiError ? err.message : t('partners.error'));
      } finally {
        if (isMounted.current) setPending(null);
      }
    },
    [pending, load, loadDetail, selectedApp],
  );

  const handleApprove = useCallback(
    async (id: string) => {
      if (pending !== null) return;
      setPending(id);
      setActionError(null);
      try {
        await adminApproveApplication(id);
        setSuccessMessage(t('partners.approveSuccess'));
        void load();
        if (selectedApp?.applicationId === id) void loadDetail(id);
      } catch (err) {
        setActionError(err instanceof ApiError ? err.message : t('partners.error'));
      } finally {
        if (isMounted.current) setPending(null);
      }
    },
    [pending, load, loadDetail, selectedApp],
  );

  const handleRejectConfirm = useCallback(
    async (reason: string) => {
      if (!rejectingId) return;
      setIsRejecting(true);
      setRejectError(null);
      try {
        await adminRejectApplication(rejectingId, reason);
        setRejectingId(null);
        setSuccessMessage(t('partners.rejectSuccess'));
        void load();
        if (selectedApp?.applicationId === rejectingId) void loadDetail(rejectingId);
      } catch (err) {
        setRejectError(err instanceof ApiError ? err.message : t('partners.error'));
      } finally {
        if (isMounted.current) setIsRejecting(false);
      }
    },
    [rejectingId, load, loadDetail, selectedApp],
  );

  return (
    <>
      {rejectingId !== null && (
        <RejectModal
          onConfirm={handleRejectConfirm}
          onCancel={() => {
            setRejectingId(null);
            setRejectError(null);
          }}
          isSubmitting={isRejecting}
          error={rejectError}
        />
      )}

      {successMessage !== null && (
        <div className={styles.successBanner}>{successMessage}</div>
      )}
      {actionError !== null && <p className={styles.errorText}>{actionError}</p>}

      {isLoading ? (
        <p className={styles.loadingText}>{t('partners.loading')}</p>
      ) : loadError !== null ? (
        <div>
          <p className={styles.errorText}>{loadError}</p>
          <button className={styles.btnSecondary} onClick={() => void load()}>
            {t('partners.retry')}
          </button>
        </div>
      ) : applications.length === 0 ? (
        <p className={styles.emptyText}>{t('partners.noApplications')}</p>
      ) : (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>{t('partners.columnCompanyName')}</th>
              <th>{t('partners.columnCategory')}</th>
              <th>{t('partners.columnContact')}</th>
              <th>{t('partners.columnEmail')}</th>
              <th>{t('partners.columnSubmittedAt')}</th>
              <th>{t('partners.columnStatus')}</th>
              <th>{t('partners.columnActions')}</th>
            </tr>
          </thead>
          <tbody>
            {applications.map((app) => (
              <tr key={app.applicationId}>
                <td>
                  <button
                    className={styles.linkButton}
                    onClick={() => {
                      if (selectedApp?.applicationId === app.applicationId) {
                        setSelectedApp(null);
                      } else {
                        void loadDetail(app.applicationId);
                      }
                    }}
                  >
                    {app.companyName}
                  </button>
                </td>
                <td>{app.category}</td>
                <td>{app.contactName}</td>
                <td>{app.contactEmail}</td>
                <td>{formatDate(app.submittedAt)}</td>
                <td>
                  <span className={`${styles.badge} ${appStatusBadgeClass(app.status)}`}>
                    {appStatusLabel(app.status)}
                  </span>
                </td>
                <td>
                  <div className={styles.actions}>
                    {app.status === 'submitted' && (
                      <button
                        className={styles.btnSmall}
                        onClick={() => void handleStartReview(app.applicationId)}
                        disabled={pending === app.applicationId}
                      >
                        {t('partners.startReview')}
                      </button>
                    )}
                    {(app.status === 'submitted' || app.status === 'under_review') && (
                      <>
                        <button
                          className={styles.btnSmallPrimary}
                          onClick={() => void handleApprove(app.applicationId)}
                          disabled={pending === app.applicationId}
                        >
                          {t('partners.approveApplication')}
                        </button>
                        <button
                          className={styles.btnSmallWarning}
                          onClick={() => {
                            setRejectingId(app.applicationId);
                            setRejectError(null);
                          }}
                          disabled={pending === app.applicationId}
                        >
                          {t('partners.rejectApplication')}
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* Application detail panel */}
      {selectedApp !== null && (
        <div className={styles.formContainer} style={{ marginTop: 'var(--space-6)' }}>
          {isLoadingDetail ? (
            <p className={styles.loadingText}>{t('partners.loading')}</p>
          ) : (
            <>
              <h3 style={{ marginBottom: 'var(--space-4)', fontWeight: 'var(--fw-semibold)' }}>
                {selectedApp.companyName}
              </h3>
              <dl style={{ display: 'grid', gridTemplateColumns: '200px 1fr', gap: 'var(--space-2)' }}>
                <dt style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                  {t('partners.columnStatus')}
                </dt>
                <dd style={{ fontSize: 'var(--text-sm)' }}>
                  <span className={`${styles.badge} ${appStatusBadgeClass(selectedApp.status)}`}>
                    {appStatusLabel(selectedApp.status)}
                  </span>
                </dd>
                <dt style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                  {t('partners.columnCategory')}
                </dt>
                <dd style={{ fontSize: 'var(--text-sm)' }}>{selectedApp.category}</dd>
                <dt style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                  {t('partners.contactDetails')}
                </dt>
                <dd style={{ fontSize: 'var(--text-sm)' }}>
                  {selectedApp.contactName} · {selectedApp.contactEmail}
                  {selectedApp.contactPhone !== null && ` · ${selectedApp.contactPhone}`}
                </dd>
                {selectedApp.message !== null && selectedApp.message !== '' && (
                  <>
                    <dt style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                      {t('partners.applicationMessage')}
                    </dt>
                    {/* Plain text — no dangerouslySetInnerHTML */}
                    <dd style={{ fontSize: 'var(--text-sm)', whiteSpace: 'pre-wrap' }}>
                      {selectedApp.message}
                    </dd>
                  </>
                )}
              </dl>
            </>
          )}
        </div>
      )}
    </>
  );
};

// ---------------------------------------------------------------------------
// Companies tab
// ---------------------------------------------------------------------------

const CompaniesTab = () => {
  const [companies, setCompanies] = useState<AdminPartnerCompanySummary[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [activatingId, setActivatingId] = useState<string | null>(null);
  const [isActivating, setIsActivating] = useState(false);
  const [activateError, setActivateError] = useState<string | null>(null);

  const [pausingId, setPausingId] = useState<string | null>(null);
  const [isPausing, setIsPausing] = useState(false);
  const [pauseError, setPauseError] = useState<string | null>(null);

  const [endingId, setEndingId] = useState<string | null>(null);
  const [isEnding, setIsEnding] = useState(false);
  const [endError, setEndError] = useState<string | null>(null);

  const isMounted = useRef(true);
  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const load = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const res = await adminListPartnerCompanies();
      if (isMounted.current) setCompanies(res.data.partners);
    } catch {
      if (isMounted.current) setLoadError(t('partners.error'));
    } finally {
      if (isMounted.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleCreate = useCallback(
    async (form: CompanyFormState) => {
      setIsCreating(true);
      setCreateError(null);
      try {
        const latitude = parseFloat(form.latitude);
        const longitude = parseFloat(form.longitude);
        if (isNaN(latitude) || isNaN(longitude)) {
          setCreateError(t('partners.invalidCoordinates'));
          return;
        }
        const req: AdminCreatePartnerRequest = {
          companyName: form.companyName,
          category: form.category,
          publicDescription: form.publicDescription,
          address: form.address,
          latitude,
          longitude,
          publicPhone: form.publicPhone || null,
          publicWebsiteUrl: form.publicWebsiteUrl || null,
        };
        await adminCreatePartnerCompany(req);
        setShowCreateForm(false);
        setSuccessMessage(t('partners.createSuccess'));
        void load();
      } catch (err) {
        setCreateError(err instanceof ApiError ? err.message : t('partners.error'));
      } finally {
        if (isMounted.current) setIsCreating(false);
      }
    },
    [load],
  );

  const handleActivateConfirm = useCallback(async () => {
    if (!activatingId) return;
    setIsActivating(true);
    setActivateError(null);
    try {
      await adminActivatePartner(activatingId);
      setActivatingId(null);
      setSuccessMessage(t('partners.activateSuccess'));
      void load();
    } catch (err) {
      setActivateError(err instanceof ApiError ? err.message : t('partners.error'));
    } finally {
      if (isMounted.current) setIsActivating(false);
    }
  }, [activatingId, load]);

  const handlePauseConfirm = useCallback(
    async (reason: string) => {
      if (!pausingId) return;
      setIsPausing(true);
      setPauseError(null);
      try {
        await adminPausePartner(pausingId, reason || undefined);
        setPausingId(null);
        setSuccessMessage(t('partners.pauseSuccess'));
        void load();
      } catch (err) {
        setPauseError(err instanceof ApiError ? err.message : t('partners.error'));
      } finally {
        if (isMounted.current) setIsPausing(false);
      }
    },
    [pausingId, load],
  );

  const handleEndConfirm = useCallback(
    async (reason: string) => {
      if (!endingId) return;
      setIsEnding(true);
      setEndError(null);
      try {
        await adminEndPartnership(endingId, reason || undefined);
        setEndingId(null);
        setSuccessMessage(t('partners.endSuccess'));
        void load();
      } catch (err) {
        setEndError(err instanceof ApiError ? err.message : t('partners.error'));
      } finally {
        if (isMounted.current) setIsEnding(false);
      }
    },
    [endingId, load],
  );

  return (
    <>
      {activatingId !== null && (
        <ActivateModal
          onConfirm={handleActivateConfirm}
          onCancel={() => {
            setActivatingId(null);
            setActivateError(null);
          }}
          isSubmitting={isActivating}
          error={activateError}
        />
      )}
      {pausingId !== null && (
        <StatusActionModal
          title={t('partners.pauseConfirmTitle')}
          reasonLabel={t('partners.pauseReasonLabel')}
          reasonPlaceholder={t('partners.pauseReasonPlaceholder')}
          confirmLabel={t('partners.pausePartner')}
          onConfirm={handlePauseConfirm}
          onCancel={() => {
            setPausingId(null);
            setPauseError(null);
          }}
          isSubmitting={isPausing}
          error={pauseError}
        />
      )}
      {endingId !== null && (
        <StatusActionModal
          title={t('partners.endConfirmTitle')}
          reasonLabel={t('partners.endReasonLabel')}
          reasonPlaceholder={t('partners.endReasonPlaceholder')}
          confirmLabel={t('partners.endPartnership')}
          onConfirm={handleEndConfirm}
          onCancel={() => {
            setEndingId(null);
            setEndError(null);
          }}
          isSubmitting={isEnding}
          error={endError}
        />
      )}

      <div className={styles.tabHeader}>
        {!showCreateForm && (
          <button className={styles.btnPrimary} onClick={() => setShowCreateForm(true)}>
            {t('partners.createCompany')}
          </button>
        )}
      </div>

      {showCreateForm && (
        <div className={styles.formContainer}>
          <CompanyForm
            initial={EMPTY_COMPANY_FORM}
            onSave={handleCreate}
            onCancel={() => {
              setShowCreateForm(false);
              setCreateError(null);
            }}
            isSaving={isCreating}
            saveError={createError}
          />
        </div>
      )}

      {successMessage !== null && (
        <div className={styles.successBanner}>{successMessage}</div>
      )}
      {actionError !== null && <p className={styles.errorText}>{actionError}</p>}

      {isLoading ? (
        <p className={styles.loadingText}>{t('partners.loading')}</p>
      ) : loadError !== null ? (
        <div>
          <p className={styles.errorText}>{loadError}</p>
          <button className={styles.btnSecondary} onClick={() => void load()}>
            {t('partners.retry')}
          </button>
        </div>
      ) : companies.length === 0 ? (
        <p className={styles.emptyText}>{t('partners.noCompanies')}</p>
      ) : (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>{t('partners.columnCompanyName')}</th>
              <th>{t('partners.columnCategory')}</th>
              <th>{t('partners.columnStatus')}</th>
              <th>{t('partners.columnAddress')}</th>
              <th>{t('partners.columnActivatedAt')}</th>
              <th>{t('partners.columnUpdatedAt')}</th>
              <th>{t('partners.columnActions')}</th>
            </tr>
          </thead>
          <tbody>
            {companies.map((company) => (
              <tr key={company.partnerId}>
                <td>{company.companyName}</td>
                <td>{company.category}</td>
                <td>
                  <span className={`${styles.badge} ${companyStatusBadgeClass(company.status)}`}>
                    {companyStatusLabel(company.status)}
                  </span>
                </td>
                <td>{company.address}</td>
                <td>{formatDate(company.activatedAt)}</td>
                <td>{formatDate(company.updatedAt)}</td>
                <td>
                  <div className={styles.actions}>
                    {(company.status === 'draft' || company.status === 'paused') && (
                      <button
                        className={styles.btnSmallPrimary}
                        onClick={() => {
                          setActivatingId(company.partnerId);
                          setActivateError(null);
                        }}
                        disabled={pending === company.partnerId}
                      >
                        {t('partners.activatePartner')}
                      </button>
                    )}
                    {company.status === 'active' && (
                      <button
                        className={styles.btnSmallWarning}
                        onClick={() => {
                          setPausingId(company.partnerId);
                          setPauseError(null);
                        }}
                        disabled={pending === company.partnerId}
                      >
                        {t('partners.pausePartner')}
                      </button>
                    )}
                    {company.status !== 'ended' && (
                      <button
                        className={styles.btnSmall}
                        onClick={() => {
                          setEndingId(company.partnerId);
                          setEndError(null);
                        }}
                        disabled={pending === company.partnerId}
                      >
                        {t('partners.endPartnership')}
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
};

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

type Tab = 'applications' | 'companies';

export default function PartnersPage() {
  const [activeTab, setActiveTab] = useState<Tab>('applications');

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{t('partners.pageTitle')}</h1>
        <p className={styles.description}>{t('partners.pageDescription')}</p>
      </div>

      <div className={styles.tabs} role="tablist">
        <button
          role="tab"
          aria-selected={activeTab === 'applications'}
          className={activeTab === 'applications' ? styles.tabActive : styles.tab}
          onClick={() => setActiveTab('applications')}
        >
          {t('partners.applicationsTab')}
        </button>
        <button
          role="tab"
          aria-selected={activeTab === 'companies'}
          className={activeTab === 'companies' ? styles.tabActive : styles.tab}
          onClick={() => setActiveTab('companies')}
        >
          {t('partners.companiesTab')}
        </button>
      </div>

      {activeTab === 'applications' ? <ApplicationsTab /> : <CompaniesTab />}
    </div>
  );
}
