package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.delay

@Composable
fun rememberChatCooldownSeconds(gate: ChatSendCooldown): Int {
    val deadline by gate.deadlineMillis.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val seconds by produceState(gate.remainingSeconds(), gate, deadline, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                value = gate.remainingSeconds()
                if (value == 0) break
                delay(250)
            } while (true)
        }
    }
    return seconds
}

/** The stable status announces once; the readable numeric countdown is not a live region. */
@Composable
fun ChatCooldownNotice(seconds: Int) {
    var shown by remember { mutableStateOf(false) }
    val active = seconds > 0
    LaunchedEffect(active) {
        if (active) shown = true
        else if (shown) { delay(3_000); shown = false }
    }
    if (active || shown) Column(Modifier.testTag("chat-flood-notice")) {
        Text(stringResource(if (active) R.string.chat_flood_paused else R.string.chat_flood_ready),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (active) Text(pluralStringResource(R.plurals.chat_flood_wait, seconds, seconds),
            Modifier.testTag("chat-flood-countdown"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
