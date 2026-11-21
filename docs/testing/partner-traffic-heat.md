# Partner traffic heat validation

Scope: manager-only global traffic planning in the native company workspace.
Production deployment, feature activation and app publication are not authorized.
Physical Android/iPhone acceptance remains **OPEN**.

## Required source and CI checks

- Backend unit suite, lint, typecheck, build and deploy-lockfile consistency.
- Shared projection: consent provenance/version, future and >48-hour timestamps,
  malformed data, ten-account floor, actual resolution-10 H3 geometry, duplicates,
  deterministic top-1,000 cap, relative bands and removal of private/unknown data.
- Real Firestore/Auth emulator: exact manager membership, customer/cross-company/
  redeemer rejection, authority revoked after entry guard, inactive company,
  erasure/deletion fences, dark-launch flag and strict input. Verify source
  aggregate is not changed by reads.
- Real client Auth + callable HTTP transport: manager success and anonymous,
  customer and downgraded-redeemer rejection. All data is synthetic in loopback
  demo emulators, with credential stripping and outbound transport guard.
- Android parser/state and native UI; iOS model/coordinator/layout tests. Include
  account/company change during an in-flight load, monotonic expiry including
  request delay, denied/error/empty/truncated states, manager-only entry and
  no-map text fallback. Native builds/tests run in CI, not on this Linux host.
- Contract/localization parity and generated native string mirrors.
- Exact final PR-head checks, automatic code/security reviews and unresolved
  review threads. The PR record records actual runs; this checklist is not a
  claim that pending checks have passed.

## Deferred acceptance

Physical Android and iPhone: touch pan/zoom within scrolling content, VoiceOver/
TalkBack, large text, map-network failure, scene transitions/account switch,
foreground expiry and background snapshot clearing. Keep these open until real
hardware is available. Simulator/emulator evidence cannot close them.

Before any separately authorized activation, stage the already-reviewed consent
producer/readers and portal changes together, verify indexes/rules as applicable,
use a synthetic manager/redeemer acceptance fixture and verify dark-launch OFF.
This document does not authorize running a production probe, deploying, toggling
flags or publishing either app.
