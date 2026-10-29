package com.kungsbackacarcommunity.app.clubs

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ClubCalendarSubscriptionControl(
    clubId: String,
    repository: ClubCalendarSubscriptionRepository? = remember { FirebaseCalendarSubscriptionRepository.createIfAvailable() },
) {
    var open by remember(clubId) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("club_calendar_subscribe")) {
        Text(stringResource(R.string.clubCalendar_subscribe))
    }
    if (open) key(clubId, repository) {
        ClubCalendarSubscriptionDialog(clubId, repository) { open = false }
    }
}

/** The membership-keyed parent disposes this state on logout/removal; never save a bearer in saved state. */
@Composable
private fun ClubCalendarSubscriptionDialog(
    clubId: String, repository: ClubCalendarSubscriptionRepository?, onDismiss: () -> Unit,
) {
    var subscription by remember { mutableStateOf<CalendarSubscription?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun request(action: String) {
        if (busy) return
        busy = true; failed = false; changed = false; copied = false
        val version = subscription?.version
        subscription = subscription?.copy(url = null)
        scope.launch {
            try {
                val repo = repository ?: throw ClubException("unavailable")
                subscription = repo.manage(clubId, action, if (action == "status") null else version)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                subscription = null
                changed = (e as? FirebaseFunctionsException)?.code == FirebaseFunctionsException.Code.ABORTED
                failed = true
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { request("status") }
    val copyLabel = stringResource(R.string.clubCalendar_clipboardLabel)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clubCalendar_subscribe)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.clubCalendar_explanation))
            Text(stringResource(R.string.clubCalendar_privacy), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.clubCalendar_androidInstructions))
            Text(stringResource(R.string.clubCalendar_appleInstructions), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("club_calendar_busy"))
            if (failed) {
                Text(stringResource(if (changed) R.string.clubCalendar_changed else R.string.clubCalendar_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { request("status") }, enabled = !busy) { Text(stringResource(R.string.clubs_retry)) }
            }
            subscription?.let { current ->
                Text(stringResource(if (current.active) R.string.clubCalendar_active else R.string.clubCalendar_inactive))
                if (current.url == null) {
                    if (current.active) Text(stringResource(R.string.clubCalendar_replaceWarning), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { request("issue") }, enabled = !busy, modifier = Modifier.testTag("club_calendar_issue")) {
                        Text(stringResource(if (current.active) R.string.clubCalendar_replace else R.string.clubCalendar_create))
                    }
                } else {
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clipboard != null) {
                            val clip = ClipData.newPlainText(copyLabel, current.url)
                            clip.description.extras = PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                            clipboard.setPrimaryClip(clip)
                            copied = true
                        }
                    }, enabled = !busy, modifier = Modifier.testTag("club_calendar_copy")) { Text(stringResource(R.string.clubCalendar_copy)) }
                    if (copied) Text(stringResource(R.string.clubCalendar_copied), modifier = Modifier.testTag("club_calendar_copied"))
                }
                if (current.active) TextButton(onClick = { request("revoke") }, enabled = !busy,
                    modifier = Modifier.testTag("club_calendar_revoke")) { Text(stringResource(R.string.clubCalendar_revoke)) }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clubs_close)) } },
    )
}
