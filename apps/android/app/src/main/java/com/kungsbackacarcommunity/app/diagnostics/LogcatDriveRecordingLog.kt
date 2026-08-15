package com.kungsbackacarcommunity.app.diagnostics

import android.util.Log
import com.kungsbackacarcommunity.app.drives.DriveRecordingLog

/**
 * Logcat-backed [DriveRecordingLog] (#849 follow-up): emits the drive-recording
 * lifecycle as low-noise, structured [Log] lines under a single stable [TAG] so a
 * recurrence of "the in-progress drive vanished after a restart" can be traced
 * from a device log (`adb logcat -s DriveRecording`).
 *
 * Every call is wrapped so it can NEVER throw into the recording path — the
 * contract [DriveRecordingLog] requires. It writes nowhere but logcat: nothing
 * here is PII (only ids the app itself minted, counts, and timestamps) and
 * nothing is uploaded, so it is safe to leave on in release without a privacy or
 * quota cost. Genuine ERRORS still go through [ClientErrorReporter]; this is
 * telemetry, not error reporting, so it deliberately does not file issues.
 */
class LogcatDriveRecordingLog : DriveRecordingLog {
    override fun started(sourceSessionId: String, startedAtMillis: Long) {
        emit("start session=$sourceSessionId startedAt=$startedAtMillis")
    }

    override fun resumed(sourceSessionId: String, restoredPoints: Int, startedAtMillis: Long) {
        emit(
            "resume session=$sourceSessionId restoredPoints=$restoredPoints " +
                "startedAt=$startedAtMillis",
        )
    }

    override fun milestone(sourceSessionId: String, pointCount: Int) {
        emit("progress session=$sourceSessionId points=$pointCount")
    }

    override fun stopped(sourceSessionId: String, totalPoints: Int) {
        emit("stop session=$sourceSessionId totalPoints=$totalPoints")
    }

    override fun restoredToMap(sourceSessionId: String, points: Int) {
        emit("restore-to-map session=$sourceSessionId points=$points")
    }

    private fun emit(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            // Never let telemetry throw into the recording path.
        }
    }

    private companion object {
        const val TAG = "DriveRecording"
    }
}
