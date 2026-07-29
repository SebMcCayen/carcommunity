package com.kungsbackacarcommunity.app.navigation.turnbyturn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera padding and top-chrome rules behind three of the reported
 * navigation complaints: the puck sitting off to one side, the maneuver banner
 * eating the screen, and the search result lingering in the corner while
 * driving.
 *
 * These are the only parts of that work that CAN be tested off-device: the
 * screen itself lives in `src/nav`, which needs the Mapbox Navigation SDK and a
 * downloads token and is therefore only ever compiled by CI. The arithmetic is
 * here so it is at least pinned.
 */
class NavChromeTest {
    /**
     * **The regression test for "my location is a little bit to the right".**
     *
     * Whatever the chrome above the map is doing, the two SIDE paddings must be
     * the same number: the follow camera places the framed point at a focal
     * point expressed as a fraction of the padded box, so unequal left/right
     * padding is exactly a puck pushed off the centre line. Anyone adding a
     * side-specific inset later has to break this test to do it.
     */
    @Test
    fun sidePaddingIsAlwaysSymmetricSoThePuckStaysHorizontallyCentred() {
        val cases =
            listOf(
                NavCameraPadding.following(NavManeuverCompact.HEIGHT_DP, false),
                NavCameraPadding.following(NavManeuverCompact.HEIGHT_DP, true),
                NavCameraPadding.following(0.0, false),
                NavCameraPadding.following(NavManeuverCompact.DEFAULT_HEIGHT_DP, true),
                NavCameraPadding.overview(NavManeuverCompact.HEIGHT_DP, false),
                NavCameraPadding.overview(NavManeuverCompact.HEIGHT_DP, true),
            )
        for (padding in cases) {
            assertTrue(
                "Left and right padding must be equal or the puck sits off-centre: $padding",
                padding.horizontallyCentred,
            )
            assertEquals(NavCameraPadding.SIDE_DP, padding.left, 0.0)
            assertEquals(NavCameraPadding.SIDE_DP, padding.right, 0.0)
        }
    }

    /**
     * The top padding is the chrome above the map, so it has to move with the
     * banner one dp for one dp. A banner that shrank while the padding stayed
     * put would leave the puck reserved for space nothing occupies — the
     * vertical version of the same bug.
     */
    @Test
    fun topPaddingTracksTheManeuverBannerHeight() {
        val small = NavCameraPadding.following(40.0, destinationBarVisible = false)
        val large = NavCameraPadding.following(140.0, destinationBarVisible = false)
        assertEquals(100.0, large.top - small.top, 0.0)
        // ...and only the top: shrinking the banner must not move the puck
        // sideways or change how much road is shown below it.
        assertEquals(small.bottom, large.bottom, 0.0)
        assertEquals(small.left, large.left, 0.0)
        assertEquals(small.right, large.right, 0.0)
    }

    /**
     * Hiding the destination pill frees exactly its own height and nothing else.
     */
    @Test
    fun hidingTheDestinationBarFreesExactlyItsOwnHeight() {
        val shown = NavCameraPadding.following(NavManeuverCompact.HEIGHT_DP, true)
        val hidden = NavCameraPadding.following(NavManeuverCompact.HEIGHT_DP, false)
        assertEquals(
            NavCameraPadding.DESTINATION_BAR_DP,
            shown.top - hidden.top,
            0.0,
        )
        assertNotEquals(shown.top, hidden.top)
    }

    /**
     * The steady-state padding a driver actually gets: banner compacted and the
     * pill hidden. Pinned as a number so a future edit to any of the three
     * constants is a visible change to where the puck sits, not a silent one.
     */
    @Test
    fun steadyStateFollowingPaddingIsTheCompactBannerWithNoDestinationBar() {
        val padding = NavCameraPadding.following(NavManeuverCompact.HEIGHT_DP, false)
        assertEquals(
            NavCameraPadding.STATUS_CHROME_DP + NavManeuverCompact.HEIGHT_DP,
            padding.top,
            0.0,
        )
        assertEquals(NavCameraPadding.BOTTOM_CHROME_DP, padding.bottom, 0.0)
    }

    /**
     * The banner really is smaller. A "compact" variant that turned out to be
     * the same size as the default would satisfy every other test here while
     * doing nothing about the complaint.
     */
    @Test
    fun theCompactBannerIsMateriallySmallerThanTheSdkDefault() {
        assertTrue(
            "Compact banner (${NavManeuverCompact.HEIGHT_DP}dp) must be smaller " +
                "than the SDK default (${NavManeuverCompact.DEFAULT_HEIGHT_DP}dp)",
            NavManeuverCompact.HEIGHT_DP < NavManeuverCompact.DEFAULT_HEIGHT_DP,
        )
        // At least a quarter off, or it is not worth the styling override.
        assertTrue(
            NavManeuverCompact.HEIGHT_DP <= NavManeuverCompact.DEFAULT_HEIGHT_DP * 0.75,
        )
        assertTrue(NavManeuverCompact.TURN_ICON_DP < 48)
        assertTrue(NavManeuverCompact.TURN_ICON_TOP_MARGIN_DP < 12)
    }

    /** Negative or non-finite chrome heights are a programming error, not a camera. */
    @Test(expected = IllegalArgumentException::class)
    fun aNegativeBannerHeightIsRejected() {
        NavCameraPadding.following(-1.0, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonFiniteBannerHeightIsRejected() {
        NavCameraPadding.following(Double.NaN, false)
    }

    /**
     * **The regression test for "I still see the search result while navigating".**
     *
     * Hidden the moment guidance is running, shown again when it is not — the
     * pill is suppressed for the ACTIVE state specifically, never permanently,
     * so a session that has not started guiding yet (and one whose guidance has
     * ended) still names the destination and still offers the back arrow.
     */
    @Test
    fun theDestinationPillIsHiddenOnlyWhileGuidanceIsActive() {
        assertFalse(NavTopChrome.destinationBarVisible(guidanceActive = true))
        assertTrue(NavTopChrome.destinationBarVisible(guidanceActive = false))
    }
}
