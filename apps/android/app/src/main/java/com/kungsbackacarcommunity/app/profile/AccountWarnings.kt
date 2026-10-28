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
import kotlinx.coroutines.CancellationException
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

/** Own profile only. A response from a previous auth session is discarded. */
@Composable
fun AccountWarningsSection(uid: String) {
    val context = LocalContext.current
    val configured = remember { FirebaseApp.getApps(context).isNotEmpty() }
    if (!configured) return
    key(uid) {
        val changes = remember { Channel<Unit>(Channel.CONFLATED) }
        var warnings by remember { mutableStateOf(emptyList<AccountWarning>()) }
        var cursor by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        var more by remember { mutableStateOf(false) }
        val auth = remember { FirebaseAuth.getInstance() }
        DisposableEffect(uid) {
            val registration = FirebaseFirestore.getInstance().collection("accountWarningSignals").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error == null && snapshot?.metadata?.isFromCache == false) { more = false; changes.trySend(Unit) }
                }
            onDispose { registration.remove(); changes.close() }
        }
        LaunchedEffect(uid) {
            changes.trySend(Unit)
            for (ignored in changes) {
                loading = true
                val append = more
                more = false
                val started = SystemClock.elapsedRealtime()
                try {
                    if (auth.currentUser?.uid != uid) { warnings = emptyList(); break }
                    val data = FirebaseFunctions.getInstance("europe-west1").getHttpsCallable("account-listWarnings")
                        .call(mapOf("cursor" to if (append) cursor else null)).await().data as? Map<*, *> ?: error("Invalid warning response")
                    if (auth.currentUser?.uid != uid || data["userId"] != uid) { warnings = emptyList(); break }
                    val rows = (data["warnings"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { row -> AccountWarning.decode(row, started) } } ?: error("Invalid warnings")
                    warnings = ((if (append) warnings else emptyList()) + rows).distinctBy { it.id }.filter { it.active(SystemClock.elapsedRealtime()) }
                    cursor = (data["meta"] as? Map<*, *>)?.get("nextCursor") as? String
                    failed = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Failed refresh must not keep showing a possibly revoked reason.
                    if (!append) warnings = emptyList()
                    failed = true
                    more = append // retry the same cursor when a later page fails
                } finally { loading = false }
            }
        }
        LaunchedEffect(uid) {
            while (true) { delay(1000); warnings = warnings.filter { it.active(SystemClock.elapsedRealtime()) } }
        }
        Column {
            Text(stringResource(R.string.accountWarnings_title), style = MaterialTheme.typography.titleMedium)
            if (loading) Text(stringResource(R.string.accountWarnings_loading))
            if (failed) Text(stringResource(R.string.accountWarnings_error))
            if (!loading && !failed && warnings.isEmpty()) Text(stringResource(R.string.accountWarnings_empty))
            for (warning in warnings) {
                Text(warning.reason)
                val expiry = warning.expiresAt?.let { runCatching { java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM).withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrDefault(it) }
                Text(if (expiry == null) stringResource(R.string.accountWarnings_untilRevoked) else "${stringResource(R.string.accountWarnings_expires)} $expiry")
            }
            if (failed) Button(onClick = { changes.trySend(Unit) }, enabled = !loading) { Text(stringResource(R.string.accountWarnings_retry)) }
            if (cursor != null && !failed) Button(onClick = { more = true; changes.trySend(Unit) }, enabled = !loading) { Text(stringResource(R.string.accountWarnings_more)) }
        }
    }
}
