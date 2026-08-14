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

    // ---- Pre-flight refusals ---------------------------------------------

    /**
     * The one that matters most: "too far" and "stop the car" are different
     * sentences, and neither can drift onto the other.
     *
     * A single generic "you can't collect this yet" would be true in both cases
     * and useful in neither — and telling a member to stop when the answer is
     * "walk 300 m" is an instruction to brake for nothing.
     */
    @Test
    fun tooFarAndMovingAreDifferentInstructions() {
        val tooFar = CrownSpawnMessages.refusalTitleRes(CrownCollectState.TooFar(300.0))
        val moving = CrownSpawnMessages.refusalTitleRes(CrownCollectState.Moving)
        assertEquals(R.string.crownHunt_spawnMoveCloser, tooFar)
        assertEquals(R.string.crownHunt_spawnStopFirst, moving)
        assertNotEquals(tooFar, moving)
    }

    /** [CrownCollectState.Ready] has nothing to explain, and says nothing. */
    @Test
    fun theReadyStateShowsNoRefusalCopyAtAll() {
        assertNull(CrownSpawnMessages.refusalTitleRes(CrownCollectState.Ready))
        assertNull(CrownSpawnMessages.refusalDetailRes(CrownCollectState.Ready))
    }

    /** Every state that is NOT ready explains itself with its own headline. */
    @Test
    fun everyNonReadyStateHasItsOwnHeadline() {
        val states =
            listOf(
                CrownCollectState.TooFar(120.0),
                CrownCollectState.Moving,
                CrownCollectState.NoPosition,
                CrownCollectState.FeatureOff,
            )
        val titles = states.map { CrownSpawnMessages.refusalTitleRes(it) }
        for ((state, title) in states.zip(titles)) {
            assertNotNull("$state has no headline", title)
            assertNotEquals("$state maps to a missing resource", 0, title)
        }
        assertEquals("the four refusals must not share wording", 4, titles.toSet().size)
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

    /** A broken distance renders as 0, never as "NaN m". */
    @Test
    fun aBrokenDistanceNeverReachesTheScreenAsNaN() {
        assertEquals(0, CrownDistanceFormat.wholeMetres(Double.NaN))
        assertEquals(0, CrownDistanceFormat.wholeMetres(-5.0))
        assertEquals(0.0, CrownDistanceFormat.kilometres(Double.NaN), 0.0)
        assertFalse(CrownDistanceFormat.useKilometres(Double.NaN))
    }
}
