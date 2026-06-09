import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

interface UserDetailPageProps {
  params: Promise<{ id: string }>;
}

export default async function UserDetailPage({ params }: UserDetailPageProps) {
  const { id } = await params;
  return (
    <PlaceholderPage
      title={`User — ${id}`}
      description="Placeholder detail view for user, moderation, and entitlement foundation."
      behaviors={[
        'Display user role, status, subscription entitlement, and last active timestamp',
        'Display moderation status summary from backend status and moderation actions',
        'Display minimal account timeline fields (createdAt, updatedAt)',
        'Do not expose sensitive personal data in admin placeholders',
        'TODO: Send warning action',
        'TODO: Suspend user action',
        'TODO: Restore access action',
        'TODO: View user audit log history',
      ]}
      todoNoteKey="placeholder.userDetailTodoNote"
    />
  );
}
