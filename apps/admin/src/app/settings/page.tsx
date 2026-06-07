import { PlaceholderPage } from '@/components/ui/PlaceholderPage';

export default function SettingsPage() {
  return (
    <PlaceholderPage
      title="Settings & Feature Flags"
      description="Configure feature flags and operational settings for the platform."
      behaviors={[
        'View and toggle feature flags for platform features',
        'Feature flags are stored and enforced by the backend — not client-side',
        'Sensitive feature flags (live location, Kronjakt, partner analytics) require careful rollout',
        'Admin role required for feature flag changes (backend-enforced)',
        'All feature flag changes logged to the audit log',
        'Configure platform-level thresholds (e.g. live session time limits)',
        'Configure admin notification preferences',
      ]}
      todoNoteKey="placeholder.settingsTodoNote"
    />
  );
}
