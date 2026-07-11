package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.LocalKccStatusColors

/** Test tag on the whole map-first home, so UI tests can assert it renders. */
const val MAP_HOME_TEST_TAG = "map_home"

/**
 * The map-first home (Waze/Life360 style): a full-bleed [MapSurface] behind a
 * prominent "Where to?" search bar, a transient "Loading roads…" status line,
 * a right-side stack of floating circular controls, and a bottom-right "Create
 * route" pill. The user's own position is drawn by the surface itself (the
 * Mapbox location puck at the real GPS location), so it stays anchored to the
 * ground when the map pans rather than sliding with the screen centre.
 *
 * All map interaction is routed through [mapSurface]; the real Mapbox render +
 * GPS puck sit behind that same seam (the stub renders a neutral placeholder).
 * The caller's marker model is still pushed via [MapSurface.setUserMarker] so
 * the surface can reflect live-sharing state on the puck.
 *
 * @param isLiveSharing whether the live-location session is currently sharing —
 *   turns the broadcast control GREEN and is pushed to the surface so the puck
 *   can signal live sharing (wired to the real live-location state).
 * @param participantCount other members stashed to show on the map (e.g. a
 *   group-drive roster); surfaced as a small chip, preserved for the real impl.
 * @param avatarUrl resolved download URL for the signed-in user's profile
 *   picture, shown in the top-right profile button; null falls back to the
 *   generic account icon.
 */
@Composable
fun MapHome(
    mapSurface: MapSurface,
    isLiveSharing: Boolean,
    participantCount: Int,
    userLabel: String,
    avatarUrl: String? = null,
    onSearch: () -> Unit,
    onVoiceSearch: () -> Unit,
    onToggleLiveShare: () -> Unit,
    onLayers: () -> Unit,
    onRecenter: () -> Unit,
    onMusic: () -> Unit,
    onCreateRoute: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadState by mapSurface.loadState.collectAsState()
    val trafficOn by mapSurface.trafficEnabled.collectAsState()

    // Keep the surface's marker in sync with the live-share state + display name.
    // Keyed on mapSurface too so the marker is re-pushed if the surface instance
    // is swapped (e.g. StubMapSurface -> a real Mapbox-backed surface).
    LaunchedEffect(mapSurface, userLabel, isLiveSharing) {
        mapSurface.setUserMarker(MapUserMarker(label = userLabel, isLiveSharing = isLiveSharing))
    }

    // Test hook only — a testTag (not contentDescription) so the internal tag
    // string never leaks into TalkBack. The container itself is decorative.
    Box(modifier = modifier.fillMaxSize().testTag(MAP_HOME_TEST_TAG)) {
        // Full-bleed map (behind everything). The user's own position is drawn
        // by the surface itself (the Mapbox location puck at the real GPS
        // location), so it stays put when the map pans — there is deliberately
        // no centre-locked Compose "You" overlay here.
        mapSurface.Content(Modifier.fillMaxSize())

        // Top: search bar + avatar/menu, then the loading status line.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBarRow(
                avatarUrl = avatarUrl,
                onSearch = onSearch,
                onVoiceSearch = onVoiceSearch,
                onOpenMore = onOpenMore,
            )
            if (loadState == MapLoadState.Loading) {
                LoadingRoadsChip()
            }
            if (participantCount > 0) {
                ParticipantChip(count = participantCount)
            }
        }

        // Right-side floating controls + Create-route CTA, bottom-right.
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val statusColors = LocalKccStatusColors.current
            // 1. Live-location share toggle — GREEN when sharing.
            CircleControl(
                icon = Icons.Filled.Podcasts,
                contentDescription =
                    stringResource(
                        if (isLiveSharing) R.string.shell_liveShareOn else R.string.shell_liveShareOff,
                    ),
                containerColor =
                    if (isLiveSharing) statusColors.success else MaterialTheme.colorScheme.surface,
                contentColor =
                    if (isLiveSharing) Color.White else MaterialTheme.colorScheme.onSurface,
                onClick = onToggleLiveShare,
            )
            // 2. Traffic-overlay toggle — highlighted when the congestion
            //    layer is on (visible only on the real Mapbox surface).
            CircleControl(
                icon = Icons.Filled.Layers,
                contentDescription =
                    stringResource(
                        if (trafficOn) R.string.shell_trafficOn else R.string.shell_trafficOff,
                    ),
                containerColor =
                    if (trafficOn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                contentColor =
                    if (trafficOn) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                onClick = onLayers,
            )
            // 3. Recenter / my-location — calls MapSurface.recenter().
            CircleControl(
                icon = Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.shell_recenter),
                onClick = onRecenter,
            )
            // 4. Music control (stub entry point).
            CircleControl(
                icon = Icons.Filled.MusicNote,
                contentDescription = stringResource(R.string.shell_music),
                onClick = onMusic,
            )
            Spacer(Modifier.height(4.dp))
            CreateRoutePill(onClick = onCreateRoute)
        }
    }
}

@Composable
private fun SearchBarRow(
    avatarUrl: String?,
    onSearch: () -> Unit,
    onVoiceSearch: () -> Unit,
    onOpenMore: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(KccRadius.full),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSearch()
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.shell_searchIcon),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.shell_searchHint),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onVoiceSearch) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.shell_voiceSearch),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
            onClick = onOpenMore,
            modifier = Modifier.size(48.dp),
        ) {
            // Always render the AccountCircle fallback so the button never
            // shows a blank circle: it covers both the window while the Storage
            // download URL resolves (avatarUrl == null, rememberStorageImageUrl)
            // and the window while Coil fetches/decodes the bitmap (Coil doesn't
            // paint anything until the image is ready). Once the avatar bitmap is
            // displayed, the cropped AsyncImage fills the button and hides it.
            Box(
                // No clip needed here: the enclosing Surface(shape = CircleShape)
                // already crops its content (icon + avatar) to the round button.
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = stringResource(R.string.shell_menu),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp).size(28.dp),
                )
                if (avatarUrl != null) {
                    // The user's profile picture, drawn on top of the fallback
                    // icon once the bitmap is ready. Decorative: the fallback Icon
                    // underneath already labels the button, so the control keeps
                    // one stable content description in every state.
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRoadsChip() {
    Surface(
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.shell_loadingRoads),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ParticipantChip(count: Int) {
    Surface(
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = stringResource(R.string.shell_participantsSharing, count),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun CircleControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

@Composable
private fun CreateRoutePill(onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.AltRoute,
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.shell_createRoute),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
