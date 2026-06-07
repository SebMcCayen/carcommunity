import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function ReportsPage() {
  return (
    <PlaceholderPage
      title="Reports & Moderation"
      description="Review and action community reports and the moderation queue."
      behaviors={[
        'List open reports with reason, reporter, and reported content',
        'Filter by status: open, under review, resolved, dismissed',
        'Filter by reason: inappropriate content, harassment, spam, safety concern',
        'View reported content detail without exposing personal data',
        'Action: mark as under review',
        'Action: resolve report (remove content)',
        'Action: dismiss report',
        'Action: warn or suspend the reported user',
        'All moderation actions are logged to the audit log',
        'Paginated — never load the full report history at once',
      ]}
    />
  );
}
