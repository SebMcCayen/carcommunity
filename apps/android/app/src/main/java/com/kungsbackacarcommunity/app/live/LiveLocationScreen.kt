package com.kungsbackacarcommunity.app.live

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Live-location session control surface (Phase 12 slice 5).
 *
 * Drives the caller's own session: choose a duration and start sharing, stop
 * sharing, or "hide me now" (a privacy stop that is always available). Whether
 * the user is currently sharing is derived from the observed [session]; this
 * screen is otherwise stateless apart from the pending duration selection.
 *
 * Sharing is gated on [canShare] (liveLocation flag AND active membership),
 * mirroring the backend live.startSession member check. "Hide me now" is never
 * gated — removing your own position must always work.
 *
 * @param nowMillis current wall-clock millis, injected so the sharing check is
 *   deterministic under test.
 */
@Composable
fun LiveLocationScreen(
    session: LiveSessionInfo?,
    nowMillis: Long,
    actionStatus: LiveActionStatus,
    canShare: Boolean,
    onStart: (LiveSessionDuration) -> Unit,
    onStop: () -> Unit,
    onHideMeNow: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val sharing = LiveLocation.isSharing(session, nowMillis)
    val busy = actionStatus == LiveActionStatus.Working
    var selectedDuration by rememberSaveable { mutableStateOf(LiveSessionDuration.ONE_HOUR) }

    AeroPage(title = stringResource(R.string.liveLocation_screenTitle), modifier = modifier) {
        // Current sharing status.
        Text(
            text =
                stringResource(
                    if (sharing) {
                        R.string.liveLocation_statusSharing
                    } else {
                        R.string.liveLocation_statusNotSharing
                    },
                ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (sharing) {
            Text(
                text = stringResource(R.string.liveLocation_sessionAutoExpires),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sharing) {
            // Stopping an active session is authenticated-gated (not
            // member-gated) on the backend, so ALWAYS offer Stop while
            // sharing — even if membership/flag state has lapsed since the
            // session started. Membership only gates STARTING (below).
            Button(
                onClick = onStop,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.liveLocation_stop))
            }
        } else if (canShare) {
            Text(
                text = stringResource(R.string.liveLocation_durationLabel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LiveDurationPicker(
                selected = selectedDuration,
                enabled = !busy,
                onSelect = { selectedDuration = it },
            )
            Button(
                onClick = { onStart(selectedDuration) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.liveLocation_start))
            }
        } else {
            // Membership gate for STARTING — mirrors the backend member
            // check on live.startSession. Copy is specific to sharing your
            // own position; "hide me now" below stays available, and Stop
            // above stays available whenever a session is active.
            InfoCard(
                title = stringResource(R.string.subscription_teaserTitle),
                body = stringResource(R.string.liveLocation_memberRequiredToShare),
            )
        }

        // Privacy action — never gated, always offered.
        OutlinedButton(
            onClick = onHideMeNow,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.liveLocation_hideNow))
        }

        if (actionStatus == LiveActionStatus.Failed) {
            Text(
                text = stringResource(R.string.liveLocation_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        InfoCard(
            title = stringResource(R.string.liveLocation_whoCanSeeTitle),
            body = stringResource(R.string.liveLocation_whoCanSeeBody),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.liveLocation_privacyOptional),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.liveLocation_privacyTimeLimited),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.liveLocation_privacyStopAnytime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Live location – member, not sharing", showBackground = true)
@Composable
private fun LiveLocationPreview() {
    KccTheme {
        LiveLocationScreen(
            session = null,
            nowMillis = 0L,
            actionStatus = LiveActionStatus.Idle,
            canShare = true,
            onStart = {},
            onStop = {},
            onHideMeNow = {},
        )
    }
}

@Preview(name = "Live location – gated", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LiveLocationGatedPreview() {
    KccTheme {
        LiveLocationScreen(
            session = null,
            nowMillis = 0L,
            actionStatus = LiveActionStatus.Idle,
            canShare = false,
            onStart = {},
            onStop = {},
            onHideMeNow = {},
        )
    }
}
