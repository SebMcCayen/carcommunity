import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function UsersPage() {
  return (
    <PlaceholderPage
      title="Users"
      description="View and manage all registered community members."
      behaviors={[
        'List all registered users with status (active, suspended, deleted)',
        'Filter by subscription status (member_monthly, free)',
        'Search by display name, email, or user ID',
        'View individual user profile and activity summary',
        'Suspend or delete a user account',
        'Send an admin warning to a user',
        'View subscription history',
        'Paginated list — never load all users at once',
      ]}
    />
  );
}
