# Prompt, consistent crown readiness

This is UX point 5, built on the stopped-window rules in point 4. The four-second physical evidence minimum, 0.5 m/s tolerance, 12-second maximum pair gap and 10-second server current-fix freshness remain unchanged.

## Removed avoidable waits

Android previously awaited `getCurrentLocation`, selected proof, then slept another two seconds. Slow or missing provider responses blocked readiness reevaluation as well as sampling. Closed-card prewarm used another three-second sleep and ended permanently after its first usable pair, even when that proof subsequently expired.

Android now uses one cancellable foreground callback request, with a one-second requested local cadence, zero displacement filter and no extra initial accuracy wait. Raw timestamps are preserved. Every actual fix updates readiness immediately; a separate 250 ms local clock expires evidence even if the provider never replies. This clock publishes only changed snapshots, runs only for an open unanswered card, and does not write location or contact the backend.

The existing coarse range read also feeds its actual timestamped sample into evidence, rather than throwing away the metadata. Prewarm is balanced power, limited to relevant nearby crowns, pauses after a usable proof, and renews before that proof reaches eight seconds old. A missing-GPS burst lasts at most eight seconds followed by a three-second off interval. A moving prewarm sample ends the burst. Opening the card switches to active foreground evidence; closing, covering the map, backgrounding or changing identity cancels its request. A confirmed result stops the active request.

iOS reuses its ordinary map position stream. A short-lived crown demand prevents Core Location automatically pausing while the member is standing still and reasserts updates when a burst begins. It releases that demand when a nearby stop is warm or the relevant lifecycle ends. Accepted samples update observed readiness directly, removing reliance on the view's one-second presentation timer. The shell cancels crown positioning immediately on background/cover, including while an earlier network refresh is pending.

## Fresh interaction without restarting dwell

Stored evidence may remain useful during the prewarm pause, but it is not permission to act on an old position. Opening/collecting requires the latest actual fix to be no older than two seconds on both clients. An active Android request asks for no initial historical fix. After a pause, one fresh stationary fix can reuse the already-earned four-second history; movement invalidates it through the stopped-window barrier. Poll/receipt times never make a cached provider sample fresh, and equal-timestamp callbacks cannot count twice.

Both native selectors choose the freshest current with an eligible stationary partner. Accuracy ranks partners only after eligibility, so an accurate but moving/approach partner cannot strand another already-usable pair. There is no network check before enabling Collect. The claim resnapshots the freshest eligible pair immediately before submission.

## Delayed claim transport

A cold or slow callable can report `position_too_old` for evidence which was fresh at the tap. Each client permits at most one automatic recovery, only after that definitive non-award, with a newer locally ready physical pair and a new idempotency key. An unknown transport failure retains its current key for a manual retry. A second stale response leaves the card/evidence recovery available rather than requiring dismiss/reopen. No automatic retry creates a position sample or bypasses the stop/geofence gates. No warm instances or deployment changes are included.

## Validation and limits

Regression cases cover callback phases, stopping before/during card opening, improving accuracy, unusable precise partners, repeated cached fixes, missing callbacks, clock expiry, GPS-demand cancellation, bounded prewarm renewal, departure during a prewarm pause and delayed stale-claim recovery/idempotency.

For a controlled one-second physical fix stream, readiness becomes available on the first four-second pair at each tested phase. The old three-second prewarm spacing could only form its first pair after six seconds, even with instantaneous reads. The new path removes that extra two-second quantization and the read-then-sleep delay; it does not shorten the physical stop requirement. A warm stop with a sufficiently fresh latest fix can open ready, while an older cache needs one actual new fix rather than another four-second dwell.

These are code-path and synthetic-clock results, not measured phone/GPS/cloud latency. Core Location and fused positioning do not guarantee exact delivery intervals, poor signal may take longer, and GPS cannot establish mechanical standstill below its noise tolerance. Physical Android/iOS tests must measure actual stop-to-unlock time, stationary scatter, slow departure, screen covering/background return, battery and delayed transport.
