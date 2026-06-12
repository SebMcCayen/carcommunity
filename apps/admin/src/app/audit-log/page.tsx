import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function AuditLogPage() {
  return (
    <PlaceholderPage
      title="Audit Log"
      description="Immutable record of all admin actions taken in this portal."
      behaviors={[
        'List all admin actions in reverse chronological order — GET /v1/admin/audit-log (backend ready)',
        'Paginated — never load the full audit log at once',
        'Filter by action type: user actions, moderation, partner, billboard, feature flags',
        'Filter by admin actor',
        'Show: action, actor user ID, timestamp, entity type, entity ID, reason',
        'Audit log entries are read-only and cannot be deleted or modified',
        'Sensitive metadata is not exposed in audit log list response',
        'All moderation actions (warn, suspend, restore) produce an audit log entry automatically',
        'All sensitive admin actions must produce an entry here before production use',
        'TODO: Connect UI to GET /v1/admin/audit-log endpoint',
        'TODO: Add filter controls for action type and actor',
      ]}
    />
  );
}
