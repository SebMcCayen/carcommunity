import { PlaceholderPage } from '@/components/ui/PlaceholderPage';
import { brand } from '@/config/brand';

export default function EventsPage() {
  return (
    <PlaceholderPage
      title="Events"
      description={`Manage official ${brand.shortName} events and community gatherings.`}
      behaviors={[
        `List upcoming, cancelled, and completed events`,
        `Show event status: draft, published, cancelled, completed`,
        `Mark events as official ${brand.shortName} events`,
        'View aggregated RSVP counts: Kommer / Kanske / Kan inte (no individual attendee data)',
        'Filter by status, date range, or official flag',
        'Create and publish official events (future — requires reason and audit logging)',
        'Edit event details: title, time, location, status (future)',
        'Cancel events with required reason (future — requires audit logging)',
        'Feature flag: gate new event types behind a feature flag for safe rollout (future)',
      ]}
    />
  );
}

// TODO: Dangerous admin actions (create, edit, cancel, delete) require a mandatory reason
//   and must be recorded in the audit log before being implemented.
//   Do not add destructive actions until audit logging is wired in.
