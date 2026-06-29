---
applyTo: ".github/workflows/**,functions/**"
---

# Firebase CI and Cost Instructions

## Budget constraint

Maximum operating budget: **SEK 500 per month** at the expected initial usage of 20–30 active users.

Every infrastructure, function, and data design decision must be evaluated against this budget. Do not introduce services or usage patterns that would exceed it at expected scale.

## Firebase Spark vs Blaze plan

- Cloud Functions require the Blaze (pay-as-you-go) plan. Blaze includes a free tier that covers low-volume MVP usage.
- Monitor spend in the Firebase console. Set a billing budget alert below SEK 500.
- Do not deploy services or enable features that have significant fixed monthly costs at low usage.

## Cloud Functions cost practices

- Prefer `onCall` callable functions over `onRequest` to avoid unnecessary invocations from unauthenticated traffic.
- Set the minimum instance count to 0 (`minInstances: 0`) unless a specific latency requirement justifies a warm instance.
- Set concurrency and memory only as high as needed. Start with defaults (256 MB, concurrency 80) and tune from evidence.
- Avoid functions that poll on a schedule when Firestore/Realtime Database triggers can replace them.
- Batch Firestore writes where possible to reduce write operation counts.

## Firestore cost practices

- Firestore costs are based on reads, writes, and deletes. Design read patterns to minimize document reads per user interaction.
- Use aggregation queries (`count()`, `sum()`, `average()`) instead of reading all documents to compute totals.
- Cache stable, infrequently-changed documents in memory within a function invocation.
- Delete stale documents promptly (expired live location records, old ephemeral state).

## Realtime Database cost practices

- Realtime Database costs are based on bandwidth and storage. Keep live location payloads minimal (uid, lat, lng, timestamp).
- Remove location records immediately when a session ends; do not let stale data accumulate.

## CI workflow rules

- CI must run against Firebase Emulator Suite, never against the production Firebase project.
- Do not add CI steps that deploy to production. Deployments must be intentional, reviewed, and triggered separately.
- Do not commit Firebase debug tokens, service account credentials, or project-specific secrets to the repository.
- Cloud Functions must pass TypeScript compilation and unit tests in CI before merge.
- Firebase Emulator integration tests may run in CI using the `firebase-tools` CLI.

## Dependency management

- Do not add Firebase product SDKs beyond those needed for implemented features.
- Keep `firebase-admin` and `firebase-functions` versions consistent across the monorepo.
- Review the Firebase release notes before upgrading to a new major version of Firebase SDKs.
