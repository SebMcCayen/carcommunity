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
- Configured day/night/cleared trail rendering checks remain pending. The
  emulator GLES implementation rejects Standard label shaders with
  `GL_MAX_VERTEX_UNIFORM_VECTORS (256)`. The optional test-only
  `redesignNativeLabels=hidden` fixture disables Standard labels; production
  configuration is unchanged. A rendered trail is still required by pixel and
  layer assertions, so a blank frame cannot pass.

The tests use local session/position fixtures. They do not establish a physical
multi-phone GPS drive or all OEM permission/background behavior.
