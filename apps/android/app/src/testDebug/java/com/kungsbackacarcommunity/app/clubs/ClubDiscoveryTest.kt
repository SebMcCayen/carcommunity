package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.live.LiveMarker
import com.kungsbackacarcommunity.app.live.LiveSharerPopup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClubDiscoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun tab(id: Int) = compose.onAllNodesWithText(text(id)).onFirst()

    @Test fun memberCanBrowsePaginateReadAnotherClubAndReturnToOwnClub() {
        val repo = DiscoveryRepository(member = true)
        compose.setContent { KccTheme { SharedClubRoute("viewer", repository = repo) } }
        compose.onNodeWithText("Own club").assertExists()
        tab(R.string.clubs_allClubs).performScrollTo().performClick()
        compose.onNodeWithText("Own club").assertDoesNotExist()
        compose.onNodeWithText("Other club").assertExists()
        compose.onNodeWithText(text(R.string.clubs_more)).performScrollTo().performClick()
        compose.onNodeWithText("Last club").assertExists()
        compose.onAllNodes(hasText(text(R.string.clubs_title)) and hasClickAction()).onFirst()
            .performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.clubs_readOnly)).assertExists()
        compose.onNodeWithText(text(R.string.clubs_join)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("page2"), repo.pages.filterNotNull()); assertFalse("other" in repo.rosters) }
        tab(R.string.clubs_myClub).performScrollTo().performClick()
        compose.onNodeWithText("Own club").assertExists()
    }

    @Test fun mapOwnClubDetailRefreshesAfterOwnerEdits() {
        val repo = DiscoveryRepository(member = true, role = ClubRole.Owner)
        compose.setContent { KccTheme { SharedClubRoute("viewer", repository = repo, initialClubId = "own") } }
        compose.onNodeWithText("Own club").assertExists()
        compose.onNodeWithText(text(R.string.clubs_edit)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Own club")).performTextReplacement("Renamed club")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(text(R.string.clubs_save)).performClick()
        compose.onNodeWithText("Renamed club").assertExists()
        compose.onNodeWithText("Own club").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.clubs_invite)).assertExists()
        tab(R.string.clubs_myClub).performScrollTo().performClick()
        compose.onNodeWithText("Renamed club").assertExists()
    }

    @Test fun nonmemberHasEmptyOwnTabAndCanBrowseAllClubs() {
        val repo = DiscoveryRepository()
        compose.setContent { KccTheme { SharedClubRoute("viewer", repository = repo) } }
        compose.onNodeWithText(text(R.string.clubs_noMembership)).assertExists()
        tab(R.string.clubs_allClubs).performScrollTo().performClick()
        compose.onNodeWithText("Own club").assertExists()
        compose.onNodeWithText("Other club").assertExists()
    }

    @Test fun mapTargetOutsideFirstPageOpensAndRevocationDisposesPrivateContent() {
        val repo = DiscoveryRepository(member = true)
        compose.setContent { KccTheme { SharedClubRoute("viewer", repository = repo, initialClubId = "last") } }
        compose.onNodeWithText("Last club").assertExists()
        compose.onNodeWithText(text(R.string.clubs_readOnly)).assertExists()
        compose.runOnIdle { assertEquals(listOf("last"), repo.gets); assertFalse("last" in repo.rosters) }
        tab(R.string.clubs_myClub).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.clubs_members)).assertExists()
        compose.runOnIdle { repo.access.value = ClubAccess.Unverified }
        compose.onNodeWithText(text(R.string.clubs_members)).assertDoesNotExist()
        tab(R.string.clubs_allClubs).performScrollTo().performClick()
        compose.onNodeWithText("Last club").assertExists()
    }

    @Test fun popupHidesAbsentAffiliationNavigatesToExactClubAndRemovesRevokedAffiliation() {
        val repo = DiscoveryRepository()
        var visited: String? = null
        var dismissed = false
        compose.setContent { KccTheme {
            LiveSharerPopup(
                sharer = LiveMarker(uid = "sharer", latitude = 57.0, longitude = 12.0),
                pointsRepository = null, onVisitProfile = {}, onDismiss = { dismissed = true },
                clubRepository = repo, onVisitClub = { visited = it },
            )
        } }
        compose.onNodeWithTag("live_sharer_popup_club").assertDoesNotExist()
        compose.runOnIdle { repo.affiliation.value = "other" }
        compose.onNodeWithTag("live_sharer_popup_club").performClick()
        compose.runOnIdle { assertEquals("other", visited); assertTrue(dismissed); repo.affiliation.value = null }
        compose.onNodeWithTag("live_sharer_popup_club").assertDoesNotExist()
        compose.onNodeWithTag("live_sharer_popup_visit").assertExists()
    }
}

private fun discoveryClub(id: String, name: String) = Club(id, name, "About $name", "Kungsbacka", "open", "owner", null, 1, 0, ClubStats())

private class DiscoveryRepository(member: Boolean = false, role: ClubRole = ClubRole.Member) : ClubRepository {
    val own = discoveryClub("own", "Own club").let { if (role == ClubRole.Owner) it.copy(ownerUid = "viewer") else it }
    val other = discoveryClub("other", "Other club")
    val last = discoveryClub("last", "Last club")
    private val membership = ClubMembership("own", role, "2026-11-01T12:00:00Z")
    val access = MutableStateFlow<ClubAccess>(if (member) ClubAccess.Member(membership) else ClubAccess.None)
    val affiliation = MutableStateFlow<String?>(null)
    val pages = mutableListOf<String?>()
    val gets = mutableListOf<String>()
    val rosters = mutableListOf<String>()
    private var currentState = if (member) ClubState(own, membership) else ClubState()
    override fun observeEnabled() = flowOf(true)
    override fun observePublicAttendanceReady() = flowOf(false)
    override fun observeAccess(): Flow<ClubAccess> = access
    override fun observeAffiliation(memberUid: String): Flow<String?> = affiliation
    override suspend fun state() = currentState
    override suspend fun directory(cursor: String?): ClubPage<Club> {
        pages += cursor
        return if (cursor == null) ClubPage(listOf(own, other), "page2") else ClubPage(listOf(last), null)
    }
    override suspend fun get(clubId: String): Club { gets += clubId; return listOf(own, other, last).first { it.id == clubId } }
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> {
        rosters += clubId; return ClubPage(emptyList(), null)
    }
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> = ClubPage(emptyList(), null)
    override fun eventParticipants(eventId: String): Flow<List<Club>> = flowOf(emptyList())
    override fun observeEventParticipants(eventId: String, clubIds: List<String>): Flow<List<Club>> = flowOf(emptyList())
    override fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?> = flowOf(null)
    override suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club> = emptyList()
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> {
        check(action == "update" && fields["clubId"] == own.id)
        currentState = currentState.copy(club = own.copy(
            name = fields["name"] as String,
            description = fields["description"] as String,
            location = fields["location"] as String,
            visibility = fields["visibility"] as String,
        ))
        return emptyMap()
    }
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("unused")
    override fun chat(): Flow<ClubChatState> = error("unused")
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor): ClubMessagePage = error("unused")
    override fun observeMessageRows(clubId: String, messageIds: List<String>): Flow<List<ClubMessage>> = error("unused")
    override suspend fun send(clubId: String, text: String, operationId: String) = error("unused")
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = error("unused")
    override suspend fun blockAuthor(uid: String) = error("unused")
}
