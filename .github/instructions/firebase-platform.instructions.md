---
applyTo: "functions/**,apps/admin/**"
---

# Firebase Platform Instructions

## Backend runtime

- Cloud Functions for Firebase 2nd generation, Firebase-supported Node.js runtime, TypeScript.
- Organize functions by domain: auth, entitlement, moderation, location, notifications, social, integrations.
- Use `onCall` (HTTPS callable) for client-initiated operations. Use `onRequest` only when an external webhook or REST contract requires it.
- Use `onDocumentWritten` / `onValueWritten` triggers for Firestore and Realtime Database event-driven logic.
- Keep individual functions small and focused. Extract shared logic into modules, not more functions.
- Always set a `timeoutSeconds` and `memory` appropriate to the function's workload. Do not leave defaults for long-running operations.
- Return typed responses. Throw `HttpsError` with a specific error code rather than untyped strings.

## Local development

- Use Firebase Emulator Suite for all local development and CI testing.
- Never connect local development to the production Firebase project.
- Start emulators with `firebase emulators:start`.
- Tests that touch Firestore, Realtime Database, Auth, or Storage must use the emulator, not production.

## Admin web hosting

- The admin web (`apps/admin`) is hosted on Firebase Hosting.
- Deploy with `firebase deploy --only hosting`.
- Do not add server-side rendering that requires a custom Node.js server outside Cloud Functions or Firebase App Hosting.

## Firebase CLI and configuration

- `firebase.json` and `.firebaserc` define project configuration and are tracked in version control.
- Do not commit service account JSON files or `GOOGLE_APPLICATION_CREDENTIALS` values.
- Use `firebase use <alias>` to switch between project aliases if multiple aliases are configured.

## Feature flags

Feature flags are backend-controlled. Cloud Functions read flag state before executing flagged behavior. Clients receive computed state and never decide access.

Flags are required for: live location, chat, Kronjakt, partner statistics, push notifications, social sharing, external data sources.
