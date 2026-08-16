package com.kungsbackacarcommunity.app.incidents

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's chosen MAX AGE for Trafikverket traffic alerts on the map — "how old
 * an alert may be and still be drawn".
 *
 * ## Why this exists
 * Trafikverket's open feed carries long-lived entries (multi-week roadworks, old
 * standing restrictions). Some drivers want only what is happening now; others
 * want the full picture. This is a per-device DISPLAY filter that hides
 * Trafikverket alerts older than the selected age, with an explicit
 * [IncidentAgeOption.ALL] "no limit" choice so nothing is ever silently dropped
 * from a user who wants it.
 *
 * ## Scope of the filter (deliberate)
 *  - Only **Trafikverket-sourced** incidents are aged out. A member's own report is
 *    the reporter's recent, first-hand observation — the whole rationale here is
 *    Trafikverket's STALE BACKLOG, not fresh crowd reports — so member incidents
 *    are always shown regardless of the setting.
 *  - Age is measured off [Incident.postedAtIso], the instant Trafikverket
 *    ORIGINALLY posted, NOT the backend sync time ([Incident.createdAtIso], which
 *    for an import is merely when the 30-min importer wrote the doc). Aging by the
 *    sync time would treat a days-old roadwork as brand new.
 *  - A Trafikverket row whose [Incident.postedAtIso] is missing/unparseable (older
 *    docs synced before the upstream time was carried) is **shown**: its age
 *    cannot be determined, so it is not hidden. Erring toward showing is the safe
 *    direction for a road-safety layer, and it matches the detail sheet, which
 *    hides the age LINE rather than guessing an age.
 *
 * ## Purity
 * [DEFAULT], [fromStoredOrdinal], [isVisible] and [visible] are pure Kotlin — no
 * Android or Mapbox types touch them — so the decision is JVM-unit-testable off
 * device. The [IncidentAgeFilterPreferenceStore] and [IncidentAgeFilterController]
 * below ARE Android-bound (a `SharedPreferences` and a Compose `CompositionLocal`),
 * mirroring how `MapZoomPreference.kt` co-locates its pure logic with its Android
 * store.
 */
enum class IncidentAgeOption(val maxAgeMillis: Long?) {
    HOURS_6(6L * 60 * 60 * 1000),
    HOURS_12(12L * 60 * 60 * 1000),
    DAY_1(24L * 60 * 60 * 1000),
    DAYS_3(3L * 24 * 60 * 60 * 1000),
    WEEK_1(7L * 24 * 60 * 60 * 1000),
    DAYS_30(30L * 24 * 60 * 60 * 1000),

    /** No limit — every alert is shown, however old. The safe "show everything" end. */
    ALL(null),
}

object IncidentAgeFilter {
    /**
     * The setting applied when nothing has been chosen yet: [IncidentAgeOption.ALL].
     *
     * "Show everything" so the filter never silently hides an alert until the user
     * deliberately opts into filtering — the same principle as the resting-zoom
     * slider defaulting to the app's original framing (an untouched control changes
     * nothing). A user annoyed by Trafikverket's backlog turns the knob down; a user
     * who wants it all does nothing.
     */
    val DEFAULT: IncidentAgeOption = IncidentAgeOption.ALL

    /** The options in slider order, strictest ([HOURS_6]) first, [ALL] last. */
    val orderedOptions: List<IncidentAgeOption> = IncidentAgeOption.entries.toList()

    /**
     * Ticks BETWEEN the two ends for a Compose `Slider` whose value is an option
     * index (`Slider`'s `steps` excludes the endpoints). With 7 options this is 5.
     */
    val sliderSteps: Int = (orderedOptions.size - 2).coerceAtLeast(0)

    /**
     * Maps a raw Compose `Slider` value (an OPTION INDEX, possibly mid-drag and so
     * fractional or out of range) to the [IncidentAgeOption] it lands on. Rounds to
     * the nearest notch and clamps into [orderedOptions], so a partial drag resolves
     * to a real option and an over-drag never indexes past the ends.
     *
     * Kept here, pure, so the layers-popup slider can render its LIVE label from the
     * finger position while dragging (not only the committed value) and commit the
     * SAME resolved option on release — both through this one function — and so the
     * rounding is unit-testable off device.
     */
    fun optionForSliderIndex(value: Float): IncidentAgeOption {
        if (orderedOptions.isEmpty()) return DEFAULT
        val idx = Math.round(value).coerceIn(0, orderedOptions.lastIndex)
        return orderedOptions[idx]
    }

    /**
     * Decodes a persisted enum NAME into an option. A null (nothing stored yet) or
     * an unrecognised name (a corrupt/hand-edited pref, or an option renamed/removed
     * in a later build) falls back to [DEFAULT] rather than throwing. Names, not
     * ordinals, so reordering or inserting options never silently reinterprets a
     * stored choice — the same robust scheme `ThemePreference.fromStoredName` uses.
     * This is the ONE place "unset means show everything" lives, kept pure so it is
     * unit-testable without a `SharedPreferences`.
     */
    fun fromStoredName(name: String?): IncidentAgeOption =
        IncidentAgeOption.entries.find { it.name == name } ?: DEFAULT

    /**
     * Whether [incident] should be DRAWN given the selected [option] and the current
     * time [nowMillis]. Pure; see the class KDoc for the scope rules this encodes:
     *  - [IncidentAgeOption.ALL] ⇒ always visible (no filtering at all);
     *  - a non-Trafikverket (member) incident ⇒ always visible;
     *  - a Trafikverket incident with no parseable [Incident.postedAtIso] ⇒ visible
     *    (age unknown, so not hidden);
     *  - otherwise visible iff its age is at most the limit. The boundary is
     *    INCLUSIVE: an alert exactly at the limit is still shown. A [postedAtIso] in
     *    the future (device/server clock skew) yields a non-positive age and is
     *    treated as brand new (visible).
     */
    fun isVisible(incident: Incident, nowMillis: Long, option: IncidentAgeOption): Boolean {
        val maxAgeMillis = option.maxAgeMillis ?: return true
        if (incident.source != INCIDENT_SOURCE_TRAFIKVERKET) return true
        val posted = parseInstant(incident.postedAtIso) ?: return true
        val ageMillis = nowMillis - posted.toEpochMilli()
        if (ageMillis <= 0) return true
        return ageMillis <= maxAgeMillis
    }

    /**
     * Filters [incidents] to those [isVisible] under [option] at [nowMillis]. Short-
     * circuits [IncidentAgeOption.ALL] to the same list instance so the common
     * "no limit" case allocates nothing.
     */
    fun visible(
        incidents: List<Incident>,
        nowMillis: Long,
        option: IncidentAgeOption,
    ): List<Incident> =
        if (option.maxAgeMillis == null) {
            incidents
        } else {
            incidents.filter { isVisible(it, nowMillis, option) }
        }

    /**
     * Parses the backend's ISO-8601 instant, returning null for anything missing,
     * blank, or malformed. Never throws — a bad timestamp means "age unknown", which
     * [isVisible] resolves to "show", not a crash. Same lenient parse the detail
     * sheet uses for the age line.
     */
    private fun parseInstant(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * Device-local persistence for the Trafikverket alert max-age filter
 * ([IncidentAgeFilter]).
 *
 * SharedPreferences, no Firebase — mirroring [com.kungsbackacarcommunity.app.map.MapZoomPreferenceStore]
 * and [com.kungsbackacarcommunity.app.design.ThemePreferenceStore]. Deliberately
 * device-local and NOT account state: "on this phone I don't want the old alerts"
 * belongs to the phone, it must work before sign-in, and syncing it would need a
 * rules/Firestore change for no user-visible benefit.
 *
 * Exposes a [StateFlow] rather than a getter so a change applies live: the map's
 * marker builder and the layers popup re-read on the spot with no reload. The flow
 * is seeded from disk on construction, so the choice survives process death.
 */
class IncidentAgeFilterPreferenceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(readStored())

    /** The current max-age setting; emits again on every [set]. */
    val maxAge: StateFlow<IncidentAgeOption> = state.asStateFlow()

    /** Records the user's choice and applies it to collectors. */
    fun set(option: IncidentAgeOption) {
        prefs.edit().putString(KEY_MAX_AGE, option.name).apply()
        state.value = option
    }

    private fun readStored(): IncidentAgeOption =
        IncidentAgeFilter.fromStoredName(prefs.getString(KEY_MAX_AGE, null))

    private companion object {
        const val PREFS_NAME = "app_incident_age_filter"
        const val KEY_MAX_AGE = "max_age"
    }
}

/**
 * Read/write access to the alert max-age filter for the map-layers popup, which
 * sits several levels down inside the shell rather than beside the activity that
 * owns the store.
 *
 * A CompositionLocal rather than parameters threaded through AuthenticatedApp,
 * MapHome and MapLayersPopup — exactly the pattern
 * [com.kungsbackacarcommunity.app.map.LocalMapZoomController] uses for the resting
 * zoom, and for the same reason: it keeps the shell's already very wide parameter
 * lists out of it, and the SAME ambient value drives both the popup control and the
 * marker builder that reads it to filter. Tests / previews get the no-op default
 * below.
 */
interface IncidentAgeFilterController {
    val maxAge: IncidentAgeOption

    fun setMaxAge(option: IncidentAgeOption)
}

/**
 * Defaults to a no-op controller reporting [IncidentAgeFilter.DEFAULT] (show
 * everything), so previews and any composable rendered outside the activity's
 * provider draw the full, unfiltered alert layer.
 */
val LocalIncidentAgeFilterController = staticCompositionLocalOf<IncidentAgeFilterController> {
    object : IncidentAgeFilterController {
        override val maxAge = IncidentAgeFilter.DEFAULT

        override fun setMaxAge(option: IncidentAgeOption) = Unit
    }
}
