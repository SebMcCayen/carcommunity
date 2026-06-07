import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

interface UserDetailPageProps {
  params: Promise<{ id: string }>;
}

export default async function UserDetailPage({ params }: UserDetailPageProps) {
  const { id } = await params;

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
      todoNote="TODO: Replace route param with real user lookup from the backend API. Backend must verify admin role before serving user data. Never expose personal data beyond what is necessary."
    />
  );
}
