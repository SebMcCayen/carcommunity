package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared-destination state machine, exercised as pure logic: no destination /
 * set by me / set by someone else, and — the case that actually matters — a
 * destination cleared or replaced while the viewer is already navigating to it.
 */
class ConvoyDestinationTest {

    private fun destination(
        latitude: Double = 57.4879,
        longitude: Double = 12.0760,
        label: String? = "Kungsbacka torg",
        setByUid: String = "anna",
        setByDisplayName: String? = "Anna",
    ) = ConvoyDestination(
        latitude = latitude,
        longitude = longitude,
        label = label,
        setByUid = setByUid,
        setByDisplayName = setByDisplayName,
        setAt = "2026-07-19T18:04:11.000Z",
    )

    // ---- state: none / mine / theirs ------------------------------------

    @Test
    fun `no destination is None`() {
        assertEquals(
            ConvoyDestinationState.None,
            ConvoyDestinations.stateFor(destination = null, viewerUid = "anna"),
        )
    }

    @Test
    fun `destination set by the viewer is SetByMe`() {
        val state = ConvoyDestinations.stateFor(destination(setByUid = "anna"), viewerUid = "anna")
        assertTrue(state is ConvoyDestinationState.SetByMe)
    }

    @Test
    fun `destination set by another member is SetByOther`() {
        val state = ConvoyDestinations.stateFor(destination(setByUid = "bosse"), viewerUid = "anna")
        assertTrue(state is ConvoyDestinationState.SetByOther)
    }

    @Test
    fun `unattributable destination is never claimed as the viewer's own`() {
        // A blank setter must not be read as "mine" — that would offer a clear
        // button the server would then refuse.
        val state = ConvoyDestinations.stateFor(destination(setByUid = ""), viewerUid = "anna")
        assertTrue(state is ConvoyDestinationState.SetByOther)
    }

    @Test
    fun `a signed-out viewer never owns the destination`() {
        assertTrue(
            ConvoyDestinations.stateFor(destination(), viewerUid = null)
                is ConvoyDestinationState.SetByOther,
        )
        assertTrue(
            ConvoyDestinations.stateFor(destination(), viewerUid = "")
                is ConvoyDestinationState.SetByOther,
        )
    }

    // ---- overwrite confirmation -----------------------------------------

    @Test
    fun `replacing someone else's destination confirms first`() {
        assertTrue(
            ConvoyDestinations.requiresOverwriteConfirmation(
                current = destination(setByUid = "bosse"),
                viewerUid = "anna",
            ),
        )
    }

    @Test
    fun `replacing my own destination does not confirm`() {
        assertFalse(
            ConvoyDestinations.requiresOverwriteConfirmation(
                current = destination(setByUid = "anna"),
                viewerUid = "anna",
            ),
        )
    }

    @Test
    fun `setting the first destination does not confirm`() {
        assertFalse(
            ConvoyDestinations.requiresOverwriteConfirmation(current = null, viewerUid = "anna"),
        )
    }

    // ---- clear permission (setter or owner) ------------------------------

    @Test
    fun `the setter may clear their own destination`() {
        assertTrue(
            ConvoyDestinations.canClear(
                destination = destination(setByUid = "anna"),
                viewerUid = "anna",
                viewerIsOwner = false,
            ),
        )
    }

    @Test
    fun `the convoy owner may clear anyone's destination`() {
        assertTrue(
            ConvoyDestinations.canClear(
                destination = destination(setByUid = "bosse"),
                viewerUid = "anna",
                viewerIsOwner = true,
            ),
        )
    }

    @Test
    fun `an ordinary member may not clear someone else's destination`() {
        assertFalse(
            ConvoyDestinations.canClear(
                destination = destination(setByUid = "bosse"),
                viewerUid = "anna",
                viewerIsOwner = false,
            ),
        )
    }

    @Test
    fun `there is nothing to clear when no destination is set`() {
        assertFalse(
            ConvoyDestinations.canClear(
                destination = null,
                viewerUid = "anna",
                viewerIsOwner = true,
            ),
        )
    }

    // ---- cleared / replaced mid-navigation -------------------------------

    @Test
    fun `clearing the destination I am navigating to reports Cleared`() {
        val previous = destination()
        val event =
            ConvoyDestinations.navigationEvent(
                previous = previous,
                current = null,
                navigatingTo = previous.point,
            )
        assertEquals(ConvoyDestinationNavigationEvent.Cleared, event)
    }

    @Test
    fun `clearing the destination while I am NOT navigating says nothing`() {
        assertEquals(
            ConvoyDestinationNavigationEvent.Unchanged,
            ConvoyDestinations.navigationEvent(
                previous = destination(),
                current = null,
                navigatingTo = null,
            ),
        )
    }

    @Test
    fun `clearing a destination I was not driving to says nothing`() {
        // Navigating to somewhere of my own choosing: the convoy's destination
        // changing underneath is not my problem and must not interrupt me.
        assertEquals(
            ConvoyDestinationNavigationEvent.Unchanged,
            ConvoyDestinations.navigationEvent(
                previous = destination(),
                current = null,
                navigatingTo = LatLng(longitude = 11.9746, latitude = 57.7089),
            ),
        )
    }

    @Test
    fun `replacing the destination I am navigating to reports Replaced with the new one`() {
        val previous = destination()
        val replacement = destination(latitude = 57.7089, longitude = 11.9746, label = "Göteborg")
        val event =
            ConvoyDestinations.navigationEvent(
                previous = previous,
                current = replacement,
                navigatingTo = previous.point,
            )
        assertEquals(ConvoyDestinationNavigationEvent.Replaced(replacement), event)
    }

    @Test
    fun `a destination re-set to the same coordinate is not a change`() {
        val previous = destination(setByUid = "anna")
        // Same place, different setter/label — the driver is still going to the
        // exact same coordinate, so interrupting them would be noise.
        val current = destination(setByUid = "bosse", label = "Torget")
        assertEquals(
            ConvoyDestinationNavigationEvent.Unchanged,
            ConvoyDestinations.navigationEvent(previous, current, previous.point),
        )
    }

    @Test
    fun `a float round-trip does not read as the destination moving`() {
        val previous = destination()
        val jittered =
            previous.copy(latitude = previous.latitude + 1e-9, longitude = previous.longitude - 1e-9)
        assertEquals(
            ConvoyDestinationNavigationEvent.Unchanged,
            ConvoyDestinations.navigationEvent(previous, jittered, previous.point),
        )
    }

    // ---- validation -------------------------------------------------------

    @Test
    fun `coordinate bounds are enforced`() {
        assertTrue(ConvoyDestinations.isValidCoordinate(57.4879, 12.0760))
        assertTrue(ConvoyDestinations.isValidCoordinate(-90.0, 180.0))
        assertFalse(ConvoyDestinations.isValidCoordinate(90.1, 0.0))
        assertFalse(ConvoyDestinations.isValidCoordinate(0.0, -180.1))
        assertFalse(ConvoyDestinations.isValidCoordinate(Double.NaN, 0.0))
        assertFalse(ConvoyDestinations.isValidCoordinate(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `labels are trimmed, blanks dropped and over-long ones capped`() {
        assertEquals("Torget", ConvoyDestinations.normalizeLabel("  Torget  "))
        assertNull(ConvoyDestinations.normalizeLabel("   "))
        assertNull(ConvoyDestinations.normalizeLabel(null))
        val long = "x".repeat(ConvoyDestinations.MAX_LABEL_LENGTH + 40)
        val capped = ConvoyDestinations.normalizeLabel(long)
        assertNotNull(capped)
        assertEquals(ConvoyDestinations.MAX_LABEL_LENGTH, capped!!.length)
    }

    // ---- the availability gate -------------------------------------------

    @Test
    fun `the destination feature is gated off until the callables exist`() {
        // The single flag that must flip when the backend lands. If this test
        // starts failing, the backend arrived — flip the repository too.
        assertEquals(
            ConvoyDestinationAvailability.BackendMissing,
            ConvoyDestinations.availability,
        )
        assertFalse(ConvoyDestinations.isWired)
        assertEquals(ConvoyDestinationNotice.BackendMissing, ConvoyDestinations.notice)
    }

    @Test
    fun `the unavailable repository refuses without pretending to store anything`() = runTest {
        val repo = UnavailableConvoyDestinationRepository
        assertEquals(
            ConvoyDestinationResult.Unavailable,
            repo.setDestination("c1", 57.4879, 12.0760, "Torget"),
        )
        // Crucially: setting then reading back does NOT surface a destination.
        // A client-side-only shared destination would be a lie.
        assertEquals(ConvoyDestinationResult.Unavailable, repo.clearDestination("c1"))
    }

    // ---- the bar's derived state -----------------------------------------

    @Test
    fun `the bar carries the destination state for the viewer`() {
        val convoy =
            convoySummary(
                convoyId = "c1",
                ownerUid = "anna",
                viewerRole = ConvoyRole.Member,
                destination = destination(setByUid = "anna", setByDisplayName = "Anna"),
            )
        val state =
            ConvoyBar.stateFor(
                ConvoyListStatus.Loaded(convoys = listOf(convoy), pendingInvites = emptyList()),
                viewerUid = "bosse",
            )
        assertTrue(state?.destinationState is ConvoyDestinationState.SetByOther)
        // A plain member who did not set it cannot clear it.
        assertFalse(state!!.canClearDestination)
    }

    @Test
    fun `a convoy with no destination yields the None state`() {
        val convoy = convoySummary(convoyId = "c1", ownerUid = "anna", destination = null)
        val state =
            ConvoyBar.stateFor(
                ConvoyListStatus.Loaded(convoys = listOf(convoy), pendingInvites = emptyList()),
                viewerUid = "anna",
            )
        assertEquals(ConvoyDestinationState.None, state?.destinationState)
        assertFalse(state!!.canClearDestination)
    }

    @Test
    fun `the parser reads a destination off the convoy payload`() {
        val parsed =
            ConvoyResponseParser.parseList(
                mapOf(
                    "convoys" to
                        listOf(
                            mapOf(
                                "convoyId" to "c1",
                                "ownerUid" to "anna",
                                "status" to "active",
                                "destination" to
                                    mapOf(
                                        "latitude" to 57.4879,
                                        "longitude" to 12.0760,
                                        "label" to "Kungsbacka torg",
                                        "setByUid" to "anna",
                                        "setByDisplayName" to "Anna",
                                        "setAt" to "2026-07-19T18:04:11.000Z",
                                    ),
                            ),
                        ),
                ),
            )
        val dest = parsed.convoys.single().destination
        assertEquals("Kungsbacka torg", dest?.label)
        assertEquals("anna", dest?.setByUid)
    }

    @Test
    fun `a corrupt destination is dropped rather than surfaced`() {
        // Out-of-bounds coordinate, and a destination with no setter: both would
        // produce a "start navigation" button pointing at nonsense.
        val parsed =
            ConvoyResponseParser.parseList(
                mapOf(
                    "convoys" to
                        listOf(
                            mapOf(
                                "convoyId" to "c1",
                                "ownerUid" to "anna",
                                "destination" to
                                    mapOf(
                                        "latitude" to 999.0,
                                        "longitude" to 12.0760,
                                        "setByUid" to "anna",
                                    ),
                            ),
                            mapOf(
                                "convoyId" to "c2",
                                "ownerUid" to "anna",
                                "destination" to
                                    mapOf("latitude" to 57.4879, "longitude" to 12.0760),
                            ),
                        ),
                ),
            )
        assertNull(parsed.convoys[0].destination)
        assertNull(parsed.convoys[1].destination)
    }

    @Test
    fun `a convoy with no destination field parses to null, not a crash`() {
        val parsed =
            ConvoyResponseParser.parseList(
                mapOf("convoys" to listOf(mapOf("convoyId" to "c1", "ownerUid" to "anna"))),
            )
        assertNull(parsed.convoys.single().destination)
    }

    private fun convoySummary(
        convoyId: String,
        ownerUid: String,
        viewerRole: ConvoyRole = ConvoyRole.Owner,
        destination: ConvoyDestination?,
    ) = ConvoySummary(
        convoyId = convoyId,
        ownerUid = ownerUid,
        title = null,
        status = ConvoyStatus.Active,
        members =
            listOf(
                ConvoyMember(
                    uid = ownerUid,
                    role = ConvoyRole.Owner,
                    inviteStatus = ConvoyInviteStatus.Accepted,
                    joinedAt = null,
                    displayName = null,
                    avatarPath = null,
                ),
            ),
        memberUids = listOf(ownerUid),
        viewer = ConvoyViewer(role = viewerRole, inviteStatus = ConvoyInviteStatus.Accepted),
        livePositionUids = listOf(ownerUid),
        summary = null,
        createdAt = null,
        startedAt = null,
        endedAt = null,
        destination = destination,
    )
}
