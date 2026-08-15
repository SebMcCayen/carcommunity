package com.kungsbackacarcommunity.app.drives

/**
 * Lightweight, structured lifecycle log for the crash-resilient drive recording
 * (#849 follow-up).
 *
 * ## Why this exists
 * The recurring "my in-progress drive vanished after I reopened the app" report
 * is hard to diagnose after the fact because the whole flow is silent: the
 * on-disk journal ([DriveRecordingJournal]) persists fixes and the coordinator
 * resumes them, but nothing records whether a relaunch actually FOUND a journal,
 * how many points it restored, or whether the on-screen route was redrawn. This
 * seam emits a handful of coarse, structured events across the recording
 * lifecycle so a future recurrence is diagnosable from a device log — without
 * per-fix logcat spam.
 *
 * ## Contract
 * - Firebase-/Android-free interface, mirroring
 *   [com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter], so
 *   [DriveRecordingCoordinator] stays pure Kotlin and JVM-unit-testable with a
 *   fake, and tests can assert exactly which events fired.
 * - Implementations must NEVER throw: a logging failure must not disturb the
 *   recording it is observing.
 * - LOW NOISE: lifecycle transitions only, plus a coarse point milestone every
 *   [MILESTONE_INTERVAL] fixes — never one event per GPS fix.
 *
 * Production wires [com.kungsbackacarcommunity.app.diagnostics.LogcatDriveRecordingLog];
 * tests/previews use [NoopDriveRecordingLog].
 */
interface DriveRecordingLog {
    /** A FRESH drive recording began (no journal to resume). */
    fun started(sourceSessionId: String, startedAtMillis: Long)

    /**
     * A relaunched-but-still-live session RESUMED a persisted drive from the
     * on-disk journal — [restoredPoints] fixes were rehydrated into the recorder,
     * carrying the original [startedAtMillis]. The key event for diagnosing the
     * "drive vanished on restart" report: its presence proves the journal survived
     * and how much of the drive came back.
     */
    fun resumed(sourceSessionId: String, restoredPoints: Int, startedAtMillis: Long)

    /**
     * A coarse progress marker: the recording has accumulated [pointCount] accepted
     * fixes (emitted every [MILESTONE_INTERVAL] points). Confirms fixes are being
     * recorded + journalled during a long drive without logging every one.
     */
    fun milestone(sourceSessionId: String, pointCount: Int)

    /** Recording STOPPED with [totalPoints] fixes accumulated (session ended). */
    fun stopped(sourceSessionId: String, totalPoints: Int)

    /**
     * The on-screen route tail was RESTORED to the map from a resumed drive —
     * [points] fixes were seeded into the breadcrumb after a relaunch. Closes the
     * loop the user actually perceives: not just "the data came back" but "the
     * route was redrawn".
     */
    fun restoredToMap(sourceSessionId: String, points: Int)

    companion object {
        /** Emit a [milestone] every this-many accepted fixes. Coarse by design. */
        const val MILESTONE_INTERVAL = 250
    }
}

/** No-op [DriveRecordingLog] for tests/previews and the config-less path. */
object NoopDriveRecordingLog : DriveRecordingLog {
    override fun started(sourceSessionId: String, startedAtMillis: Long) = Unit

    override fun resumed(sourceSessionId: String, restoredPoints: Int, startedAtMillis: Long) = Unit

    override fun milestone(sourceSessionId: String, pointCount: Int) = Unit

    override fun stopped(sourceSessionId: String, totalPoints: Int) = Unit

    override fun restoredToMap(sourceSessionId: String, points: Int) = Unit
}
