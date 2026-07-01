# GitHub Copilot Instructions for `carcommunity`

## Mandatory mobile parity

The project targets two separate native mobile applications:

- `apps/ios` (expected): Swift and SwiftUI
- `apps/android` (expected): Kotlin and Jetpack Compose

Any product change that affects mobile functionality must be evaluated and implemented for both platforms.

A mobile feature is not complete when only one platform has been updated.

Platform-native implementation details and UI conventions may differ, but functionality, business rules, security, privacy, localization, analytics, API behavior, and user outcomes must remain equivalent.

Follow the full mobile parity instructions defined in:

`.github/instructions/mobile-platform-parity.instructions.md`

## Repository context

- `carcommunity` is an open source monorepo for a Swedish car community app.
- MVP brand is Kungsbacka Car Community (KCC), but implementation must stay brand-ready for future national or multi-local branding.
- Platform scope includes:
  - React Native / Expo mobile app (current implementation) at `apps/mobile`
  - iOS native app (Swift and SwiftUI) planned at `apps/ios` (post-migration target)
  - Android native app (Kotlin and Jetpack Compose) planned at `apps/android` (post-migration target)
  - Admin web app (React + Vite, hosted on Firebase Hosting)
  - Cloud Functions for Firebase (Firebase-supported Node.js runtime, TypeScript, planned post-migration)
  - Node.js + TypeScript API at `services/api` (current backend implementation)
  - Cloud Firestore and Firebase Realtime Database
  - Mapbox maps
  - Sign in with Apple through Firebase Authentication (iOS), Google Sign-In through Firebase Authentication (Android and admin web)

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
