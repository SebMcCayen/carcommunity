import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

/**
 * Moderation reports — dedicated view for community reports requiring moderation
 * review (inappropriate content, harassment, spam, safety concerns).
 * The existing /reports page covers the combined queue; this page is scoped
 * to content moderation specifically.
 */
export default function ModerationReportsPage() {
  return (
    <PlaceholderPage
      title="Moderation Reports"
      description="Review community reports that require content moderation."
      behaviors={[
        'List open moderation reports with reason, reporter, and reported content type',
        'Filter by reason: inappropriate_content, harassment, spam, safety_concern',
        'Filter by status: open, under_review, resolved, dismissed',
        'Filter by content type: event, chat_message, user_profile',
        'View reported content summary without exposing personal data',
        'Assign reviewer (claim for review)',
        'Action: mark as under review',
        'Action: remove content and notify reporter',
        'Action: dismiss with reason',
        'Action: escalate to owner',
        'Action: warn or suspend the reported user',
        'All moderation actions produce an audit log entry automatically',
        'Paginated — never load the full moderation queue at once',
        'TODO: Connect UI to backend moderation reports endpoint',
        'TODO: Add inline user moderation actions with reason input',
      ]}
    />
  );
}
