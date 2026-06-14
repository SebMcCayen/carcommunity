/**
 * Admin diagnostics feature module.
 *
 * Provides helpers and hooks for the admin diagnostics view.
 *
 * The diagnostics view shows:
 * - Recent reports (severity, platform, feature area, app version, build, date, fingerprint).
 * - Metadata is intentionally excluded from the list view.
 *
 * TODO: Add deduplication — group reports by fingerprint and show occurrence count.
 * TODO: Add grouping UI — collapsed view per fingerprint with expandable details.
 * TODO: Add GitHub Issue creation — surface "Create Issue" action per fingerprint group.
 *       (Requires a GitHub token managed server-side; never add tokens to this client.)
 * TODO: Add severity-based alerting — highlight critical/error clusters.
 * TODO: Add privacy review — confirm that metadata shown to admins excludes personal data.
 */

export type {
  AdminDiagnosticsEntry,
  AdminDiagnosticsListResponse,
  DiagnosticsFeatureArea,
  DiagnosticsPlatform,
  DiagnosticsSeverity,
} from '@carcommunity/shared/diagnostics';

export { DIAGNOSTICS_ROUTE_PATHS } from '@carcommunity/shared/diagnostics';
