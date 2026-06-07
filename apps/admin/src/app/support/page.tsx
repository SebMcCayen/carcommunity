import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function SupportPage() {
  return (
    <PlaceholderPage
      title="Support Cases"
      description="View and manage user support requests and escalations."
      behaviors={[
        'List open and recent support cases',
        'Filter by status: open, in progress, resolved',
        'View case details and message thread',
        'Assign a case to an admin or support staff member',
        'Resolve or close a case',
        'Escalate to a moderation action if needed',
        'All support actions logged to the audit log',
        'Never expose personal data beyond what is necessary for case resolution',
      ]}
    />
  );
}
