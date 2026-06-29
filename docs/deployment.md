# Deployment Readiness

This document describes the planned production deployment setup for the car community platform.

## Target Platform

**Firebase** — Production only. No staging or development cloud environments are planned for MVP.

See [ADR-001](adr/001-firebase-platform.md) for the decision to migrate from Azure to Firebase.

## Service Overview

| Service | Hosting |
|---------|---------|
| Backend functions (`functions/`) | Cloud Functions for Firebase (2nd gen, Node.js 22) |
| Admin web (`apps/admin`) | Firebase Hosting |
| iOS app (`apps/ios`) | App Store |
| Android app (`apps/android`) | Google Play |

## Local Development

Firebase Emulator Suite provides a full local environment:

- **Functions Emulator**: runs Cloud Functions locally.
- **Firestore Emulator**: local Firestore instance.
- **Realtime Database Emulator**: local Realtime Database instance.
- **Authentication Emulator**: local Firebase Auth.
- **Storage Emulator**: local Cloud Storage for Firebase.
- **Hosting Emulator**: local Firebase Hosting preview.

Run `firebase emulators:start` to start all configured emulators.

## Security

- **No secrets are stored in this repository.**
- Firebase service account credentials and configuration must be provided through CI secrets and environment configuration at deployment time.
- Firebase App Check is required in production to protect Cloud Functions from unauthorized callers.
- Firebase Security Rules enforce data access controls on Firestore and Realtime Database.
- Admin authorization uses server-managed Firebase custom claims set exclusively by Cloud Functions.
- No credentials appear in function code or CI configuration.

## Production-Only Note

MVP infrastructure targets a single **Production** Firebase project. Separate staging, preview, or development Firebase projects are not planned at this stage. Keep changes conservative and production-safe.

Use Firebase Emulator Suite for all local and CI testing.

## CI Validation

CI validates TypeScript compilation and tests for Cloud Functions on every push/PR to `main`. Functions are **not deployed** from CI unless an explicit deployment workflow is added.

## Future Steps

The following steps are required before live production deployment and are **not yet configured**:

1. **Firebase project** — Create a Firebase project and configure `firebase.json` and `.firebaserc`.
2. **Cloud Functions deployment** — Configure `firebase deploy --only functions` in a deployment workflow (requires service account credentials in repository secrets).
3. **Firebase Hosting deployment** — Configure `firebase deploy --only hosting` for the admin web.
4. **Firebase Authentication** — Enable Sign in with Apple (iOS) and Google Sign-In (Android and admin web) providers.
5. **Firebase App Check** — Enable App Check with DeviceCheck (iOS) and Play Integrity (Android) in production.
6. **Firestore Security Rules** — Author and deploy production-safe Firestore Security Rules.
7. **Realtime Database Security Rules** — Author and deploy production-safe Realtime Database Security Rules.
8. **Cloud Storage Security Rules** — Author and deploy production-safe Cloud Storage Security Rules.
9. **Firestore indexes** — Define composite indexes required by application queries.
10. **Firestore backups** — Configure scheduled Firestore exports to Cloud Storage.
11. **Monitoring** — Set up Firebase Performance Monitoring, Crashlytics, and Cloud Functions logs and alerts.
12. **Custom domains** — Configure a custom domain for Firebase Hosting if required.
