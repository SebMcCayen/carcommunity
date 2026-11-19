# Map presentation transitions (UX point 7)

Android and iOS use the same 200 ms keyed-opacity policy for ordinary map object lifetimes. Identity comes from the source ID, independent of order, data refresh or geographic interpolation. Refreshing metadata reads the latest payload without restarting entry, buffering location, recreating all native annotations or rasterizing images each frame. Android's existing CrownMarkerAnimator remains the sole owner of native crown lifetime animation.

## Visual units and placement

A driver's photo, leader segments and true-position dot share one opacity clock. Geographic coordinates continue on point 1's presentation timeline. Point 2's stable placement token controls safe discrete relocation: a new slot appears at zero opacity and fades in at its new position, without sliding across the protected own-position zone. Static group badges and their leaders use the same rule for changes to their own-position avoidance direction. Opacity is applied to the small photo/badge and to Canvas primitives; a separate full-screen transparency buffer is not created for every driver.

Android native incidents, community events and sponsored billboards reconcile by item ID, updating a changed annotation in place. Opacity-only frames reuse cached marker images. The sponsored foot anchor, labels and tap contract remain unchanged. iOS applies the same lifetime adapter to its existing combined camera/incident/crown density presentation, sponsored billboards, driver photos and convoy edge arrows. The docked map reading panels fade on entry and ordinary user dismissal; programmatic disposal remains immediate and cancels pending dismissal work.

## Authorization, interaction and lifecycle

A fade never grants visibility. Driver retention is restricted to the CURRENT permitted roster. A UID removed for sign-out, blocking, hide or authorization loss is removed synchronously from rendering; no old photo payload is exposed during an exit. An ordinary on-screen/off-screen transition may briefly retain a driver only while that UID remains permitted. Public static markers may retain their last presentation for at most the finite exit duration. Outgoing objects stop accepting taps immediately; queued native hits also check the current source roster and grouping mask before opening details. The underlying original detail sources and authorization continue to own all actions.

Animation tasks end when their entries settle. Covered/inactive/background maps clear retained presentation and stop the opacity ticker. Android system animator disabling and iOS Reduce Motion settle immediately, including placement changes. There is no additional position delay and no permanent 60 fps opacity loop.

## Verification and remaining platform gap

Pure lifecycle tests on both platforms cover stable refresh, finite exit/noninteraction, cancellation/reentry, privacy flush, safe placement and reduced motion. Android additionally tests public-marker retention, keyed native update/retry behavior, actual source-guarded event/billboard/incident taps, and Compose lifecycle wiring. Native iOS compilation and XCTest execution require macOS CI; Android compilation and actual overlay fixtures validate the SDK-facing integration.

The existing iOS app has an event marker data seam but no community-event map renderer. This point improves the existing Android event layer; it does not claim iOS event rendering/fade parity. Adding that missing iOS feature is approved as a separate ninth parity PR, which can reuse MapPresenceLayer. Basemap labels and continuously updated route geometry remain owned by their SDK/source and receive no artificial lifetime animation per GPS update.
