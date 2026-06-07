import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

interface UserDetailPageProps {
  params: { id: string };
}

export default function UserDetailPage({ params }: UserDetailPageProps) {
  const { id } = params;
  return (
    <PlaceholderPage
      title={`User — ${id}`}
      description="Detailed view for a single user account."
      behaviors={[
        'Display user profile: display name, registration date, subscription status',
        'Show whether user has an active live location session (count only, never exact position)',
        'Show moderation history: reports filed against, warnings received',
        'Show Kronpoäng balance (display only — no cash value)',
        'Actions: send warning, suspend account, delete account',
        'Show audit log entries related to this user',
        'Never display exact live location or location history',
        'Never display partner tracking data or analytics per individual user',
      ]}
      todoNoteKey="placeholder.userDetailTodoNote"
    />
  );
}
