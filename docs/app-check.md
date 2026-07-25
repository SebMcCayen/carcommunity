# App Check — registration and production enforcement plan (Phase 15)

## Current state

| Surface  | Provider                | Status                                                            |
| -------- | ----------------------- | ----------------------------------------------------------------- |
| Functions | built-in v2 enforcement | Every callable sets `enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true'` (guard test: `functions/src/__tests__/appcheck-guard.test.ts`) |
| Android  | Play Integrity (release) / debug provider (debug builds) | Registered in `KccApplication`; no-op until `google-services.json` is provisioned. Debug builds can pin a stable debug secret — see below |
| Admin web | reCAPTCHA Enterprise   | Registered in `apps/admin/src/lib/firebase.ts`; no-op until `VITE_APPCHECK_SITE_KEY` is configured |
| iOS      | App Attest              | Descoped with the iOS app (2026-07-02 decision)                   |

Emulator/CI: the Functions emulator runs with enforcement DISABLED (the
`FUNCTIONS_EMULATOR` guard); clients use debug providers
(`VITE_APPCHECK_DEBUG_TOKEN` on web, `DebugAppCheckProviderFactory` on
Android debug builds).

## Android debug builds — stable debug token (one-time setup)

**Symptom this solves:** after every debug rebuild/reinstall, App-Check-gated
callables (`feedback-reportIssue` — "report an issue" — and every other
`onCall`) start failing with `UNAUTHENTICATED` until a new debug token is
registered by hand.

**Cause:** `DebugAppCheckProviderFactory` generates its secret with
`UUID.randomUUID()` on first run and stores it in SharedPreferences inside the
app's data dir. An uninstall/reinstall wipes it, so the next launch mints a new,
*unregistered* secret. (The Android SDK has no `FIREBASE_APP_CHECK_DEBUG_TOKEN`
environment variable — that mechanism is web/JS only.)

**Fix:** pick one UUID, pin it locally, register it once.

1. Generate a UUID: `uuidgen` (or any v4 UUID).
2. Add it to `apps/android/local.properties` — gitignored, so the value is
   never committed (this repo is public):

   ```properties
   appcheck.debugToken=<your-uuid>
   ```

3. Register the same UUID once in the Firebase console → **App Check** →
   the Android app → **⋮ → Manage debug tokens** → **Add debug token**.
4. Rebuild. `app/build.gradle.kts` exposes the value as
   `BuildConfig.APP_CHECK_DEBUG_TOKEN` (debug build type only), and
   `AppCheckDebugSecret.seedIfConfigured` writes it into the SDK's debug store
   (`com.google.firebase.appcheck.debug.store.<persistenceKey>` /
   `com.google.firebase.appcheck.debug.DEBUG_SECRET`, verified against
   `firebase-appcheck-debug` 19.3.0) **before** the provider factory is
   installed. From then on every rebuild reuses the registered token.

Leaving `appcheck.debugToken` unset is fully supported and is the default: CI
and fresh clones build green and keep the old SDK-generated-token behaviour.
Release builds are untouched — they attest with Play Integrity, and the
`BuildConfig` field is hard-coded empty outside the debug build type.

Treat the token like a credential: anyone holding it can pass App Check as this
app. Rotate by registering a new UUID and deleting the old one in the console.

**Not this bug:** if a *release* build also fails a callable, that is a separate
IAM problem — the Cloud Run service backing the function is missing the
`allUsers → roles/run.invoker` binding. That is a gcloud/console action, not a
client change.

## Production rollout (at cutover, per the migration plan)

1. Provision providers in the Firebase console: Play Integrity for the
   Android app id, reCAPTCHA Enterprise for the admin hosting domain. Configure
   `VITE_APPCHECK_SITE_KEY` in the admin build environment.
2. **Monitor-only window (min. 1 week):** keep console enforcement OFF for
   Firestore/RTDB/Storage and rely on the callables' built-in enforcement
   being observable in Cloud Monitoring (`verified` vs `unverified`
   request metrics). Register debug tokens for CI.
3. **Enforcement criteria** (all must hold before flipping console
   enforcement ON for Firestore/RTDB/Storage):
   - ≥ 99% of production requests carry valid App Check tokens over 7 days;
   - zero unexplained `unverified` spikes;
   - a tested rollback: enforcement OFF is a console switch, no deploy.
4. Flip enforcement per product: Firestore → RTDB → Storage, one at a
   time, watching error rates between steps.

## Rollback

Disable enforcement in the Firebase console (no code change, immediate).
Client-side registration stays in place — it is harmless without
enforcement.
