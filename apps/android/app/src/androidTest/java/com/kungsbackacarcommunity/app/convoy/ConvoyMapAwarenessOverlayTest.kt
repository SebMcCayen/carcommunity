package com.kungsbackacarcommunity.app.convoy

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import com.kungsbackacarcommunity.app.map.ConvoyArrowPlanner
import com.kungsbackacarcommunity.app.map.ConvoyMemberPosition
import com.kungsbackacarcommunity.app.shell.MapCameraSnapshot
import com.kungsbackacarcommunity.app.shell.MapScreenPoint
import com.kungsbackacarcommunity.app.shell.StubMapSurface
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The overlay's TIME behaviour — the one thing the pure planner tests cannot
 * reach.
 *
 * `ConvoyArrowPlanner.plan` is handed `nowMillis` explicitly and drops stale
 * members correctly; `ConvoyArrowPlannerTest` proves that. The bug this file
 * exists for lives one level up, in the composable's CACHING: if the recompute
 * key does not include time, the planner is simply never asked again once the
 * camera settles, and its staleness rule never gets a chance to fire.
 *
 * That is not a theoretical gap. A member who loses signal keeps their RTDB
 * `latest` node, so the roster keeps re-arriving structurally EQUAL; park the
 * camera (waiting at the meet point) and every other recompute key is equal too.
 *
 * So every test here holds the camera and the roster fixed and moves ONLY the
 * clock.
 */
class ConvoyMapAwarenessOverlayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val cameraLat = 57.4874
    private val cameraLng = 12.0757
    private val t0 = 1_700_000_000_000L

    /** Due north and far away: off-screen, so this member renders as an arrow. */
    private fun offScreenMember(uid: String, updatedAtMillis: Long) =
        ConvoyMemberPosition(
            uid = uid,
            latitude = cameraLat + 0.5,
            longitude = cameraLng,
            displayName = uid,
            imagePath = null,
            updatedAtMillis = updatedAtMillis,
        )

    private fun surface() =
        StubMapSurface().apply {
            setCameraSnapshotForTest(
                MapCameraSnapshot(
                    latitude = cameraLat,
                    longitude = cameraLng,
                    zoom = 12.0,
                    bearing = 0.0,
                    pitch = 0.0,
                ),
            )
            // Far off the top of any viewport, so the planner classifies this
            // member as off-screen and gives them an arrow.
            setProjectionForTest { _, _ -> MapScreenPoint(x = 500f, y = -9000f) }
        }

    /**
     * THE regression test.
     *
     * The camera never moves and the members list is the same instance
     * throughout — only `now` advances past the staleness window. The arrow must
     * go. Against a build whose recompute key omits time, `remember` holds its
     * cached placements and the arrow is still displayed here.
     */
    @Test
    fun anArrowDisappearsOnceItsPositionGoesStaleWithTheCameraAndRosterUnchanged() {
        val mapSurface = surface()
        // Recorded at t0 and never updated again — the "lost signal" case.
        val members = listOf(offScreenMember("ghost", updatedAtMillis = t0))
        var now = t0

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            ConvoyMapAwarenessOverlay(
                mapSurface = mapSurface,
                members = members,
                nowMillis = { now },
            )
        }

        // Fresh: the arrow is there.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag(CONVOY_EDGE_ARROW_TAG + "ghost").assertIsDisplayed()

        // Time passes, and NOTHING else changes: same surface, same camera
        // snapshot, same `members` instance. Only the clock the overlay reads.
        now = t0 + ConvoyArrowPlanner.STALE_AFTER_MS + 1

        // Let the staleness tick fire.
        composeTestRule.mainClock.advanceTimeBy(STALE_TICK_MS + 1)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONVOY_EDGE_ARROW_TAG + "ghost").assertDoesNotExist()
    }

    /**
     * The complement, so the test above cannot pass by simply dropping every
     * arrow on a tick: a member who KEEPS reporting survives the same elapsed
     * time. Without this, "the arrow disappeared" would be satisfied by a bug
     * that hides everyone.
     */
    @Test
    fun anArrowSurvivesTheTickWhileItsPositionKeepsBeingFresh() {
        val mapSurface = surface()
        var now = t0
        // Compose STATE, not a plain captured var: in the app this list arrives
        // through a collected StateFlow, so a newly-reported position recomposes
        // the caller and re-passes it. A plain var would leave the overlay
        // holding its old `members` parameter and quietly test nothing.
        val membersState = mutableStateOf(listOf(offScreenMember("live", updatedAtMillis = t0)))

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            ConvoyMapAwarenessOverlay(
                mapSurface = mapSurface,
                members = membersState.value,
                nowMillis = { now },
            )
        }

        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag(CONVOY_EDGE_ARROW_TAG + "live").assertIsDisplayed()

        // Same elapsed time as the stale test, but the member kept reporting.
        now = t0 + ConvoyArrowPlanner.STALE_AFTER_MS + 1
        membersState.value = listOf(offScreenMember("live", updatedAtMillis = now))

        composeTestRule.mainClock.advanceTimeBy(STALE_TICK_MS + 1)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONVOY_EDGE_ARROW_TAG + "live").assertIsDisplayed()
    }

    /**
     * The tick must be derived from the staleness window, not written twice.
     * A tick longer than the window would let an arrow outlive its own bound by
     * more than the window itself.
     */
    /**
     * A live position can arrive with no display name. The spoken description
     * must still have a subject — never open with an empty string (" is off the
     * map, at 12 o'clock, 55 km away").
     */
    @Test
    fun anArrowForANamelessMemberStillGetsASpokenSubject() {
        val mapSurface = surface()
        val nameless =
            ConvoyMemberPosition(
                uid = "nameless",
                latitude = cameraLat + 0.5,
                longitude = cameraLng,
                displayName = null,
                imagePath = null,
                updatedAtMillis = t0,
            )

        composeTestRule.setContent {
            ConvoyMapAwarenessOverlay(
                mapSurface = mapSurface,
                members = listOf(nameless),
                nowMillis = { t0 },
            )
        }

        val description =
            composeTestRule
                .onNodeWithTag(CONVOY_EDGE_ARROW_TAG + "nameless")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .first()

        assertFalse(
            "content description must not start with an empty name: '$description'",
            description.startsWith(" "),
        )
        assertTrue(
            "content description should name the member: '$description'",
            description.isNotBlank(),
        )
    }

    @Test
    fun theStaleTickIsShorterThanTheStalenessWindow() {
        // JUnit's assertTrue, NOT Kotlin's `assert`: the latter is gated on the
        // JVM's desiredAssertionStatus(), which is false on Android unless -ea is
        // passed, so `assert` here would never actually check anything and this
        // test would pass no matter what the constant said.
        assertTrue(
            "stale tick ($STALE_TICK_MS ms) must be a positive fraction of the " +
                "staleness window (${ConvoyArrowPlanner.STALE_AFTER_MS} ms)",
            STALE_TICK_MS in 1 until ConvoyArrowPlanner.STALE_AFTER_MS,
        )
    }
}
