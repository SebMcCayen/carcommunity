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
  // Garage aggregate stats — no private vehicle details, no registration data.
  totalVehicleProfiles: 0,
  usersWithVehicles: 0,
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
          {/* Garage aggregate stats — no private vehicle details, no registration data. */}
          {/* TODO: Connect to real API once admin auth and backend integration are in place. */}
          {/* TODO: Add moderation aggregate if public vehicle profiles are introduced later. */}
          <StatCard
            label="Vehicle Profiles"
            value={mockStats.totalVehicleProfiles.toLocaleString('sv-SE')}
            note="Total private vehicle profiles"
          />
          <StatCard
            label="Users with Vehicles"
            value={mockStats.usersWithVehicles.toLocaleString('sv-SE')}
            note="Users with at least one vehicle"
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
