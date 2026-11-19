import { useCallback, useEffect, useRef, useState } from 'react';
import { Building2, Image, UserRound } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  listApplications,
  getApplication,
  reviewApplication,
  type PortalApplication,
  type PortalCursor,
} from '@/features/partners/portal';
import { adminRejectApplication, type AdminPartnerApplicationSummary } from '@/features/partners';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { MapLocationPicker } from '@/components/map/MapLocationPicker';
import { formatDateOnly } from '@/lib/format';
import {
  p,
  PortalHeader,
  PartnerLogo,
  PartnerPreview,
  Status,
  ErrorNotice,
} from '../portal-components';
import styles from '../portal.module.css';

export function ApplicationReview({
  application,
  onRefresh,
}: {
  application: PortalApplication;
  onRefresh: () => Promise<void>;
}) {
  const [grant, setGrant] = useState(application.selfServiceRequested);
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [dialog, setDialog] = useState<'publish' | 'changes' | 'reject' | null>(null);
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const flight = useRef(false);
  const reviewable = ['submitted', 'under_review', 'changes_requested'].includes(
    application.status,
  );
  async function perform() {
    if (flight.current || !dialog) return;
    if (
      dialog === 'publish' &&
      (!confirmed ||
        latitude.trim() === '' ||
        longitude.trim() === '' ||
        !Number.isFinite(Number(latitude)) ||
        !Number.isFinite(Number(longitude)))
    )
      return;
    flight.current = true;
    setBusy(true);
    setError(null);
    try {
      if (dialog === 'reject')
        await adminRejectApplication(application.applicationId, reason.trim());
      else
        await reviewApplication(
          application.applicationId,
          dialog === 'publish' ? 'publish' : 'request_changes',
          dialog === 'publish'
            ? {
                expectedRevision: application.revision,
                grantSelfService: grant,
                latitude: Number(latitude),
                longitude: Number(longitude),
              }
            : { reason: reason.trim() },
        );
      setSuccess(dialog === 'publish' ? p('published') : p('saved'));
      setDialog(null);
      await onRefresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : p('error'));
    } finally {
      flight.current = false;
      setBusy(false);
    }
  }
  const facts: [string, string | null][] = [
    ['name', application.companyName],
    ['organizationNumber', application.organizationNumber],
    ['contactName', application.contactName],
    ['contactEmail', application.contactEmail],
    ['contactPhone', application.contactPhone],
    ['address', application.proposedAddress],
    ['website', application.websiteUrl],
  ];
  return (
    <>
      <PortalHeader
        title={p('reviewTitle')}
        parent={{ to: '/partners/applications', label: p('applications') }}
      />
      <ErrorNotice error={!dialog ? error : null} />
      {success && (
        <p role="status" className={styles.alert}>
          {success}
        </p>
      )}
      <div className={styles.grid}>
        <div>
          <div className={styles.identity}>
            <PartnerLogo
              name={application.companyName}
              path={application.logoPath}
              base64={application.logoPreviewBase64}
            />
            <div>
              <h2>{application.companyName}</h2>
              <Status value={application.status} />
            </div>
          </div>
          <section className={styles.section}>
            <h2>{p('companyData')}</h2>
            <dl className={styles.facts}>
              {facts.map(([label, value]) => (
                <div key={label} style={{ display: 'contents' }}>
                  <dt>{p(label)}</dt>
                  <dd>{value || '—'}</dd>
                </div>
              ))}
            </dl>
            {application.message && <p>{application.message}</p>}
            {application.reviewReason && <p>{application.reviewReason}</p>}
          </section>
          <div className={styles.reviewItem}>
            <span className={styles.reviewIcon}>
              <Building2 aria-hidden="true" />
            </span>
            <div>
              <h3>{p('company')}</h3>
              <p>{application.proposedDescription || p('previewEmpty')}</p>
            </div>
          </div>
          <div className={styles.reviewItem}>
            <span className={styles.reviewIcon}>
              <UserRound aria-hidden="true" />
            </span>
            <div>
              <h3>{p('accountPermission')}</h3>
              <p>
                {p(
                  application.selfServiceRequested
                    ? 'selfServiceRequested'
                    : 'selfServiceNotRequested',
                )}
              </p>
              {reviewable && (
                <label className={styles.check}>
                  <input
                    type="checkbox"
                    checked={grant}
                    disabled={busy || !application.submittedByUserId}
                    onChange={(event) => setGrant(event.target.checked)}
                  />
                  {p('grantSelfService')}
                </label>
              )}
            </div>
          </div>
          <div className={styles.reviewItem}>
            <span className={styles.reviewIcon}>
              <Image aria-hidden="true" />
            </span>
            <div>
              <h3>{p('billboards')}</h3>
              <p>
                {p(application.billboardRequested ? 'billboardRequested' : 'billboardNotRequested')}{' '}
                {p(
                  application.logoPath || application.logoPreviewBase64
                    ? 'logoUploaded'
                    : 'logoMissing',
                )}
              </p>
              {application.billboardPlacementRequest && (
                <p>{application.billboardPlacementRequest}</p>
              )}
              {application.partnerCompanyId && application.billboardRequested && (
                <Link
                  className={styles.linkButton}
                  to={`/billboards?partnerCompanyId=${encodeURIComponent(application.partnerCompanyId)}`}
                >
                  {p('createBillboard')}
                </Link>
              )}
            </div>
          </div>
        </div>
        <PartnerPreview
          name={application.companyName}
          category={application.category}
          description={application.proposedDescription}
          address={application.proposedAddress}
          phone={application.publicPhone}
          website={application.websiteUrl}
          logoPath={application.logoPath}
          logoPreviewBase64={application.logoPreviewBase64}
        />
      </div>
      {reviewable && (
        <footer className={styles.footer}>
          <p>{p('publishNote')}</p>
          <div className={styles.actions}>
            <button
              className={styles.linkButton}
              disabled={busy}
              onClick={() => {
                setReason('');
                setError(null);
                setDialog('reject');
              }}
            >
              {p('reject')}
            </button>
            <button
              className={styles.secondary}
              disabled={busy}
              onClick={() => {
                setReason('');
                setError(null);
                setDialog('changes');
              }}
            >
              {p('requestChanges')}
            </button>
            <button
              className={styles.button}
              disabled={busy}
              onClick={() => {
                setConfirmed(false);
                setError(null);
                setDialog('publish');
              }}
            >
              {p('approvePublish')}
            </button>
          </div>
        </footer>
      )}
      {application.partnerCompanyId && (
        <Link
          className={styles.button}
          to={`/partners/${encodeURIComponent(application.partnerCompanyId)}`}
        >
          {p('openProfile')}
        </Link>
      )}
      {dialog && (
        <ActionDialog
          title={p(
            dialog === 'publish'
              ? 'approveTitle'
              : dialog === 'changes'
                ? 'requestChangesTitle'
                : 'rejectTitle',
          )}
          onCancel={() => setDialog(null)}
          cancelDisabled={busy}
        >
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void perform();
            }}
          >
            <ErrorNotice error={error} />
            {dialog === 'publish' ? (
              <>
                <p>{p('publishNote')}</p>
                <MapLocationPicker
                  latitude={latitude}
                  longitude={longitude}
                  onChange={(lat, lng) => {
                    setLatitude(lat);
                    setLongitude(lng);
                  }}
                  labelLat={p('latitude')}
                  labelLng={p('longitude')}
                  helpText={p('locationConfirm')}
                  unavailableText={p('mapUnavailable')}
                  loadErrorText={p('mapLoadError')}
                  required
                  disabled={busy}
                />
                <label className={styles.check}>
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(event) => setConfirmed(event.target.checked)}
                    disabled={busy}
                  />
                  {p('approveConfirm')}
                </label>
                {(!latitude || !longitude) && (
                  <p role="alert">
                    {p('latitude')} / {p('longitude')}
                  </p>
                )}
              </>
            ) : (
              <label className={styles.field}>
                {p('reason')}
                <textarea
                  autoFocus
                  required
                  minLength={3}
                  maxLength={2000}
                  value={reason}
                  disabled={busy}
                  onChange={(event) => setReason(event.target.value)}
                />
              </label>
            )}
            <button
              className={styles.button}
              type="submit"
              disabled={
                busy ||
                (dialog === 'publish'
                  ? !confirmed || !latitude || !longitude
                  : reason.trim().length < 3)
              }
            >
              {busy ? p('loading') : p(dialog === 'publish' ? 'approvePublish' : 'send')}
            </button>
          </form>
        </ActionDialog>
      )}
    </>
  );
}
export default function PartnerApplicationsPage() {
  const [params, setParams] = useSearchParams();
  const id = params.get('application');
  const status = params.get('status') ?? '';
  const [items, setItems] = useState<AdminPartnerApplicationSummary[]>([]);
  const [cursors, setCursors] = useState<(PortalCursor | null)[]>([null]);
  const [next, setNext] = useState<PortalCursor | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [application, setApplication] = useState<PortalApplication | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const generation = useRef(0);
  const load = useCallback(async () => {
    const run = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      if (id) {
        const result = await getApplication(id);
        if (run === generation.current) setApplication(result);
      } else {
        const result = await listApplications(cursors[pageIndex], status);
        if (run === generation.current) {
          setItems(result.items);
          setNext(result.nextCursor);
        }
      }
    } catch (err) {
      if (run === generation.current) setError(err instanceof Error ? err.message : p('error'));
    } finally {
      if (run === generation.current) setLoading(false);
    }
  }, [id, status, cursors, pageIndex]);
  const invalidate = useCallback(() => {
    generation.current++;
  }, []);
  useEffect(() => {
    void load();
    return invalidate;
  }, [load, invalidate]);
  if (id)
    return (
      <div className={styles.page}>
        {loading ? (
          <p role="status">{p('loading')}</p>
        ) : (
          application && <ApplicationReview key={id} application={application} onRefresh={load} />
        )}
        <ErrorNotice error={error} retry={() => void load()} />
      </div>
    );
  return (
    <div className={styles.page}>
      <PortalHeader title={p('applications')} description={p('reviewDescription')} />
      <div className={styles.toolbar}>
        <label className={styles.field}>
          {p('status')}
          <select
            value={status}
            onChange={(event) => {
              setPageIndex(0);
              setCursors([null]);
              setParams(event.target.value ? { status: event.target.value } : {});
            }}
          >
            <option value="">{p('allStatuses')}</option>
            {[
              'submitted',
              'under_review',
              'changes_requested',
              'approved',
              'rejected',
              'withdrawn',
            ].map((value) => (
              <option key={value} value={value}>
                {p(value)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorNotice error={error} retry={() => void load()} />
      {loading ? (
        <p role="status">{p('loading')}</p>
      ) : items.length ? (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                {['name', 'contactName', 'status', 'submittedAt', 'actions'].map((value) => (
                  <th key={value}>{p(value)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.applicationId}>
                  <td>{item.companyName}</td>
                  <td>{item.contactName}</td>
                  <td>
                    <Status value={item.status} />
                  </td>
                  <td>{formatDateOnly(item.submittedAt)}</td>
                  <td>
                    <Link
                      to={`?application=${encodeURIComponent(item.applicationId)}`}
                      className={styles.linkButton}
                    >
                      {p('review')}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !error && <p>{p('emptyApplications')}</p>
      )}
      <div className={styles.pager}>
        <button
          className={styles.secondary}
          disabled={loading || pageIndex === 0}
          onClick={() => setPageIndex((i) => i - 1)}
        >
          {p('previous')}
        </button>
        <span>{pageIndex + 1}</span>
        <button
          className={styles.secondary}
          disabled={loading || !next}
          onClick={() => {
            setCursors((previous) => [...previous.slice(0, pageIndex + 1), next]);
            setPageIndex((i) => i + 1);
          }}
        >
          {p('next')}
        </button>
      </div>
    </div>
  );
}
