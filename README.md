# carcommunity

carcommunity is an open source monorepo for a safety-focused car community platform.

The MVP brand is **Kungsbacka Car Community (KCC)**.  
The codebase is intentionally **brand-ready** so it can support a future national name or multiple local communities without hardcoding KCC-specific behavior into core architecture.

## Open source status

- This repository is open source.
- Product and technical decisions are documented in `docs/` and `.github/copilot-instructions.md`.
- Never commit secrets to this repository.

## High-level architecture

> **Migration in progress.** The current implementation uses `apps/mobile` (React Native / Expo) and `services/api` (Node.js + Fastify + PostgreSQL). These are **legacy migration sources** frozen to new product features. The target architecture below is what the codebase is actively migrating towards. See [ADR-001](docs/adr/001-firebase-platform.md) and [docs/migration/](docs/migration/) for the migration plan.

**Target architecture:**

- iOS native app (Swift / SwiftUI) — planned at `apps/ios`
- Android native app (Kotlin / Jetpack Compose) — planned at `apps/android`
- Admin web app (React, hosted on Firebase Hosting) — `apps/admin`
- Cloud Functions for Firebase (2nd gen, Node.js 22, TypeScript) — `functions/` (planned move from `apps/functions`)
- Cloud Firestore (durable data) and Firebase Realtime Database (live location, presence)
- Firebase Authentication (Sign in with Apple on iOS; Google Sign-In on Android and admin web)
- Firebase Cloud Messaging (push notifications)
- Firebase App Check
- Mapbox Maps SDK
- GitHub Actions CI workflows

## Repository structure

```text
.
├── apps/
│   ├── ios/            # Swift / SwiftUI native iOS app (planned)
│   ├── android/        # Kotlin / Jetpack Compose native Android app (planned)
│   ├── mobile/         # LEGACY: React Native / Expo app (frozen — migration source)
│   └── admin/          # Admin web app (React, hosted on Firebase Hosting)
├── functions/          # Cloud Functions for Firebase (planned move from apps/functions)
├── firebase/           # Firebase CLI config, Security Rules, indexes
├── contracts/          # Language-neutral cross-platform contracts (planned)
├── services/
│   └── api/            # LEGACY: Node.js + Fastify + PostgreSQL API (frozen — migration source)
├── packages/
│   └── shared/         # TypeScript contracts (backend/admin use)
├── docs/               # Product, architecture, security, data and design docs
│   └── migration/      # Migration plan, inventory, and cutover checklist
└── .github/            # CI workflows and Copilot instructions
```

## Local development

Local development uses the **Firebase Emulator Suite** for all backend services. No cloud account is required for day-to-day development.

Minimum prerequisites:

- Node.js `>=24` (LTS line for this repository)
- npm (current stable release)
- Java 11+ (required by Firebase Emulator Suite)
- Firebase CLI (`npm install -g firebase-tools`)

Start Firebase emulators:

```bash
cd firebase
firebase emulators:start
```

Run workspace checks:

```bash
npm install
npm run lint
npm run typecheck
npm test
npm run build
```

> See [docs/deployment.md](docs/deployment.md) for CI and production deployment details.

## Security note

- No secrets in repository files or git history.
- Use `.env.example` as template only.

## Documentation

- [Product decisions](docs/product-decisions.md)
- [Architecture](docs/architecture.md)
- [Migration plan](docs/migration/native-firebase-migration-plan.md)
- [Current state inventory](docs/migration/current-state-inventory.md)
- [Feature parity matrix](docs/migration/feature-parity-matrix.md)
- [Backend domain mapping](docs/migration/backend-domain-mapping.md)
- [Cutover checklist](docs/migration/cutover-checklist.md)
- [Security](docs/security.md)
- [Data model](docs/data-model.md)
- [Firebase data model](docs/firebase-data-model.md)
- [API guidelines](docs/api-guidelines.md)
- [Design system](docs/design-system.md)
- [Deployment](docs/deployment.md)

## License and brand assets

- Source code is licensed under the [MIT License](LICENSE).
- Kungsbacka Car Community names, logos, crown mark, and brand assets are **not** covered by the MIT License and may not be used without permission.
