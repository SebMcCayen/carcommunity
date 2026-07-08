'use client';

import { useEffect, useState } from 'react';
import { StatCard } from '@/components/ui/StatCard';
import { COUNTABLE_STATS, loadDashboardStats, type DashboardStats } from '@/features/dashboard';
import { isFirestoreEmulatorEnabled } from '@/lib/firestore';
import styles from './page.module.css';

/** Formats a live count, or "—" when the stat isn't available yet. */
function fmt(value: number | null, loading: boolean): string {
  if (loading) return '…';
  if (value === null) return '—';
  return value.toLocaleString('sv-SE');
}

export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    loadDashboardStats()
      .then((s) => {
        if (!active) return;
        setStats(s);
        // safeCount() turns per-tile failures into null, so loadDashboardStats
        // resolves even when a countable query was denied/failed. Surface that
        // as an error (while still rendering what loaded) — placeholder tiles
        // are always null by design and must not count as failures.
        if (COUNTABLE_STATS.some((key) => s[key] === null)) {
          setError('Some statistics could not be loaded. Try refreshing.');
        }
      })
      .catch(() => {
        if (active) setError('Could not load dashboard statistics. Try refreshing.');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const s = stats;
  const openReports = s?.openReports ?? null;

  // Avoid claiming "production database" when reads are routed to the local
  // Firestore emulator (dev / non-PROD builds with VITE_FIRESTORE_EMULATOR_HOST).
  const dataSource = isFirestoreEmulatorEnabled()
    ? 'the local Firestore emulator'
    : 'the production database';

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Dashboard</h1>
        <p className={styles.subtitle}>
          Live figures from {dataSource}. Tiles showing “—” aren’t wired to a
          data source yet (Realtime Database or pending admin-read access).
        </p>
      </div>

      {error && (
        <p className={styles.subtitle} role="alert">{error}</p>
      )}

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Overview</h2>
        <div className={styles.statsGrid}>
          <StatCard label="Total Users" value={fmt(s?.totalUsers ?? null, loading)} note="All registered accounts" />
          <StatCard
            label="Active Members"
            value={fmt(s?.activeMembers ?? null, loading)}
            note="Accounts with an active membership"
            variant="success"
          />
          <StatCard
            label="Live Location Sessions"
            value={fmt(s?.liveSessions ?? null, loading)}
            note="Realtime Database — not yet wired"
          />
          <StatCard
            label="Open Reports"
            value={fmt(openReports, loading)}
            note="Awaiting moderation review"
            variant={typeof openReports === 'number' && openReports > 0 ? 'warning' : 'default'}
          />
          <StatCard
            label="Pending Partner Approvals"
            value={fmt(s?.pendingPartners ?? null, loading)}
            note="Needs admin-read access"
          />
          <StatCard
            label="Pending Billboards"
            value={fmt(s?.pendingBillboards ?? null, loading)}
            note="Needs admin-read access"
          />
          <StatCard
            label="Vehicle Profiles"
            value={fmt(s?.vehicleProfiles ?? null, loading)}
            note="Total private vehicle profiles"
          />
          <StatCard
            label="Users with Vehicles"
            value={fmt(s?.usersWithVehicles ?? null, loading)}
            note="Aggregate not yet available"
          />
        </div>
      </section>
    </div>
  );
}
