package com.kungsbackacarcommunity.app.live

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroPage
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import coil.compose.AsyncImage

/**
 * Live-location session control surface (Phase 12 slice 5).
 *
 * Drives the caller's own session: start sharing immediately (no time/duration
 * is chosen), stop sharing, or "hide me now" (a privacy stop that is always
 * available). Whether the user is currently sharing is derived from the observed
 * [session]; this screen is otherwise stateless.
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

    AeroPage(title = stringResource(R.string.liveLocation_screenTitle), modifier = modifier) {
        // Current sharing status.
        OutlinedCard(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)) {
            Row(modifier = Modifier.padding(KccSpacing.s4), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                Icon(Icons.Filled.Podcasts, contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(KccSpacing.s8))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                    Text(
                        text = stringResource(
                            if (sharing) R.string.liveLocation_statusSharing else R.string.liveLocation_statusNotSharing,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (sharing) {
                        Text(
                            text = stringResource(R.string.liveLocation_sessionAutoExpires),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (sharing) {
            // Stopping an active session is authenticated-gated (not
            // member-gated) on the backend, so ALWAYS offer Stop while
            // sharing — even if membership/flag state has lapsed since the
            // session started. Membership only gates STARTING (below).
            Button(
                onClick = onStop,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.padding(end = KccSpacing.s2))
                Text(text = stringResource(R.string.liveLocation_stop))
            }
        } else if (canShare) {
            // Starting is IMMEDIATE — no time/duration is chosen. Start shares at
            // once for the 6h default window; it auto-stops at 6h with no prompt to
            // prolong, and the user can Stop anytime. The backend callable still
            // takes a duration, so the fixed 6h default key is passed through
            // unchanged.
            Button(
                onClick = { onStart(LiveLocation.DEFAULT_SESSION_DURATION) },
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.padding(end = KccSpacing.s2))
            Text(text = stringResource(R.string.liveLocation_hideNow))
        }

        // Keep the privacy actions above the image, including with large text.
        session?.mainCar?.let { LiveVehicleCard(it) }

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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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

/** Only the display-safe vehicle projection already attached to this session. */
@Composable
private fun LiveVehicleCard(car: LiveMainCar) {
    val imageUrl = rememberStorageImageUrl(LocalContext.current, car.imagePath)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.2f), contentAlignment = Alignment.Center) {
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize())
            } else {
                Icon(painterResource(R.drawable.ic_generic_car), contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(64.dp))
            }
        }
        Column(Modifier.padding(KccSpacing.s4), verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
            Text(text = "${car.make} ${car.model}", style = MaterialTheme.typography.titleMedium)
            Text(text = car.modelYear.toString(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
