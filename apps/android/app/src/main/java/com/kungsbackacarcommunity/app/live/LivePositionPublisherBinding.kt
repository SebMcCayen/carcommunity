package com.kungsbackacarcommunity.app.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.kungsbackacarcommunity.app.location.LocationAccess

/**
 * A server-started convoy session needs the same publisher as a manual session.
 * Retry after permission/settings recovery and foreground re-entry, and also
 * when one active session replaces another without a false sharing emission.
 * The foreground-service facade is idempotent for an already-running owner.
 */
@Composable
fun BindLivePositionPublisher(
    sharing: Boolean,
    uid: String,
    sessionId: String?,
    locationAccess: LocationAccess,
    recoveryKey: Int,
    startPublisher: (String) -> Unit,
) {
    val latestStart by rememberUpdatedState(startPublisher)
    LaunchedEffect(sharing, uid, sessionId, locationAccess, recoveryKey) {
        if (sharing && uid.isNotBlank() && !sessionId.isNullOrBlank() && locationAccess == LocationAccess.GRANTED) {
            latestStart(uid)
        }
    }
}
