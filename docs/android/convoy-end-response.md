# Android convoy-end response

The choice observes the document named by the own RTDB session's backend-stamped
`convoyId`. Only an unexpired convoy-auto session and a matching `status=ended`
document raise a new choice. Coordinator/list optimistic removal, missing or denied
documents, and manual sessions cannot raise it. A loaded null document retains the
ordinary recording-stop path. Own-session loading is reset on account/repository
change so a previous account's snapshot cannot enter this binding.

Continue intentionally replaces the old session via the existing manual start
callable (the normal single-start helper correctly rejects starts while already
sharing, so it cannot implement this transfer). Confirmation requires a different,
active manual session with no convoy ID. Old active echoes do not complete it. A
20-second deadline covers RPC and echo. Failure/timeout while the old session is
still live returns the choice; after sharing ends it stops and saves. A newer
unrelated active session dismisses the old choice without stopping that session.
The recording coordinator and existing points survive the successful transfer.

End checks the latest observed ID before issuing the existing stop command for a
still-active old session, and waits for an observed end before stopping/saving the
recording. Failed/timeout stops with an active old session offer retry. Stops are
never retried against an observed replacement session. Command-issued markers,
choice and deadline survive recreation; uid changes discard them. Self-stop/hide
and expiry keep the ordinary neutral save reason, with no later repeated choice.

The existing stop callable does not accept an expected session ID. The Android
guard prevents stale UI decisions and retries from stopping an *observed* newer
session; it cannot make the existing API atomic against an unseen concurrent
replacement on another device. This patch makes no backend changes.

## Integration boundaries

`AuthenticatedApp.kt`, against base `165a7df`:

- Imports around original lines 125–132: replace old choice glue imports with the
  new binding and unchanged dialog extracted into a helper.
- Original lines 3336–3344: key own-session load state by account/repository.
- Original lines 3385–3419: scope self-stop marker by uid and remove old choice,
  decided and pending IDs (owned by the new end binding).
- Original line 3605: instantiate the end binding before the recorder effect.
- Original line 3641: add end-binding lifecycle guards and observe session identity;
  start only after the own-session listener has emitted.
- Original lines 3731–3799: replace the old stopped-only prompt/optimistic-sharing
  transfer resolution with the binding's actual document/session decisions.
- Original lines 8256–8330: render the extracted existing choice dialog.

No edits to permission/publisher effects, Follow-me, ConvoyRoute, ConvoyScreens,
iOS, backend, production configuration, deployment, version or store metadata.

## Validation handoff

Static `git diff --check` passes. Local Gradle compile and targeted unit-test
clients were gracefully interrupted with exit 130 at the parent's host-memory
coordination request. The compile reached `compileDebugKotlin`; neither command
produced a completed build/test result. No emulator was used. Tests below are
**added but not yet executed**; JVM regression results and native checks remain
required before merge.

CI run `40556002491`, job `122372101824`, compiled both production variants but
failed `compileDebugUnitTestKotlin`: the parser fixture directly called the
package-private `DataSnapshot` constructor. The fixture now uses the SDK's public
`InternalHelpers.createDataSnapshot` factory with the same reference and actual
node. The pinned Firebase 22.0.1 runtime's signatures were verified with `javap`;
assertions and production lifecycle/cancellation/ownership code are unchanged.
The correction requires a fresh CI run; no local Gradle was started.

Parent sequential JVM command (existing JDK/SDK/Gradle-home environment):

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*ConvoyEnd*' --tests '*LiveSessionConvoyParsingTest' \
  --tests '*LiveLocationTest' --tests '*SingleSessionRecordingTest' \
  --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx3g
```

Shared JVM/native UI class:
`com.kungsbackacarcommunity.app.drives.ConvoyEndBindingTest`.
It drives the production binding and dialog, uses the real process recording
holder with two retained route points, and independently controls document/session
snapshots and slow/failing commands. Cases cover early prompt, old active echoes,
new manual confirmation, explicit End, failed/timeout transfers, retrying failed
End, rotation, stale clicks, account changes, denied/null/manual sources,
self-stop/hide and expiry. `LiveSessionConvoyParsingTest` uses actual RTDB
DataSnapshots without listeners or writes to test the appended field.

Native/manual limits: real Firestore/RTDB/callable delivery ordering, actual
network latency and disconnect recovery, background/foreground transitions,
physical-device rotation, GPS/foreground-service continuity and the existing
cross-device unscoped-stop race have not been exercised here.
