# carcommunity

carcommunity is an open source monorepo for a safety-focused car community platform.

The MVP brand is **Kungsbacka Car Community (KCC)**.  
The codebase is intentionally **brand-ready** so it can support a future national name or multiple local communities without hardcoding KCC-specific behavior into core architecture.

## Open source status

- This repository is open source.
- Product and technical decisions are documented in `docs/` and `.github/copilot-instructions.md`.
- Never commit secrets to this repository.

## High-level architecture

> **Migration complete.** The legacy React Native / Expo app (`apps/mobile`) and the legacy Node.js + Fastify + PostgreSQL API (`services/api`) were removed from this repository on 2026-07-28. All durable data lives in Cloud Firestore; there is no relational database and no REST API service. The removed code remains recoverable from the `legacy-final` git tag. See [ADR-001](docs/adr/001-firebase-platform.md) and [docs/migration/](docs/migration/) for the migration record.

**Architecture:**

- Android native app (Kotlin / Jetpack Compose) — `apps/android` (MVP mobile client)
- iOS native app — descoped from MVP (parked on the Future Ideas board)
- Admin web app (React, hosted on Firebase Hosting) — `apps/admin`
- Cloud Functions for Firebase (2nd gen, Node.js 22, TypeScript) — `functions/`
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
│   ├── android/        # Kotlin / Jetpack Compose native Android app (MVP)
│   └── admin/          # Admin web app (React + Vite, hosted on Firebase Hosting)
├── functions/          # Cloud Functions for Firebase
├── firebase/           # Security Rules, indexes, RTDB rules
├── contracts/          # Language-neutral cross-platform contracts
├── packages/
│   └── shared/         # TypeScript contracts (admin web use)
├── scripts/            # Repository tooling (e.g. Firestore index drift check)
├── docs/               # Product, architecture, security, data and design docs
│   └── migration/      # Migration record: plan, inventory, and cutover checklist
└── .github/            # CI workflows and Copilot instructions
```

## Local development

Local backend work uses the **Firebase Emulator Suite** (rules in `firebase/`, Functions in `functions/`). No cloud account is required for day-to-day development.

Minimum prerequisites:

- Node.js `>=24` for the root npm workspaces (`packages/shared`, `apps/admin`)
- Node.js `>=22.13` and pnpm for `functions/`
- JDK 21 (required by the Firebase Emulator Suite for the functions emulator tests)
- Firebase CLI (`npm install -g firebase-tools`)
- JDK 21 and the Android SDK for `apps/android` (Gradle wrapper included)

Run the root workspace checks (`packages/shared` and `apps/admin`):

```bash
npm install
npm run lint
npm run typecheck
npm test
npm run build
```

Run the Cloud Functions checks:

```bash
pnpm -C functions install
pnpm -C functions run lint
pnpm -C functions run typecheck
pnpm -C functions run test
pnpm -C functions run build
```

Start the Firebase emulators (from the repository root — `firebase.json` lives there):

```bash
firebase emulators:start
```

Build and test the Android app:

```bash
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
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
