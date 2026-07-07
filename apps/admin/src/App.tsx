import { Routes, Route } from 'react-router-dom';
import { AdminLayout } from '@/components/layout/AdminLayout';
import { ProtectedRoute } from '@/components/auth/ProtectedRoute';

// Public pages
import LoginPage from '@/app/login/page';
import UnauthorizedPage from '@/app/unauthorized/page';

// Protected pages
import DashboardPage from '@/app/page';
import UsersPage from '@/app/users/page';
import UserDetailPage from '@/app/users/[id]/page';
import EventsPage from '@/app/events/page';
import NewEventPage from '@/app/events/new/page';
import EventDetailPage from '@/app/events/[eventId]/page';
import NotificationsPage from '@/app/notifications/page';
import PartnersPage from '@/app/partners/page';
import PartnerApplicationsPage from '@/app/partners/applications/page';
import PartnerOffersPage from '@/app/partners/offers/page';
import PartnerInsightsPage from '@/app/partners/[partnerId]/insights/page';
import BillboardsPage from '@/app/billboards/page';
import KronjaktPage from '@/app/kronjakt/page';
import BadgesPage from '@/app/badges/page';
import ReportsPage from '@/app/reports/page';
import ErrorReportsPage from '@/app/error-reports/page';
import ModerationReportsPage from '@/app/moderation-reports/page';
import AnnouncementsPage from '@/app/announcements/page';
import AccountDeletionsPage from '@/app/account-deletions/page';
import EventChatPage from '@/app/event-chat/page';
import LiveLocationPage from '@/app/live-location/page';
import SupportPage from '@/app/support/page';
import AuditLogPage from '@/app/audit-log/page';
import FeatureFlagsPage from '@/app/feature-flags/page';
import SubscriptionPage from '@/app/subscription/page';
import SettingsPage from '@/app/settings/page';

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
        <Route path="/subscription" element={<SubscriptionPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>
    </Routes>
  );
}
