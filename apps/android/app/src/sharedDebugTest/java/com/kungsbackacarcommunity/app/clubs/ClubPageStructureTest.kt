package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same production route/row/menu checks on Robolectric and native Android; fake repositories only. */
@RunWith(AndroidJUnit4::class)
class ClubPageStructureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val openedProfiles = mutableListOf<String>()
    private val scale = mutableFloatStateOf(1f)
    private var neutral = Color.Unspecified
    private fun text(id: Int) = compose.activity.getString(id)
    private fun profile(uid: String = "target") = compose.onNodeWithTag("club-member-profile-$uid")
    private fun overflow(uid: String = "target") = compose.onNodeWithTag("club-member-overflow-$uid")
    private fun menu(action: ClubMemberAction, uid: String = "target") = compose.onNodeWithTag("club-member-action-${action.name}-$uid")
    private fun show(repo: ClubPageTestRepository, dark: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                KccTheme(darkTheme = dark) {
                    neutral = MaterialTheme.colorScheme.onSurface
                    Surface(Modifier.fillMaxSize()) {
                        SharedClubRoute("viewer", repository = repo, onOpenProfile = { openedProfiles += it })
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.runOnIdle { repo.memberLoads > 0 } }
    }
    private fun choose(action: ClubMemberAction, uid: String = "target") {
        overflow(uid).performScrollTo().performClick()
        menu(action, uid).performClick()
    }
    private fun layouts(label: String): List<TextLayoutResult> {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results
    }

    @Test fun nameAndAvatarTapNavigateThroughActualRouteCallbackWithRecipientUid() {
        show(ClubPageTestRepository())
        compose.onNodeWithText("Robin Andersson", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("club-member-avatar-target", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("target", "target"), openedProfiles) }
        overflow().assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertContentDescriptionEquals(compose.activity.getString(R.string.clubPage_memberActions, "Robin Andersson"))
    }

    @Test fun longPressOpensContextMenuButMutationsRequireConfirmationAndCancelIsSilent() {
        val repo = ClubPageTestRepository()
        show(repo)
        menu(ClubMemberAction.RemoveMember).assertDoesNotExist()
        profile().performScrollTo().performTouchInput { longClick() }
        menu(ClubMemberAction.TransferOwnership).assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.clubs_memberActionConfirm, "Robin Andersson")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.clubs_cancel)).performClick()
        compose.runOnIdle { assertTrue(repo.mutations.isEmpty()) }
    }

    @Test fun confirmedPromotionUsesCanonicalPayloadAndBusyGuardPreventsRepeatedActions() {
        val repo = ClubPageTestRepository().apply { gate = CompletableDeferred() }
        show(repo)
        choose(ClubMemberAction.PromoteModerator)
        compose.onNodeWithText(text(R.string.clubs_confirm)).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { repo.mutations.size == 1 } }
        overflow().assertIsNotEnabled()
        profile().assertIsNotEnabled()
        compose.runOnIdle {
            val request = repo.mutations.single()
            assertEquals("moderator", request.action)
            assertEquals(mapOf("clubId" to "club", "targetUid" to "target", "enabled" to true), request.fields)
            assertTrue(request.operationId.length >= 16)
        }
        repo.gate!!.complete(Unit)
        compose.waitUntil(5_000) { compose.runOnIdle { repo.memberLoads >= 2 } }
        compose.runOnIdle { assertEquals(1, repo.mutations.size) }
    }

    @Test fun threeModeratorCapKeepsDemotionAvailableAndPromotionDisabled() {
        val repo = ClubPageTestRepository(moderatorCount = 3)
        show(repo)
        overflow().performScrollTo().performClick()
        menu(ClubMemberAction.PromoteModerator).assertIsNotEnabled()
        menu(ClubMemberAction.TransferOwnership).assertIsEnabled()
        compose.onNodeWithText(text(R.string.clubs_moderatorLimit)).assertIsDisplayed()
        menu(ClubMemberAction.TransferOwnership).performClick()
        compose.onNodeWithText(text(R.string.clubs_cancel)).performClick()
        overflow("moderator").performScrollTo().performClick()
        menu(ClubMemberAction.DemoteModerator, "moderator").assertIsEnabled()
    }

    @Test fun moderatorHasOnlyRemovalForOrdinaryMembersAndNoOwnerOrModeratorMenu() {
        val repo = ClubPageTestRepository(ClubRole.Moderator)
        show(repo)
        overflow("owner").assertDoesNotExist()
        overflow("viewer").assertDoesNotExist()
        overflow().performScrollTo().performClick()
        menu(ClubMemberAction.RemoveMember).assertIsDisplayed()
        menu(ClubMemberAction.PromoteModerator).assertDoesNotExist()
        menu(ClubMemberAction.TransferOwnership).assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.mutations.isEmpty()) }
    }

    @Test fun ordinaryMemberStillOpensProfilesButCannotExposeManagementFromLongPress() {
        val repo = ClubPageTestRepository(ClubRole.Member).apply { club = club.copy(ownerUid = "viewer") }
        show(repo)
        overflow().assertDoesNotExist()
        profile().performScrollTo().performTouchInput { longClick() }
        menu(ClubMemberAction.RemoveMember).assertDoesNotExist()
        menu(ClubMemberAction.PromoteModerator).assertDoesNotExist()
        profile().performClick()
        compose.onNodeWithText(text(R.string.clubs_edit)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("target"), openedProfiles); assertTrue(repo.mutations.isEmpty()) }
    }

    @Test fun accessRevocationDisposesPrivateRosterAndAnyOpenMemberMenu() {
        val repo = ClubPageTestRepository()
        show(repo)
        overflow().performScrollTo().performClick()
        menu(ClubMemberAction.RemoveMember).assertExists()
        compose.runOnIdle { repo.access.value = ClubAccess.None }
        menu(ClubMemberAction.RemoveMember).assertDoesNotExist()
        profile().assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.mutations.isEmpty()) }
    }

    @Test fun serverFailureKeepsRosterAndAllowsFreshConfirmedRetry() {
        val repo = ClubPageTestRepository().apply { failure = ClubException("permission-denied") }
        show(repo)
        choose(ClubMemberAction.RemoveMember)
        compose.onNodeWithText(text(R.string.clubs_confirm)).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { repo.mutations.size == 1 } }
        compose.onNodeWithText(text(R.string.clubs_permissionError)).assertExists()
        profile().assertExists()
        compose.runOnIdle { repo.failure = null }
        choose(ClubMemberAction.RemoveMember)
        compose.onNodeWithText(text(R.string.clubs_confirm)).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { repo.mutations.size == 2 } }
        compose.runOnIdle { assertNotEquals(repo.mutations[0].operationId, repo.mutations[1].operationId) }
    }

    @Test fun darkReadingTextUsesNeutralThemeColorInsteadOfInheritedGold() {
        val repo = ClubPageTestRepository()
        show(repo, dark = true)
        for (label in listOf(repo.club.name, repo.club.description, "184", "Robin Andersson")) {
            assertEquals(label, neutral, layouts(label).single().layoutInput.style.color)
        }
    }

    @Test fun memberNamesAndStatisticsWrapAtLargeFontWithoutOverflowOrLostMenuTarget() {
        val repo = ClubPageTestRepository().apply {
            roster = roster.map { if (it.uid == "target") it.copy(displayName = "Alexandra Lindström Andersson") else it }
        }
        show(repo)
        for (fontScale in listOf(1.3f, 2f)) {
            compose.runOnIdle { scale.floatValue = fontScale }
            profile().performScrollTo().assertIsDisplayed()
            val name = layouts("Alexandra Lindström Andersson").single()
            assertFalse(name.didOverflowWidth)
            assertFalse(name.didOverflowHeight)
            overflow().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            assertFalse(layouts(text(R.string.clubs_statsNote)).single().didOverflowWidth)
        }
    }

    @Test fun clubManagementComesAfterRosterAndOpenClubHasNoInvitationAction() {
        val repo = ClubPageTestRepository().apply { club = club.copy(visibility = "open") }
        show(repo)
        compose.onNodeWithText(text(R.string.clubs_invite)).assertDoesNotExist()
        val order = listOf(repo.club.name, text(R.string.clubs_statistics), text(R.string.clubs_calendar),
            text(R.string.clubs_members), text(R.string.clubs_manage)).map {
            compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(order.zipWithNext().all { (before, after) -> before < after })
    }
}

private data class ClubPageTestMutation(val action: String, val fields: Map<String, Any?>, val operationId: String)
private class ClubPageTestRepository(role: ClubRole = ClubRole.Owner, moderatorCount: Int = 1) : ClubRepository {
    private val membership = ClubMembership("club", role, "2026-10-01T10:00:00Z")
    val access = MutableStateFlow<ClubAccess>(ClubAccess.Member(membership))
    var club = Club("club", "Motorvännerna", "Gemensamma lugna rundor längs västkusten.", "Kungsbacka", "closed",
        if (role == ClubRole.Owner) "viewer" else "owner", null, 4, moderatorCount, ClubStats(184, 12_684_500.0, 729_600))
    var roster = listOf(
        ClubMember(if (role == ClubRole.Owner) "viewer" else "owner", "Sofia Karlsson", ClubRole.Owner),
        ClubMember(if (role == ClubRole.Moderator) "viewer" else "moderator", "Alva Lindström", ClubRole.Moderator),
        ClubMember("target", "Robin Andersson", ClubRole.Member),
        ClubMember(if (role == ClubRole.Member) "viewer" else "extra", "Samira Berg", ClubRole.Member),
    )
    var memberLoads = 0
    val mutations = mutableListOf<ClubPageTestMutation>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    override fun observeEnabled() = flowOf(true)
    override fun observePublicAttendanceReady() = flowOf(true)
    override fun observeAccess() = access
    override suspend fun state() = ClubState(club, membership)
    override suspend fun directory(cursor: String?) = ClubPage<Club>(emptyList(), null)
    override suspend fun get(clubId: String) = club
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> { memberLoads++; return ClubPage(roster, null) }
    override suspend fun events(clubId: String, cursor: String?) = ClubPage<ClubEvent>(emptyList(), null)
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> {
        mutations += ClubPageTestMutation(action, fields, operationId)
        gate?.await()
        failure?.let { throw it }
        return emptyMap()
    }
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("Not part of this fixture")
    override fun chat() = flowOf<ClubChatState>(ClubChatState.Unavailable)
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor) = ClubMessagePage(emptyList(), null, false)
    override fun observeMessageRows(clubId: String, messageIds: List<String>) = flowOf(emptyList<ClubMessage>())
    override suspend fun send(clubId: String, text: String, operationId: String): Unit = error("No chat calls in this fixture")
    override fun eventParticipants(eventId: String) = flowOf(emptyList<Club>())
    override fun observeEventParticipants(eventId: String, clubIds: List<String>) = flowOf(emptyList<Club>())
    override fun eventAttendance(eventId: String, clubId: String) = flowOf<Boolean?>(false)
    override suspend fun moreEventParticipants(eventId: String, afterId: String) = emptyList<Club>()
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String): Unit = error("No event mutation in this fixture")
    override suspend fun blockAuthor(uid: String): Unit = error("No block call in this fixture")
}
