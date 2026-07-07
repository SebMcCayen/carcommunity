package com.kungsbackacarcommunity.app.diagnostics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions

/**
 * [DiagnosticsReporter] backed by the public `diagnostics-submitReport`
 * callable (europe-west1), Phase 12 slice 22. Guarded ([createIfAvailable]):
 * returns null when google-services.json is absent (CI / local validation),
 * mirroring the rest of the Firebase wiring.
 *
 * `report` is best-effort and non-blocking: it enqueues the callable and
 * swallows every failure. The report is anonymous-safe (the callable is
 * PUBLIC precisely so sign-in failures remain reportable) and carries no PII
 * beyond the already-sanitized [DiagnosticsReport].
 */
class FirebaseDiagnosticsReporter private constructor(
    private val functions: FirebaseFunctions,
) : DiagnosticsReporter {

    override fun report(report: DiagnosticsReport) {
        try {
            functions.getHttpsCallable(SUBMIT_REPORT).call(report.toData())
        } catch (error: Exception) {
            // Diagnostics must never crash the app or mask the original error.
        }
    }

    companion object {
        private const val REGION = "europe-west1"
        private const val SUBMIT_REPORT = "diagnostics-submitReport"

        fun createIfAvailable(context: Context): DiagnosticsReporter? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseDiagnosticsReporter(FirebaseFunctions.getInstance(REGION))
        }
    }
}
