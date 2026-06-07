import { PlaceholderPage } from '@/components/ui/PlaceholderPage';
import { brand } from '@/config/brand';

export default function EventsPage() {
  return (
    <PlaceholderPage
      title="Events"
      description={`Manage official ${brand.shortName} events and community gatherings.`}
      behaviors={[
        'List upcoming and past events',
        `Distinguish official ${brand.shortName} events from community-created content`,
        'Create and publish official events',
        'Edit event details: title, time, location, status',
        'Cancel or archive events',
        'View RSVP / interest count (aggregated, not individual attendees)',
        'Feature flag: gate new event types behind a feature flag for safe rollout',
      ]}
    />
  );
}
