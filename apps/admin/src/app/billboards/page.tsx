import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function BillboardsPage() {
  return (
    <PlaceholderPage
      title="Digital Billboards"
      description="Review and manage digital billboard placements shown in the app."
      behaviors={[
        'List all billboard campaigns with status (pending review, active, paused, rejected)',
        'Review submitted billboard content before approval',
        'Approve or reject billboard submissions',
        'Pause or disable a running billboard campaign',
        'Billboards must be labeled as Marknadsföring or Sponsrad placering in the app',
        'Billboards must not appear as popups or block app functions',
        'Billboards are calmed in driving mode (app-enforced, confirmed here)',
        'All billboard approvals and rejections logged to the audit log',
      ]}
    />
  );
}
