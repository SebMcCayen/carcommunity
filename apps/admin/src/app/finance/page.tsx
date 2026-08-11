'use client';

import { useEffect, useMemo, useState } from 'react';
import { StatCard } from '@/components/ui/StatCard';
import {
  GCP_BILLING_URL,
  formatCount,
  formatSek,
  loadFinanceEstimate,
  type FinanceEstimate,
  type ServiceLine,
} from '@/features/finance';
import { CompositionBars, ProjectionChart } from './charts';
import styles from './page.module.css';

type SortKey = 'service' | 'gross' | 'billable' | 'sekPerMonth';

/** The permanent honesty banner — this board is a model estimate, not the bill. */
function HonestyBanner() {
  return (
    <div className={styles.banner} role="note">
      <span className={styles.bannerMark} aria-hidden="true">
        ⚠
      </span>
      <div className={styles.bannerBody}>
        <strong>Estimated from a cost model — not your actual bill.</strong> Every figure here is
        computed from the code plus a maintained price table, not read from Google Cloud. For the
        invoiced amount see the{' '}
        <a className={styles.bannerLink} href={GCP_BILLING_URL} target="_blank" rel="noreferrer">
          Google Cloud billing console
        </a>
        . Mapbox and Claude bill separately on their own dashboards.
      </div>
    </div>
  );
}

function SortHeader({
  label,
  numeric,
  active,
  onClick,
}: {
  label: string;
  numeric?: boolean;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <th className={numeric ? styles.numeric : undefined} onClick={onClick} aria-sort={active ? 'descending' : 'none'}>
      {label} {active ? '▾' : ''}
    </th>
  );
}

function ServiceTable({ services }: { services: ServiceLine[] }) {
  const [sortKey, setSortKey] = useState<SortKey>('sekPerMonth');

  const sorted = useMemo(() => {
    const copy = [...services];
    copy.sort((a, b) => {
      if (sortKey === 'service') return a.service.localeCompare(b.service);
      return b[sortKey] - a[sortKey];
    });
    return copy;
  }, [services, sortKey]);

  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr>
            <SortHeader label="Service / driver" active={sortKey === 'service'} onClick={() => setSortKey('service')} />
            <th>Type</th>
            <SortHeader label="Gross / month" numeric active={sortKey === 'gross'} onClick={() => setSortKey('gross')} />
            <th className={styles.numeric}>Free tier</th>
            <SortHeader label="Billable" numeric active={sortKey === 'billable'} onClick={() => setSortKey('billable')} />
            <SortHeader label="SEK / month" numeric active={sortKey === 'sekPerMonth'} onClick={() => setSortKey('sekPerMonth')} />
          </tr>
        </thead>
        <tbody>
          {sorted.map((l) => {
            const isTrafikverket = l.driver.includes('Trafikverket');
            return (
              <tr key={`${l.service}-${l.driver}`} className={isTrafikverket ? styles.rowDominant : undefined}>
                <td>
                  <div>{l.service}</div>
                  <div className={styles.rowDriver} title={l.note}>
                    {l.driver}
                  </div>
                </td>
                <td>
                  <span className={`${styles.badge} ${l.committed ? styles.badgeCommitted : ''}`}>
                    {l.committed ? 'Committed' : 'Variable'}
                  </span>
                </td>
                <td className={styles.numeric}>
                  {l.gross > 0 ? `${formatCount(l.gross)} ${l.unit}` : '—'}
                </td>
                <td className={styles.numeric}>{l.freeTier > 0 ? formatCount(l.freeTier) : '—'}</td>
                <td className={styles.numeric}>{l.billable > 0 ? formatCount(l.billable) : '—'}</td>
                <td className={`${styles.numeric} ${l.free ? styles.free : styles.cost}`}>
                  {l.free ? 'Free' : formatSek(l.sekPerMonth)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function CommittedTable({ est }: { est: FinanceEstimate }) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Scheduled job</th>
            <th>Cadence</th>
            <th className={styles.numeric}>Runs / day</th>
            <th className={styles.numeric}>Writes / month</th>
            <th className={styles.numeric}>Reads / month</th>
          </tr>
        </thead>
        <tbody>
          {est.googleCloud.committedJobs.map((j) => (
            <tr key={j.id} className={j.id === 'incidents-syncTrafikverket' ? styles.rowDominant : undefined}>
              <td>
                <div>{j.label}</div>
                <div className={styles.rowDriver} title={j.note}>
                  {j.id}
                </div>
              </td>
              <td>{j.schedule}</td>
              <td className={styles.numeric}>{j.runsPerDay < 1 ? j.runsPerDay.toFixed(2) : formatCount(j.runsPerDay)}</td>
              <td className={styles.numeric}>{formatCount(j.writesPerMonth)}</td>
              <td className={styles.numeric}>{formatCount(j.readsPerMonth)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function FinancePage() {
  const [est, setEst] = useState<FinanceEstimate | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    loadFinanceEstimate()
      .then((e) => {
        if (active) setEst(e);
      })
      .catch(() => {
        if (active) setError('Could not load the cost estimate. Try refreshing.');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const projectionPoints = useMemo(
    () =>
      est
        ? est.projection.map((p) => ({
            members: p.members,
            total: p.grandTotalSekPerMonth,
            mapbox: p.mapboxSekPerMonth,
          }))
        : [],
    [est],
  );

  const compositionSlices = useMemo(
    () =>
      est
        ? [
            { label: 'Google Cloud (estimated)', sek: est.googleCloud.totalSekPerMonth },
            { label: 'Mapbox (estimated)', sek: est.mapbox.sekPerMonth },
            { label: 'Fixed subscriptions', sek: est.fixedSubscriptions.totalSekPerMonth },
          ].sort((a, b) => b.sek - a.sek)
        : [],
    [est],
  );

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Finance &amp; Cost Model</h1>
          <p className={styles.subtitle}>
            An estimate of monthly spend in SEK, computed from the code and a maintained price table.
          </p>
        </div>
        {est && (
          <span className={styles.asOf}>
            <span>Generated {new Date(est.generatedAtMs).toLocaleString('sv-SE')}</span>
            <span className={styles.asOfMuted}>
              {est.member.source === 'metrics-snapshot'
                ? `${formatCount(est.member.count)} members (as of ${est.member.asOf})`
                : `${formatCount(est.member.count)} members (fallback — no snapshot yet)`}
            </span>
            <span className={styles.asOfMuted}>
              FX 1 USD = {est.fx.usdToSek} SEK (as of {est.fx.capturedOn})
            </span>
          </span>
        )}
      </div>

      <HonestyBanner />

      {error && (
        <p className={styles.subtitle} role="alert">
          {error}
        </p>
      )}
      {loading && !est && <p className={styles.subtitle}>Loading…</p>}

      {est && est.member.source === 'fallback' && (
        <p className={styles.fallbackNote} role="status">
          No <code>metrics/&#123;date&#125;</code> snapshot exists yet, so the variable half is scaled
          by a labelled default of {formatCount(est.member.count)} members — not a live figure. Once
          the daily metrics job (Growth &amp; Metrics) writes its first snapshot, this board reads the
          real member count automatically.
        </p>
      )}

      {est && (
        <>
          {/* Top-line totals */}
          <section className={styles.section}>
            <div className={styles.totalsGrid}>
              <StatCard
                label="Estimated total / month"
                value={formatSek(est.grandTotalSekPerMonth)}
                note="Google Cloud + Mapbox + subscriptions"
              />
              <StatCard
                label="Google Cloud (estimated)"
                value={formatSek(est.googleCloud.totalSekPerMonth)}
                note={`Committed ${formatSek(est.googleCloud.committedSekPerMonth)} + variable ${formatSek(est.googleCloud.variableSekPerMonth)}`}
              />
              <StatCard
                label="Mapbox (estimated)"
                value={formatSek(est.mapbox.sekPerMonth)}
                note="Separate vendor"
                variant={est.mapbox.sekPerMonth > 0 ? 'warning' : 'default'}
              />
              <StatCard
                label="Fixed subscriptions"
                value={est.fixedSubscriptions.hasUnset ? 'Set your plan cost' : formatSek(est.fixedSubscriptions.totalSekPerMonth)}
                note="Claude & tooling"
                variant={est.fixedSubscriptions.hasUnset ? 'warning' : 'default'}
              />
            </div>
            <div className={styles.chartCard}>
              <p className={styles.sectionNote}>Where the estimated monthly money goes</p>
              <CompositionBars slices={compositionSlices} />
            </div>
          </section>

          {/* Google Cloud detail */}
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Google Cloud — service breakdown</h2>
            <p className={styles.sectionNote}>
              Per service: gross usage → minus the free tier → billable → SEK. Sortable. Only usage
              above the free tier costs money, so several services read “Free” today. The dominant
              line is <strong>Trafikverket writes</strong> (highlighted). Estimated total{' '}
              {formatSek(est.googleCloud.totalSekPerMonth)}/month.
            </p>
            <ServiceTable services={est.googleCloud.services} />
          </section>

          {/* Committed / Trafikverket */}
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Committed (scheduled) jobs</h2>
            <p className={styles.sectionNote}>
              These run on a fixed cadence regardless of member count — cost = cadence × work. The
              Trafikverket import (highlighted) writes ~{formatCount(est.googleCloud.trafikverketSituationsPerRun)}{' '}
              incidents every 30 minutes (cap {formatCount(est.googleCloud.trafikverketSituationsCap)}), which is{' '}
              <strong>~{formatSek(est.googleCloud.trafikverketWritesSekPerMonth)}/month</strong> of Firestore
              writes and dwarfs every other committed line.
            </p>
            <CommittedTable est={est} />
          </section>

          {/* Projection over time */}
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Projected cost as the community grows</h2>
            <p className={styles.sectionNote}>
              A <strong>projection from the current model</strong> — not historical spend (there is
              none yet). Solid line = estimated grand total; dashed line = the Mapbox share, which
              overtakes everything else as members climb. The leftmost point is today’s{' '}
              {formatCount(est.member.count)} members.
            </p>
            <div className={styles.chartCard}>
              <ProjectionChart points={projectionPoints} />
            </div>
          </section>

          {/* Mapbox — separate vendor */}
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Mapbox — separate vendor</h2>
            <p className={styles.sectionNote}>
              The member app is a <strong>mobile app</strong> (Maps SDK + Navigation SDK for
              Android), so Mapbox bills by <strong>Monthly Active Users (MAU)</strong> and navigation
              trips — <em>not</em> per web map load. The basemap is free up to 25,000 MAU, so it costs
              nothing at this scale; <strong>Navigation is the real, growing cost driver</strong>. Nav
              usage assumes {Math.round(est.mapbox.assumptions.navUsingFraction * 100)}% of members
              navigate, {est.mapbox.assumptions.navTripsPerNavigatingMemberPerMonth} trips each per
              month. Billed by Mapbox on its own dashboard — never part of the Google Cloud total.
            </p>
            <div className={styles.vendorCard}>
              <div className={styles.vendorHead}>
                <span className={styles.vendorTitle}>Mapbox subtotal</span>
                <span className={styles.vendorAmount}>{formatSek(est.mapbox.sekPerMonth)}/mo</span>
              </div>
              {est.mapbox.lines.map((l) => (
                <div key={l.id} className={styles.subRow}>
                  <div>
                    <div>{l.label}</div>
                    <div className={styles.rowDriver} title={l.note}>
                      {l.driver} · {l.usage}
                    </div>
                  </div>
                  <div>
                    <span className={l.free ? styles.free : styles.cost}>
                      {l.free ? 'Free' : `${formatSek(l.sekPerMonth)}/mo`}
                    </span>
                  </div>
                </div>
              ))}
              <p className={styles.vendorMeta}>
                Source: {est.mapbox.source} · captured {est.mapbox.capturedOn}
              </p>
            </div>
          </section>

          {/* Fixed subscriptions — separate section */}
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Fixed subscriptions &amp; tooling</h2>
            <p className={styles.sectionNote}>
              Flat recurring vendor costs — not usage-driven, never blended into the Google Cloud or
              Mapbox estimates. Edit the amounts in{' '}
              <code>functions/src/finance/assumptions.ts</code>.
            </p>
            <div className={styles.vendorCard}>
              {est.fixedSubscriptions.items.map((s) => (
                <div key={s.id} className={styles.subRow}>
                  <div>
                    <div>{s.name}</div>
                    {s.note && <div className={styles.rowDriver}>{s.note}</div>}
                  </div>
                  <div>
                    {s.sekPerMonth === null ? (
                      <span className={styles.subUnset}>Set your plan cost</span>
                    ) : (
                      <span className={styles.cost}>
                        {formatSek(s.sekPerMonth)}/mo
                        <span className={styles.rowDriver}>
                          {' '}
                          ({s.amount} {s.currency}/{s.period === 'annual' ? 'yr' : 'mo'})
                        </span>
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </section>

          {/* Function inventory / uncosted flag */}
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Function inventory</h2>
            <p className={styles.sectionNote}>
              {formatCount(est.functionInventory.totalCallables)} callables +{' '}
              {formatCount(est.functionInventory.scheduledJobs)} scheduled jobs are accounted for in
              the model.
            </p>
            {est.functionInventory.uncosted.length === 0 ? (
              <div className={styles.uncosted}>
                ✓ Every function is mapped to a cost driver. When a new function is added, a CI test
                forces it to be classified — an unclassified or explicitly-uncosted function would
                appear here as “needs a driver estimate”, never as a hidden zero.
              </div>
            ) : (
              <div className={styles.uncosted}>
                <strong>Uncosted — needs a driver estimate:</strong>
                <ul>
                  {est.functionInventory.uncosted.map((name) => (
                    <li key={name}>{name}</li>
                  ))}
                </ul>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
