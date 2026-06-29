import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function AccountDeletionsPage() {
  return (
    <PlaceholderPage
      title="Account Deletion Requests"
      description="Review and process user requests to delete their accounts and associated data."
      behaviors={[
        'List pending account deletion requests with anonymised user ID and request timestamp',
        'Show request status: pending, processing, completed, cancelled',
        'Initiate data deletion — triggers backend purge of user data per retention policy',
        'Confirm deletion completed — marks request as resolved',
        'Cancel a deletion request if the user retracts it (within cancellation window)',
        'All deletion actions produce an immutable audit log entry',
        'Do not expose personal data in the deletion request list',
        'Deletion must remove: profile, drive history, live location data, saved offers, blocks, reports',
        'Data retained for legal purposes must follow the retention policy and never be shown here',
        'Paginated — never load all deletion requests at once',
        'TODO: Connect UI to backend account deletion endpoint',
        'TODO: Add confirmation dialog requiring explicit admin acknowledgement before processing',
        'TODO: Implement cancellation window logic aligned with privacy policy',
      ]}
    />
  );
}
