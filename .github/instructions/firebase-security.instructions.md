---
applyTo: "functions/**,apps/admin/**,apps/ios/**,apps/android/**"
---

# Firebase Security Instructions

## Authentication

- Firebase Authentication is the identity provider for all clients.
- iOS uses Sign in with Apple through Firebase Authentication.
- Android uses Google Sign-In through Firebase Authentication.
- Admin web uses Google Sign-In through Firebase Authentication.
- The Firebase `uid` is the canonical identity key. Never use email as an identity key.
- Provider account linking is not included in the MVP; do not implement it without an approved decision.

## Admin authorization

- Admin roles are granted through server-managed Firebase custom claims.
- Custom claims are set exclusively by Cloud Functions using the Firebase Admin SDK.
- Clients must never set or modify custom claims.
- Cloud Functions must verify the `admin` custom claim on every protected admin operation.
- Do not rely on Firestore document fields or client-supplied values to determine admin access.

## Firebase App Check

- App Check is required in production to protect Cloud Functions from unauthorized callers.
- iOS: use DeviceCheck or App Attest.
- Android: use Play Integrity.
- Enforce App Check on all callable functions in production. Use debug tokens only in emulator/CI environments.
- Never commit App Check debug tokens to version control.

## Firebase Security Rules

- Firestore, Realtime Database, and Cloud Storage must have production-safe Security Rules deployed before going live.
- Default deny: rules must start from a deny-all baseline and allow only what is explicitly needed.
- Rules must never grant write access to admin-only fields (custom claims, subscription entitlements, moderation state) from clients.
- Live location paths in Realtime Database must allow writes only from the authenticated owner and reads only from verified active members.
- Test Security Rules with the Firebase Emulator and Rules Unit Testing before deployment.

## Secrets and credentials

- Never commit Firebase service account JSON files or private keys.
- Never commit `GOOGLE_APPLICATION_CREDENTIALS` values or `google-services.json` / `GoogleService-Info.plist` with production credentials.
- Use CI secrets and environment configuration to provide credentials at deployment time.
- Use `.env.example` placeholders only in the repository.

## Token and credential storage on mobile

- Never store Firebase ID tokens or refresh tokens in plain text.
- Use Keychain on iOS.
- Use Android Keystore-backed secure storage on Android.
- Never log tokens, credentials, payment information, or exact GPS coordinates.

## Subscription entitlement

- Subscription entitlement (`member_monthly`) is stored and verified server-side only.
- Cloud Functions verify purchase receipts with Apple and Google before granting entitlement.
- Clients never decide final subscription access; they render based on Cloud Function responses.
- Do not unlock premium functionality from locally cached purchase state alone.
