import { LIVE_LOCATION_TTL_MINUTES_MAX } from '@carcommunity/shared/live-location';
import { getFeatureFlagDefault } from '@/features/feature-flags';
import { StatCard } from '@/components/ui/StatCard';
import styles from '../page.module.css';

const placeholderSummary = {
  activeSessionCount: 0,
  expiredSessionCount: 0,
  operationalStatus: 'Safe default placeholder',
  featureFlagStatus: getFeatureFlagDefault('liveLocation')
    ? 'Static default: enabled'
    : 'Static default: disabled',
  supportActionStatus: 'Not enabled',
};

export default function LiveLocationPage() {
  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Live Location Admin</h1>
        <p className={styles.subtitle}>
          Moderation and operations placeholder only. No exact coordinates or route history are shown.
        </p>
      </div>

      <div className={styles.authWarning} role="alert">
        <span aria-hidden="true">⚠</span>
        <span>
          <strong>Operations-only placeholder.</strong> Admin live location is for moderation
          and operations only. Backend-verified admin or owner authorization, audit logging, and
          feature flag checks are required before production use.
        </span>
      </div>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Operational summary</h2>
        <div className={styles.statsGrid}>
          <StatCard
            label="Active Sessions"
            value={placeholderSummary.activeSessionCount}
            note="Placeholder count until backend admin data is ready"
          />
          <StatCard
            label="Expired Sessions"
            value={placeholderSummary.expiredSessionCount}
            note="Prepared for TTL and expiry monitoring"
          />
          <StatCard
            label="Operational Status"
            value={placeholderSummary.operationalStatus}
            note="Privacy-preserving safe default"
            variant="success"
          />
          <StatCard
            label="Feature Flag"
            value={placeholderSummary.featureFlagStatus}
            note="Based on the shared static liveLocation flag"
          />
          <StatCard
            label="Support Actions"
            value={placeholderSummary.supportActionStatus}
            note="Hide/stop actions stay disabled in this placeholder"
            variant="warning"
          />
          <StatCard
            label="Latest Position TTL"
            value={`${LIVE_LOCATION_TTL_MINUTES_MAX} min max`}
            note="Prepared for short-lived latest-position cleanup"
          />
        </div>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Blocking relationships</h2>
        {/*
          TODO: Individual block relationships are privacy-sensitive and must never
          be exposed in admin lists or used for surveillance. Only aggregate
          operational counts (e.g. total active block relationships) may be surfaced
          here if genuinely useful for operational monitoring. Removing individual
          user blocks via the admin panel is not permitted in this step.
        */}
        {/* `#666` measured 3.48:1 against the dark page background — below AA.
            `--text-secondary` is theme-aware and measures 8.42:1 there. */}
        <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>
          Block relationship counts are not shown in this placeholder.
          Individual blocking decisions are user-private and must not be exposed to admin views.
        </p>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Prepared admin behaviours</h2>
        <ul className={styles.behaviorList}>
          <li>Show active live location session count without exposing exact positions</li>
          <li>Track expired sessions and TTL cleanup readiness for the latest-position record</li>
          <li>Prepare hide/stop support action placeholders for confirmed moderation incidents</li>
          <li>Surface feature flag status and operational health for rollout control</li>
          <li>Never display precise user tracking, raw coordinates, or public route history</li>
          <li>Keep future admin actions audited and backend-authorized before any production use</li>
        </ul>
      </section>
    </div>
  );
}
