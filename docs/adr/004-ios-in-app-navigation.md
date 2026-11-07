# ADR-004: iOS navigation inside CarCommunity

Status: accepted by the owner on 2026-11-07: “Gör så iOS användare kan navigera i appen, samma som Android”.

This supersedes ADR-002's temporary iOS navigation deferral. Use the existing
Mapbox account/public runtime token and Downloads:Read credential used by Android.
No new service plan, key or Firebase deployment is required. The owner was informed
that iOS and Android use the same published Navigation SDK pricing, while additional
iOS users/trips can increase aggregate usage charges.

## Decision

Use Mapbox Navigation SDK v3 for native active guidance, voice, rerouting, camera
following and trip progress. Search selection only previews a destination. The
explicit Navigate action opens a full-screen native controller inside CarCommunity;
it does not launch Apple Maps or another application. Guidance is a retained
sibling overlay, so opening/closing it does not run shell disappear/reappear
teardown or account wiring. Auth/restriction removal still tears down the shell.
The shell owns the combined live-drive/guidance idle timer; disable SDK ownership.

Only a recent, accurate real-device origin may be used. No Kungsbacka/default camera
coordinate is substituted for the user's location. Failed/denied location and failed
routes have dismiss/retry paths. Cancellation cancels GPS/routing work, stops active
guidance and returns the SDK to idle. Never start a Free Drive trip at application
launch, search, preview, dismissal or completion. Retain the existing live-drive
session independently; stopping navigation must not stop that session.

## Dependency and build boundary

Like Android's nav/noNav variants, secret-free `KCC.xcodeproj` remains buildable
without a Downloads token. `project.navigation.yml` generates a separate ignored
`KCCNavigation.xcodeproj`, with the real SDK linked and `KCC_NAVIGATION_SDK` defined.
Generate it using `python3 apps/ios/scripts/navigation-project.py`; it installs the
independent committed dependency lock. Maps 11.26.0 and Navigation 3.26.0 are aligned;
the real variant uses Maps' source package identity required by Navigation, without
also linking the binary-distribution identity. Shared Firebase pins are unchanged.

**TestFlight/App Store archives must use the navigation variant**, including any
private signing overlay. Base-project builds explicitly show navigation unavailable;
they never silently fall back to an external maps application.

The dedicated `iOS Mapbox Navigation SDK` CI check compiles unsigned arm64 Release
with the real SDK and exercises the coordinator on Simulator using no-network fakes.
The existing secret-free Debug/Release/UI/Firebase checks continue unchanged. The
download credential is scoped to the same trusted same-repository/production
environment boundary as Android; forks and Dependabot cannot receive it. It is not
an app runtime token and must never be bundled or uploaded in artifacts.

## Acceptance and limits

Unit tests cover permission denial, inaccurate/stale origins, GPS cancellation,
late route completion, teardown and retry. UI tests cover search/saved-place entry,
the in-app destination, ending navigation, failure recovery, large text and no URL
handoff. They use Debug-only, Firebase-unconfigured fixtures and make no paid trips.

Physical-device route/voice/background/arrival checks remain required with the next
signed TestFlight build. The first implementation uses Mapbox's native guidance UI;
community markers and custom Android navigation-overlay controls are a separate
parity enhancement, not part of this search-to-navigation fix.

Pricing references checked 2026-11-07:
[shared pricing](https://www.mapbox.com/pricing),
[iOS](https://docs.mapbox.com/ios/navigation/guides/pricing/),
[Android](https://docs.mapbox.com/android/navigation/guides/pricing/).
