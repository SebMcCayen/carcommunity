package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kungsbackacarcommunity.app.live.LiveActionStatus
import com.kungsbackacarcommunity.app.live.LiveLocationScreen
import com.kungsbackacarcommunity.app.live.LiveSessionDuration
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import com.kungsbackacarcommunity.app.live.LiveSessionStatus
import com.kungsbackacarcommunity.app.shell.PlaceActionsSheet
import com.kungsbackacarcommunity.app.shell.SaveLocationDialog

/**
 * Screenshot integration entry point. The host owns theme, test captions and capture.
 * These are production components with local state only: no map SDK, repositories,
 * image downloads, API calls or production writes. Place coordinates are a test fixture.
 */
@Composable
fun Wave2MapPreview(screen: String) {
    when (screen) {
        "map-place" -> {
            var saving by remember { mutableStateOf(false) }
            if (saving) {
                SaveLocationDialog(initialName = "", coordinateHint = "57.4900, 12.0800",
                    onSave = { saving = false }, onDismiss = { saving = false })
            } else {
                PlaceActionsSheet(placeName = null, coordinateText = "57.4900, 12.0800",
                    onNavigate = {}, onCopy = {}, onSave = { saving = true }, onDismiss = {})
            }
        }
        "map-live" -> {
            val activeSession = remember {
                LiveSessionInfo(sessionId = "android-test-only", status = LiveSessionStatus.ACTIVE,
                    duration = LiveSessionDuration.SIX_HOURS, expiresAtMillis = 21_600_000L)
            }
            var session by remember { mutableStateOf<LiveSessionInfo?>(activeSession) }
            LiveLocationScreen(session = session, nowMillis = 0L,
                actionStatus = LiveActionStatus.Idle, canShare = true,
                onStart = { session = activeSession }, onStop = { session = null },
                onHideMeNow = { session = null })
        }
        else -> error("Unknown map preview case: $screen")
    }
}
