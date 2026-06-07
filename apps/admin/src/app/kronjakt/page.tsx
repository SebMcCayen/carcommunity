import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function KronjaktPage() {
  return (
    <PlaceholderPage
      title="Kronjakt"
      description="Manage Kronjakt campaigns — the community treasure-hunt experience."
      behaviors={[
        'List active and scheduled Kronjakt campaigns',
        'Create and configure new campaigns: name, duration, locations, rewards',
        'Pause or end a running campaign',
        'View participation counts (aggregated)',
        'Kronpoäng has no cash value and cannot be bought, sold, or transferred',
        'Kronjakt must never encourage speeding, risky driving, or unsafe stops',
        'Feature flag: Kronjakt campaign types must be gated for safe rollout',
        'Collection is disabled while driving (app-enforced)',
      ]}
    />
  );
}
