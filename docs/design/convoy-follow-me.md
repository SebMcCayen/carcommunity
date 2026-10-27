# Android convoy Follow me contract

The persistent shared trail and the transient Follow-me reaction are separate.
The reaction keeps its anti-spam cooldown. In persistent mode, turning the trail
on, off or taking over is not blocked by that cooldown. Only an in-flight toggle
briefly disables the Follow-me button; other reaction buttons retain their own
gates. The selected/ring state comes from observed server leadership, never an
optimistic local success. A failed request leaves the observed state intact.

## Shared leadership and map

There is one `convoys/{convoyId}/followMe/current` document. Any accepted member
of an active convoy can become its leader. A takeover replaces the pointer and
clears the previous route. Turning off deletes it only if the caller is still
leader; an old leader's delayed off request must not clear a newer leader.
Membership and convoy lifecycle must be checked inside the same transaction as
the pointer update, so activation cannot race past leaving or ending the convoy.
Rules reject trail writes after the convoy ends or the writer loses membership
or leadership. Existing suspension/deletion checks remain in force.

Android feeds the same decoded shared route to every participant's map,
**including the leader**. The private approximately 1 km self-breadcrumb remains
a separate layer/buffer; the shared violet route retains its approximately
15 km rolling window and 4-second write throttle. A line requires at least two
valid positions: activation while stationary does not invent a route. Membership
and freshness gates remove stale/removed leaders' geometry. The four-minute
freshness window matches convoy markers and tolerates the existing three-minute
stationary heartbeat without adding writes. Removing the viewer
also clears their map even before a denied listener reports its error.

`rememberConvoyFollowMeBinding` scopes observation to convoy, repository and
signed-in user; no old snapshot may carry across a new listener's initial wait.
Its publisher is recreated on leadership, convoy or activation changes, avoiding
old geometry/throttle state in a new session. Scope disposal clears the map.

## Queued writes and compatibility

Each real activation/takeover creates an opaque `activationId`. A repeated on
request by the current leader preserves its id and route. Updated Android
captures that id when publishing and uses a Firestore transaction to compare it
again before writing. A takeover, off or same-UID reactivation invalidates an old
queued write, including across a transaction retry.

Deploy the updated callable **and** Firestore rules before rolling out this
Android change. Rules allow the optional id but prohibit changing it from the
client. Legacy documents without an id and old clients that partially update
only points/timestamp remain compatible. Those old clients do not acquire the
new generation guard automatically; the protection applies to updated Android.
The transaction adds a document read per trail flush; the existing write throttle
and write count are unchanged. No extra callable is used per position update.

## Verification scope

The shared Android UI tests run unchanged under Robolectric and native
instrumentation. Button tests cover selected/pending states, on/off, takeover,
reclaim within a reaction cooldown, failures and cancellation. Three production
bindings share a local protocol fixture to check identical route delivery,
A→B→C takeover, off, late former-leader requests, same-user reactivation, delayed
first snapshots in a new convoy and removed viewers. Maps are observable
`StubMapSurface` instances; these tests do not prove real Mapbox tiles or GPS.

Separately test real callable transactions and Firestore rules in the local
Firebase emulator, including competing activations, ended/removed members and
generation-aware transaction retries. This separation must be explicit: local
protocol fixtures are not evidence of a three-phone Firebase end-to-end run.
No iOS changes or production deployment are part of this patch.

### Executed checks (2026-10-27)

| Layer | Result |
| --- | --- |
| Android focused JVM | 35 passed: six binding scenarios, ten button scenarios, ten trail tests, nine reaction tests. |
| Native Android emulator | The same 16 binding/button scenarios passed on API 34 (`OK (16 tests)`). |
| Backend core | 25 focused Follow-me tests passed. |
| Local Firebase emulator | 27 callable/rules/race tests passed, including simultaneous takeovers, lifecycle races, deletion propagation, optional/immutable generations and retry after same-UID reclaim. |
| Emulator CI grouping | 18 runner/grouping tests passed; the new race suite is included once through the `convoy*` group. |
| Backend static checks | Typecheck and targeted lint passed. |
| Android build | Lint, debug app and instrumentation APK assembly passed. |

Android UI tests are in `src/sharedDebugTest/java`, compiled into both
`testDebugUnitTest` and `androidTest`. Run the focused Gradle tests with
`--tests '*ConvoyFollowMeBindingTest' --tests '*ConvoyFollowMeButtonTest'
--tests '*FollowMeTrailTest' --tests '*ConvoyReactionTest'`. Native execution uses
`AndroidJUnitRunner` with the two `ConvoyFollowMe*Test` classes. Backend suites
are `convoy/followMe-core.test.ts`, `__tests__/convoy-followme.emulator.test.ts`
and `__tests__/convoy-followme-races.emulator.test.ts`. The race suite explicitly
requires loopback Firestore and a `demo-*` project; local runs blocked production
ADC lookup and shut emulators down afterwards.

These results establish the patched Android/shared-state behaviour and local
backend transactions. They do not establish production deployment, a configured
Mapbox rendering test, an active Navigation SDK route, or a physical multi-phone
GPS drive. No production account or live convoy was used.
