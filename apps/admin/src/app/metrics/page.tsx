'use client';

import { useEffect, useMemo, useState } from 'react';
import { StatCard } from '@/components/ui/StatCard';
import {
  brandSlices,
  loadMetricsSeries,
  metersToKm,
  newUsersPerDay,
  trendOf,
  type MetricsSnapshot,
} from '@/features/metrics';
import { isFirestoreEmulatorEnabled } from '@/lib/firestore';
import { BarChart, HBar, LineChart } from './charts';
import styles from './page.module.css';

function fmt(value: number): string {
  return value.toLocaleString('sv-SE');
}

/** A chart card: a titled panel that renders its chart, or a note when too sparse. */
function ChartCard({
  title,
  subtitle,
  ready,
  emptyNote,
  children,
}: {
  title: string;
  subtitle?: string;
  ready: boolean;
  emptyNote: string;
  children: React.ReactNode;
}) {
  return (
    <div className={styles.chartCard}>
      <div className={styles.chartHead}>
        <h3 className={styles.chartTitle}>{title}</h3>
        {subtitle && <span className={styles.chartSub}>{subtitle}</span>}
      </div>
      {ready ? children : <p className={styles.chartEmpty}>{emptyNote}</p>}
    </div>
  );
}

export default function MetricsPage() {
  const [series, setSeries] = useState<MetricsSnapshot[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    loadMetricsSeries()
      .then((s) => {
        if (active) setSeries(s);
      })
      .catch(() => {
        if (active) setError('Could not load metrics. Try refreshing.');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const latest = series && series.length > 0 ? series[series.length - 1]! : null;
  const hasTrend = (series?.length ?? 0) >= 2;

  const usersTrend = useMemo(() => (series ? trendOf(series, 'totalUsers') : []), [series]);
  const newUsers = useMemo(() => (series ? newUsersPerDay(series) : []), [series]);
  const kmTrend = useMemo(
    () =>
      series
        ? trendOf(series, 'totalDistanceMeters').map((p) => ({ date: p.date, value: metersToKm(p.value) }))
        : [],
    [series],
  );
  const eventsTrend = useMemo(() => (series ? trendOf(series, 'eventsHeld') : []), [series]);
  const slices = useMemo(() => brandSlices(latest), [latest]);
  const maxBrand = slices.length > 0 ? slices[0]!.count : 0;

  const dataSource = isFirestoreEmulatorEnabled()
    ? 'the local Firestore emulator'
    : 'the production database';

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Growth &amp; Metrics</h1>
          <p className={styles.subtitle}>
            Community totals from {dataSource}, captured once a day. Every figure is an
            aggregate — no member is identifiable here.
          </p>
        </div>
        {latest && (
          <span className={styles.asOf} title={`Captured ${new Date(latest.capturedAtMs).toLocaleString('sv-SE')}`}>
            As of {latest.date}
          </span>
        )}
      </div>

      {error && (
        <p className={styles.subtitle} role="alert">
          {error}
        </p>
      )}

      {loading && !series && <p className={styles.subtitle}>Loading…</p>}

      {/* Empty state — the snapshot job has not written a document yet. */}
      {!loading && series && series.length === 0 && !error && (
        <div className={styles.emptyState} role="status">
          <span className={styles.emptyMark} aria-hidden="true">
            ◔
          </span>
          <h2 className={styles.emptyTitle}>Collecting data</h2>
          <p className={styles.emptyText}>
            Metrics are captured once a day and there is no historical backfill, so this page
            starts empty and fills in over the coming days. The first snapshot appears after the
            daily job first runs — check back tomorrow.
          </p>
        </div>
      )}

      {latest && (
        <>
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Overview</h2>
            <div className={styles.statsGrid}>
              <StatCard label="Total members" value={fmt(latest.totalUsers)} note="All registered accounts" />
              <StatCard
                label="Convoys created"
                value={fmt(latest.convoysCreated)}
                note="All time"
                variant="success"
              />
              <StatCard label="Total distance" value={`${fmt(metersToKm(latest.totalDistanceMeters))} km`} note="Across all saved drives" />
              <StatCard label="Events held" value={fmt(latest.eventsHeld)} note="All time" />
              <StatCard label="Drives saved" value={fmt(latest.drivesSaved)} note="All time" />
              <StatCard label="Crowns collected" value={fmt(latest.crownsCollected)} note="Kronjakt" />
              <StatCard label="Friend connections" value={fmt(latest.friendConnections)} note="Undirected pairs" />
              <StatCard label="Active convoys" value={fmt(latest.activeConvoys)} note="Live right now" />
              <StatCard label="Vehicle profiles" value={fmt(latest.vehicleProfiles)} note="Cars in members' garages" />
            </div>
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Trends over time</h2>
            {!hasTrend && (
              <p className={styles.dayOneNote}>
                Only one snapshot so far — trend lines need at least two days of data. The hero
                figures above are already live; the charts below start drawing tomorrow.
              </p>
            )}
            <div className={styles.chartGrid}>
              <ChartCard
                title="Total members"
                subtitle="Cumulative"
                ready={hasTrend}
                emptyNote="Needs a second day of data."
              >
                <LineChart points={usersTrend} />
              </ChartCard>
              <ChartCard
                title="New members per day"
                subtitle="Derived from the daily total"
                ready={newUsers.length >= 1}
                emptyNote="Needs a second day of data."
              >
                <BarChart points={newUsers} />
              </ChartCard>
              <ChartCard title="Distance driven" subtitle="Kilometres, cumulative" ready={hasTrend} emptyNote="Needs a second day of data.">
                <LineChart points={kmTrend} format={(v) => `${fmt(v)} km`} />
              </ChartCard>
              <ChartCard title="Events held" subtitle="Cumulative" ready={hasTrend} emptyNote="Needs a second day of data.">
                <LineChart points={eventsTrend} />
              </ChartCard>
            </div>
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Vehicle brands in members&apos; garages</h2>
            {slices.length === 0 ? (
              <p className={styles.chartEmpty}>No vehicle profiles yet.</p>
            ) : (
              <div className={styles.brandCard}>
                <div className={styles.brandList}>
                  {slices.map((s) => (
                    <HBar key={s.makeId} label={s.label} count={s.count} max={maxBrand} />
                  ))}
                </div>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
