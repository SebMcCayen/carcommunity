# Firebase Cost Controls

This document describes the budget alert configuration and architecture rules for the production Firebase project.

## Budget alerts

The Firebase project (`kungsbacka-car-community`) must have four billing budget alerts configured in Google Cloud Billing.

| Threshold | Amount | Purpose |
|-----------|--------|---------|
| Alert 1 | SEK 100 | Early warning — investigate unexpected usage |
| Alert 2 | SEK 250 | Elevated usage — review active features and query patterns |
| Alert 3 | SEK 400 | High usage — consider temporarily disabling non-critical features |
| Alert 4 | SEK 500 | Maximum monthly budget — immediate action required |

### How to configure

1. Open [Google Cloud Console → Billing → Budgets & alerts](https://console.cloud.google.com/billing).
2. Select the billing account linked to the Firebase project.
3. Create a budget scoped to the `kungsbacka-car-community` project.
4. Add threshold rules at SEK 100, 250, 400, and 500 with email notifications to the project owner.

### ⚠️ Budget alerts do not stop spending

Budget alerts are **notifications only**. Google Cloud and Firebase do **not** automatically pause or disable services when a budget threshold is reached. Exceeding a budget alert does not prevent further charges.

To stop charges when the budget is exceeded, you must:

- Manually disable Cloud Functions or other billable services in the Firebase or Google Cloud console.
- Or implement a Cloud Function triggered by a Pub/Sub budget notification to disable services programmatically (advanced — not configured in MVP).

Monitor the [Firebase Usage and Billing dashboard](https://console.firebase.google.com/project/kungsbacka-car-community/usage) regularly.

---

## Architecture cost rules

The following rules apply to all Firebase and Google Cloud services in this project. These rules exist to prevent unexpected spend at the target scale of 20–30 active users.

### Cloud Functions

- `minInstances` must be `0` on all functions. Warm instances incur continuous compute charges regardless of traffic. Do not set `minInstances > 0` without documented justification and an approved budget increase.
- `maxInstances` must be explicitly limited on every function. Use a low ceiling appropriate to expected traffic (for example `maxInstances: 10`). Uncapped functions can scale unexpectedly during abuse or traffic spikes.
- Prefer `onCall` callable functions over `onRequest` HTTP functions to reduce invocations from unauthenticated traffic.
- Avoid scheduled polling functions when Firestore or Realtime Database change triggers can replace them.
- Batch Firestore writes to reduce write operation counts.

### BigQuery

BigQuery exports from Firestore and Firebase Analytics are **disabled by default**.

Do not enable BigQuery streaming exports or Analytics BigQuery integration without explicit approval. These exports incur continuous streaming and storage charges even at low data volumes.

### Firebase Extensions

Paid Firebase Extensions require approval before installation. Review the pricing model of any extension before enabling it. Extensions that execute Cloud Functions on a schedule or in response to every document write can produce unexpected costs at scale.

### Always-on servers

Always-on servers (for example, continuously running Cloud Run services, GCE VMs, or Kubernetes workloads) are **prohibited** in this project. All backend compute must be event-driven or request-driven with scale-to-zero behavior.

### Firestore queries

All Firestore queries must be bounded:

- Always apply a `limit()` clause to list queries. Do not fetch unbounded collections.
- Use `count()`, `sum()`, and `average()` aggregation queries instead of reading all documents to compute totals.
- Paginate results; do not load entire collections into memory.
- Cache stable, infrequently-changed documents within a single function invocation to avoid repeated reads.
- Delete stale documents promptly (for example, expired live location records).

### Production logging

Production Cloud Functions must not emit high-volume logs unnecessarily:

- Do not log every request body or response payload in production.
- Do not log exact GPS coordinates or other personal data.
- Use structured logging with severity levels; log at `DEBUG` or `INFO` only for events that are actionable.
- Cloud Logging ingestion and retention incur charges above the free tier. Keep log volume proportional to operational need.

### Development environment

Firebase Emulator Suite is the **default development and CI testing environment**. Do not run development or test workloads against the production Firebase project. All CI validation runs against the local emulators.

---

## Relevant instructions

- [`firebase-ci-cost.instructions.md`](../.github/instructions/firebase-ci-cost.instructions.md) — CI workflow rules and Firestore/Realtime Database cost practices.
- [`firebase-platform.instructions.md`](../.github/instructions/firebase-platform.instructions.md) — Platform-level Firebase conventions.
- [`firebase-security.instructions.md`](../.github/instructions/firebase-security.instructions.md) — Security rules for Firestore, Realtime Database, and Storage.
