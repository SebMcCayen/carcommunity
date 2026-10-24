# Native Driver bridge verification

Implementation checkpoint: 2026-10-24. No production deployment or data changes.

## Executed evidence

- 52 reader tests: authorization, bilateral blocks, parent reauthorization,
  pagination, invalid data, quota and expiry behavior.
- Six real Firestore-emulator reader checks passed in the implementation lane.
- TypeScript typecheck/build, focused lint, App Check/instance/coverage guards
  and strict JSON Schema compilation passed in that lane.
- Main's real callable transport run passed **all seven tests** with Auth,
  Firestore and Functions emulators using `demo-test`. It exercises original
  field projection, nanosecond cursor serialization, tie-safe reply paging,
  parent deletion, reverse Driver/KCC blocks, onboarding/suspension reasons and
  quota validation through the actual callable SDK.
- 24 finance-inventory/runner guard tests passed after registering the new
  emulator file in the Social group and classifying the new callables as
  `uncosted`. All 77 emulator files map to exactly one CI group.

The first local callable attempts did not load the entire Functions manifest
within the CLI's default ten-second discovery window; all seven cases were
skipped after their Auth provisioning setup timed out. Retrying with
`FUNCTIONS_DISCOVERY_TIMEOUT=120` loaded the manifest and produced the seven
passing results. Do not describe the skipped attempts as successful tests.

The focused local command did not run the Database emulator; existing unrelated
block triggers attempted the non-existent demo project's RTDB during teardown.
Use the normal repository runner or include Database for repeatable teardown:

```sh
FUNCTIONS_DISCOVERY_TIMEOUT=120 firebase emulators:exec \
  --project demo-test --only auth,database,firestore,functions \
  'cd functions && pnpm exec vitest run --config vitest.emulator.config.ts src/__tests__/driver-feed.emulator.test.ts'
```

## Remaining gates

Full Functions and all grouped emulator CI results must be checked independently
of these focused results. Native builds, rendered design acceptance, live App
Check acceptance and feed reads are separate gates. Verify enabled TTL on
`driverRateLimits.expiresAt` before deliberate deployment. No live TTL setting,
backend availability, production permission change or native release is proved
by a passing local emulator test.
