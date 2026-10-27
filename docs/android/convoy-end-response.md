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
If RTDB stops before an already-observed active convoy document changes to ended,
the recorder is held for a bounded reconciliation window (20 seconds). Only the
matching ended document opens the choice. A missing/denied document or deliberate
stop releases the hold; an elapsed window consumes the old session and releases
ordinary stop/save so a much later document cannot offer an already-saved transfer.
This deadline survives recreation and still honors expiry/account/session changes.
All decisions use the observed session status/expiry directly, so a one-frame lag
in the shell's derived sharing producer cannot consume a newly active manual
session or prematurely complete End.

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
The correction passed CI run `40557833985` at head `1dc9276`: unit tests, lint,
debug/release builds, nav variant/alignment and 141 instrumented tests all passed.

Ready-for-review Codex review `5672316203` reported a P1 stale-sharing/manual-start
race and a P2 RTDB-before-Firestore delivery-order race. Both are addressed solely
in the end binding with six additional regressions (manual start, early End,
inverted delivery order, bounded no-document fallback, deadline across rotation,
and null/self-stop release). The resulting head requires fresh CI/central tests.
No further local Gradle or emulator use; no shared AuthenticatedApp integration
hunks were edited for these review fixes. Copilot review could not be dispatched:
GitHub's reviewer picker reports "Monthly limit reached" despite API success.

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
