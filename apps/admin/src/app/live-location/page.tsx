import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function LiveLocationPage() {
  return (
    <PlaceholderPage
      title="Live Location Admin"
      description="Operational view of active live location sessions. No exact coordinates are ever shown here."
      behaviors={[
        'Show count of currently active live location sessions',
        'Show session duration (time elapsed since start — never exact coordinates)',
        'Identify sessions running unusually long',
        'Admin force-stop a session if a safety concern is confirmed',
        'Never display exact user coordinates or route history',
        'Never store or log exact location data via the admin panel',
        'All admin actions on live sessions are logged to the audit log',
        'Reminder: live location is opt-in, time-limited, and stoppable by the user at any time',
      ]}
    />
  );
}
