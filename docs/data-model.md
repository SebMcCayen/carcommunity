# Data model design (PostgreSQL) — LEGACY REFERENCE

> ⚠️ **Historical document — not the persistence architecture.**
>
> This document describes the PostgreSQL data model of the former `services/api` backend
> (Fastify / Prisma). That service was **removed from the repository on 2026-07-28** and never
> held production data; the schema and its migrations are recoverable from the `legacy-final`
> git tag. The document is kept only as a reference for where Firestore's shapes came from.
>
> **Do not add new Firebase schema decisions to this document.**
>
> The target persistence architecture is defined in:
>
> - [`docs/firebase-data-model.md`](firebase-data-model.md) — Cloud Firestore, Realtime Database, and Storage paths
> - [`docs/migration/backend-domain-mapping.md`](migration/backend-domain-mapping.md) — PostgreSQL → Firebase domain mapping
> - [`docs/adr/001-firebase-platform.md`](adr/001-firebase-platform.md) — authoritative platform decision

This document defines the PostgreSQL data model used by the former `services/api` backend of **carcommunity**.

It is a conceptual design document and intentionally does **not** contain SQL migrations or ORM-specific code.

## Data model goals

- Support a multi-organization platform from day one, even if MVP starts with one organization.
- Keep internal naming generic and avoid hardcoding brand names in schema/table names.
- Separate identity, roles, and subscription/entitlement concerns.
- Favor privacy-safe storage and explicit consent for sensitive/behavioral data.
- Preserve auditability for admin and points-related actions.
- Enable partner analytics through aggregate data only.

## Naming conventions

- Use lowercase snake_case table and field names.
- Prefer generic names (for example `organization`, `membership`, `entitlement`) over brand-specific names.
- Primary key convention: `id` (UUID).
- Foreign key convention: `<entity>_id` (for example `organization_id`, `user_id`).
- Timestamp convention: `created_at`, `updated_at`; optional `deleted_at` for soft deletion.
- Use explicit status enums/lookup tables where lifecycle state matters.

## Multi-organization / brand-ready model

MVP organization is **Kungsbacka Car Community**, but the model remains organization-agnostic.

### Table sketch: `organizations`

| Field        | Type (conceptual) | Notes                       |
| ------------ | ----------------- | --------------------------- |
| id           | uuid              | PK                          |
| slug         | text              | Unique org slug             |
| display_name | text              | Public org name             |
| legal_name   | text              | Optional legal entity name  |
| locale       | text              | Default locale              |
| timezone     | text              | Default timezone            |
| status       | enum              | active, suspended, archived |
| created_at   | timestamptz       |                             |
| updated_at   | timestamptz       |                             |

All organization-owned records carry `organization_id`.

## Users and identities

Do not use email as primary identity. Use provider subject identifiers from Apple/Google as stable identity anchors.

### Table sketch: `users`

| Field         | Type (conceptual) | Notes                                      |
| ------------- | ----------------- | ------------------------------------------ |
| id            | uuid              | PK                                         |
| public_handle | text              | App-visible username                       |
| display_name  | text              | Profile name                               |
| avatar_url    | text              | Optional                                   |
| email         | text              | Optional contact channel, not identity key |
| phone         | text              | Optional                                   |
| status        | enum              | active, blocked, deleted                   |
| created_at    | timestamptz       |                                            |
| updated_at    | timestamptz       |                                            |

### Table sketch: `user_identities`

| Field            | Type (conceptual) | Notes                                        |
| ---------------- | ----------------- | -------------------------------------------- |
| id               | uuid              | PK                                           |
| user_id          | uuid              | FK users.id                                  |
| provider         | enum              | apple, google, other                         |
| provider_subject | text              | Stable provider subject; unique per provider |
| provider_email   | text              | Optional mirrored claim                      |
| last_login_at    | timestamptz       |                                              |
| created_at       | timestamptz       |                                              |

## Organization membership and roles

Roles are separate from subscriptions.

### Table sketch: `organization_memberships`

| Field           | Type (conceptual) | Notes                                                                          |
| --------------- | ----------------- | ------------------------------------------------------------------------------ |
| id              | uuid              | PK                                                                             |
| organization_id | uuid              | FK organizations.id                                                            |
| user_id         | uuid              | FK users.id                                                                    |
| role            | enum              | owner, admin, user; future: moderator, event_manager, partner_manager, support |
| status          | enum              | invited, active, removed                                                       |
| joined_at       | timestamptz       |                                                                                |
| created_at      | timestamptz       |                                                                                |
| updated_at      | timestamptz       |                                                                                |

Constraint idea: one active membership per (`organization_id`, `user_id`).

## Subscriptions and entitlements

Subscription/payment state is separate from access grants. Admin users can operate without subscription.

### Table sketch: `subscriptions`

| Field                    | Type (conceptual) | Notes                                         |
| ------------------------ | ----------------- | --------------------------------------------- |
| id                       | uuid              | PK                                            |
| organization_id          | uuid              | FK organizations.id                           |
| user_id                  | uuid              | FK users.id                                   |
| provider                 | enum              | app_store, play_store, other                  |
| external_subscription_id | text              | Provider reference                            |
| status                   | enum              | trialing, active, past_due, canceled, expired |
| current_period_start     | timestamptz       |                                               |
| current_period_end       | timestamptz       |                                               |
| created_at               | timestamptz       |                                               |
| updated_at               | timestamptz       |                                               |

### Table sketch: `entitlements`

| Field           | Type (conceptual) | Notes                               |
| --------------- | ----------------- | ----------------------------------- |
| id              | uuid              | PK                                  |
| organization_id | uuid              | FK organizations.id                 |
| key             | text              | Internal key, e.g. `member_monthly` |
| display_name    | text              | e.g. `KCC Medlem Månad`             |
| created_at      | timestamptz       |                                     |

### Table sketch: `user_entitlements`

| Field           | Type (conceptual) | Notes                               |
| --------------- | ----------------- | ----------------------------------- |
| id              | uuid              | PK                                  |
| organization_id | uuid              | FK organizations.id                 |
| user_id         | uuid              | FK users.id                         |
| entitlement_id  | uuid              | FK entitlements.id                  |
| source_type     | enum              | subscription, admin_grant, campaign |
| starts_at       | timestamptz       |                                     |
| ends_at         | timestamptz       | Nullable for open-ended             |
| status          | enum              | active, revoked, expired            |
| created_at      | timestamptz       |                                     |

## Live location sessions

Model active sessions explicitly and keep them short-lived.

### Table sketch: `live_location_sessions`

| Field           | Type (conceptual) | Notes                  |
| --------------- | ----------------- | ---------------------- |
| id              | uuid              | PK                     |
| organization_id | uuid              | FK organizations.id    |
| user_id         | uuid              | FK users.id            |
| started_at      | timestamptz       |                        |
| expires_at      | timestamptz       | Short TTL              |
| ended_at        | timestamptz       | Null when active       |
| status          | enum              | active, ended, expired |
| created_at      | timestamptz       |                        |

## Latest live locations

Store current/latest position separately. Do not keep automatic permanent history.

### Table sketch: `latest_live_locations`

| Field                    | Type (conceptual) | Notes                           |
| ------------------------ | ----------------- | ------------------------------- |
| live_location_session_id | uuid              | PK/FK live_location_sessions.id |
| organization_id          | uuid              | FK organizations.id             |
| user_id                  | uuid              | FK users.id                     |
| latitude                 | numeric           |                                 |
| longitude                | numeric           |                                 |
| accuracy_meters          | numeric           | Optional                        |
| heading_degrees          | numeric           | Optional                        |
| speed_mps                | numeric           | Optional                        |
| captured_at              | timestamptz       | Device capture time             |
| updated_at               | timestamptz       | Server update time              |

Retention: purge rows shortly after session end/expiry.

## Blocking

### Table sketch: `user_blocks`

| Field           | Type (conceptual) | Notes               |
| --------------- | ----------------- | ------------------- |
| id              | uuid              | PK                  |
| organization_id | uuid              | FK organizations.id |
| blocker_user_id | uuid              | FK users.id         |
| blocked_user_id | uuid              | FK users.id         |
| reason          | text              | Optional            |
| created_at      | timestamptz       |                     |

## Admin suspensions

### Table sketch: `admin_suspensions`

| Field                      | Type (conceptual) | Notes                   |
| -------------------------- | ----------------- | ----------------------- |
| id                         | uuid              | PK                      |
| organization_id            | uuid              | FK organizations.id     |
| user_id                    | uuid              | FK users.id             |
| suspended_by_admin_user_id | uuid              | FK users.id             |
| reason                     | text              | Required                |
| starts_at                  | timestamptz       |                         |
| ends_at                    | timestamptz       | Nullable for indefinite |
| status                     | enum              | active, lifted, expired |
| created_at                 | timestamptz       |                         |

## Events

### Table sketch: `events`

| Field              | Type (conceptual) | Notes                                 |
| ------------------ | ----------------- | ------------------------------------- |
| id                 | uuid              | PK                                    |
| organization_id    | uuid              | FK organizations.id                   |
| title              | text              |                                       |
| description        | text              |                                       |
| start_at           | timestamptz       |                                       |
| end_at             | timestamptz       |                                       |
| venue_name         | text              |                                       |
| latitude           | numeric           | Optional                              |
| longitude          | numeric           | Optional                              |
| visibility         | enum              | public, members_only, private         |
| created_by_user_id | uuid              | FK users.id                           |
| status             | enum              | draft, published, canceled, completed |
| created_at         | timestamptz       |                                       |
| updated_at         | timestamptz       |                                       |

## RSVP / attendance

### Table sketch: `event_rsvps`

| Field           | Type (conceptual) | Notes                        |
| --------------- | ----------------- | ---------------------------- |
| id              | uuid              | PK                           |
| organization_id | uuid              | FK organizations.id          |
| event_id        | uuid              | FK events.id                 |
| user_id         | uuid              | FK users.id                  |
| response        | enum              | going, maybe, declined       |
| responded_at    | timestamptz       |                              |
| checked_in_at   | timestamptz       | Optional attendance check-in |

## Event chat

### Table sketch: `event_chat_messages`

| Field           | Type (conceptual) | Notes                       |
| --------------- | ----------------- | --------------------------- |
| id              | uuid              | PK                          |
| organization_id | uuid              | FK organizations.id         |
| event_id        | uuid              | FK events.id                |
| sender_user_id  | uuid              | FK users.id                 |
| message_text    | text              | Sanitized/moderated content |
| status          | enum              | visible, removed            |
| created_at      | timestamptz       |                             |
| edited_at       | timestamptz       | Optional                    |

## Group driving

### Table sketch: `group_drives`

| Field             | Type (conceptual) | Notes                                |
| ----------------- | ----------------- | ------------------------------------ |
| id                | uuid              | PK                                   |
| organization_id   | uuid              | FK organizations.id                  |
| title             | text              |                                      |
| organizer_user_id | uuid              | FK users.id                          |
| starts_at         | timestamptz       |                                      |
| status            | enum              | planned, active, completed, canceled |
| created_at        | timestamptz       |                                      |

### Table sketch: `group_drive_participants`

| Field           | Type (conceptual) | Notes                  |
| --------------- | ----------------- | ---------------------- |
| id              | uuid              | PK                     |
| organization_id | uuid              | FK organizations.id    |
| group_drive_id  | uuid              | FK group_drives.id     |
| user_id         | uuid              | FK users.id            |
| role            | enum              | organizer, participant |
| joined_at       | timestamptz       |                        |

## Saved drives

Saved only after explicit user action.

### Table sketch: `saved_drives`

| Field             | Type (conceptual) | Notes                               |
| ----------------- | ----------------- | ----------------------------------- |
| id                | uuid              | PK                                  |
| organization_id   | uuid              | FK organizations.id                 |
| user_id           | uuid              | FK users.id                         |
| title             | text              | User-supplied                       |
| started_at        | timestamptz       |                                     |
| ended_at          | timestamptz       |                                     |
| distance_km       | numeric           | Summary metric                      |
| duration_seconds  | integer           | Summary metric                      |
| average_speed_kph | numeric           | Summary metric                      |
| route_summary     | jsonb             | Privacy-safe generalized route data |
| created_at        | timestamptz       |                                     |

Route detail policy: keep detail minimal, deletable, and scoped to user intent.

## Vehicles / garage

### Table sketch: `vehicles`

| Field           | Type (conceptual) | Notes               |
| --------------- | ----------------- | ------------------- |
| id              | uuid              | PK                  |
| organization_id | uuid              | FK organizations.id |
| owner_user_id   | uuid              | FK users.id         |
| make            | text              |                     |
| model           | text              |                     |
| model_year      | integer           | Optional            |
| nickname        | text              | Optional            |
| image_url       | text              | Optional            |
| created_at      | timestamptz       |                     |
| updated_at      | timestamptz       |                     |

## Badges

### Table sketch: `badges`

| Field           | Type (conceptual) | Notes                    |
| --------------- | ----------------- | ------------------------ |
| id              | uuid              | PK                       |
| organization_id | uuid              | FK organizations.id      |
| key             | text              | Unique badge key per org |
| display_name    | text              |                          |
| description     | text              |                          |
| created_at      | timestamptz       |                          |

### Table sketch: `user_badges`

| Field           | Type (conceptual) | Notes                   |
| --------------- | ----------------- | ----------------------- |
| id              | uuid              | PK                      |
| organization_id | uuid              | FK organizations.id     |
| user_id         | uuid              | FK users.id             |
| badge_id        | uuid              | FK badges.id            |
| awarded_at      | timestamptz       |                         |
| source          | enum              | system, admin, campaign |

## Kronpoäng ledger

Use a ledger pattern; never only a mutable balance.

### Table sketch: `points_ledgers`

| Field           | Type (conceptual) | Notes               |
| --------------- | ----------------- | ------------------- |
| id              | uuid              | PK                  |
| organization_id | uuid              | FK organizations.id |
| user_id         | uuid              | FK users.id         |
| currency_key    | text              | e.g. `kronpoang`    |
| created_at      | timestamptz       |                     |

### Table sketch: `points_ledger_entries`

| Field                    | Type (conceptual) | Notes                                                      |
| ------------------------ | ----------------- | ---------------------------------------------------------- |
| id                       | uuid              | PK                                                         |
| organization_id          | uuid              | FK organizations.id                                        |
| ledger_id                | uuid              | FK points_ledgers.id                                       |
| user_id                  | uuid              | FK users.id (denormalized for query speed)                 |
| direction                | enum              | credit, debit                                              |
| amount                   | integer           | Positive integer                                           |
| reason_type              | enum              | event_attendance, campaign_claim, admin_adjustment, expiry |
| reference_type           | text              | Related entity type                                        |
| reference_id             | uuid              | Related entity id                                          |
| created_by_admin_user_id | uuid              | Nullable                                                   |
| created_at               | timestamptz       |                                                            |

Business rules:

- No cash value.
- Not transferable between users.
- Balance is computed from ledger entries.

## Kronjakt campaigns, points, and claims

### Table sketch: `kronjakt_campaigns`

| Field           | Type (conceptual) | Notes                        |
| --------------- | ----------------- | ---------------------------- |
| id              | uuid              | PK                           |
| organization_id | uuid              | FK organizations.id          |
| name            | text              |                              |
| description     | text              |                              |
| starts_at       | timestamptz       |                              |
| ends_at         | timestamptz       |                              |
| status          | enum              | draft, active, paused, ended |
| created_at      | timestamptz       |                              |

### Table sketch: `kronjakt_points`

| Field            | Type (conceptual) | Notes                    |
| ---------------- | ----------------- | ------------------------ |
| id               | uuid              | PK                       |
| organization_id  | uuid              | FK organizations.id      |
| campaign_id      | uuid              | FK kronjakt_campaigns.id |
| geofence         | jsonb             | Geofence definition      |
| reward_points    | integer           |                          |
| anti_fraud_rules | jsonb             | Lightweight ruleset      |
| status           | enum              | active, inactive         |
| created_at       | timestamptz       |                          |

### Table sketch: `kronjakt_claims`

| Field                     | Type (conceptual) | Notes                               |
| ------------------------- | ----------------- | ----------------------------------- |
| id                        | uuid              | PK                                  |
| organization_id           | uuid              | FK organizations.id                 |
| campaign_id               | uuid              | FK kronjakt_campaigns.id            |
| kronjakt_point_id         | uuid              | FK kronjakt_points.id               |
| user_id                   | uuid              | FK users.id                         |
| claimed_at                | timestamptz       |                                     |
| status                    | enum              | pending, approved, rejected         |
| risk_score                | numeric           | Fraud risk indicator                |
| anti_fraud_signals        | jsonb             | Device/time/location sanity signals |
| reviewed_by_admin_user_id | uuid              | Nullable                            |
| review_reason             | text              | Optional                            |

## Partner companies

### Table sketch: `partner_companies`

| Field           | Type (conceptual) | Notes               |
| --------------- | ----------------- | ------------------- |
| id              | uuid              | PK                  |
| organization_id | uuid              | FK organizations.id |
| name            | text              |                     |
| slug            | text              | Unique per org      |
| category        | text              |                     |
| status          | enum              | active, inactive    |
| created_at      | timestamptz       |                     |

## Partner applications

### Table sketch: `partner_applications`

| Field                     | Type (conceptual) | Notes                                    |
| ------------------------- | ----------------- | ---------------------------------------- |
| id                        | uuid              | PK                                       |
| organization_id           | uuid              | FK organizations.id                      |
| company_name              | text              | Submitted company                        |
| contact_name              | text              |                                          |
| contact_email             | text              |                                          |
| message                   | text              |                                          |
| status                    | enum              | submitted, in_review, approved, rejected |
| reviewed_by_admin_user_id | uuid              | Nullable                                 |
| reviewed_at               | timestamptz       | Nullable                                 |
| created_at                | timestamptz       |                                          |

## Partner offers

### Table sketch: `partner_offers`

| Field              | Type (conceptual) | Notes                          |
| ------------------ | ----------------- | ------------------------------ |
| id                 | uuid              | PK                             |
| organization_id    | uuid              | FK organizations.id            |
| partner_company_id | uuid              | FK partner_companies.id        |
| title              | text              |                                |
| description        | text              |                                |
| starts_at          | timestamptz       |                                |
| ends_at            | timestamptz       |                                |
| status             | enum              | draft, active, paused, expired |
| created_at         | timestamptz       |                                |
| updated_at         | timestamptz       |                                |

## Digital billboards

### Table sketch: `digital_billboards`

| Field              | Type (conceptual) | Notes                            |
| ------------------ | ----------------- | -------------------------------- |
| id                 | uuid              | PK                               |
| organization_id    | uuid              | FK organizations.id              |
| sponsor_company_id | uuid              | FK partner_companies.id          |
| placement_key      | text              | Placement/surface identifier     |
| marketing_label    | text              | Human-readable campaign label    |
| campaign_starts_at | timestamptz       |                                  |
| campaign_ends_at   | timestamptz       |                                  |
| status             | enum              | scheduled, active, paused, ended |
| created_at         | timestamptz       |                                  |

### Table sketch: `digital_billboard_daily_metrics`

| Field                | Type (conceptual) | Notes                    |
| -------------------- | ----------------- | ------------------------ |
| id                   | uuid              | PK                       |
| organization_id      | uuid              | FK organizations.id      |
| digital_billboard_id | uuid              | FK digital_billboards.id |
| day                  | date              | Aggregation day          |
| impressions          | integer           | Aggregate                |
| interactions         | integer           | Aggregate                |
| unique_viewer_count  | integer           | Aggregate                |
| created_at           | timestamptz       |                          |

## Partner insights and aggregated statistics

No company-facing per-user data. Use aggregate reporting tables with threshold checks.

### Table sketch: `partner_daily_stats`

| Field                   | Type (conceptual) | Notes                         |
| ----------------------- | ----------------- | ----------------------------- |
| id                      | uuid              | PK                            |
| organization_id         | uuid              | FK organizations.id           |
| partner_company_id      | uuid              | FK partner_companies.id       |
| day                     | date              |                               |
| metric_key              | text              | e.g. offer_views, redemptions |
| metric_value            | numeric           | Aggregate value               |
| contributing_user_count | integer           | For minimum-threshold gating  |
| created_at              | timestamptz       |                               |

Display rule: hide metrics below configured minimum contributor threshold.

## Partner passing statistics opt-in

Store opt-in on user privacy settings and aggregate by partner + period.

### Table sketch: `user_privacy_settings`

| Field                       | Type (conceptual) | Notes                |
| --------------------------- | ----------------- | -------------------- |
| user_id                     | uuid              | PK/FK users.id       |
| organization_id             | uuid              | FK organizations.id  |
| allow_partner_passing_stats | boolean           | Explicit opt-in flag |
| updated_at                  | timestamptz       |                      |

### Table sketch: `partner_passing_period_stats`

| Field              | Type (conceptual) | Notes                   |
| ------------------ | ----------------- | ----------------------- |
| id                 | uuid              | PK                      |
| organization_id    | uuid              | FK organizations.id     |
| partner_company_id | uuid              | FK partner_companies.id |
| period_start       | date              |                         |
| period_end         | date              |                         |
| passing_count      | integer           | Aggregate only          |
| unique_user_count  | integer           | Aggregate only          |
| created_at         | timestamptz       |                         |

No individual passing records are exposed to partner-facing surfaces.

## Reports and moderation

### Table sketch: `reports`

| Field            | Type (conceptual) | Notes                              |
| ---------------- | ----------------- | ---------------------------------- |
| id               | uuid              | PK                                 |
| organization_id  | uuid              | FK organizations.id                |
| reporter_user_id | uuid              | FK users.id                        |
| target_type      | text              | e.g. user, event, message          |
| target_id        | uuid              | Target entity id                   |
| category         | text              | Abuse/spam/safety etc.             |
| details          | text              | Report narrative                   |
| status           | enum              | open, triaged, resolved, dismissed |
| created_at       | timestamptz       |                                    |
| updated_at       | timestamptz       |                                    |

### Table sketch: `report_admin_actions`

| Field           | Type (conceptual) | Notes                          |
| --------------- | ----------------- | ------------------------------ |
| id              | uuid              | PK                             |
| organization_id | uuid              | FK organizations.id            |
| report_id       | uuid              | FK reports.id                  |
| admin_user_id   | uuid              | FK users.id                    |
| action          | text              | e.g. warn_user, remove_content |
| reason          | text              |                                |
| created_at      | timestamptz       |                                |

## Admin messages

### Table sketch: `admin_messages`

| Field           | Type (conceptual) | Notes                         |
| --------------- | ----------------- | ----------------------------- |
| id              | uuid              | PK                            |
| organization_id | uuid              | FK organizations.id           |
| admin_user_id   | uuid              | FK users.id                   |
| target_scope    | enum              | user, role_group, all_members |
| target_user_id  | uuid              | Nullable                      |
| title           | text              |                               |
| body            | text              |                               |
| status          | enum              | draft, sent, archived         |
| sent_at         | timestamptz       | Nullable                      |
| created_at      | timestamptz       |                               |

## Support cases

### Table sketch: `support_cases`

| Field                  | Type (conceptual) | Notes                                             |
| ---------------------- | ----------------- | ------------------------------------------------- |
| id                     | uuid              | PK                                                |
| organization_id        | uuid              | FK organizations.id                               |
| user_id                | uuid              | FK users.id                                       |
| subject                | text              |                                                   |
| description            | text              |                                                   |
| status                 | enum              | open, in_progress, waiting_user, resolved, closed |
| priority               | enum              | low, medium, high                                 |
| assigned_admin_user_id | uuid              | Nullable                                          |
| created_at             | timestamptz       |                                                   |
| updated_at             | timestamptz       |                                                   |

## Error reports

Store sanitized diagnostics only.

### Table sketch: `error_reports`

| Field             | Type (conceptual) | Notes                 |
| ----------------- | ----------------- | --------------------- |
| id                | uuid              | PK                    |
| organization_id   | uuid              | FK organizations.id   |
| user_id           | uuid              | Nullable FK users.id  |
| platform          | text              | ios, android, backend |
| app_version       | text              |                       |
| environment       | text              | prod, staging         |
| error_fingerprint | text              | Grouping hash         |
| sanitized_payload | jsonb             | No secrets/PII        |
| occurred_at       | timestamptz       |                       |
| created_at        | timestamptz       |                       |

## GitHub issue mapping

### Table sketch: `github_issue_mappings`

| Field                   | Type (conceptual) | Notes                           |
| ----------------------- | ----------------- | ------------------------------- |
| id                      | uuid              | PK                              |
| organization_id         | uuid              | FK organizations.id             |
| source_type             | text              | e.g. error_report, support_case |
| source_id               | uuid              | Related internal record         |
| github_issue_id         | bigint            | Numeric GitHub issue id         |
| github_issue_url        | text              | Canonical issue URL             |
| mapped_by_admin_user_id | uuid              | Nullable                        |
| mapped_at               | timestamptz       |                                 |

## Feature flags

### Table sketch: `feature_flags`

| Field           | Type (conceptual) | Notes               |
| --------------- | ----------------- | ------------------- |
| id              | uuid              | PK                  |
| organization_id | uuid              | FK organizations.id |
| key             | text              | Flag key            |
| description     | text              |                     |
| enabled         | boolean           | Global org default  |
| rollout_rules   | jsonb             | Optional targeting  |
| created_at      | timestamptz       |                     |
| updated_at      | timestamptz       |                     |

## Notification preferences

### Table sketch: `notification_preferences`

| Field           | Type (conceptual) | Notes                                 |
| --------------- | ----------------- | ------------------------------------- |
| id              | uuid              | PK                                    |
| organization_id | uuid              | FK organizations.id                   |
| user_id         | uuid              | FK users.id                           |
| channel         | enum              | push, email, in_app                   |
| topic           | text              | event_updates, offers, admin_messages |
| enabled         | boolean           |                                       |
| updated_at      | timestamptz       |                                       |

## Push tokens

### Table sketch: `push_tokens`

| Field           | Type (conceptual) | Notes               |
| --------------- | ----------------- | ------------------- |
| id              | uuid              | PK                  |
| organization_id | uuid              | FK organizations.id |
| user_id         | uuid              | FK users.id         |
| platform        | enum              | ios, android        |
| token           | text              | Device push token   |
| status          | enum              | active, invalidated |
| last_seen_at    | timestamptz       |                     |
| created_at      | timestamptz       |                     |

## Audit log

Use append-only audit events for sensitive admin actions.

### Table sketch: `audit_log_entries`

| Field               | Type (conceptual) | Notes                    |
| ------------------- | ----------------- | ------------------------ |
| id                  | uuid              | PK                       |
| organization_id     | uuid              | FK organizations.id      |
| actor_admin_user_id | uuid              | FK users.id              |
| target_entity_type  | text              | Entity type              |
| target_entity_id    | uuid              | Entity id                |
| action              | text              | Action key               |
| reason              | text              | Optional but recommended |
| metadata            | jsonb             | Sanitized context        |
| occurred_at         | timestamptz       | Event time               |
| created_at          | timestamptz       | Ingest time              |

Pattern: append-only; no in-place edits/deletes except legally required redaction.

## Versioning and releases metadata

### Table sketch: `app_releases`

| Field                 | Type (conceptual) | Notes                  |
| --------------------- | ----------------- | ---------------------- |
| id                    | uuid              | PK                     |
| organization_id       | uuid              | FK organizations.id    |
| platform              | enum              | ios, android, backend  |
| version_name          | text              | Human-readable version |
| version_code          | text              | Build number/code      |
| released_at           | timestamptz       |                        |
| min_supported_version | text              | Optional               |
| status                | enum              | active, deprecated     |
| created_at            | timestamptz       |                        |

## Retention and deletion behavior

- **Account deletion:** delete or anonymize personal data across profile, identities, push tokens, saved drives, and message content where required.
- Preserve minimal non-personal records needed for legal/compliance (for example aggregate financial-like ledgers and redacted audit entries).
- Apply TTL cleanup for live location session artifacts and transient telemetry.
- Keep aggregate analytics only when no direct personal re-identification is possible.
- Maintain deletion/anonymization job metadata for traceability.

## Indexing considerations

- Add indexes on all foreign keys, especially `organization_id` and `user_id`.
- Use composite indexes for common access paths, e.g.:
  - (`organization_id`, `status`)
  - (`organization_id`, `user_id`)
  - (`organization_id`, `created_at`)
- Enforce uniqueness where needed:
  - `user_identities(provider, provider_subject)`
  - `organization_memberships(organization_id, user_id)` for active membership
  - `partner_companies(organization_id, slug)`
- For time-series/aggregate tables, index period columns (`day`, `period_start`, `period_end`).
- For moderation/reporting, index (`target_type`, `target_id`) and (`organization_id`, `status`).
- Consider partial indexes for active records (active sessions, active tokens, open reports).

---

This model is intentionally modular and organization-first, enabling the MVP organization (Kungsbacka Car Community) while keeping schemas generic for additional brands and organizations.
