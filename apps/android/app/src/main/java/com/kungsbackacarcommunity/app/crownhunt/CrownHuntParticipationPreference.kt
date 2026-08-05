package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this member takes part in Kronjakt (Crown Hunt) at all.
 *
 * ## What this controls
 * A single, device-local opt-out. When a member is NOT participating, the whole
 * game is taken off THEIR screen — every crown marker on the map (both the
 * hand-placed admin points and the auto-spawn layer) and every piece of
 * Crown-Hunt map UI (the tapped-crown popups). It is a visibility switch, not an
 * account change: the backend is untouched, nothing is un-earned, and flipping
 * it back on shows everything again.
 *
 * ## The default is PARTICIPATING
 * An unset preference means "taking part", so the game is visible to everyone by
 * default and a member only ever disappears from it by their own explicit
 * choice. That default lives in the ONE pure place [fromStored] below, kept free
 * of Android types so it is unit-testable without a `SharedPreferences`.
 *
 * This mirrors [com.kungsbackacarcommunity.app.map.MapZoomPreference] /
 * `ThemePreference`: a pure decision object co-located with its Android-bound
 * [CrownHuntParticipationPreferenceStore] and Compose [CrownHuntParticipationController].
 */
object CrownHuntParticipation {
    /**
     * Whether the member takes part when the preference has never been set. TRUE
     * — the game is on for everyone until they choose otherwise; an unset value
     * must never read as "opted out", or a fresh install would hide Kronjakt
     * from a member who never asked to leave it.
     */
    const val DEFAULT_PARTICIPATING: Boolean = true

    /**
     * Decodes the persisted raw value into a usable participation flag. A null
     * (nothing stored yet) falls back to [DEFAULT_PARTICIPATING]; a stored
     * boolean is taken as-is. This is the ONE place "unset means participating"
     * lives, kept pure so it is unit-testable without a `SharedPreferences`.
     */
    fun fromStored(raw: Boolean?): Boolean = raw ?: DEFAULT_PARTICIPATING
}

/**
 * Device-local persistence for the Kronjakt participation flag
 * ([CrownHuntParticipation]).
 *
 * SharedPreferences, no Firebase — mirroring
 * [com.kungsbackacarcommunity.app.map.MapZoomPreferenceStore] and
 * [com.kungsbackacarcommunity.app.design.ThemePreferenceStore]. Deliberately
 * device-local: "hide the game on THIS phone" is a per-device viewing choice,
 * it must work before any network round-trip, and syncing it would need a
 * rules/Firestore change for no user-visible benefit.
 *
 * Exposes a [StateFlow] rather than a getter so a change applies live: the map's
 * crown layers and Crown-Hunt UI re-read on the spot, hiding or showing without
 * an app restart. The flow is seeded from disk on construction, so the choice
 * survives process death.
 */
class CrownHuntParticipationPreferenceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(readStored())

    /** Whether the member is taking part; emits again on every [set]. */
    val participating: StateFlow<Boolean> = state.asStateFlow()

    /** Records the member's choice and applies it to collectors. */
    fun set(participating: Boolean) {
        // Same-value writes are a no-op for the flow but still touch disk; guard
        // both so an idempotent toggle neither re-emits nor re-writes.
        if (state.value == participating && prefs.contains(KEY_PARTICIPATING)) return
        prefs.edit().putBoolean(KEY_PARTICIPATING, participating).apply()
        state.value = participating
    }

    private fun readStored(): Boolean {
        val raw =
            if (prefs.contains(KEY_PARTICIPATING)) {
                prefs.getBoolean(KEY_PARTICIPATING, CrownHuntParticipation.DEFAULT_PARTICIPATING)
            } else {
                null
            }
        return CrownHuntParticipation.fromStored(raw)
    }

    private companion object {
        const val PREFS_NAME = "app_crown_hunt_participation"
        const val KEY_PARTICIPATING = "participating"
    }
}

/**
 * Read/write access to the participation preference for the map-layers popup,
 * which sits several levels down inside the shell rather than beside the activity
 * that owns the store.
 *
 * A CompositionLocal rather than parameters threaded through AuthenticatedApp,
 * MapHome and MapLayersPopup — exactly the pattern
 * [com.kungsbackacarcommunity.app.map.LocalMapZoomController] uses, and for the
 * same reason: it keeps the shell's already very wide parameter lists out of it.
 * Tests / previews get the no-op default below.
 */
interface CrownHuntParticipationController {
    val participating: Boolean

    fun setParticipating(participating: Boolean)
}

/**
 * Defaults to a no-op controller reporting [CrownHuntParticipation.DEFAULT_PARTICIPATING],
 * so previews and any composable rendered outside the activity's provider still
 * behave as "taking part".
 */
val LocalCrownHuntParticipationController = staticCompositionLocalOf<CrownHuntParticipationController> {
    object : CrownHuntParticipationController {
        override val participating = CrownHuntParticipation.DEFAULT_PARTICIPATING

        override fun setParticipating(participating: Boolean) = Unit
    }
}
