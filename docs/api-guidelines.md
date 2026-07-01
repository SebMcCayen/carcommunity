# API Guidelines

These guidelines define how backend APIs must be designed for consistency, security, and product correctness.

> **Migration in progress.** The target backend interaction model is Firebase-native (Callable Cloud Functions, direct Firebase SDK access, and HTTP Cloud Functions for external integrations). The legacy `/v1` REST conventions that apply only to the frozen `services/api` implementation are documented in the [Legacy `/v1` REST conventions (frozen)](#legacy-v1-rest-conventions-frozen) section at the bottom of this document.

## API principles

- Backend is the source of truth for all state and decisions.
- Never trust the client for admin rights, subscription status, access control, Kronjakt/Kronpoäng awarding, or live location visibility.
- Validate all inputs server-side.
- Prefer explicit, predictable response and error formats.
- Do not introduce GraphQL unless explicitly requested.

---

## Target Firebase interaction model

### Callable Cloud Functions

Use callable functions for:

- Authenticated mobile operations (iOS and Android).
- Authenticated admin operations.
- Security-sensitive mutations.
- Operations requiring server-side validation.
- Subscription verification.
- Moderation actions.
- Kronpoäng awarding and Kronjakt claims.
- Business rules that cannot safely run directly in clients.

Callable functions must:

- Use the Firebase SDK authentication context — clients do not attach a manual `Authorization` header.
- Verify that `context.auth` (or equivalent Gen 2 `CallableRequest.auth`) is present before processing any request.
- Verify App Check on every callable function in production.
- Return stable machine-readable error codes using `HttpsError` status codes (for example `unauthenticated`, `permission-denied`, `invalid-argument`, `not-found`, `internal`).
- Use `FieldValue.serverTimestamp()` — never trust client-supplied timestamps.
- Be idempotent where possible; use Firestore transactions for multi-document operations.
- Log only privacy-safe diagnostics — never log tokens, credentials, or user-identifying sensitive data.

#### Callable function naming

Callable function names follow a `domain.action` or `domainAction` convention.
Canonical names are defined in the language-neutral contracts (`contracts/functions/` — planned).

Examples:

- `auth.completeOnboarding`
- `live.startSession`
- `live.stopSession`
- `live.hideMeNow`
- `subscription.verify`
- `admin.suspendUser`
- `admin.setFeatureFlag`

#### Firebase Authentication context

Every callable function receives the authenticated Firebase UID via `context.auth.uid`.

- Never trust a UID supplied in the request body or URL for authorization.
- The `admin: true` Firebase custom claim gates admin-only callables.
- The `activeMember: true` custom claim gates subscription-gated callables (verified by the subscription callable, not the client).
- Clients must refresh their ID-token claims (force-refresh) after a privileged claim change.

#### App Check context

Every callable function and HTTP function must enforce App Check in production.

- Use the Firebase Admin SDK `AppCheck.verifyToken()` or the Gen 2 built-in enforcement.
- Disable App Check enforcement in Firebase console during emulator development; use the debug App Check provider.

### Direct Firebase SDK access

Allow direct Firestore or Realtime Database access from native clients only where all of the following hold:

- Firebase Security Rules can fully enforce access (read and write).
- The operation is simple and safe (for example reading the authenticated user's own profile).
- The read pattern is bounded (no unbounded collection scans).
- The data model is intentionally designed for client access.
- Privacy-sensitive fields are separated into restricted subcollections or documents (for example `userPrivate/{uid}`).

Do not allow direct client access to documents that contain moderation state, admin flags, subscription entitlement decisions, exact partner analytics, or other sensitive backend-managed fields.

#### Firestore pagination

Use cursor-based pagination for all client-visible list reads:

```
startAfter(lastDocumentSnapshot).limit(pageSize)
```

Do not use offset-based pagination. Offsets are expensive and inconsistent in Firestore.

#### Realtime Database listeners

Bound all Realtime Database listeners to specific paths or shallow queries. Do not allow clients to listen to root-level or unscoped paths.

### HTTP Cloud Functions

Use HTTP functions only for:

- External provider webhooks (for example App Store server notifications, Google Play developer notifications).
- Third-party integrations that require a specific HTTP contract.

HTTP functions must:

- Verify request authenticity (webhook signature or equivalent) before processing.
- Not be used as a general-purpose mobile API endpoint — use callable functions instead.
- Use `Authorization: ****** ID token>` when the function requires an authenticated user context that cannot use the callable SDK.

---

## Stable error codes

Use these stable machine-readable codes in callable function errors:

| Code                  | Meaning                          |
| --------------------- | -------------------------------- |
| `unauthenticated`     | No valid Firebase session        |
| `permission-denied`   | Authenticated but not authorized |
| `invalid-argument`    | Input validation failed          |
| `not-found`           | Resource does not exist          |
| `already-exists`      | Conflict with existing resource  |
| `resource-exhausted`  | Rate limit or quota exceeded     |
| `failed-precondition` | State precondition not met       |
| `internal`            | Unexpected server error          |
| `unavailable`         | Temporary unavailability         |
| `feature-disabled`    | Feature flag is off              |

## Idempotency

Non-idempotent operations that can be retried safely should use Firestore transactions or conditional writes to prevent duplicate effects.

## Audit logging

Sensitive actions must produce audit logs in Firestore (for example `auditLog/` collection).

Dangerous admin actions must include: actor UID, target UID, action type, reason, confirmation state, and server timestamp.

Audit logs must be written by Cloud Functions (not clients) and must be immutable from client writes.

## Privacy-safe logging

- Log only what is required for operations and security.
- Never log tokens, credentials, personal data, exact location traces, routes, or raw sensitive payloads.
- Sanitize payloads server-side before forwarding to error-tracking services.

## Input validation

All callable function inputs must be validated server-side before processing.

Reject unknown/invalid fields. Validate all types, ranges, enums, and formats. Do not process unsafe input until validation passes.

## Testing expectations

- Input validation tests for all callable functions.
- Authorization and role-enforcement tests, including admin-only callables.
- Subscription entitlement tests for `member_monthly`-gated callables.
- Blocking/suspension enforcement tests.
- Firestore Security Rules tests (emulator) for every collection.
- Realtime Database Security Rules tests for live location paths.
- Callable function integration tests (emulator) for every business-rule path.
- Kronjakt claim tests: geofence, speed, active session, cooldown, risk score.
- Partner statistics tests: aggregated-only, threshold enforcement, no individual-user exposure.

---

## Legacy `/v1` REST conventions (frozen)

> ⚠️ **This section applies only to the frozen legacy `services/api` implementation (Node.js / Fastify / Prisma / PostgreSQL).** These conventions do not apply to the target Firebase backend. Do not add new endpoints here.

The legacy `services/api` uses REST endpoints under `/v1`. The conventions below remain in effect only for bug fixes and migration-compatibility work in that frozen directory.

### Versioning

- All legacy endpoints are namespaced under `/v1`.
- Breaking changes would require `/v2` — but new versions must not be added; target new features in Firebase callable functions.

### Authentication (legacy)

- Protected legacy endpoints require `Authorization: ****** ID token>`.
- The legacy backend verifies the token with Firebase Admin SDK `verifyIdToken()`.

### Response format (legacy)

```json
{
  "ok": true,
  "data": {},
  "meta": {}
}
```

### Error format (legacy)

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

### Pagination (legacy)

Page-based: `page`, `pageSize` query parameters with `meta.total`, `meta.hasNext`.

### Legacy endpoint groups

- `/v1/auth`, `/v1/me`, `/v1/subscription`, `/v1/live`, `/v1/events`, `/v1/chat`
- `/v1/groups`, `/v1/vehicles`, `/v1/badges`, `/v1/points`, `/v1/crown-hunt`
- `/v1/partners`, `/v1/offers`, `/v1/reports`, `/v1/feedback`
- `/v1/notifications`, `/v1/settings`, `/v1/feature-flags`
- `/v1/admin/*`

These endpoint groups exist as migration reference only. New product features must not be added to them.
