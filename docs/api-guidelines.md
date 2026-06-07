# API Guidelines (Node.js Backend)

These guidelines define how backend APIs must be designed for consistency, security, and product correctness.

## API principles

- Backend is the source of truth for all state and decisions.
- Never trust the client for admin rights, subscription status, access control, Kronjakt/Kronpoäng awarding, or live location visibility.
- Keep endpoint naming consistent, resource-oriented, and versioned.
- Validate every request body, path parameter, and query parameter server-side.
- Prefer explicit, predictable response and error formats across all endpoints.
- Do not introduce GraphQL unless explicitly requested.

## Versioning

- All public endpoints must be namespaced under `/v1`.
- Breaking changes require a new version namespace (for example `/v2`).
- Non-breaking additions can be introduced within the current version.

## Authentication

- All protected endpoints require authenticated user context.
- Authentication must be enforced server-side for every request, including realtime and push-related APIs.
- Never rely on client-declared identity or role.

## Authorization

- Authorization is enforced by API policy, not by client UI behavior.
- Blocking must be enforced by API, especially for live location visibility and user interaction.
- Suspended users must be blocked from community/member actions.
- Suspended users must still be allowed access to support, account deletion, policy/terms, and subscription management endpoints.

## Admin authorization

- Admin access is restricted to backend-verified admin principals.
- Dangerous admin actions must require:
  - explicit reason
  - explicit confirmation
- Admin list endpoints must minimize returned personal data and avoid unnecessary fields.

## Subscription entitlement checks

- Subscription-gated endpoints must verify entitlement `member_monthly` on the backend.
- Admin users bypass subscription gating checks only where appropriate.
- Admin bypass does **not** bypass security logging and audit requirements.

## Request validation

- Validate all request bodies using strict schemas.
- Reject unknown/invalid fields where possible.
- Validate all path/query inputs for type, range, enum, and format.
- Do not process unsafe input until validation passes.

## Response format

Use a consistent top-level shape:

```json
{
  "ok": true,
  "data": {},
  "meta": {}
}
```

Guidelines:

- `ok`: boolean success indicator.
- `data`: response payload object/array.
- `meta`: optional metadata (pagination, filters, request identifiers, etc.).

## Error format

Use a consistent error shape:

```json
{
  "ok": false,
  "error": {
    "code": "forbidden",
    "message": "You are not allowed to perform this action.",
    "details": {}
  }
}
```

Rules:

- Always return stable machine-readable `error.code`.
- Keep `error.message` safe and user-appropriate.
- Never expose stack traces to clients.
- Include `details` only when safe and useful.

## Pagination

- All list endpoints must support pagination.
- Use explicit pagination parameters (for example `page`, `pageSize`, cursor-based alternatives where needed).
- Return pagination metadata in `meta`.

Example:

```json
{
  "ok": true,
  "data": [],
  "meta": {
    "page": 1,
    "pageSize": 20,
    "total": 140,
    "hasNext": true
  }
}
```

## Filtering and sorting

- List endpoints should support explicit filtering and sorting query parameters.
- Only allow whitelisted filter/sort fields.
- Default sorting should be deterministic and documented.
- Reject invalid filter/sort values with a consistent validation error.

## Rate limiting

- Apply rate limiting per endpoint class and risk profile.
- Use stricter limits for authentication, write operations, and abuse-prone endpoints.
- Return clear limit-exceeded errors and retry hints when appropriate.

## Idempotency

- Non-idempotent operations that can be retried safely must support idempotency keys.
- Duplicate submissions with the same idempotency key must not create duplicate effects.
- Log idempotency collisions and suspicious repeated requests.

## Audit logging

- Sensitive actions must produce audit logs.
- Dangerous admin actions must include actor, target, action type, reason, confirmation state, and timestamp.
- Audit logs must be immutable and queryable for investigations.

## Privacy-safe logging

- Log only what is required for operations and security.
- Never log tokens, secrets, personal data, exact location traces, routes, or raw sensitive payloads.
- For error reporting, sanitize payloads server-side before storage or forwarding.

## Feature flags

- Feature flag evaluation is backend-authoritative for protected capabilities.
- Expose client-consumable flags through `/v1/feature-flags` only after server-side eligibility checks.
- Admin changes to flags must be audited.

## Realtime API

Live location endpoints:

- `POST /v1/live/sessions/start` (start session)
- `PATCH /v1/live/sessions/location` (update latest location)
- `POST /v1/live/sessions/stop` (stop session)
- `POST /v1/live/hide-now` (hide me now)
- `GET /v1/live/visible-users` (fetch visible live users, entitled users only)

Rules:

- Visibility must be enforced backend-side.
- Blocked users and suspended users must be enforced by API policy.
- Client-reported state must not bypass entitlement or safety controls.

## Push notification API

- Push registration and delivery preferences must be authenticated.
- Token/device registration updates must be validated and rate-limited.
- Push-triggering actions should be auditable when security-sensitive.

## External API proxy/caching guidelines

- External integrations (for example SMHI, Trafikverket, NOBIL, Mapbox-related backend calls) must be proxied by backend where relevant.
- Apply caching to reduce latency, external dependency load, and quota cost.
- Mobile clients must not hold secret API keys.
- Cache policy should balance freshness and operational reliability.

## GitHub issue integration rules

For backend error-reporting workflows that optionally create GitHub Issues:

- Always sanitize and deduplicate server-side before issue creation.
- Never include:
  - tokens
  - personal data
  - exact location
  - routes
  - raw logs
- Include minimal diagnostic context needed for triage.

## API endpoint groups

Recommended public groups:

- `/v1/auth`
- `/v1/me`
- `/v1/subscription`
- `/v1/live`
- `/v1/events`
- `/v1/chat`
- `/v1/groups`
- `/v1/vehicles`
- `/v1/badges`
- `/v1/points`
- `/v1/crown-hunt`
- `/v1/partners`
- `/v1/offers`
- `/v1/reports`
- `/v1/feedback`
- `/v1/notifications`
- `/v1/settings`
- `/v1/feature-flags`
- `/v1/admin`

Admin groups:

- `/v1/admin/dashboard`
- `/v1/admin/users`
- `/v1/admin/events`
- `/v1/admin/reports`
- `/v1/admin/partners`
- `/v1/admin/offers`
- `/v1/admin/billboards`
- `/v1/admin/crown-hunt`
- `/v1/admin/support`
- `/v1/admin/audit`
- `/v1/admin/statistics`
- `/v1/admin/feature-flags`

## Testing expectations

- Request validation tests for all write endpoints.
- Authorization and role-enforcement tests, including admin-only paths.
- Subscription entitlement tests for `member_monthly`-gated features.
- Blocking/suspension behavior tests, especially for live location and interaction paths.
- Pagination/filter/sort contract tests for all list endpoints.
- Error-shape consistency tests across representative failure scenarios.
- Audit-log coverage tests for sensitive and dangerous actions.
- Realtime live-location flow tests (start/update/stop/hide/visible users).
- Kronjakt claim tests ensuring backend validates geofence, speed, active session, cooldown, and risk score.
- Partner statistics tests verifying aggregated-only responses with threshold enforcement and no individual-user exposure.

## Endpoint naming examples

- `GET /v1/events`
- `GET /v1/events/{eventId}`
- `POST /v1/events/{eventId}/join`
- `POST /v1/reports`
- `GET /v1/admin/reports`
- `POST /v1/admin/users/{userId}/suspend`

## Additional domain rules

- Kronjakt claim endpoint must be backend-authoritative:
  - validate geofence
  - validate speed
  - require active session
  - enforce cooldown
  - evaluate risk score
  - app never awards points directly
- Kronjakt is the gameplay feature; Kronpoäng is the awarded points currency.
- Partner statistics endpoints must expose only aggregated data, enforce minimum thresholds, and never expose individual users.
