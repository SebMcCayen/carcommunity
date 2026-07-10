# GitHub Copilot Instructions for `carcommunity`

## Authoritative source priority

When documentation conflicts, use this priority order:

1. `docs/adr/001-firebase-platform.md`
2. `docs/architecture.md`
3. `docs/migration/native-firebase-migration-plan.md`
4. `.github/instructions/mobile-platform-parity.instructions.md`
5. `.github/instructions/firebase-platform.instructions.md`
6. `.github/instructions/firebase-data.instructions.md`
7. `.github/instructions/firebase-security.instructions.md`
8. `.github/instructions/firebase-ci-cost.instructions.md`
9. `docs/product-decisions.md`

## Platform rules

The legacy React Native / Expo app (`apps/mobile`) and the Fastify / Prisma / PostgreSQL API (`services/api`) have been **removed from the repository**. Do not reintroduce them. Their behavior lives on in the migration docs (`docs/migration/`) and contracts (`contracts/`).

New mobile features are implemented in `apps/android`. An iOS app is **descoped from the MVP** (product decision 2026-07-02 — parked on the ideas board); do not scaffold or target `apps/ios` without explicit approval.

New backend business logic must target Firebase Cloud Functions, Firestore, Realtime Database, Storage, Security Rules, and App Check.

The TypeScript package `packages/shared` is **not** an executable shared mobile library. It contains TypeScript contracts for the backend and admin web. Native platforms must align through language-neutral contracts, not shared runtime code.

A mobile feature is incomplete until the Android implementation and its tests are present.

## Mobile parity (future iOS)

The MVP ships a single native mobile application: `apps/android` (Kotlin and Jetpack Compose).

If and when an iOS app (Swift and SwiftUI) is approved post-MVP, mobile functionality must reach parity across both platforms: implementation details and UI conventions may differ, but functionality, business rules, security, privacy, localization, analytics, API behavior, and user outcomes must remain equivalent. Until then, keep features iOS-portable by aligning through the language-neutral contracts in `contracts/` rather than platform-specific backend behavior.

The full parity instructions live in `.github/instructions/mobile-platform-parity.instructions.md` and apply once iOS work is approved.

## Repository context

- `carcommunity` is an open source monorepo for a Swedish car community app.
- MVP brand is Kungsbacka Car Community (KCC), but implementation must stay brand-ready for future national or multi-local branding.
- Platform scope includes:
  - Android native app (Kotlin and Jetpack Compose) at `apps/android` (shipped — MVP client)
  - iOS native app (Swift and SwiftUI) at `apps/ios` (descoped from MVP; post-MVP candidate, not yet approved)
  - Admin web app (React + Vite, hosted on Firebase Hosting) at `apps/admin`
  - Cloud Functions for Firebase (Firebase-supported Node.js runtime, TypeScript) at `functions/`
  - Cloud Firestore (durable data) and Firebase Realtime Database (ephemeral live location, presence)
  - Cloud Storage for Firebase
  - Firebase Authentication, App Check, Cloud Messaging
  - Mapbox Maps SDK (Android native SDK; iOS SDK when iOS is approved)
  - Google Sign-In through Firebase Authentication (Android and admin web); Sign in with Apple when iOS is approved

## Language and naming rules

- Write all code, comments, variable names, function names, commit messages, and technical documentation in English.
- Keep MVP user-facing text in Swedish, always via i18n keys.
- Do not hardcode `Kungsbacka Car Community` or `KCC` in components; use brand configuration and i18n.
- Internal subscription entitlement naming must stay generic (for example: `member_monthly`, not brand-specific names).

## Security and secrets

- Never commit secrets or sensitive data: tokens, credentials, private keys, signing keys, production data, `.env` files, Apple/Google/Firebase credentials, service account keys, DB connection strings.
- Use `.env.example` placeholders only.
- Never generate fake secrets or placeholder values that look real.
- Never expose personal data in logs, analytics, GitHub Issues, partner statistics, or admin dashboards.

## Backend authority and access control

- Backend is the source of truth for:
  - authentication (Firebase Authentication + custom claims)
  - admin roles (Firebase custom claims set by Cloud Functions)
  - subscription access
  - feature access
  - live location access
  - Kronpoäng
- Mobile app must never call GitHub APIs directly.
- Mobile app must never contain GitHub tokens or backend secrets.
- Admin access must always be verified by backend.
- Subscription access must always be verified by backend.

## Privacy, safety, and product rules

- Free users may share their own live location, but only active members may view other users’ live locations.
- Live location must be opt-in, time-limited, stoppable, and include “Hide me now”.
- Do not store automatic location history.
- Saved drives must only be stored after explicit user action.
- Partner stats must be opt-in, aggregated, and privacy-safe.
- Companies must never receive personal data, live location, routes, drive history, or individual tracking data.
- Kronpoäng has no cash value and cannot be bought, sold, transferred, or exchanged for money.
- Kronjakt must never encourage speeding, risky driving, unsafe stops, or fastest-to-location behavior.
- Digital billboards must be clearly marked as marketing/sponsored placement.

## Engineering principles

- MVP runs on Firebase (production only); keep code conservative and production-safe.
- Put risky functionality behind feature flags.
- Prefer simple, secure, maintainable solutions over clever complexity.
- Use TypeScript where applicable.
- Prefer explicit types and clear validation.
- Use pagination for list endpoints and list views.
- Use backend caching for external APIs.
- Use optimistic UI only when rollback behavior is clearly defined.
- Do not introduce unnecessary dependencies.
- For new dependencies, prefer well-maintained packages with active security support.
- Keep code modular and testable.
- Add tests for important business rules and security-sensitive logic.

## UI and accessibility

- Follow KCC Crown UI design principles when building UI.
- Use design tokens for colors, spacing, radius, typography, and themes.
- Support light mode, dark mode, and system theme.
- Accessibility is mandatory: readable contrast, large tap targets, labels, and never rely on color alone.

## Copilot implementation behavior

- Ask for clarification only when a decision is truly blocking.
- Keep implementations MVP-light unless task requirements explicitly ask for more.
- Do not add deployment steps unless explicitly requested.
- Do not add paid cloud services unless explicitly requested.
- Do not assume multiple environments; this MVP uses Production only.
