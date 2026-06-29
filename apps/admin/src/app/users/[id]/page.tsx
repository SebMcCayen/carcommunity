import { useParams } from 'react-router-dom';
import { PlaceholderPage } from '@/components/ui/PlaceholderPage';
import { UserPointsSection } from './PointsSection';

export default function UserDetailPage() {
  const { id = '' } = useParams<{ id: string }>();

  return (
    <>
      <UserPointsSection userId={id} />
      <PlaceholderPage
        title={`User — ${id}`}
        description="Placeholder detail view for user, moderation, and entitlement foundation."
        behaviors={[
          'Display user role, status, subscription entitlement, and last active timestamp',
          'Display moderation status summary from backend status and moderation actions',
          'Display minimal account timeline fields (createdAt, updatedAt)',
          'Display onboarding completed status (onboardingCompletedAt) — boolean indicator only',
          'Display anonymous partner statistics opt-in as a boolean (true/false) — read-only, do not allow admin to change this value',
          'Do not expose sensitive personal data in admin placeholders',
          'Send warning — POST /v1/admin/users/:userId/warn (backend ready, reason required)',
          'Temporary suspension — POST /v1/admin/users/:userId/suspend-temporary (backend ready, reason + expiresAt required)',
          'Permanent suspension — POST /v1/admin/users/:userId/suspend-permanent (backend ready, reason required)',
          'Restore access — POST /v1/admin/users/:userId/restore-access (backend ready, reason required)',
          'View audit log history — GET /v1/admin/audit-log (backend ready)',
          'TODO: Connect UI to live moderation endpoints (do not trust client-side admin flags)',
          'TODO: Reason input field required for all moderation actions before production use',
          // IMPORTANT: Dangerous actions (permanent suspension) MUST require explicit confirmation
          // dialog and audit log entry before any production deployment. Never skip this step.
          'TODO: Add explicit confirmation dialog for permanent suspension before production use',
          'TODO: Protect owner accounts — normal admins must not be able to moderate owner users in UI',
        ]}
        todoNoteKey="placeholder.userDetailTodoNote"
      />
    </>
  );
}
