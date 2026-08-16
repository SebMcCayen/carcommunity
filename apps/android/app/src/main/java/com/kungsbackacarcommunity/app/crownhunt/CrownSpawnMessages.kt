package com.kungsbackacarcommunity.app.crownhunt

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/**
 * Every word the crown popup can say, and the ONE place each of them is chosen.
 *
 * Kept apart from the composable that renders them so the choices themselves are
 * JVM-unit-testable. That matters more here than in most UI: the copy for a
 * refusal is a safety surface, not decoration. "Stop the car first" appearing
 * where "move closer" belonged would be an instruction to brake for no reason,
 * and a `risk_review` mapped to a fraud-flavoured line would accuse a member who
 * did nothing wrong. `CrownSpawnMessagesTest` pins all of it exhaustively.
 *
 * ## The tone rules these mappings encode
 *
 * - **A refusal is never an accusation.** `risk_review` says only that the
 *   position could not be verified. It deliberately does NOT name which signal
 *   tripped — that would both teach a spoofer what to change and tell an honest
 *   member with a poor fix that they were suspected of something.
 * - **`already_taken` is not an error.** Crowns are claimed once GLOBALLY, so
 *   being beaten to one is the feature working. It reads as news, not failure.
 * - **`must_be_stationary` carries no number and no urgency.** See
 *   [CrownCollectGate]: no speed is ever shown, nothing counts down, and nothing
 *   flashes. A driver sees a static line in a popup they chose to open.
 * - **Transport failures blame the app.** "Something went wrong", never
 *   "you did something wrong".
 */
object CrownSpawnMessages {

    /**
     * The line shown for a completed `crownHunt.claimSpawn` call.
     *
     * Exhaustive over [CrownSpawnClaimResult] by construction — a code the
     * backend adds cannot reach production without the compiler demanding copy
     * for it, which is the whole reason the client mirrors the enum rather than
     * rendering the server's own `message` field. (The server's Swedish message
     * is still there and still correct; it simply is not localizable, and a
     * feature that will ship in two languages cannot have its safety copy live
     * only in one.)
     */
    @StringRes
    fun resultMessageRes(result: CrownSpawnClaimResult): Int =
        when (result) {
            CrownSpawnClaimResult.AWARDED -> R.string.crownHunt_spawnResultAwarded
            CrownSpawnClaimResult.ALREADY_TAKEN -> R.string.crownHunt_spawnResultAlreadyTaken
            // "You already got this one", NOT "someone beat you" (ALREADY_TAKEN):
            // a shared crown the caller collected earlier is still on the map, so
            // this is the honest reading of a re-tap, not a lost race.
            CrownSpawnClaimResult.ALREADY_COLLECTED ->
                R.string.crownHunt_spawnResultAlreadyCollected
            CrownSpawnClaimResult.OUTSIDE_RADIUS -> R.string.crownHunt_spawnResultOutsideRadius
            CrownSpawnClaimResult.MUST_BE_STATIONARY ->
                R.string.crownHunt_spawnResultMustBeStationary
            CrownSpawnClaimResult.POSITION_TOO_OLD -> R.string.crownHunt_spawnResultPositionTooOld
            CrownSpawnClaimResult.CROWN_EXPIRED -> R.string.crownHunt_spawnResultCrownExpired
            CrownSpawnClaimResult.DAILY_LIMIT_REACHED -> R.string.crownHunt_spawnResultDailyLimit
            CrownSpawnClaimResult.RISK_REVIEW -> R.string.crownHunt_spawnResultRiskReview
            CrownSpawnClaimResult.FEATURE_DISABLED ->
                R.string.crownHunt_spawnResultFeatureDisabled
            CrownSpawnClaimResult.NOT_ELIGIBLE -> R.string.crownHunt_spawnResultNotEligible
        }

    /**
     * The headline for a state in which the button is NOT live, or null for
     * [CrownCollectState.Ready] — which has no explaining to do.
     *
     * `TooFar` and `Moving` map to genuinely different sentences on purpose. The
     * temptation is one generic "you can't collect this yet", which is the
     * single worst option available: it is true in both cases, actionable in
     * neither, and leaves a member standing next to a crown with no idea whether
     * to walk closer or to put the handbrake on.
     */
    @StringRes
    fun refusalTitleRes(state: CrownCollectState): Int? =
        when (state) {
            CrownCollectState.Ready -> null
            is CrownCollectState.TooFar -> R.string.crownHunt_spawnMoveCloser
            CrownCollectState.Moving -> R.string.crownHunt_spawnStopFirst
            CrownCollectState.NoPosition -> R.string.crownHunt_spawnNoPosition
            // Confirming is not a refusal: nothing has gone wrong and the member
            // need only stay put. The BUTTON carries the "confirming you're
            // stopped" line (see [collectActionLabelRes]), so there is no separate
            // headline to print above the distance — the same choice Ready makes.
            is CrownCollectState.Confirming -> null
            CrownCollectState.FeatureOff -> R.string.crownHunt_spawnResultFeatureDisabled
        }

    /**
     * The supporting line under [refusalTitleRes], or null when the headline
     * says everything.
     *
     * [CrownCollectState.FeatureOff] has none: "Crown Hunt is not available right
     * now" is complete, and anything added would be an explanation of an
     * operator's configuration choice that the member can neither verify nor act
     * on.
     */
    @StringRes
    fun refusalDetailRes(state: CrownCollectState): Int? =
        when (state) {
            CrownCollectState.Ready -> null
            is CrownCollectState.TooFar -> R.string.crownHunt_spawnMoveCloserDetail
            CrownCollectState.Moving -> R.string.crownHunt_spawnStopFirstDetail
            CrownCollectState.NoPosition -> R.string.crownHunt_spawnNoPositionDetail
            // The confirming line lives on the button, not in a detail paragraph.
            is CrownCollectState.Confirming -> null
            CrownCollectState.FeatureOff -> null
        }

    /**
     * The Collect button's own label for the current [state] and in-flight
     * [collecting] status — the ONE place the button decides what it says.
     *
     * Kept here (not in the composable) so the "confirming you're stopped" step is
     * unit-testable rather than only visible on a device: the whole point of the
     * step is that the button stops looking live-then-refusing, and that promise
     * is worth pinning. The seconds-remaining variant is chosen in the composable
     * (it needs the argument), off [CrownCollectState.Confirming.secondsRemaining];
     * this returns the argument-free resource.
     */
    @StringRes
    fun collectActionLabelRes(state: CrownCollectState, collecting: Boolean): Int =
        when {
            collecting -> R.string.crownHunt_spawnCollecting
            state is CrownCollectState.Confirming -> R.string.crownHunt_spawnConfirming
            else -> R.string.crownHunt_spawnCollect
        }

    /** The tier's name, for the popup's header line. */
    @StringRes
    fun rarityLabelRes(rarity: CrownRarity): Int =
        when (rarity) {
            CrownRarity.COMMON -> R.string.crownHunt_rarityCommon
            CrownRarity.UNCOMMON -> R.string.crownHunt_rarityUncommon
            CrownRarity.RARE -> R.string.crownHunt_rarityRare
            CrownRarity.LEGENDARY -> R.string.crownHunt_rarityLegendary
        }
}

/**
 * The crown silhouette drawable for [rarity] — the one place an abstract
 * [CrownMarkerStyle.Glyph] becomes a resource id.
 *
 * Separate from [CrownMarkerStyle] (which is deliberately Android-free so its
 * legibility rules are unit-testable off-device), exactly as
 * `incidentGlyphRes` is separate from `IncidentMarkerStyle`.
 */
@DrawableRes
fun crownGlyphRes(rarity: CrownRarity): Int =
    when (CrownMarkerStyle.glyph(rarity)) {
        CrownMarkerStyle.Glyph.BAND -> R.drawable.ic_crown_band
        CrownMarkerStyle.Glyph.JEWELLED_BAND -> R.drawable.ic_crown_jewelled_band
        CrownMarkerStyle.Glyph.FIVE_POINT -> R.drawable.ic_crown_five_point
        CrownMarkerStyle.Glyph.ROYAL -> R.drawable.ic_crown_royal
    }

/**
 * The crown silhouette for a HAND-PLACED admin Kronjakt point.
 *
 * The ROYAL crown, matching the "official / curated" reading of
 * [CrownMarkerStyle.ADMIN_POINT_DISC]. Separate from [crownGlyphRes] (which is
 * keyed by rarity) because an admin point has no rarity — it is its own source.
 */
@DrawableRes
fun crownPointGlyphRes(): Int = R.drawable.ic_crown_royal

/**
 * How a distance to a crown is written.
 *
 * Pure, so the switch-over point and the rounding are pinned by tests rather
 * than eyeballed. The rule: metres (whole numbers) below a kilometre, then one
 * decimal of a kilometre. Nobody parked 40 m from a crown is helped by "0.0 km",
 * and nobody 3 km away cares about the last 12 metres.
 *
 * This is the ONLY number this feature shows about the user's relationship to a
 * crown. There is deliberately no ETA and no closing speed — both would turn a
 * collectable into something to hurry towards.
 */
object CrownDistanceFormat {
    /** Below this many metres the distance is written in whole metres. */
    const val KILOMETRE_THRESHOLD_METERS: Double = 1_000.0

    /** Whether [meters] should be written as kilometres rather than metres. */
    fun useKilometres(meters: Double): Boolean =
        meters.isFinite() && meters >= KILOMETRE_THRESHOLD_METERS

    /**
     * [meters] rounded to a whole metre, never negative. A non-finite distance
     * (a broken projection) collapses to 0 rather than rendering "NaN m".
     */
    fun wholeMetres(meters: Double): Int =
        if (!meters.isFinite() || meters <= 0.0) 0 else Math.round(meters).toInt()

    /** [meters] as kilometres, rounded to one decimal. */
    fun kilometres(meters: Double): Double =
        if (!meters.isFinite() || meters <= 0.0) {
            0.0
        } else {
            Math.round(meters / 100.0) / 10.0
        }
}
