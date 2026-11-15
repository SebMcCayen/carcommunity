import { useState, type FormEvent } from 'react';
import { StatCard } from '@/components/ui/StatCard';
import { translate } from '@/i18n';
import {
  formatCount,
  formatSek,
  updateWorkloadAssumptions,
  type FinanceEstimate,
  type WorkloadLine,
} from '@/features/finance';
import styles from './page.module.css';

const t = (key: string) => translate('sv', `financeCoverage.${key}`);
const date = (ms: number) => new Date(ms).toLocaleString('sv-SE');
const money = (value: number | null | undefined) =>
  value == null ? t('missing') : formatSek(value);

function AssumptionsForm({ rows, onSaved }: { rows: WorkloadLine[]; onSaved: () => void }) {
  const [rates, setRates] = useState(() =>
    Object.fromEntries(rows.map((row) => [row.id, String(row.assumedEventsPerMemberPerDay)])),
  );
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  async function save(reset = false) {
    const overrides = reset
      ? []
      : rows.map((row) => ({ id: row.id, eventsPerMemberPerDay: Number(rates[row.id]) }));
    if (
      overrides.some(
        (row) =>
          !rates[row.id]?.trim() ||
          !Number.isFinite(row.eventsPerMemberPerDay) ||
          row.eventsPerMemberPerDay < 0 ||
          row.eventsPerMemberPerDay > 1000,
      )
    ) {
      setMessage(t('invalid'));
      return;
    }
    setBusy(true);
    setMessage('');
    try {
      await updateWorkloadAssumptions(overrides);
      setMessage(t('saved'));
      onSaved();
    } catch {
      setMessage(t('saveFailed'));
    } finally {
      setBusy(false);
    }
  }
  function submit(event: FormEvent) {
    event.preventDefault();
    void save();
  }

  return (
    <details className={styles.chartCard}>
      <summary>{t('editAssumptions')}</summary>
      <p className={styles.sectionNote}>{t('assumptionsHelp')}</p>
      <form onSubmit={submit}>
        <fieldset disabled={busy} className={styles.assumptionsFields}>
          <legend>{t('frequency')}</legend>
          {rows.map((row) => (
            <label key={row.id} className={styles.assumptionField}>
              <span>{t(`workloads.${row.labelKey}`)}</span>
              <input
                type="number"
                min="0"
                max="1000"
                step="any"
                required
                value={rates[row.id] ?? ''}
                onChange={(event) => setRates((old) => ({ ...old, [row.id]: event.target.value }))}
              />
            </label>
          ))}
          <div className={styles.assumptionActions}>
            <button type="submit">{busy ? t('saving') : t('save')}</button>
            <button type="button" onClick={() => void save(true)}>
              {t('reset')}
            </button>
          </div>
        </fieldset>
        <p role="status">{message}</p>
      </form>
    </details>
  );
}

export function CostCoverage({ est, onSaved }: { est: FinanceEstimate; onSaved: () => void }) {
  const billing = est.reportedBilling;
  const rows = est.workloads;
  return (
    <>
      <section className={styles.section} aria-labelledby="reported-billing-title">
        <h2 className={styles.sectionTitle} id="reported-billing-title">
          {t('billingTitle')}
        </h2>
        <p className={styles.sectionNote}>{t('billingHelp')}</p>
        <p role="status">
          {t(`states.${billing?.state ?? 'not-configured'}`)}
          {billing?.lastAttemptFailed && <> · {t('refreshFailed')}</>}
        </p>
        {billing?.data && (
          <p className={styles.sectionNote}>
            {t('period')}: {billing.data.month} · {t('through')}: {billing.data.throughDate} (UTC) ·{' '}
            {t('updated')}: {date(billing.data.capturedAtMs)}
            <br />
            {t('exported')}: {billing.data.latestExportAt}
          </p>
        )}
        <div className={styles.totalsGrid}>
          <StatCard label={t('reported')} value={money(billing?.totalSek)} note={t('netCost')} />
          <StatCard
            label={t('billingForecast')}
            value={money(billing?.forecastSek)}
            note={t('forecastHelp')}
          />
          <StatCard
            label={t('modelPeriod')}
            value={money(billing?.modelForPeriodSek)}
            note={t('samePeriod')}
          />
          <StatCard
            label={t('difference')}
            value={money(billing?.differenceSek)}
            note={t('differenceHelp')}
          />
        </div>
        {billing?.data && (
          <>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <caption className={styles.sectionNote}>{t('reportedServices')}</caption>
                <thead>
                  <tr>
                    <th>{t('service')}</th>
                    <th className={styles.numeric}>{t('netCost')}</th>
                  </tr>
                </thead>
                <tbody>
                  {billing.data.services.map((service) => (
                    <tr key={service.id}>
                      <td>{service.label}</td>
                      <td className={styles.numeric}>{formatSek(service.netCost)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className={styles.sectionNote}>
              {t('unallocated')}: <strong>{money(billing.unallocatedToFunctionsSek)}</strong>.{' '}
              {t('unallocatedHelp')}
            </p>
          </>
        )}
      </section>
      <section className={styles.section} aria-labelledby="workload-cost-title">
        <h2 className={styles.sectionTitle} id="workload-cost-title">
          {t('workloadTitle')}
        </h2>
        <p className={styles.sectionNote}>{t('workloadHelp')}</p>
        <p role="status">
          {t('usageStatus')}: {t(`states.${est.usageEvidence?.state ?? 'not-configured'}`)}
          {est.usageEvidence?.lastAttemptFailed && <> · {t('refreshFailed')}</>}
        </p>
        {!rows ? (
          <p>{t('backendPending')}</p>
        ) : (
          <>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <caption className={styles.sectionNote}>{t('allocationHelp')}</caption>
                <thead>
                  <tr>
                    <th>{t('function')}</th>
                    <th>{t('source')}</th>
                    <th className={styles.numeric}>{t('events')}</th>
                    <th className={styles.numeric}>{t('traffic')}</th>
                    <th className={styles.numeric}>{t('monthly')}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td>{t(`workloads.${row.labelKey}`)}</td>
                      <td>
                        {t(row.source)}
                        <div className={styles.rowDriver}>
                          {row.capturedAtMs ? (
                            <>
                              {row.periodStart} – {row.periodEnd} (UTC) · {t('updated')}:{' '}
                              {date(row.capturedAtMs)}
                            </>
                          ) : (
                            <>{t('assumptionDate')}</>
                          )}
                        </div>
                      </td>
                      <td className={styles.numeric}>{formatCount(row.eventsPerMonth)}</td>
                      <td className={styles.numeric}>
                        {(row.resources.sentBytes / 1024 ** 2).toLocaleString('sv-SE', {
                          maximumFractionDigits: 1,
                        })}{' '}
                        MiB
                      </td>
                      <td className={styles.numeric}>{formatSek(row.sekPerMonth)}</td>
                    </tr>
                  ))}
                  <tr>
                    <td colSpan={4}>{t('other')}</td>
                    <td className={styles.numeric}>{money(est.otherModelledSekPerMonth)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <details className={styles.chartCard}>
              <summary>{t('unitAssumptions')}</summary>
              <p className={styles.sectionNote}>{t('unitHelp')}</p>
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>{t('function')}</th>
                      <th>{t('reads')}</th>
                      <th>{t('writes')}</th>
                      <th>{t('deletes')}</th>
                      <th>{t('seconds')}</th>
                      <th>{t('bytes')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.id}>
                        <td>{t(`workloads.${row.labelKey}`)}</td>
                        <td>{row.readsPerEvent}</td>
                        <td>{row.writesPerEvent}</td>
                        <td>{row.deletesPerEvent}</td>
                        <td>{row.secondsPerEvent}</td>
                        <td>{formatCount(row.bytesPerEvent)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </details>
            {est.assumptionsUpdatedAtMs && (
              <p className={styles.sectionNote}>
                {t('assumptionsUpdated')}: {date(est.assumptionsUpdatedAtMs)}
              </p>
            )}
            <AssumptionsForm key={est.generatedAtMs} rows={rows} onSaved={onSaved} />
          </>
        )}
      </section>
    </>
  );
}
