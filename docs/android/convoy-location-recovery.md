# Android convoy location recovery and shared trail visibility

Scope: Android session-bound position publishing and the existing Follow-me line.
No backend, rules, entitlement, recording contract or iOS implementation changes.

## Starting and recovering position publishing

Accepting/starting an active convoy already creates the live session server-side.
The Android publisher must follow that observed session without a second manual
share action. It now also re-evaluates when location permission/services recover,
the app resumes, or another active session replaces the previous session for the
same user. Permission recovery previously reattached the drive recorder but did
not retry the position service, leaving its RTDB marker and shared trail empty.

Both precise-location entry points request coarse and fine permissions together,
as required by the [Android runtime permission contract](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime).
The existing fine-location requirement and denial/settings guidance remain; a
denial never starts location publishing. A session must actually be observed as
sharing before its service is started. Existing stop, hide, expiry, account and
foreground-service protections still apply.

With fine permission already granted, a disabled device location switch does not
prevent registering the service's fused request. It can therefore receive fixes
when Quick Settings enables location without a new permission result, provider
state emission or activity resume. `SERVICES_OFF` is permission-granted by the
classifier's permission-first contract; `PERMISSION_DENIED` remains blocked.

Android 14 may reject a background location-service start with `SecurityException`
despite a while-in-use permission reading as granted. That narrow granted,
background-only case joins the existing foreground retry. A foreground or missing
permission fault is still thrown. This follows the [platform restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
No background location permission or exemption is added.

## Shared trail rendering

The shared trail retains its existing violet identity and privacy/freshness gates.
It now has a 6 px emissive core over a 10 px dark casing. Its oldest segment keeps
85% opacity, rather than fading to invisible. Both layers clear together; partial
style creation repairs casing/core order, and the existing style-reload path
restores the current geometry. Private gold breadcrumbs remain a separate source.

There must still be at least two real accepted positions before a line can be
drawn. This change never invents geometry or makes stale/removed members visible.
It does not change the position or Firestore write cadence.

## Verification

- 37 focused JVM/Robolectric cases passed: publisher binding, foreground start
  decisions, Follow-me binding/button and trail logic.
- Debug app and instrumentation APK assembly passed.
- 23 native API 34 emulator cases passed: publisher binding (5), Follow-me
  binding (8) and button state (10).
- Combined Android regression snapshot `263821f`: 3,881 JVM cases, zero failures
  or errors, one pre-existing skipped case; lintDebug, debug APK and
  instrumentation APK assembly passed. This local integration includes the
  chat/age controls, invite picker, convoy end, automatic theme, Driver links
  and saved-route recovery PR heads; it is not a separate product branch.
- Three configured Mapbox Standard captures passed on API 34 with the emulator's
  `-gpu lavapipe` backend: shared trail visible in night and day, then cleared.
  Labels/icons remained enabled. Both native core/casing visibility and actual
  gold/violet pixels are asserted; clearing preserves gold and removes violet.
  The old emulator backend rejected symbol shaders with
  `GL_MAX_VERTEX_UNIFORM_VECTORS (256)` and could not produce valid evidence.
  No production map setting or pixel assertion was weakened to work around it.

### Native map evidence (local synthetic data)

| Case | Capture | Native result |
| --- | --- | --- |
| Night, Follow me visible | [Night](evidence/2026-10-27-convoy/convoy-follow-night.png) | Passed, 24.526 s |
| Day, Follow me visible | [Day](evidence/2026-10-27-convoy/convoy-follow-day.png) | Passed, 12.264 s |
| Follow me cleared | [Cleared](evidence/2026-10-27-convoy/convoy-follow-cleared.png) | Passed, 8.269 s |

Reproduce with `Wave2RedesignCaptureTest`, `wave2Screen=map-convoy`,
`redesignMap=configured`, `redesignFollowMe=visible|cleared`, and
`redesignTheme=dark|light`; the light case also uses `redesignMapAppearance=day`.
The test APK needs the normal public Mapbox configuration, not Firebase access.

The tests use local session/position fixtures. They do not establish a physical
multi-phone GPS drive or all OEM permission/background behavior.
