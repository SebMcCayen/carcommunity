package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.LocalKccStatusColors

/**
 * The map-first home (Map tab) — a Waze/Life360-style full-bleed map with a
 * floating search bar, a transient "Loading roads…" line, a vertical stack of
 * circular map controls, and a "Create route" CTA.
 *
 * The map itself is rendered by the injected [mapSurface] seam ([MapSurface]);
 * in this build that is a [StubMapSurface] placeholder so the whole screen
 * compiles/tests/passes CI with no device, GPS, or Mapbox token. The real map
 * drops in later behind the same seam.
 *
 * All actions except the live-location share toggle and Recenter are stubs
 * (search, voice, layers, music, create route) — they invoke their callback so
 * the shell can surface a "coming soon" hint. Recenter drives
 * [MapSurface.recenter]; the share toggle is wired by the shell to the real
 * live-location start/stop state.
 *
 * @param isSharing whether the caller is currently sharing live location; the
 *   share control turns green when true.
 */
@Composable
fun MapFirstHomeScreen(
    mapSurface: MapSurface,
    isSharing: Boolean,
    onToggleShare: () -> Unit,
    onSearch: () -> Unit,
    onVoiceSearch: () -> Unit,
    onLayers: () -> Unit,
    onMusic: () -> Unit,
    onCreateRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadState by mapSurface.loadState.collectAsState()
    val userMarker by mapSurface.userMarker.collectAsState()
    val haptics = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxSize()) {
        // Full-bleed map placeholder behind every overlay.
        mapSurface.Content(Modifier.fillMaxSize())

        // Centered "You / Online" callout above a blue dot with an accuracy halo.
        if (userMarker != null) {
            UserMarkerOverlay(
                label = userMarker!!.label,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Top: rounded search bar + transient loading line.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(onSearch = onSearch, onVoiceSearch = onVoiceSearch)
            if (loadState == MapLoadState.Loading) {
                LoadingRoadsLine()
            }
        }

        // Right floating control stack, sitting just above the Create-route CTA.
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val shareTint =
                if (isSharing) LocalKccStatusColors.current.success else Color.Unspecified
            CircleControl(
                icon = Icons.Filled.Sensors,
                contentDescription =
                    stringResource(
                        if (isSharing) R.string.shell_liveShareOn else R.string.shell_liveShareOff,
                    ),
                tint = shareTint,
                testTag = TAG_SHARE,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleShare()
                },
            )
            CircleControl(
                icon = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.shell_mapLayers),
                testTag = TAG_LAYERS,
                onClick = onLayers,
            )
            CircleControl(
                icon = Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.shell_recenter),
                testTag = TAG_RECENTER,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    mapSurface.recenter()
                },
            )
            CircleControl(
                icon = Icons.Filled.MusicNote,
                contentDescription = stringResource(R.string.shell_music),
                testTag = TAG_MUSIC,
                onClick = onMusic,
            )
        }

        // Bottom-right "Create route" CTA pill.
        ExtendedFloatingActionButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCreateRoute()
            },
            icon = { Icon(Icons.Filled.Route, contentDescription = null) },
            text = { Text(stringResource(R.string.shell_createRoute)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .testTag(TAG_CREATE_ROUTE),
        )
    }
}

@Composable
private fun SearchBar(onSearch: () -> Unit, onVoiceSearch: () -> Unit) {
    Surface(
        onClick = onSearch,
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TAG_SEARCH),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = stringResource(R.string.shell_voiceSearch),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onVoiceSearch)
                        .testTag(TAG_VOICE),
            )
        }
    }
}

@Composable
private fun LoadingRoadsLine() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(TAG_LOADING),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.shell_loadingRoads),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun UserMarkerOverlay(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = MaterialTheme.shapes.small,
            shadowElevation = 2.dp,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        // Accuracy halo behind the blue dot.
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                modifier = Modifier.size(28.dp),
            ) {}
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp),
            ) {}
        }
    }
}

@Composable
private fun CircleControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor =
            if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.size(48.dp).testTag(testTag),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

/** Test tags for the map-first home controls (used by Compose UI tests). */
const val TAG_SEARCH = "shell_search_bar"
const val TAG_VOICE = "shell_voice"
const val TAG_LOADING = "shell_loading_roads"
const val TAG_SHARE = "shell_control_share"
const val TAG_LAYERS = "shell_control_layers"
const val TAG_RECENTER = "shell_control_recenter"
const val TAG_MUSIC = "shell_control_music"
const val TAG_CREATE_ROUTE = "shell_create_route"

@Preview(name = "Map-first home", showBackground = true)
@Composable
private fun MapFirstHomeScreenPreview() {
    KccTheme {
        MapFirstHomeScreen(
            mapSurface = StubMapSurface(initialState = MapLoadState.Loaded, autoLoad = false),
            isSharing = false,
            onToggleShare = {},
            onSearch = {},
            onVoiceSearch = {},
            onLayers = {},
            onMusic = {},
            onCreateRoute = {},
        )
    }
}
