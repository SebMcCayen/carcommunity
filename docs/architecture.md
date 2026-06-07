# Architecture

## Overview

carcommunity is planned as a monorepo with a mobile client, an admin web client, and a backend API as the system of truth. The architecture is designed for a production-only MVP hosted on Azure, with strong control over privacy, subscription entitlement, moderation, and operational safety.

```text
apps/mobile (Expo React Native) ─┐
apps/admin  (React web)          ├──> services/api (Node.js + TypeScript) ───> PostgreSQL
external providers/APIs          ┘                 │
                                                    ├── Realtime WebSocket
                                                    ├── Error ingestion + GitHub issue bridge
                                                    └── Feature flags + operational controls
```

## Architecture goals

- Keep one source of truth in backend services for security-critical and business-critical state.
- Ship MVP safely in production-only Azure without separate dev/staging environments.
- Minimize privacy risk by default, especially for location and partner analytics.
- Support modular growth for social, partner, and event capabilities.
- Maintain high performance on mobile-first user journeys.

## Monorepo structure

Planned structure:

```text
.
├── apps/
│   ├── mobile/         # React Native / Expo app
│   └── admin/          # React-based admin web app (for example Next.js or similar)
├── services/
│   └── api/            # Node.js (latest LTS) + TypeScript backend
├── docs/
└── .github/
```

## Application boundaries

- **apps/mobile**: end-user UX, map rendering, client-side purchase initiation, realtime consumption.
- **apps/admin**: moderation, partner management, billboard approval, operational dashboards.
- **services/api**: authentication verification, subscription verification, entitlements, authorization, business rules, realtime coordination, persistence, integrations.
- **PostgreSQL**: durable storage for users, entitlements, moderation state, saved drives, partner aggregates, and operational metadata.

## Mobile app architecture

- Built with **React Native / Expo**.
- Uses Mapbox for mapping in app flows.
- Calls backend APIs for all trusted operations and data requiring secrets/caching.
- Handles Apple/Google purchase flows client-side, then submits receipts/tokens to backend verification.
- Receives feature flags and remote config to safely gate MVP features.

## Admin web architecture

- Built as a modern React-based web app (for example Next.js or similar).
- Authenticates against backend and uses backend-issued authorization context.
- Focus areas:
  - user moderation (blocking/suspension),
  - partner and billboard administration,
  - partner statistics (aggregated only),
  - operational controls and feature rollout.

## Backend API architecture

- Node.js latest LTS with TypeScript.
- Layered services:
  - API layer (HTTP + WebSocket endpoints),
  - domain services (auth, entitlement, moderation, social features),
  - integration adapters (Apple/Google verification, external data sources),
  - data access layer for PostgreSQL.
- Backend is source of truth for:
  - auth identity binding,
  - admin role,
  - subscription entitlement,
  - live location visibility,
  - blocking/suspension,
  - Kronpoäng,
  - partner statistics,
  - admin actions.

## Database architecture

- **PostgreSQL** as primary datastore.
- Core domains:
  - identity and auth subject mapping,
  - subscription entitlements (`member_monthly`),
  - moderation and safety state,
  - social/group/event entities,
  - saved drives,
  - partner entities and aggregate metrics,
  - operational tables (feature flags snapshot references, idempotency, audit logs).
- Live location is stored as ephemeral latest-state records with short TTL behavior, not long-term history.

## Authentication architecture

- iOS: Apple login.
- Android: Google login.
- Backend verifies provider identity tokens.
- Stable provider subject (`sub`) is canonical identity key; email is not identity.
- Backend issues/maintains session/auth context for app and admin access.

```text
Client login (Apple/Google)
    -> provider token
    -> backend verifies with provider
    -> backend binds stable provider subject
    -> backend session/token returned
```

## Subscription architecture

- Purchases are initiated on client via platform-native Apple/Google purchase systems.
- Backend verifies receipt/token with provider.
- Backend stores entitlement as internal `member_monthly`.
- Entitlement checks happen server-side for protected features.

## Authorization and entitlement checks

- Central authorization middleware/policies in backend.
- Effective access is computed from:
  - authenticated identity,
  - role (admin/user),
  - moderation state (blocked/suspended),
  - entitlement state (`member_monthly`),
  - feature flag state.
- Clients never decide final access; they only render based on backend responses.

## Live location architecture

- Location sharing is session-based and explicit.
- Store **latest location only** during active sharing.
- Short TTL expiration for active location record.
- No automatic historical location timeline.
- “Hide me now” immediately removes latest location and closes sharing session.

## Realtime architecture

- MVP realtime is backend-managed **WebSocket**.
- WebSocket channels support live location visibility, chat updates, and group-driving state updates.
- Backend enforces auth and entitlement per channel/event.
- Fan-out is coordinated server-side to keep privacy controls centralized.

## Event chat architecture

- Event chat runs through backend realtime + persisted messages in PostgreSQL.
- Membership and moderation checks happen before send/read.
- Chat feature is gateable by feature flag.
- Message processing supports sanitization and abuse controls.

## Group driving architecture

- Group creation/join/leave and drive-state transitions are backend-authoritative.
- Realtime updates publish participant state changes.
- Live location visibility is constrained to authorized participants.
- Safety controls (block/suspend/hide) override group visibility immediately.

## Saved drives architecture

- Saved drives are created only by explicit user action.
- Saved drives are stored separately from live location sessions.
- No implicit conversion of live location stream into history.

## Kronpoäng and Kronjakt architecture

- Kronpoäng calculation and balance are backend-owned.
- Kronjakt logic and progression are backend-owned and flag-gated.
- Client receives computed state; it does not author rewards or score truth.

## Partner and billboard architecture

- Partners are managed via admin workflows in backend.
- Digital billboards are sponsored map placements.
- All billboard placements require admin approval.
- Billboard content must be clearly marked as marketing.

## Partner insights and aggregation architecture

- Partner insights are aggregate-only analytics.
- No individual user tracking is exposed to companies.
- Aggregation logic runs server-side with privacy-preserving outputs.

## Error logging and GitHub issue integration

- Mobile/admin clients send error events to backend.
- Backend sanitizes sensitive fields and deduplicates recurring issues.
- Backend may create GitHub Issues from qualified error clusters.
- Mobile app never calls GitHub directly.

```text
App error -> API ingest -> sanitize/dedupe -> threshold/rules -> optional GitHub Issue
```

## Feature flags and remote config

Feature flags are required for:

- live location,
- chat,
- Kronjakt,
- partner statistics,
- push notifications,
- social sharing,
- external data sources.

Flags/config are backend-controlled and consumed by clients for safe rollout and kill switches.

## External data source integration

- External APIs are integrated through backend whenever secrets or caching are needed.
- Backend adapter layer normalizes and validates third-party data before exposure.
- Client direct calls are limited to non-sensitive/public integrations only when no secret/caching concern exists.

## Caching strategy

- Cache at backend boundaries for expensive or rate-limited external requests.
- Cache derived aggregate views for admin/partner insights where safe.
- Keep auth, entitlement, and moderation checks backed by authoritative server state with strict invalidation.
- Use short-lived caching for realtime-adjacent reads to balance freshness and load.

## Production-only Azure hosting model

- MVP runs in **production-only Azure** (no separate dev/staging Azure environments).
- Risk mitigation relies on:
  - CI/CD quality gates,
  - branch protection,
  - feature flags/kill switches,
  - automated backups,
  - safe, backward-compatible migrations.

## Performance-first architecture

- Mobile-first latency optimization for map, feed, and realtime interactions.
- API design prioritizes coarse-grained, low-roundtrip endpoints.
- Use targeted indexing and query shaping in PostgreSQL.
- Apply pagination, bounded payloads, and transport compression where appropriate.

## Observability and operational controls

- Centralized backend logging, error ingestion, and audit trails for admin actions.
- Realtime health metrics (connections, fan-out, lag, disconnect rates).
- Operational controls via flags and admin tools to degrade gracefully during incidents.
- Alerting focuses on auth failures, entitlement verification failures, moderation anomalies, and integration outages.

## Security boundaries

- Backend-only handling of secrets and provider verification.
- Stable provider subject identity mapping protects against email-based account drift.
- Strict server-side authorization for all protected reads/writes and realtime channels.
- Data minimization for location and partner insights.
- Sanitization and deduplication in error pipelines before downstream issue creation.

## Future scalability

- Monorepo boundaries support independent scaling of mobile, admin, and API concerns.
- Realtime architecture can evolve from single-node WebSocket handling to distributed pub/sub-backed fan-out as load grows.
- Domain modules (chat, group driving, rewards, partner analytics) can be isolated into separate services when required.
- Feature flagging enables incremental rollout of new capabilities without destabilizing core MVP flows.
