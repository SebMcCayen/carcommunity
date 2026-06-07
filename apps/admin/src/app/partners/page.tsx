import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function PartnersPage() {
  return (
    <PlaceholderPage
      title="Partners"
      description="Manage partner companies and their integration with the community."
      behaviors={[
        'List all partner companies with status (pending, active, suspended)',
        'Review and approve new partner applications',
        'Suspend or remove a partner',
        "View a partner's active offer list",
        'View aggregated partner statistics (opt-in and privacy-safe only)',
        'Partners must never receive personal data, live location, or individual user tracking',
        'Partner statistics: aggregated counts only — never individual user data',
        'All partner approvals and suspensions logged to the audit log',
      ]}
    />
  );
}
