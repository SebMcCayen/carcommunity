# Android Chat and account-menu verification — 2026-10-29

Scope: Notifications moves from Chat into the map avatar menu; Chat keeps four
message destinations and uses the existing gold palette. No iOS, backend,
subscription, release/version or production configuration changes.

## Rendered evidence

Android API 34 Google APIs x86_64 emulator, 1080×2400, density 420, font scale 1.0,
English UI. These are actual native Compose pixels from local fixtures, not mockups.

- [Profile menu](profile-menu.png): actual `MapHome` menu with all destination
  callbacks available and an unread notification fixture. The blank background is
  `StubMapSurface`, not a failed production map. Saved places remains after
  My drives to preserve the existing picker access; the requested seven account
  entries retain their relative order. The dot appears on the avatar and inbox row.
- [Dark Chat](chat-gold-dark.png) and [light Chat](chat-gold-light.png): actual
  `ChatHubRoute`/`ChannelChatContent`, populated by the existing local preview
  repository. Component captures synchronize with Compose drawing and omit system
  bars/the host's bottom navigation. Gold selected tab, own-message bubble and
  contrasting unread dots; all four labels are visible. No real messages/accounts.

The capture fixture does not send messages or load Firebase/Mapbox. It is distinct
from the actual `AuthenticatedApp` navigation test described below.

## Checks performed

- Initial targeted JVM/Robolectric run: 48 tests, zero failures/errors, one
  pre-existing skipped `ChatHubInsetsTest` case (47 executed successfully).
  Covered hub navigation/layout/state, menu entries/actions and chat colours.
- After the selected-tab unread contrast adjustment: five focused tests passed
  (`ChatHubTabLabelTest`, `ProfileMenuEntriesTest`, `ChatAccentContrastTest`).
  The layout case exercises 320/360/390 dp, SV/EN, font scales 1/1.5/2 and both
  themes. Gold/onPrimary text and incoming-link/quote ink meet 4.5:1 contrast.
- `:app:lintDebug` passed: zero errors; warnings/hints remain in the report.
- Debug app and instrumentation APKs compiled successfully after the final edits.
- [Five native cases](native-five-cases.txt) passed: actual shell notifications,
  presentation fixture, Chat→Back, Saved places→Back and profile-menu routing.
  The first notifications case needed one runner retry after its final Back
  transition. The test now waits up to five seconds for the visible map instead
  of treating a still-running Activity transition as a failure.
- [Final two native cases](native-final-two-cases.txt) both passed on first attempt
  after that test synchronization and synchronized component-capture adjustment;
  RetryRunner and crash buffers were empty. This final run uses the final APKs.

The actual-shell notifications test verifies that notification-only unread does
not mark Chat unread, Chat has no Notifications page or inbox subscription, the
avatar menu opens the existing inbox, its seen marker is updated and Back returns
to the map with the avatar badge cleared. It uses an in-memory notification item,
not a production account or write. Existing push routing remains in the standalone
inbox route; friend/event/convoy notification actions retain their existing wiring.

No physical-device, production-auth or push-delivery test is claimed. CI/review
results are recorded on the PR separately from this local evidence. No release
package was published.
