# services/api — Legacy Migration Source

> ⚠️ **This directory is frozen to new product features.**

## Why this directory still exists

`services/api` contains the original Node.js / Fastify / Prisma / PostgreSQL backend API for the Kungsbacka Car Community platform. It remains in the repository as a **migration reference** only.

It is kept alive so that:

- Existing business logic, validation rules, and authorization behavior are fully documented and traceable.
- Feature-by-feature parity can be verified before backend cutover.
- The legacy API remains buildable as a behavior reference until the Firebase backend reaches verified parity.

## What changes are allowed

- **Critical security fixes** that affect the legacy build while migration is in progress.
- **Build maintenance** to keep the legacy build operational (dependency security patches, broken tooling fixes).
- **Behavior extraction** — documenting existing business rules into migration documentation or language-neutral contracts.
- **Migration-specific compatibility work** explicitly requested by a tracked migration task.

## What changes are prohibited

- New product features, routes, or business logic.
- New Prisma schema changes that extend product capability.
- New external integrations or service dependencies.
- Any change that moves the migration target further from the Firebase-native architecture.

## Target replacement

The replacement for `services/api` is the Firebase backend:

- **Cloud Functions for Firebase** (2nd gen, Firebase-supported Node.js, TypeScript) at `apps/functions` (planned move to `functions/`)
- **Cloud Firestore** for durable application data
- **Firebase Realtime Database** for ephemeral live-location and presence data
- **Cloud Storage for Firebase** for files and route storage
- **Firebase Security Rules** for client-side access enforcement
- **Firebase Authentication** for identity and token verification
- **Firebase App Check** for client integrity

## Migration and cutover documents

- [`docs/migration/native-firebase-migration-plan.md`](../../docs/migration/native-firebase-migration-plan.md) — phased migration plan
- [`docs/migration/backend-domain-mapping.md`](../../docs/migration/backend-domain-mapping.md) — PostgreSQL → Firebase domain mapping
- [`docs/migration/feature-parity-matrix.md`](../../docs/migration/feature-parity-matrix.md) — feature-by-feature parity tracking
- [`docs/migration/cutover-checklist.md`](../../docs/migration/cutover-checklist.md) — cutover gates
- [`docs/adr/001-firebase-platform.md`](../../docs/adr/001-firebase-platform.md) — platform decision record
- [`docs/firebase-data-model.md`](../../docs/firebase-data-model.md) — target Firebase data model

## Deletion gate

This directory **must not be deleted** until:

1. All Firebase backend callables, triggers, and Security Rules have verified feature parity with this implementation.
2. No production data depends solely on the legacy PostgreSQL database.
3. All cutover checklist gates in `docs/migration/cutover-checklist.md` are met.
4. Legacy deletion is explicitly approved in a separate pull request as described in the cutover checklist.
