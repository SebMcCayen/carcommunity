# App Check — registration and production enforcement plan (Phase 15)

## Current state

| Surface  | Provider                | Status                                                            |
| -------- | ----------------------- | ----------------------------------------------------------------- |
| Functions | built-in v2 enforcement | Every callable sets `enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true'` (guard test: `functions/src/__tests__/appcheck-guard.test.ts`) |
| Android  | Play Integrity (release) / debug provider (debug builds) | Registered in `KccApplication`; no-op until `google-services.json` is provisioned |
| Admin web | reCAPTCHA Enterprise            | Registered in `apps/admin/src/lib/firebase.ts`; no-op until `VITE_APPCHECK_SITE_KEY` is configured |
| iOS      | App Attest              | Descoped with the iOS app (2026-07-02 decision)                   |

Emulator/CI: the Functions emulator runs with enforcement DISABLED (the
`FUNCTIONS_EMULATOR` guard); clients use debug providers
(`VITE_APPCHECK_DEBUG_TOKEN` on web, `DebugAppCheckProviderFactory` on
Android debug builds).

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
