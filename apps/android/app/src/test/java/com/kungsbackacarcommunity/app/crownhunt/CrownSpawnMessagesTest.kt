package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which words the crown popup shows, for every code the backend can return.
 *
 * Copy is not decoration in this feature. A refusal is the only thing standing
 * between a member and either a pointless errand or a nudge to touch the phone
 * while moving, so the mapping is asserted case by case — and, crucially,
 * asserted to be DISTINCT, because the failure mode that a "shows some message"
 * test would sail past is every code collapsing onto one vague line.
 */
class CrownSpawnMessagesTest {

    // ---- Exhaustive, and distinct ----------------------------------------

    /**
     * Every one of the ten claim results maps to its own string.
     *
     * `entries` drives the loop, so a code added to [CrownSpawnClaimResult]
     * without copy fails to compile in [CrownSpawnMessages] first — and if it
     * were given a duplicate string to appease the compiler, this fails.
     */
    @Test
    fun everyClaimResultHasItsOwnMessage() {
        val byResult = CrownSpawnClaimResult.entries.associateWith {
            CrownSpawnMessages.resultMessageRes(it)
        }
        assertEquals(
            "one message per result",
            CrownSpawnClaimResult.entries.size,
            byResult.values.toSet().size,
        )
        for ((result, res) in byResult) {
            assertNotEquals("$result maps to a missing resource", 0, res)
        }
    }

    /** The named mappings, spelled out so a silent re-point is visible in review. */
    @Test
    fun eachClaimResultMapsToTheStringWrittenForIt() {
        assertEquals(
            R.string.crownHunt_spawnResultAwarded,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.AWARDED),
        )
        assertEquals(
            R.string.crownHunt_spawnResultAlreadyTaken,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.ALREADY_TAKEN),
        )
        assertEquals(
            R.string.crownHunt_spawnResultAlreadyCollected,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.ALREADY_COLLECTED),
        )
        assertEquals(
            R.string.crownHunt_spawnResultOutsideRadius,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.OUTSIDE_RADIUS),
        )
        assertEquals(
            R.string.crownHunt_spawnResultMustBeStationary,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.MUST_BE_STATIONARY),
        )
        assertEquals(
            R.string.crownHunt_spawnResultPositionTooOld,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.POSITION_TOO_OLD),
        )
        assertEquals(
            R.string.crownHunt_spawnResultCrownExpired,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.CROWN_EXPIRED),
        )
        assertEquals(
            R.string.crownHunt_spawnResultDailyLimit,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.DAILY_LIMIT_REACHED),
        )
        assertEquals(
            R.string.crownHunt_spawnResultRiskReview,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.RISK_REVIEW),
        )
        assertEquals(
            R.string.crownHunt_spawnResultFeatureDisabled,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.FEATURE_DISABLED),
        )
        assertEquals(
            R.string.crownHunt_spawnResultNotEligible,
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.NOT_ELIGIBLE),
        )
    }

    /**
     * `risk_review` gets the NEUTRAL line and, in particular, is not merged with
     * the "you were moving" one.
     *
     * The risk score is built from signals the member cannot see (mock-location,
     * impossible jumps, claim velocity, accuracy). Naming one would teach a
     * spoofer what to change AND accuse an honest member with a poor fix of
     * something they did not do. So it says only that the position could not be
     * verified.
     */
    @Test
    fun riskReviewIsNeutralAndNeverBorrowsAnotherCodesWording() {
        val risk = CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.RISK_REVIEW)
        assertEquals(R.string.crownHunt_spawnResultRiskReview, risk)
        assertNotEquals(
            "risk_review must not read as the stationary refusal",
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.MUST_BE_STATIONARY),
            risk,
        )
        assertNotEquals(
            "risk_review must not read as a generic transport failure",
            R.string.crownHunt_spawnErrorClaim,
            risk,
        )
    }

    /**
     * Being beaten to a crown is news, not an error — and specifically not the
     * "it disappeared" line, which would misdescribe what happened.
     */
    @Test
    fun alreadyTakenHasItsOwnGracefulLineSeparateFromExpiry() {
        assertNotEquals(
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.CROWN_EXPIRED),
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.ALREADY_TAKEN),
        )
    }

    /**
     * Re-collecting a SHARED crown you already picked up ("you already got this
     * one") is a distinct, benign message — NOT the "someone beat you" race line
     * (ALREADY_TAKEN) and, above all, NOT the generic transport error the missing
     * enum value used to produce (#874).
     */
    @Test
    fun alreadyCollectedIsItsOwnBenignLineAndNeverTheGenericError() {
        val collected = CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.ALREADY_COLLECTED)
        assertEquals(R.string.crownHunt_spawnResultAlreadyCollected, collected)
        assertNotEquals(
            "already_collected must not read as the 'someone beat you' race line",
            CrownSpawnMessages.resultMessageRes(CrownSpawnClaimResult.ALREADY_TAKEN),
            collected,
        )
        assertNotEquals(
            "already_collected must not read as a generic transport failure",
            R.string.crownHunt_spawnErrorClaim,
            collected,
        )
    }

    /**
     * The wire code the backend actually sends for a re-tap of a shared crown
     * MUST round-trip to a rendered enum value. Before #874 the enum omitted it,
     * so `fromWire` returned null, the response failed to parse, and the popup
     * showed the generic error. Pinned so a future prune of the enum cannot
     * silently reintroduce that.
     */
    @Test
    fun knownBackendResultCodeParsesToAnEnumValue() {
        val wireCodes =
            listOf(
                "awarded",
                "already_taken",
                "already_collected",
                "outside_radius",
                "must_be_stationary",
                "position_too_old",
                "crown_expired",
                "daily_limit_reached",
                "risk_review",
                "feature_disabled",
                "not_eligible",
            )
        for (code in wireCodes) {
            assertNotNull(
                "backend result code '$code' must parse to an enum value, not null",
                CrownSpawnClaimResult.fromWire(code),
            )
        }
        assertEquals(
            CrownSpawnClaimResult.ALREADY_COLLECTED,
            CrownSpawnClaimResult.fromWire("already_collected"),
        )
    }

    // ---- Pre-flight refusals ---------------------------------------------

    /**
     * "Too far" keeps its own instruction, and it never drifts onto the stop/still
     * family: telling a member to stop when the answer is "walk 300 m" would be an
     * instruction to brake for nothing. TooFar draws the proximity bar; the in-range
     * waits ([Moving]/[Confirming]) are a different, calmer message entirely.
     */
    @Test
    fun tooFarKeepsItsOwnMoveCloserInstruction() {
        val tooFar = CrownSpawnMessages.refusalTitleRes(CrownCollectState.TooFar(300.0))
        assertEquals(R.string.crownHunt_spawnMoveCloser, tooFar)
        // The in-range "still moving" moment no longer borrows a distinct "stop the
        // car first" headline, so it can never be confused with the move-closer one.
        assertNotEquals(tooFar, CrownSpawnMessages.refusalTitleRes(CrownCollectState.Moving))
    }

    /**
     * Fix 2: in range, [CrownCollectState.Moving] (speed still settling to a stop)
     * folds into the SAME calm confirming presentation as [CrownCollectState.Confirming]
     * rather than flashing the old blunt "stop the car first". So it carries NO
     * refusal headline and NO detail — the button alone says "confirming you're
     * stopped…". The two are one wait seen at two moments; a member must not see a
     * countdown on one approach to a crown and "stop the car first" on the next.
     */
    @Test
    fun movingInRangeFoldsIntoTheConfirmingWaitAndShowsNoStopFirstHeadline() {
        assertNull(
            "Moving must no longer show the 'stop the car first' headline",
            CrownSpawnMessages.refusalTitleRes(CrownCollectState.Moving),
        )
        assertNull(
            "Moving must show no refusal detail — the button carries the confirming line",
            CrownSpawnMessages.refusalDetailRes(CrownCollectState.Moving),
        )
    }

    /**
     * The folded [CrownCollectState.Moving] button reads as the INDETERMINATE
     * confirming label — the same argument-free "confirming you're stopped…" as
     * [CrownCollectState.Confirming], never the numeric "(N s)" variant (a settling
     * speed has no defined seconds-remaining) and never the old "stop the car first".
     * In-flight still wins: one press is one call.
     */
    @Test
    fun movingCollectButtonReadsAsIndeterminateConfirmingNotStopFirst() {
        assertEquals(
            R.string.crownHunt_spawnConfirming,
            CrownSpawnMessages.collectActionLabelRes(CrownCollectState.Moving, collecting = false),
        )
        // Same experience as the dwell-confirming wait — the whole point of Fix 2.
        assertEquals(
            "Moving and Confirming must present the same confirming button label",
            CrownSpawnMessages.collectActionLabelRes(CrownCollectState.Confirming(3), collecting = false),
            CrownSpawnMessages.collectActionLabelRes(CrownCollectState.Moving, collecting = false),
        )
        // Not the numeric-seconds resource — Moving is the indeterminate form.
        assertNotEquals(
            R.string.crownHunt_spawnConfirmingSeconds,
            CrownSpawnMessages.collectActionLabelRes(CrownCollectState.Moving, collecting = false),
        )
        assertEquals(
            R.string.crownHunt_spawnCollecting,
            CrownSpawnMessages.collectActionLabelRes(CrownCollectState.Moving, collecting = true),
        )
    }

    /** [CrownCollectState.Ready] has nothing to explain, and says nothing. */
    @Test
    fun theReadyStateShowsNoRefusalCopyAtAll() {
        assertNull(CrownSpawnMessages.refusalTitleRes(CrownCollectState.Ready))
        assertNull(CrownSpawnMessages.refusalDetailRes(CrownCollectState.Ready))
    }

    /**
     * Every genuine REFUSAL that is not ready explains itself with its own headline.
     *
     * The in-range waits ([Moving], [Confirming], [WaitingForSignal]) are NOT
     * refusals and are excluded on purpose — they carry no headline, the button
     * says "confirming you're stopped…" / "waiting for a better GPS signal…". Only
     * the states that need a distinct sentence above the distance are listed here.
     */
    @Test
    fun everyNonReadyRefusalStateHasItsOwnHeadline() {
        val states =
            listOf(
                CrownCollectState.TooFar(120.0),
                CrownCollectState.NoPosition,
                CrownCollectState.FeatureOff,
            )
        val titles = states.map { CrownSpawnMessages.refusalTitleRes(it) }
        for ((state, title) in states.zip(titles)) {
            assertNotNull("$state has no headline", title)
            assertNotEquals("$state maps to a missing resource", 0, title)
        }
        assertEquals("the three refusals must not share wording", 3, titles.toSet().size)
    }

    /**
     * "Waiting for your position" is not a refusal and must not be phrased as
     * one — nothing has gone wrong and the member need only stay put.
     */
    @Test
    fun waitingForAPositionIsNotWordedAsARefusal() {
        assertEquals(
            R.string.crownHunt_spawnNoPosition,
            CrownSpawnMessages.refusalTitleRes(CrownCollectState.NoPosition),
        )
        assertEquals(
            R.string.crownHunt_spawnNoPositionDetail,
            CrownSpawnMessages.refusalDetailRes(CrownCollectState.NoPosition),
        )
    }

    /**
     * The feature being switched off has no detail line: there is nothing the
     * member can do about an operator's configuration, and inventing a next step
     * would be a lie.
     */
    @Test
    fun theFeatureOffStateOffersNoFalseNextStep() {
        assertEquals(
            R.string.crownHunt_spawnResultFeatureDisabled,
            CrownSpawnMessages.refusalTitleRes(CrownCollectState.FeatureOff),
        )
        assertNull(CrownSpawnMessages.refusalDetailRes(CrownCollectState.FeatureOff))
    }

    /**
     * The confirming step is not a refusal: it carries no headline and no detail
     * (the BUTTON says "confirming you're stopped"), exactly as Ready stays quiet.
     */
    @Test
    fun theConfirmingStateSaysNothingInTheRefusalCopyBecauseTheButtonCarriesIt() {
        val confirming = CrownCollectState.Confirming(2)
        assertNull(CrownSpawnMessages.refusalTitleRes(confirming))
        assertNull(CrownSpawnMessages.refusalDetailRes(confirming))
    }

    /**
     * WaitingForSignal is a wait, not a refusal, exactly like Confirming: no
     * headline, no detail, the BUTTON carries "waiting for a better GPS signal".
     */
    @Test
    fun theWaitingForSignalStateSaysNothingInTheRefusalCopyBecauseTheButtonCarriesIt() {
        assertNull(CrownSpawnMessages.refusalTitleRes(CrownCollectState.WaitingForSignal))
        assertNull(CrownSpawnMessages.refusalDetailRes(CrownCollectState.WaitingForSignal))
    }

    /**
     * The Collect button's own label: "Collecting…" while a call is in flight,
     * "Confirming you're stopped…" while the dwell/accuracy are not ready, and
     * plain "Collect" once it is live. In-flight wins over confirming — one press
     * is one call, and the button must read as busy the instant it is.
     */
    @Test
    fun theCollectButtonLabelReflectsWhetherItIsConfirmingOrLiveOrInFlight() {
        assertEquals(
            R.string.crownHunt_spawnCollect,
            CrownSpawnMessages.collectActionLabelRes(CrownCollectState.Ready, collecting = false),
        )
        assertEquals(
            R.string.crownHunt_spawnConfirming,
            CrownSpawnMessages.collectActionLabelRes(
                CrownCollectState.Confirming(3),
                collecting = false,
            ),
        )
        assertEquals(
            R.string.crownHunt_spawnCollecting,
            CrownSpawnMessages.collectActionLabelRes(
                CrownCollectState.Confirming(3),
                collecting = true,
            ),
        )
        assertEquals(
            R.string.crownHunt_spawnCollecting,
            CrownSpawnMessages.collectActionLabelRes(CrownCollectState.Ready, collecting = true),
        )
    }

    /**
     * WaitingForSignal has its OWN button label — "waiting for a better GPS
     * signal", distinct from the dwell "confirming you're stopped" — so a member
     * held up by a fuzzy fix is told GPS, not stillness, is the hold-up. In-flight
     * still wins: one press is one call.
     */
    @Test
    fun theCollectButtonLabelDistinguishesWaitingForGpsFromConfirmingStillness() {
        assertEquals(
            R.string.crownHunt_spawnWaitingForSignal,
            CrownSpawnMessages.collectActionLabelRes(
                CrownCollectState.WaitingForSignal,
                collecting = false,
            ),
        )
        assertEquals(
            R.string.crownHunt_spawnCollecting,
            CrownSpawnMessages.collectActionLabelRes(
                CrownCollectState.WaitingForSignal,
                collecting = true,
            ),
        )
    }

    // ---- Rarity ----------------------------------------------------------

    @Test
    fun everyRarityHasItsOwnLabel() {
        val labels = CrownRarity.entries.map { CrownSpawnMessages.rarityLabelRes(it) }
        assertEquals(CrownRarity.entries.size, labels.toSet().size)
        for ((rarity, res) in CrownRarity.entries.zip(labels)) {
            assertNotEquals("$rarity maps to a missing resource", 0, res)
        }
    }

    // ---- Distance --------------------------------------------------------

    @Test
    fun distanceIsWrittenInMetresBelowAKilometreAndKilometresAboveIt() {
        assertTrue(CrownDistanceFormat.useKilometres(1_000.0))
        assertTrue(CrownDistanceFormat.useKilometres(2_400.0))
        assertFalse(CrownDistanceFormat.useKilometres(999.0))
        assertEquals(120, CrownDistanceFormat.wholeMetres(119.6))
        assertEquals(1.4, CrownDistanceFormat.kilometres(1_440.0), 0.001)
    }

    /**
     * The exact metre-vs-kilometre decision the proximity bar's "… kvar / … to go"
     * line rides on (via `crownDistanceShort`): a nearby crown stays whole metres,
     * a far one flips to a one-decimal kilometre — so a 5 km crown never renders as
     * a runaway "5000 m".
     */
    @Test
    fun proximityRemainingUsesMetresWhenNearAndKilometresWhenFar() {
        // 120 m -> "120 m" (metres branch, whole number).
        assertFalse(CrownDistanceFormat.useKilometres(120.0))
        assertEquals(120, CrownDistanceFormat.wholeMetres(120.0))
        // 1400 m -> "1.4 km" (kilometre branch, one decimal).
        assertTrue(CrownDistanceFormat.useKilometres(1_400.0))
        assertEquals(1.4, CrownDistanceFormat.kilometres(1_400.0), 0.001)
        // A far crown that used to read "5000 m" is 5.0 km, not 5000.
        assertTrue(CrownDistanceFormat.useKilometres(5_000.0))
        assertEquals(5.0, CrownDistanceFormat.kilometres(5_000.0), 0.001)
    }

    /** A broken distance renders as 0, never as "NaN m". */
    @Test
    fun aBrokenDistanceNeverReachesTheScreenAsNaN() {
        assertEquals(0, CrownDistanceFormat.wholeMetres(Double.NaN))
        assertEquals(0, CrownDistanceFormat.wholeMetres(-5.0))
        assertEquals(0.0, CrownDistanceFormat.kilometres(Double.NaN), 0.0)
        assertFalse(CrownDistanceFormat.useKilometres(Double.NaN))
    }
}
