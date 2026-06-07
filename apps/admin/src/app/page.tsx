import { StatCard } from '@/components/ui/StatCard';
import styles from './page.module.css';

// TODO: Replace mock data with real API calls once backend integration is in place.
// TODO: Backend must verify admin role before serving any data to this page.

const mockStats = {
  totalUsers: 1247,
  activeMembers: 432,
  liveSessions: 18,
  openReports: 7,
  pendingPartners: 3,
  pendingBillboards: 5,
};

export default function DashboardPage() {
  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Dashboard</h1>
        <p className={styles.subtitle}>
          Admin overview — all figures are placeholder mock data only.
        </p>
      </div>

      {/* Auth warning — must be removed once real auth is in place */}
      <div className={styles.authWarning} role="alert">
        <span aria-hidden="true">⚠</span>
        <span>
          <strong>Authentication not configured.</strong> Microsoft Entra ID integration and backend
          role validation are required before production use. Admin access must be verified by the
          backend. Do not trust client-side admin flags.
        </span>
      </div>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Overview</h2>
        <div className={styles.statsGrid}>
          <StatCard
            label="Total Users"
            value={mockStats.totalUsers.toLocaleString('sv-SE')}
            note="All registered accounts"
          />
          <StatCard
            label="Active Members"
            value={mockStats.activeMembers.toLocaleString('sv-SE')}
            note="member_monthly subscription"
            variant="success"
          />
          <StatCard
            label="Live Location Sessions"
            value={mockStats.liveSessions}
            note="Currently active sessions"
          />
          <StatCard
            label="Open Reports"
            value={mockStats.openReports}
            note="Awaiting moderation review"
            variant={mockStats.openReports > 0 ? 'warning' : 'default'}
          />
          <StatCard
            label="Pending Partner Approvals"
            value={mockStats.pendingPartners}
            note="Awaiting admin review"
            variant={mockStats.pendingPartners > 0 ? 'warning' : 'default'}
          />
          <StatCard
            label="Pending Billboards"
            value={mockStats.pendingBillboards}
            note="Awaiting content review"
            variant={mockStats.pendingBillboards > 0 ? 'warning' : 'default'}
          />
        </div>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Planned admin behaviours</h2>
        <ul className={styles.behaviorList}>
          <li>View and manage active users and subscription status</li>
          <li>Monitor active live location session count (no exact coordinates)</li>
          <li>Review and action moderation reports</li>
          <li>Send warnings and suspend users</li>
          <li>Manage partner companies and approve partner offers</li>
          <li>Review and approve digital billboard content</li>
          <li>Manage Kronjakt campaigns</li>
          <li>View aggregated statistics</li>
          <li>View immutable audit log of all admin actions</li>
        </ul>
      </section>
    </div>
  );
}
