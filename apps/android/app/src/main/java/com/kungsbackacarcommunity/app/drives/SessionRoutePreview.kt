package com.kungsbackacarcommunity.app.drives

/**
 * Turns the recorder's IN-MEMORY fixes into the [RoutePoint]s the end-of-session
 * summary's route map draws.
 *
 * ## Why in-memory, and not Cloud Storage
 * The summary opens the instant a live session ends — the drive has just been
 * auto-saved, but its `route.bin` upload is a fire-and-forget SECOND step
 * (DriveRecordingCoordinator.startRouteUpload) that may still be in flight, or
 * may have failed. History reads the route back from Storage
 * (`rideRoutes/{uid}/{rideId}/route.bin`), which is right there and wrong here:
 * it would make the summary wait on a network round-trip for bytes the device
 * literally just produced, and would show "route unavailable" whenever the
 * upload had not landed yet. So the summary renders the exact same fixes the
 * uploader is sending — no network, no wait, and no second copy of the data.
 *
 * ## No filtering, deliberately
 * The recorded points are mapped 1:1 in arrival order. The GPS-jump filter that
 * [DriveSummary] applies to DISTANCE/top speed is not applied here, because
 * `route.bin` stores every recorded fix and the History replay map draws every
 * decoded fix — so filtering here would make the drive's route look different in
 * the summary than in History, for the same drive.
 *
 * Pure (no Android/Compose/Firebase types) so the guard and the conversion are
 * JVM-unit-testable; the GL rendering itself is on-device only.
 */
object SessionRoutePreview {
    /**
     * The smallest route that can be drawn: a polyline needs two points, and
     * `drawRoute` returns early below this. A drive under it (a stationary or
     * permission-less session) has no road to show, so the summary shows its
     * empty-state note instead of an empty map.
     */
    const val MIN_DRAWABLE_POINTS = 2

    /**
     * The drawable route for [points], or an EMPTY list when there are fewer
     * than [MIN_DRAWABLE_POINTS] — the caller renders the empty state for that
     * case rather than composing a map that would draw nothing.
     */
    fun routePoints(points: List<RecordedPoint>): List<RoutePoint> =
        if (points.size < MIN_DRAWABLE_POINTS) {
            emptyList()
        } else {
            points.map { it.toRoutePoint() }
        }
}
