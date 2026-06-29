import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function AnnouncementsPage() {
  return (
    <PlaceholderPage
      title="Announcements"
      description="Create and manage platform-wide announcements shown to users in the app."
      behaviors={[
        'List published and draft announcements in reverse chronological order',
        'Filter by status: draft, published, expired',
        'Create a new draft announcement — title, body (plain text), and optional expiry date',
        'Edit a draft announcement',
        'Publish an announcement (makes it visible to users)',
        'Expire or retract a published announcement',
        'Select audience: all users, members only, or admins only',
        'Announcements are immutable after publishing — create a new version instead',
        'All publish and retract actions produce an audit log entry',
        'Do not use announcements for marketing or commercial content (use notifications instead)',
        'Paginated — never load all announcements at once',
        'TODO: Connect UI to backend announcements endpoint',
        'TODO: Add publish and retract confirmation dialogs with required reason',
      ]}
    />
  );
}
