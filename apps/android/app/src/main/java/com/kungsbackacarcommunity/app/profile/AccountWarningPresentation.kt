package com.kungsbackacarcommunity.app.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

internal data class AccountWarningPage(
    val userId: String,
    val warnings: List<AccountWarning>,
    val nextCursor: String? = null,
)

internal interface AccountWarningsSource {
    val currentUid: String?
    fun observe(uid: String, onAuthChanged: (String?) -> Unit, onChanged: () -> Unit): () -> Unit
    suspend fun load(cursor: String?, requestStarted: Long): AccountWarningPage
}

/** In-memory, own-session state only. Never save warning reasons across profiles or sign-out. */
internal class AccountWarningPresentation(
    private val uid: String,
    private val source: AccountWarningsSource,
    private val now: () -> Long,
) {
    var warnings by mutableStateOf(emptyList<AccountWarning>())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var authenticatedUid = source.currentUid
    private var generation = 0L

    fun authChanged(currentUid: String?) {
        if (authenticatedUid == currentUid) return
        authenticatedUid = currentUid
        generation++
        warnings = emptyList()
        loading = false
        failed = false
    }

    fun expire() {
        warnings = warnings.filter { it.active(now()) }
    }

    suspend fun refresh() {
        authChanged(source.currentUid)
        if (authenticatedUid != uid) return
        val requestGeneration = generation
        fun ownsSession() = generation == requestGeneration && source.currentUid == uid
        loading = true
        failed = false
        val collected = linkedMapOf<String, AccountWarning>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        try {
            do {
                val page = source.load(cursor, now())
                if (!ownsSession()) return
                if (page.userId != uid) {
                    // A mismatched response can never establish ownership of private reasons.
                    warnings = emptyList()
                    return
                }
                for (warning in page.warnings) collected[warning.id] = warning
                // Show confirmed rows immediately, even while later pages are still pending.
                // Keep previously known rows until a complete successful refresh confirms removal.
                warnings = (warnings + page.warnings).associateBy { it.id }.values.filter { it.active(now()) }
                cursor = page.nextCursor
                check(cursor == null || cursors.add(cursor)) { "Repeated warning cursor" }
            } while (cursor != null)
            warnings = collected.values.filter { it.active(now()) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (ownsSession()) {
                expire()
                failed = true
            }
        } finally {
            if (generation == requestGeneration) {
                if (source.currentUid != uid) authChanged(source.currentUid)
                loading = false
            }
        }
    }
}
