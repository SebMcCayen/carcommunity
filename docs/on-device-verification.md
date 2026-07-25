# On-device verification

A manual pre-release checklist for the things **CI structurally cannot test**.

Large parts of this app only exist on a real, provisioned device. CI builds have no
Mapbox token, no GPS, no camera, no Play Billing and no App Check attestation — so
they compile the `StubMapSurface` and the `src/noNav` stub instead of the real map
and navigation. That means a green CI run says **nothing** about whether the map
renders, the puck follows you, a reroute fires, or a photo is stripped of its GPS
EXIF. Only a device can prove that.

**Run this before each upload to Internal testing.**

## Prerequisites

- A **signed release AAB** from the *Build Android Release* workflow (it injects the
  Mapbox token + real `google-services.json`). A local debug build will *not*
  exercise the map/nav paths.
- A real Android device with location enabled — not an emulator.

## Checklist

### Highest risk — newest code, never run on a device

1. **Nav re-routing** — start navigation, then deliberately leave the planned route.
   The "Rerouting…" pill should appear, a new route should draw from your position,
   guidance should continue on it, and the pill should clear. Also exit navigation
   mid-reroute — the pill must not stick.
2. **Image compression + metadata strip** — upload a profile picture **and** a car
   photo taken with the phone camera (so it carries GPS EXIF). Both should upload,
   look correct, and not be full-resolution. A pic that *fails* to upload is the
   fail-closed path working: anything that can't be proven metadata-clean is dropped
   rather than uploaded.
3. **Live-share entitlement** — as a **non-subscriber**: you can start sharing your
   own location, but must **not** see other members' positions. As a **subscriber**:
   you can see others.

### Map / GPS stack

4. **GPS puck** appears and the camera auto-centres on the first fix. **Re-centre**
   glides smoothly rather than jumping.
5. **Layers popup** — traffic, night mode and 3D/2D each visibly change the map. The
   **compass** rotates with the map and taps back to north-up.
6. **Automatic night mode** — with the phone's dark theme scheduled
   (Settings → Display → Dark theme → Sunset to sunrise), the map switches to the
   night preset when the system flips.

### New flows

7. **First-login welcome** shows exactly once; its CTAs land on Subscription /
   Profile / Garage; it never reappears for that account.
8. **"+" → Single session** starts a drive; ending it offers to save; the drive then
   appears in **History** with plausible distance / duration / average speed, and
   **Share** opens the share sheet.
9. **Chat hub** — Community / Convoy / Friends tabs load and send; the unread badge
   on the map chat bubble clears when opened.
10. **Convoy** — create one and invite a friend; on accept they get a green dot;
    start then end it; the summary is visible to both members.
11. **Badge wall (public)** — your own profile shows the medallion wall, the climb to
    the next tier and your Kronpoäng. Open **another** member's profile: their earned
    badges and tiers must render (badges are public), and there must be **no**
    progress bars or counter numbers on that screen — the telemetry behind a badge
    (streak, distance, meets) stays private to its owner.

## Known gaps — do NOT report these as bugs

These are tracked, expected, and waiting on something outside the app:

| Symptom | Why |
|---|---|
| Sign-in and "Report a problem" fail | **App Check / Play release SHA not provisioned** — the device sends `app: MISSING` and enforced callables reject it |
| Convoy summary shows no distance | Backend convoy route roll-up not built; `summary.distanceMeters` is always null |
| Drive detail has no map replay | The recorded route path isn't persisted/exposed to the client — only aggregate stats are |
| Push / email notifications never arrive | FCM send path + email provider aren't set up yet |
| Terms / Privacy links open placeholder pages | Real hosted content pending |

## Maintaining this

When a PR adds code CI can't exercise — anything touching the real map, GPS,
navigation, camera/image pipeline, Play Billing, App Check, or native SDKs — add a
line to the checklist above. PRs already state "needs on-device verification" in
their descriptions; this file is where that should actually land so it survives past
the PR.
