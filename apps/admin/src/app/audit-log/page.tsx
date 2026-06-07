import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function AuditLogPage() {
  return (
    <PlaceholderPage
      title="Audit Log"
      description="Immutable record of all admin actions taken in this portal."
      behaviors={[
        'List all admin actions in reverse chronological order',
        'Filter by action type: user actions, moderation, partner, billboard, feature flags',
        'Filter by admin actor',
        'Show: action, actor, timestamp, affected resource ID',
        'Paginated — never load the full audit log at once',
        'Audit log entries are read-only and cannot be deleted or modified',
        'All sensitive admin actions must produce an entry here before production use',
      ]}
    />
  );
}
