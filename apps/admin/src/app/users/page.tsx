import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function UsersPage() {
  return (
    <PlaceholderPage
      title="Users"
      description="Placeholder list for backend user foundation fields."
      behaviors={[
        'List user role (user, admin, owner)',
        'List user status (active, warned, temporarily_suspended, permanently_suspended, deleted)',
        'List subscription entitlement (none, member_monthly)',
        'Show last active timestamp when available',
        'Show moderation status derived from backend user status',
        'Search by display name, optional email, or user ID',
        'Paginated list — never load all users at once',
        'TODO: Send warning action (backend moderation endpoint required)',
        'TODO: Suspend user action (backend moderation endpoint required)',
        'TODO: Restore access action (backend moderation endpoint required)',
        'TODO: View audit log entries for selected user',
      ]}
    />
  );
}
