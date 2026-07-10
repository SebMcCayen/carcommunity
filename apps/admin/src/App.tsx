import { lazy } from 'react';
import { Routes, Route } from 'react-router-dom';
import { AdminLayout } from '@/components/layout/AdminLayout';
import { ProtectedRoute } from '@/components/auth/ProtectedRoute';

// Public pages — kept in the entry chunk so the login screen renders without
// an extra network round-trip. The entry graph only needs firebase app/auth/
// app-check; everything heavier loads with the protected routes below.
import LoginPage from '@/app/login/page';
import UnauthorizedPage from '@/app/unauthorized/page';

// Protected pages — lazy-loaded (route-level code splitting) so page code and
// its heavy dependencies (Firestore, feature modules, i18n dictionaries) stay
// out of the initial bundle. The Suspense boundary lives in AdminLayout so the
// sidebar stays visible while a page chunk is fetched.
const DashboardPage = lazy(() => import('@/app/page'));
const UsersPage = lazy(() => import('@/app/users/page'));
const UserDetailPage = lazy(() => import('@/app/users/[id]/page'));
const EventsPage = lazy(() => import('@/app/events/page'));
const NewEventPage = lazy(() => import('@/app/events/new/page'));
const EventDetailPage = lazy(() => import('@/app/events/[eventId]/page'));
const NotificationsPage = lazy(() => import('@/app/notifications/page'));
const PartnersPage = lazy(() => import('@/app/partners/page'));
const PartnerApplicationsPage = lazy(() => import('@/app/partners/applications/page'));
const PartnerOffersPage = lazy(() => import('@/app/partners/offers/page'));
const PartnerInsightsPage = lazy(() => import('@/app/partners/[partnerId]/insights/page'));
const BillboardsPage = lazy(() => import('@/app/billboards/page'));
const KronjaktPage = lazy(() => import('@/app/kronjakt/page'));
const BadgesPage = lazy(() => import('@/app/badges/page'));
const ReportsPage = lazy(() => import('@/app/reports/page'));
const ErrorReportsPage = lazy(() => import('@/app/error-reports/page'));
const ModerationReportsPage = lazy(() => import('@/app/moderation-reports/page'));
const AnnouncementsPage = lazy(() => import('@/app/announcements/page'));
const AccountDeletionsPage = lazy(() => import('@/app/account-deletions/page'));
const EventChatPage = lazy(() => import('@/app/event-chat/page'));
const LiveLocationPage = lazy(() => import('@/app/live-location/page'));
const SupportPage = lazy(() => import('@/app/support/page'));
const AuditLogPage = lazy(() => import('@/app/audit-log/page'));
const FeatureFlagsPage = lazy(() => import('@/app/feature-flags/page'));
const CredentialsPage = lazy(() => import('@/app/credentials/page'));
const SubscriptionPage = lazy(() => import('@/app/subscription/page'));
const SettingsPage = lazy(() => import('@/app/settings/page'));

export function App() {
  return (
    <Routes>
      {/* Public routes */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/unauthorized" element={<UnauthorizedPage />} />

      {/* Protected routes — require Firebase auth + admin claim */}
      <Route
        element={
          <ProtectedRoute>
            <AdminLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="/users/:id" element={<UserDetailPage />} />
        <Route path="/events" element={<EventsPage />} />
        <Route path="/events/new" element={<NewEventPage />} />
        <Route path="/events/:eventId" element={<EventDetailPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/partners" element={<PartnersPage />} />
        <Route path="/partners/applications" element={<PartnerApplicationsPage />} />
        <Route path="/partners/offers" element={<PartnerOffersPage />} />
        <Route path="/partners/:partnerId/insights" element={<PartnerInsightsPage />} />
        <Route path="/billboards" element={<BillboardsPage />} />
        <Route path="/kronjakt" element={<KronjaktPage />} />
        <Route path="/badges" element={<BadgesPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/error-reports" element={<ErrorReportsPage />} />
        <Route path="/moderation-reports" element={<ModerationReportsPage />} />
        <Route path="/announcements" element={<AnnouncementsPage />} />
        <Route path="/account-deletions" element={<AccountDeletionsPage />} />
        <Route path="/event-chat" element={<EventChatPage />} />
        <Route path="/live-location" element={<LiveLocationPage />} />
        <Route path="/support" element={<SupportPage />} />
        <Route path="/audit-log" element={<AuditLogPage />} />
        <Route path="/feature-flags" element={<FeatureFlagsPage />} />
        <Route path="/credentials" element={<CredentialsPage />} />
        <Route path="/subscription" element={<SubscriptionPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>
    </Routes>
  );
}
