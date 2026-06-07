import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function PartnerOffersPage() {
  return (
    <PlaceholderPage
      title="Partner Offers"
      description="Review and manage offers published by approved partner companies."
      behaviors={[
        'List all active and pending partner offers',
        'Filter by partner company and offer status',
        'Review offer content before it is visible to members',
        'Approve or reject a submitted offer',
        'Disable a live offer if needed',
        'Offers are visible only to member_monthly subscribers (backend-enforced)',
        'Partner offer content must be labeled as Partnererbjudande in the app',
        'All offer approvals and rejections logged to the audit log',
      ]}
    />
  );
}
