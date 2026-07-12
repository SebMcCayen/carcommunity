package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.LocalKccStatusColors

/** Test tag on the whole map-first home, so UI tests can assert it renders. */
const val MAP_HOME_TEST_TAG = "map_home"

/**
 * Shared surface opacity for the map-overlay popups (chat + layers). Slightly
 * translucent so the live map shows through a little and all the popups read as
 * one consistent floating layer, while staying opaque enough to be readable.
 */
private const val POPUP_SURFACE_ALPHA = 0.92f

/**
 * The map-first home (Waze/Life360 style): a full-bleed [MapSurface] behind a
 * prominent "Where to?" search bar, a transient "Loading roads…" status line,
 * and a right-side stack of floating circular controls. The user's own position
 * is drawn by the surface itself (the Mapbox location puck at the real GPS
 * location), so it stays anchored to the ground when the map pans rather than
 * sliding with the screen centre.
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
 * @param moreMenuEntries the profile/account menu items (Profile, Friends,
 *   Settings, Sign out, …). Tapping the top-right profile button opens these in
 *   a transparent [Popup] *over* the map (no dimming scrim, so the map stays
 *   visible) rather than navigating to a full-screen hub. Unavailable entries
 *   (null [HubEntry.onClick]) are omitted; tapping an available entry runs its
 *   action (which navigates to that destination, or signs out) and closes the
 *   popup, and tapping outside the popup dismisses it.
 * @param unreadChatCount number of unread ("missed") chat messages shown as a
 *   badge on the floating chat control. There is no global/community-chat
 *   inbox client-side yet (chat is per-event only), so callers pass 0 as a
 *   placeholder until a global unread-count source exists. See the chat popup.
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
    onRecenter: () -> Unit,
    moreMenuEntries: List<HubEntry>,
    modifier: Modifier = Modifier,
    unreadChatCount: Int = 0,
) {
    val loadState by mapSurface.loadState.collectAsState()
    val trafficOn by mapSurface.trafficEnabled.collectAsState()
    val mapMode by mapSurface.mapMode.collectAsState()
    val is3d by mapSurface.is3d.collectAsState()

    // Keep the surface's marker in sync with the live-share state + display name.
    // Keyed on mapSurface too so the marker is re-pushed if the surface instance
    // is swapped (e.g. StubMapSurface -> a real Mapbox-backed surface).
    LaunchedEffect(mapSurface, userLabel, isLiveSharing) {
        mapSurface.setUserMarker(MapUserMarker(label = userLabel, isLiveSharing = isLiveSharing))
    }

    // Chat popup open/close is local UI state: tapping the bubble opens the
    // overlay, tapping outside it (Dialog dismiss) minimizes back to the bubble.
    var chatOpen by remember { mutableStateOf(false) }

    // Profile/account menu open/close is local UI state too: tapping the
    // top-right profile button opens the menu as a transparent Popup *over* the
    // map (so the map stays visible), tapping outside it dismisses.
    var moreOpen by remember { mutableStateOf(false) }

    // Map-layers popup open/close is local UI state: tapping the layers control
    // opens the transparent toggle sheet, tapping outside it dismisses it.
    var layersOpen by remember { mutableStateOf(false) }

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
                onOpenMore = { moreOpen = true },
                // The profile/account menu popup is composed next to the profile
                // button (inside SearchBarRow) so the Popup anchors to the
                // button's real measured bounds instead of a hard-coded offset.
                moreMenuEntries = moreMenuEntries,
                moreMenuOpen = moreOpen,
                onDismissMore = { moreOpen = false },
            )
            if (loadState == MapLoadState.Loading) {
                LoadingRoadsChip()
            }
            if (participantCount > 0) {
                ParticipantChip(count = participantCount)
            }
        }

        // Right-side floating controls, bottom-right.
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
            // 2. Map-layers control — opens the transparent layers popup
            //    (traffic / day-night / 3D toggles). Highlighted while any of
            //    those non-default layers is active, so an enabled overlay is
            //    still discoverable from the collapsed control.
            val layersActive = trafficOn || mapMode == MapMode.Night || !is3d
            CircleControl(
                icon = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.shell_layersButton),
                containerColor =
                    if (layersActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                contentColor =
                    if (layersActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                onClick = { layersOpen = true },
                modifier = Modifier.testTag(MAP_HOME_LAYERS_TAG),
            )
            // 3. Recenter / my-location — calls MapSurface.recenter().
            CircleControl(
                icon = Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.shell_recenter),
                onClick = onRecenter,
            )
            // 4. Chat bubble — opens the community-chat popup; shows a badge
            //    with the unread ("missed") message count when > 0.
            ChatCircleControl(
                unreadCount = unreadChatCount,
                onClick = { chatOpen = true },
            )
        }

        // Chat overlay: tapping outside the card dismisses it (back to bubble).
        if (chatOpen) {
            ChatPopup(
                unreadCount = unreadChatCount,
                onDismiss = { chatOpen = false },
            )
        }

        // The profile/account menu popup itself lives inside SearchBarRow,
        // anchored to the profile button's measured bounds (see ProfileMenuPopup).

        // Map-layers overlay: a transparent popup (no scrim) so the map stays
        // visible behind it while the user flips the traffic / day-night / 3D
        // toggles. Each toggle reads and writes the surface state live.
        if (layersOpen) {
            MapLayersPopup(
                trafficOn = trafficOn,
                nightMode = mapMode == MapMode.Night,
                is3d = is3d,
                onTrafficChange = { mapSurface.setTrafficEnabled(it) },
                onNightModeChange = {
                    mapSurface.setMapMode(if (it) MapMode.Night else MapMode.Day)
                },
                on3dChange = { mapSurface.set3dEnabled(it) },
                onDismiss = { layersOpen = false },
            )
        }
    }
}

/** Test tag on the floating map-layers control. */
const val MAP_HOME_LAYERS_TAG = "map_home_layers"

/** Test tag on the map-layers popup card. */
const val MAP_HOME_LAYERS_POPUP_TAG = "map_home_layers_popup"

/**
 * The transparent map-layers popup shown when the layers control is tapped.
 * Rendered as a [Popup] (not a [Dialog]) so there is NO dimming scrim and the
 * live map stays fully visible behind the toggle card; tapping outside the card
 * or pressing Back dismisses it (focusable popup). Each row reflects the current
 * surface state and updates it live:
 * - Traffic — the congestion overlay ([MapSurface.setTrafficEnabled]).
 * - Night mode — the Standard style's day/night light preset
 *   ([MapSurface.setMapMode]).
 * - 3D buildings — the tilted 3D vs flat 2D camera ([MapSurface.set3dEnabled]).
 *
 * The visible effects (traffic lines, light preset, tilt) only render on the
 * real token-provisioned Mapbox surface; on the stub the switches still move so
 * the wiring is exercised without a device.
 */
@Composable
private fun MapLayersPopup(
    trafficOn: Boolean,
    nightMode: Boolean,
    is3d: Boolean,
    onTrafficChange: (Boolean) -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    on3dChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(16.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .testTag(MAP_HOME_LAYERS_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            // Slightly translucent so the map shows through (matches the chat popup).
            color = MaterialTheme.colorScheme.surface.copy(alpha = POPUP_SURFACE_ALPHA),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.shell_layersTitle),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.shell_layersClose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LayerToggleRow(
                    label = stringResource(R.string.shell_layersTraffic),
                    checked = trafficOn,
                    onCheckedChange = onTrafficChange,
                )
                LayerToggleRow(
                    label = stringResource(R.string.shell_layersNightMode),
                    checked = nightMode,
                    onCheckedChange = onNightModeChange,
                )
                LayerToggleRow(
                    label = stringResource(R.string.shell_layers3d),
                    checked = is3d,
                    onCheckedChange = on3dChange,
                )
            }
        }
    }
}

/** A single labelled toggle row in the map-layers popup. */
@Composable
private fun LayerToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Test tag on the profile/account menu popup card. */
const val MAP_HOME_MORE_POPUP_TAG = "map_home_more_popup"

/**
 * The profile/account menu shown when the top-right profile button is tapped.
 * Rendered as a [Popup] (not a [Dialog]) so there is no dimming scrim — the map
 * stays fully visible behind the menu, which is the whole point of overlaying it
 * rather than navigating to a full-screen hub. `focusable = true` lets an
 * outside tap (or the Back gesture) dismiss it via [onDismiss].
 *
 * Must be composed next to the profile button (its direct layout parent is the
 * anchor): the [PopupPositionProvider] then receives the button's real measured
 * bounds and drops the card just below its bottom edge, end-aligned — so the
 * position holds up under font scaling, status-bar/display-cutout insets, and
 * search-row layout changes instead of relying on a hard-coded offset.
 * `anchorBounds` is already in window coordinates, so no extra insets padding
 * is applied here (that would double-count the status bar).
 *
 * Reuses the shared [HubRow] so each row matches the hub landings; unavailable
 * entries (null [HubEntry.onClick]) are omitted. Tapping an entry runs its
 * action — navigating to that destination's full route, or signing out — and
 * then closes the popup.
 */
@Composable
private fun ProfileMenuPopup(
    entries: List<HubEntry>,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider =
        remember(density) {
            val gapPx = with(density) { 8.dp.roundToPx() }
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // End-align the card to the button: the card's trailing edge
                    // sits on the button's trailing edge so the card grows toward
                    // the screen center. anchorBounds is in PHYSICAL window
                    // coordinates (left < right in both directions). LTR: trailing
                    // = physical right → card.right = anchor.right. RTL: the
                    // button sits at the physical LEFT (mirrored Row) and trailing
                    // = physical left → card.left = anchor.left. Clamped so the
                    // card never leaves the window on narrow screens.
                    val x =
                        when (layoutDirection) {
                            LayoutDirection.Ltr -> anchorBounds.right - popupContentSize.width
                            LayoutDirection.Rtl -> anchorBounds.left
                        }.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
                    val y =
                        (anchorBounds.bottom + gapPx)
                            .coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
                    return IntOffset(x, y)
                }
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            // Cap (rather than fix) the width so the card can shrink on narrow
            // windows; the Popup itself constrains content to the window size.
            modifier = Modifier.widthIn(max = 280.dp).testTag(MAP_HOME_MORE_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entries.forEach { entry ->
                    val onClick = entry.onClick
                    if (onClick != null) {
                        HubRow(entry.label, entry.icon) {
                            onClick()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** Test tag on the top-right profile/account menu button. */
const val MAP_HOME_MORE_TAG = "map_home_more"

/** Test tag on the floating chat control (bubble). */
const val MAP_HOME_CHAT_TAG = "map_home_chat"

/** Test tag on the chat overlay/popup card. */
const val MAP_HOME_CHAT_POPUP_TAG = "map_home_chat_popup"

/**
 * A [CircleControl]-styled chat bubble with an unread-count badge. Matches the
 * other floating controls (size/shape/elevation/haptics); the badge is hidden
 * when [unreadCount] is 0.
 */
@Composable
private fun ChatCircleControl(
    unreadCount: Int,
    onClick: () -> Unit,
) {
    val description =
        if (unreadCount > 0) {
            stringResource(R.string.shell_chatUnread, unreadCount)
        } else {
            stringResource(R.string.shell_chat)
        }
    BadgedBox(
        badge = {
            if (unreadCount > 0) {
                Badge {
                    Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                }
            }
        },
        // Merge the descendant CircleControl's click/label semantics into this
        // tagged node so onNodeWithTag(MAP_HOME_CHAT_TAG) exposes the click action
        // (the clickable Surface is a child of the BadgedBox).
        modifier = Modifier.testTag(MAP_HOME_CHAT_TAG).semantics(mergeDescendants = true) {},
    ) {
        CircleControl(
            icon = Icons.AutoMirrored.Filled.Chat,
            contentDescription = description,
            onClick = onClick,
        )
    }
}

/**
 * The community-chat overlay shown when the bubble is tapped. Rendered as a
 * [Popup] (not a [Dialog]) anchored to the LOWER part of the screen — just above
 * the bottom navigation bar — with NO dimming scrim, so the live map stays
 * visible behind it; its surface is slightly translucent to match the layers
 * popup. Tapping outside the card (or Back) minimizes it back to the bubble
 * (focusable popup). Content is a placeholder: there is no global/community-chat
 * inbox client-side yet (chat is per-event only), so this explains where chat
 * lives and shows the caught-up empty state. Wire real messages here once a
 * global unread-count + conversation source exists (backend, out of this lane).
 */
@Composable
private fun ChatPopup(
    unreadCount: Int,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    // Sit above the system nav-bar inset AND the app's bottom
                    // navigation bar (~80.dp tall) so the card never hides behind
                    // the tab bar, with a small breathing gap above it.
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 92.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .testTag(MAP_HOME_CHAT_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            // Slightly translucent so the map shows through (matches the layers popup).
            color = MaterialTheme.colorScheme.surface.copy(alpha = POPUP_SURFACE_ALPHA),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.shell_chatTitle),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.shell_chatClose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text =
                        if (unreadCount > 0) {
                            stringResource(R.string.shell_chatUnread, unreadCount)
                        } else {
                            stringResource(R.string.shell_chatEmpty)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.shell_chatComingSoon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    avatarUrl: String?,
    onSearch: () -> Unit,
    onVoiceSearch: () -> Unit,
    onOpenMore: () -> Unit,
    moreMenuEntries: List<HubEntry>,
    moreMenuOpen: Boolean,
    onDismissMore: () -> Unit,
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
        // This Box is the popup's anchor: composing ProfileMenuPopup inside it
        // hands the button's real measured window bounds to the popup's
        // PopupPositionProvider, so the menu tracks the button wherever the
        // search row lays it out (font scaling, insets, future layout changes).
        Box {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                onClick = onOpenMore,
                modifier = Modifier.size(48.dp).testTag(MAP_HOME_MORE_TAG),
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
            // Profile/account menu: a transparent Popup (no dimming scrim, the map
            // stays visible) anchored to this button's measured bounds; tapping
            // outside (or Back) dismisses.
            if (moreMenuOpen) {
                ProfileMenuPopup(
                    entries = moreMenuEntries,
                    onDismiss = onDismissMore,
                )
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
    modifier: Modifier = Modifier,
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
        modifier = modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}
