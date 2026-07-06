package com.kungsbackacarcommunity.app.diagnostics

/**
 * Fire-and-forget diagnostics sink (Phase 12 slice 22). Firebase-free so the
 * crash hook and call sites are testable. Implementations must never throw and
 * must return promptly — callers may be on the dying main thread during an
 * uncaught-exception crash.
 */
fun interface DiagnosticsReporter {
    fun report(report: DiagnosticsReport)
}

/** No-op reporter used when Firebase is unavailable (CI / local validation builds). */
object NoopDiagnosticsReporter : DiagnosticsReporter {
    override fun report(report: DiagnosticsReport) = Unit
}
