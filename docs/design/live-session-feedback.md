# Android live-session completion and driven trail

Scope: Android S01/S16 map chrome and end-of-drive feedback. No iOS, backend,
entitlement, location-recording, storage, or publication changes.

## One map stop entry

The persistent centre footer control remains the entry to stopping a live
session. Remove the duplicate text action beside the elapsed/distance/speed
band, allowing that band to use the dock's available width. Keep the existing
confirmation and convoy leave/end choices, and the background notification's
safety stop action.

## Stop → saving → confirmed save

When an eligible stop is initiated with an active recorder, show a full-screen
native result surface immediately. Pending work shows the existing localized
"Saving drive" message and car/progress treatment; it does not claim success.
The same surface transitions to the saved message and check badge only on the
recorder's definitive `Kept` outcome. Capture the ride id before clearing the
recorder so History retains its deep link.

Use a short fade/vertical entrance (240 ms) and a one-shot badge reveal (280 ms).
Animation never introduces a fake save delay or advances the result. Respect
disabled system animations with immediate transitions and a static pending badge.
The content scrolls for small screens and enlarged text. The result text is a
polite accessibility live region; icons are decorative beside that text.

Closing pending feedback only hides the screen, leaving the existing background
save/retry process running. History is enabled after confirmed success. A refused,
busy or timed-out stop retracts pending feedback if the real session is still
recording. Save failure retracts it in favour of the existing retry/refusal UI;
discard and delete clear it too. No new drive API or persistence path is added.

## Private driven trail

Use the existing crown-gold token for a 6 px round-capped core, with a 10 px
ink-black casing. The narrow dark outline separates the trail from pale roads
and traffic colours. Opacity increases from 72% at the oldest retained point to
100% at the newest point; the path no longer disappears into a zero-opacity tail.
An emissive core resists Mapbox Standard's night lighting (see the official
[style-layer guidance](https://docs.mapbox.com/android/maps/guides/styles/style-layers/)). Both layers stay below
labels, are restored on style reload, and hide together when the session ends.

Keep the existing approximately 1 km private window, GPS/jump filtering and
memory/session lifetime. The violet shared Follow-me trail remains distinct.
No point is transmitted, retained longer or saved by this styling change.

## Verification boundaries

Use focused drive/save-state, stop-attempt, breadcrumb and follow-me tests; check
pending versus confirmed screen semantics, dismissal, History enablement and
disabled-animation behaviour. Native captures use only local test state and
synthetic route points. They prove rendering, not a production stop/save round
trip, physical-device GPS performance or full visual-catalogue acceptance.

Native screenshots and verification details: [evidence](evidence/2026-10-27-live-session-feedback/README.md).
