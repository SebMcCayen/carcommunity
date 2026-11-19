import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { listCompanies, type PortalCursor } from '@/features/partners/portal';
import { adminCreatePartnerCompany, type AdminPartnerCompanySummary } from '@/features/partners';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { CompanyForm, EMPTY_COMPANY_FORM, type CompanyFormState } from './company-form';
import { p, PortalHeader, Status, ErrorNotice } from './portal-components';
import styles from './portal.module.css';
export default function PartnerCompaniesPage() {
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '';
  const [items, setItems] = useState<AdminPartnerCompanySummary[]>([]);
  const [cursors, setCursors] = useState<(PortalCursor | null)[]>([null]);
  const [next, setNext] = useState<PortalCursor | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const generation = useRef(0);
  const flight = useRef(false);
  const load = useCallback(async () => {
    const run = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      const result = await listCompanies(cursors[pageIndex], status);
      if (run === generation.current) {
        setItems(result.items);
        setNext(result.nextCursor);
      }
    } catch (err) {
      if (run === generation.current) setError(err instanceof Error ? err.message : p('error'));
    } finally {
      if (run === generation.current) setLoading(false);
    }
  }, [cursors, pageIndex, status]);
  const invalidate = useCallback(() => {
    generation.current++;
  }, []);
  useEffect(() => {
    void load();
    return invalidate;
  }, [load, invalidate]);
  async function create(form: CompanyFormState) {
    if (flight.current) return;
    flight.current = true;
    setSaving(true);
    setSaveError(null);
    try {
      if (!form.latitude.trim() || !form.longitude.trim())
        throw new Error(p('latitude') + ' / ' + p('longitude'));
      await adminCreatePartnerCompany({
        ...form,
        latitude: Number(form.latitude),
        longitude: Number(form.longitude),
      });
      setCreating(false);
      await load();
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : p('error'));
    } finally {
      setSaving(false);
      flight.current = false;
    }
  }
  return (
    <div className={styles.page}>
      <PortalHeader title={p('companies')} description={p('profileTitle')} />
      <div className={styles.toolbar}>
        <label className={styles.field}>
          {p('status')}
          <select
            value={status}
            onChange={(event) => {
              setCursors([null]);
              setPageIndex(0);
              setParams(event.target.value ? { status: event.target.value } : {});
            }}
          >
            <option value="">{p('allStatuses')}</option>
            {['draft', 'active', 'paused', 'ended'].map((value) => (
              <option key={value} value={value}>
                {p(value)}
              </option>
            ))}
          </select>
        </label>
        <button
          className={styles.button}
          onClick={() => {
            setSaveError(null);
            setCreating(true);
          }}
        >
          + {p('createCompany')}
        </button>
        <Link className={styles.secondary} to="/partners/statistics">
          {p('statistics')}
        </Link>
      </div>
      <ErrorNotice error={error} retry={() => void load()} />
      {loading ? (
        <p role="status">{p('loading')}</p>
      ) : items.length ? (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                {['name', 'address', 'status', 'actions'].map((value) => (
                  <th key={value}>{p(value)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {items.map((company) => (
                <tr key={company.partnerId}>
                  <td>
                    <Link to={`/partners/${encodeURIComponent(company.partnerId)}`}>
                      {company.companyName}
                    </Link>
                  </td>
                  <td>{company.address || '—'}</td>
                  <td>
                    <Status value={company.status} />
                  </td>
                  <td>
                    <Link
                      className={styles.linkButton}
                      to={`/partners/${encodeURIComponent(company.partnerId)}`}
                    >
                      {p('openProfile')}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !error && <p>{p('emptyCompanies')}</p>
      )}
      <div className={styles.pager}>
        <button
          className={styles.secondary}
          disabled={loading || !pageIndex}
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
      {creating && (
        <ActionDialog
          title={p('companies')}
          onCancel={() => setCreating(false)}
          cancelDisabled={saving}
        >
          <CompanyForm
            initial={EMPTY_COMPANY_FORM}
            onSave={create}
            isSaving={saving}
            saveError={saveError}
          />
        </ActionDialog>
      )}
    </div>
  );
}
