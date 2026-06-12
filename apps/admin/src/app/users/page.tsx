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
        'Send warning action — POST /v1/admin/users/:userId/warn (backend ready)',
        'Temporary suspension action — POST /v1/admin/users/:userId/suspend-temporary (backend ready)',
        'Permanent suspension action — POST /v1/admin/users/:userId/suspend-permanent (backend ready)',
        'Restore access action — POST /v1/admin/users/:userId/restore-access (backend ready)',
        'TODO: Connect UI to live backend moderation endpoints (never trust client-side admin flags)',
        'TODO: Reason input field required for all moderation actions',
        'TODO: Confirmation dialog required for dangerous actions (suspend-permanent) before production use',
        'TODO: View audit log entries for selected user',
      ]}
    />
  );
}
