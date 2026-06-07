# Security, Privacy, and Operational Security Requirements

This document defines baseline security requirements for CarCommunity. It is a technical requirement set, not legal advice, and it does not claim automatic legal compliance.

## Security principles

- Default deny for sensitive access and actions.
- Least privilege for users, admins, systems, and integrations.
- Backend is the source of truth for security decisions.
- Privacy by design and data minimization.
- Defense in depth across mobile, backend, database, and operations.
- Secure-by-default configuration in all environments.
- Security-sensitive logic must be covered by tests.

## Security configuration baselines

- `LIVE_LOCATION_TTL_MINUTES_MAX`: 15
- `KRONJAKT_MIN_SPEED_KMH`: 10
- `KRONJAKT_MIN_STATIONARY_SECONDS`: 30
- `PARTNER_STATS_MIN_UNIQUE_USERS`: 10
- Implementations may use stricter values, but never weaker than these baselines.

## Open source security model

- Code is public; security must rely on architecture and controls, not obscurity.
- No secrets in repository history or source files.
- Threat model and controls must assume reverse engineering of client apps.
- Security-critical checks must be server-side and verifiable.

## Secret management

- No secrets in repo.
- Use GitHub Secrets and Azure Key Vault-backed secrets for CI/CD and runtime.
- Commit `.env.example` only.
- Ignore real `.env` files in Git.
- Never commit signing keys, Apple keys, Google credentials, Azure credentials, GitHub tokens, database connection strings, production data, or private certificates.

## GitHub security configuration

- Enable secret scanning, dependency alerts, and Dependabot updates.
- Restrict direct pushes to protected branches.
- Require pull requests for production-impacting changes.
- Enforce least-privilege access to repository settings and environments.
- Use GitHub environments for deployment approvals and secret scoping.

## Branch protection and CI/CD

- Protect default and release branches with required reviews and status checks.
- Require CI to pass before merge.
- Require signed/verified commits where feasible.
- Deployments to production must use trusted workflows and protected environments.
- CI/CD credentials must come from GitHub Secrets/Azure secrets, never hardcoded.

## Production-only risk controls

- Enforce stricter logging redaction and operational guardrails in production.
- Restrict production access to authorized maintainers only.
- Separate production credentials from non-production credentials.
- Use feature flags/kill switches for high-risk functionality.

## Authentication security

- Authentication tokens must be short-lived and securely stored.
- Protect login and account recovery with rate limiting and abuse controls.
- Mobile app must never contain backend secrets or GitHub tokens.
- Mobile app must never call GitHub APIs directly.

## Authorization security

- Backend is source of truth for access decisions.
- All privileged and cross-user actions must be authorized on backend.
- Authorization checks must be explicit and deny by default.
- Admin role must always be verified by backend.

## Subscription security

- Subscription entitlement must always be verified by backend.
- Client-side subscription state is advisory only.
- Admin suspension must block access regardless of subscription.
- Suspended users must still access support, subscription management, account deletion, privacy policy, and terms.

## Admin security

- Admin access requires backend role validation on every privileged action.
- Sensitive admin actions require audit logs.
- Apply rate limiting to admin actions where relevant.
- Use separation of duties where feasible for high-impact operations.

## Live location privacy and security

- Live location sharing is opt-in.
- Sharing must be manually started by user action.
- Sharing must be time-limited.
- Store/display latest location only with short TTL: treat location as stale and purge latest-location records/caches at or before `LIVE_LOCATION_TTL_MINUTES_MAX`.
- “Hide me now” must remove latest location immediately.
- No automatic location history collection.
- Free users may share their own live location.
- Only active members may see other users’ live locations.
- Blocked users must not see blocker’s live location.

## Saved drives privacy

- Saved drives are stored only after explicit user action.
- Users can delete saved drives at any time.
- No silent auto-save of drive history.

## Blocking and suspension rules

- Blocking must immediately prevent blocked user visibility and interaction as defined by product rules.
- Blocked users must not access blocker live-location visibility.
- Suspension must override subscription and normal feature access.
- Suspended users retain access only to support, subscription management, account deletion, privacy policy, and terms.

## Kronjakt anti-fraud

- Backend validates all claims.
- App never awards points directly.
- Enforce geofence validation.
- Require low speed/stationary condition for claims: speed must be less than or equal to `KRONJAKT_MIN_SPEED_KMH` for at least `KRONJAKT_MIN_STATIONARY_SECONDS` before claim acceptance; backend configuration may apply stricter limits.
- Require active live session for claim validity.
- Use short-lived location buffer for validation.
- Detect impossible jumps.
- Use Android mock-location detection on Android builds that support these signals; if unavailable, increase fraud risk score and require stronger backend checks.
- Use Play Integrity on supported Android builds; if unavailable, apply stricter risk scoring and backend-side claim throttling.
- Use Apple App Attest on supported iOS builds; if unavailable, apply stricter risk scoring and backend-side claim throttling.
- Enforce cooldowns and max limits.
- Maintain risk score and queue suspicious activity for admin review.

## Partner statistics privacy

- Partner statistics are opt-in only.
- Share aggregated data only.
- Enforce minimum threshold of `PARTNER_STATS_MIN_UNIQUE_USERS` unique users before sharing, applied per partner report slice (time window + metric + geographic segment); privacy risk assessment may increase this threshold.
- Do not share personal data with companies.
- Do not share exact location, routes, drive history, or individual timestamps.

## Digital billboard safety and advertising controls

- Digital billboards must be clearly marked as marketing or sponsored placement.
- Placement must not block primary UI actions.
- Placement must not distract users during driving mode.
- Safety UX rules override ad rendering choices.

## Error logging and GitHub Issues privacy

- Sanitize all error reports before sending to GitHub Issues.
- Never include personal data, tokens, exact location, route data, or raw logs in GitHub Issues.
- Redact identifiers and sensitive payload fields before external reporting.

## Logging rules

- Log only what is needed for security and operations.
- Avoid storing sensitive personal/location details unless strictly necessary.
- Apply structured logging with redaction filters.
- Add audit logs for admin actions.
- Define access controls and retention for logs.

## Rate limiting

- Use pagination for list endpoints.
- Add rate limiting for:
  - Login-sensitive operations
  - Chat
  - Bug reports
  - Kronjakt claims
  - Partner application forms
  - Admin actions where relevant

## Data retention principles

- Keep only the minimum data required for product and security operations.
- Prefer short retention windows for sensitive and high-risk data.
- Define retention by data category and purpose.
- Ensure deletion workflows are auditable and reliable.

## Account deletion

- Provide user-initiated account deletion.
- Deletion must remove or irreversibly anonymize user data according to system design.
- Suspension state must not block the ability to request account deletion.
- Preserve only minimal records needed for fraud/security integrity where required by system policy.

## Incident handling principles

- Maintain an incident response process with detection, triage, containment, recovery, and review.
- Prioritize user safety and privacy impact in incident severity.
- Rotate/revoke compromised secrets immediately.
- Record timelines, impact, and corrective actions for post-incident learning.

## Dependency management

- Keep dependencies updated through regular patching.
- Use automated dependency scanning and alerting.
- Review transitive dependencies for risk in critical paths.
- Pin versions where appropriate to reduce supply chain drift.

## Secure coding guidelines

- Validate all untrusted input server-side.
- Use parameterized queries and safe encoding/escaping.
- Enforce authentication and authorization checks on backend APIs.
- Avoid storing or logging secrets.
- Add tests for security-sensitive logic and access control decisions.

## Mobile app security

- Treat mobile app as untrusted client.
- Never embed backend secrets, GitHub tokens, or private keys.
- Never call GitHub APIs directly from app.
- Protect local storage for auth/session artifacts.
- Minimize sensitive data cached on device.

## Backend security

- Backend must enforce all access control, entitlement, and anti-fraud decisions.
- Apply strict input validation and output sanitization.
- Enforce rate limits and abuse detection controls.
- Use service-to-service secret management via secure secret stores.

## Database security

- Apply least privilege to database users/roles.
- Encrypt data at rest and in transit.
- Separate production and non-production datasets and credentials.
- Restrict direct production database access and audit administrative access.

## Admin web security

- Require strong authentication and backend-verified admin authorization.
- Protect admin endpoints with CSRF/session hardening where applicable.
- Log and audit all privileged state changes.
- Apply UI and API guardrails for destructive operations.

## Security checklist before release

- [ ] No secrets committed; `.env.example` is the only environment template committed.
- [ ] Real `.env` files are ignored and absent from commits.
- [ ] CI/CD uses GitHub Secrets and Azure secrets only.
- [ ] Branch protection and required checks are enabled.
- [ ] Security-sensitive logic has tests.
- [ ] Backend verifies admin role and subscription entitlement.
- [ ] Suspension overrides access correctly while preserving required suspended-user access.
- [ ] Live location rules (opt-in, manual start, time-limited, latest-only, short TTL, hide-now) are enforced.
- [ ] Blocking rules prevent visibility leaks.
- [ ] Saved drives require explicit save and support delete.
- [ ] Kronjakt anti-fraud validations are backend-enforced.
- [ ] Partner statistics sharing is opt-in, aggregated, thresholded, and non-personal.
- [ ] Digital billboards are safely labeled and non-distracting in driving mode.
- [ ] Error reporting to GitHub is sanitized.
- [ ] Pagination and required rate limiting are implemented.
- [ ] Admin actions produce audit logs.
