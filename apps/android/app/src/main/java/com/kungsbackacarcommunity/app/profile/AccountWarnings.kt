package com.kungsbackacarcommunity.app.profile

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.firebase.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.time.Instant

/** Server duration projected onto elapsed realtime; device date/time is irrelevant. */
data class AccountWarning(val id: String, val reason: String, val expiresAt: String?, val deadline: Long?) {
    fun active(now: Long): Boolean = deadline == null || now < deadline
    companion object {
        fun decode(data: Map<*, *>, requestStarted: Long): AccountWarning? {
            val id = data["id"] as? String ?: return null
            val reason = data["reason"] as? String ?: return null
            val remaining = (data["remainingMillis"] as? Number)?.toLong()
            return AccountWarning(id, reason, data["expiresAt"] as? String, remaining?.let { requestStarted + it.coerceAtLeast(0) })
        }
    }
}

/** Own profile only. No placeholder or title is rendered without a confirmed active warning. */
@Composable
fun AccountWarningsSection(uid: String) {
    val context = LocalContext.current
    val configured = remember { FirebaseApp.getApps(context).isNotEmpty() }
    if (!configured) return
    val source = remember { FirebaseAccountWarningsSource() }
    AccountWarningsContent(uid, source)
}

@Composable
internal fun AccountWarningsContent(
    uid: String,
    source: AccountWarningsSource,
    now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    key(uid, source) {
        val changes = remember { Channel<Unit>(Channel.CONFLATED) }
        val presentation = remember { AccountWarningPresentation(uid, source, now) }
        DisposableEffect(uid, source) {
            val stop = source.observe(uid, { currentUid ->
                presentation.authChanged(currentUid)
                changes.trySend(Unit)
            }, { changes.trySend(Unit) })
            onDispose { stop(); changes.close() }
        }
        LaunchedEffect(uid, source) {
            changes.trySend(Unit)
            for (ignored in changes) presentation.refresh()
        }
        LaunchedEffect(uid, source) {
            while (true) { delay(1000); presentation.expire() }
        }
        AccountWarningsView(presentation, onRetry = { changes.trySend(Unit) })
    }
}

@Composable
internal fun AccountWarningsView(presentation: AccountWarningPresentation, onRetry: () -> Unit) {
    if (presentation.warnings.isEmpty()) return
    Column {
        Text(stringResource(R.string.accountWarnings_title), style = MaterialTheme.typography.titleMedium)
        for (warning in presentation.warnings) {
            Text(warning.reason)
            val expiry = warning.expiresAt?.let { runCatching { java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM).withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrDefault(it) }
            Text(if (expiry == null) stringResource(R.string.accountWarnings_untilRevoked) else "${stringResource(R.string.accountWarnings_expires)} $expiry")
        }
        if (presentation.loading) Text(stringResource(R.string.accountWarnings_loading))
        if (presentation.failed) {
            Text(stringResource(R.string.accountWarnings_error))
            Button(onClick = onRetry, enabled = !presentation.loading) { Text(stringResource(R.string.accountWarnings_retry)) }
        }
    }
}

private class FirebaseAccountWarningsSource : AccountWarningsSource {
    private val auth = FirebaseAuth.getInstance()
    override val currentUid: String? get() = auth.currentUser?.uid

    override fun observe(uid: String, onAuthChanged: (String?) -> Unit, onChanged: () -> Unit): () -> Unit {
        val authListener = FirebaseAuth.AuthStateListener { onAuthChanged(it.currentUser?.uid) }
        auth.addAuthStateListener(authListener)
        val registration = FirebaseFirestore.getInstance().collection("accountWarningSignals").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot?.metadata?.isFromCache == false) onChanged()
            }
        return { auth.removeAuthStateListener(authListener); registration.remove() }
    }

    override suspend fun load(cursor: String?, requestStarted: Long): AccountWarningPage {
        val data = FirebaseFunctions.getInstance("europe-west1").getHttpsCallable("account-listWarnings")
            .call(mapOf("cursor" to cursor)).await().data as? Map<*, *> ?: error("Invalid warning response")
        val userId = data["userId"] as? String ?: error("Invalid warning owner")
        val rows = (data["warnings"] as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.let { row -> AccountWarning.decode(row, requestStarted) }
        } ?: error("Invalid warnings")
        return AccountWarningPage(userId, rows, (data["meta"] as? Map<*, *>)?.get("nextCursor") as? String)
    }
}
