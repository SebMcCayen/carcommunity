package com.kungsbackacarcommunity.app.diagnostics

/**
 * Test double for [CrashTelemetry]: records everything instead of touching the
 * Firebase SDK, so a swallowed-error call site can assert "this produced a
 * non-fatal with the right feature and the original throwable".
 *
 * Lives in the test source set only.
 */
class RecordingCrashTelemetry : CrashTelemetry {
    val keys = mutableMapOf<String, String>()
    val breadcrumbs = mutableListOf<Pair<String, String?>>()
    val nonFatals = mutableListOf<Pair<String, Throwable>>()

    override fun setKey(key: String, value: String) {
        keys[key] = value
    }

    override fun log(event: String, detail: String?) {
        breadcrumbs += event to detail
    }

    override fun recordNonFatal(feature: String, throwable: Throwable) {
        nonFatals += feature to throwable
    }
}
