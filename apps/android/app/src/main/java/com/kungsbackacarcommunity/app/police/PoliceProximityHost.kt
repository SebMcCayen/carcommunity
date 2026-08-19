package com.kungsbackacarcommunity.app.police

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.design.ReactionOverlay
import com.kungsbackacarcommunity.app.design.ReactionOverlayEvent
import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Hosts the mid-screen POLICE proximity alert over the map. REUSES the shared
 * [ReactionOverlay] — the exact component and police visuals the convoy police
 * reaction uses (police glyph, red tint, ASSERTIVE TalkBack because it is a
 * safety alert) — driven here by MAP PROXIMITY to user-reported police pins
 * rather than a convoy broadcast.
 *
 * The rule (see [PoliceProximity]): when the driver comes within
 * [PoliceProximity.ALERT_RADIUS_METERS] of a live pin, pop the overlay ONCE for
 * that pin. A monitor loop reads the current location on a short cadence and, when
 * the overlay is idle, fires the FIRST not-yet-alerted in-range pin and records
 * its id; subsequent pins pop on later ticks, so two nearby patrols each get their
 * own brief pop rather than one swallowing the other. The alerted-id set is kept
 * for the lifetime of this host (a Map session), so a driver parked next to a
 * patrol is warned once, not on every tick.
 *
 * Placed over the map like the convoy reactions host; non-blocking (the overlay
 * takes no pointer input, so the map stays interactive underneath).
 *
 * @param pins the current live police pins (from [PoliceController.nearbyPolice]).
 * @param locationProvider the driver's current fix, or null when none is available.
 */
@Composable
fun PoliceProximityHost(
    pins: List<PoliceReport>,
    locationProvider: suspend () -> LatLng?,
    modifier: Modifier = Modifier,
) {
    val caption = stringResource(R.string.policeAlert_caption)
    val currentPins = rememberUpdatedState(pins)
    val provider = rememberUpdatedState(locationProvider)

    // Ids already alerted this session — the once-per-pin de-dupe. Survives
    // recompositions and pin-list refreshes; reset only when the host leaves.
    val alertedIds = remember { mutableStateOf(setOf<String>()) }
    var overlayEvent by remember { mutableStateOf<ReactionOverlayEvent?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            // Only look for a new alert while the overlay is idle, so a pop plays
            // out before the next one is chosen (and its animation is not cut off).
            if (overlayEvent == null) {
                val driver =
                    try {
                        provider.value()
                    } catch (cancellation: CancellationException) {
                        // Honour structured concurrency: let the LaunchedEffect
                        // cancel promptly rather than doing one more proximity pass
                        // during teardown. Only a real location failure → no fix.
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                val nowAlive =
                    currentPins.value.filter { it.isLiveAt(System.currentTimeMillis()) }
                val next =
                    PoliceProximity.newAlerts(
                        driver = driver,
                        pins = nowAlive,
                        alreadyAlerted = alertedIds.value,
                    ).firstOrNull()
                if (next != null) {
                    alertedIds.value = alertedIds.value + next.id
                    overlayEvent =
                        ReactionOverlayEvent(
                            id = next.id,
                            icon = Icons.Filled.LocalPolice,
                            caption = caption,
                            tint = KccPalette.errorRed,
                            contentDescription = caption,
                            // Safety alert → interrupt TalkBack, matching the
                            // convoy police reaction.
                            assertive = true,
                            // Hold ~5s (vs the shared ~1.1s social pop): a hazard
                            // warning the driver should have time to read.
                            holdMs = POLICE_ALERT_HOLD_MS,
                            // Let the driver clear it with a tap once seen (runs the
                            // normal exit fade, not an instant cut).
                            dismissOnTap = true,
                        )
                }
            }
            delay(MONITOR_INTERVAL_MS)
        }
    }

    ReactionOverlay(
        event = overlayEvent,
        onFinished = { overlayEvent = null },
        modifier = modifier,
    )
}

/**
 * How often the monitor reads the driver's location to check proximity. A few
 * seconds is responsive enough at road speed (500 m ≈ 20 s of warning) while
 * costing only a cheap last-known-fix read per tick.
 */
private const val MONITOR_INTERVAL_MS = 4_000L

/**
 * How long the "Police nearby" alert badge holds before fading — ~5s, much longer
 * than the shared ~1.1s social pop, because this is a safety warning the driver
 * needs time to register. A tap dismisses it early (see [ReactionOverlayEvent.dismissOnTap]).
 */
private const val POLICE_ALERT_HOLD_MS = 5_000L
