/**
 * Dashboard overview stats for the admin portal (Phase 13 vertical).
 *
 * Live counts via Firestore server-side aggregation (getCountFromServer) over
 * admin-readable collections — no documents leave the server, only counts.
 * Stats that can't be counted yet return `null` and the page renders "—":
 *  - liveSessions: live sessions live in Realtime Database, not Firestore.
 *  - pendingPartners / pendingBillboards: `partnerApplications` and non-active
 *    `billboards` are not admin-readable under the current firestore.rules.
 *  - usersWithVehicles: a distinct-user aggregate Firestore can't count cheaply.
 *
 * Each count is independent: if one fails (e.g. a rules denial or a missing
 * field on a fresh project) it yields `null` for that tile only and never
 * breaks the page.
 */
import {
  collection,
  getCountFromServer,
  query,
  where,
  type Query,
} from 'firebase/firestore';
import { getAdminFirestore } from '../../lib/firestore';

export interface DashboardStats {
  totalUsers: number | null;
  activeMembers: number | null;
  openReports: number | null;
  vehicleProfiles: number | null;
  liveSessions: number | null;
  pendingPartners: number | null;
  pendingBillboards: number | null;
  usersWithVehicles: number | null;
}

/** Runs a count query; returns null on any failure so one tile can't break the page. */
async function safeCount(build: () => Query): Promise<number | null> {
  try {
    const snapshot = await getCountFromServer(build());
    return snapshot.data().count;
  } catch {
    return null;
  }
}

/**
 * Loads the dashboard overview counts. Countable tiles come from live Firestore
 * aggregation; the rest are null until their data source / rules exist.
 */
export async function loadDashboardStats(): Promise<DashboardStats> {
  const db = getAdminFirestore();

  const [totalUsers, activeMembers, openReports, vehicleProfiles] = await Promise.all([
    safeCount(() => collection(db, 'users')),
    safeCount(() => query(collection(db, 'users'), where('activeMember', '==', true))),
    safeCount(() => query(collection(db, 'moderationReports'), where('status', '==', 'pending'))),
    safeCount(() => collection(db, 'vehicles')),
  ]);

  return {
    totalUsers,
    activeMembers,
    openReports,
    vehicleProfiles,
    // Not countable from Firestore yet — see module docs.
    liveSessions: null,
    pendingPartners: null,
    pendingBillboards: null,
    usersWithVehicles: null,
  };
}
