'use client';

import { useEffect, useState } from 'react';
import { StatCard } from '@/components/ui/StatCard';
import { loadDashboardStats, type DashboardStats } from '@/features/dashboard';
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
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    loadDashboardStats()
      .then((s) => {
        if (active) setStats(s);
      })
      .catch(() => {
        if (active) setError(true);
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

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Dashboard</h1>
        <p className={styles.subtitle}>
          Live figures from the production database. Tiles showing “—” aren’t wired to a
          data source yet (Realtime Database or pending admin-read access).
        </p>
      </div>

      {error && (
        <p className={styles.subtitle}>Could not load dashboard statistics. Try refreshing.</p>
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
