package com.kungsbackacarcommunity.app.clubs

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.notifications.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ClubInvitationFlowTest {
    @get:Rule val compose = createComposeRule()
    private val invite = ClubInvitation("west", "Västkustens klubb", Instant.now().plusSeconds(3600).toString())

    @Test fun invitationArrivesOnAlreadyOpenPageAndCanBeDeclined() {
        val repo = InvitationRepository()
        compose.setContent { KccTheme { SharedClubRoute("me", repository = repo) } }
        compose.waitUntil { repo.loads > 0 }
        compose.runOnIdle { repo.invitations = listOf(invite); repo.revision.value = 1L }
        compose.onNodeWithTag("club-invitation-decline-west").performScrollTo()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "club-invitation-flow.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("club-invitation-decline-west").performClick()
        compose.waitUntil { repo.actions.isNotEmpty() }
        compose.onNodeWithTag("club-invitation-west").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("declineInvitation"), repo.actions.map { it.first }) }
    }

    @Test fun failedResponseKeepsInvitationAndBusyGuardPreventsDoubleSubmit() {
        val repo = InvitationRepository().apply { invitations = listOf(invite); gate = CompletableDeferred(); fail = true }
        compose.setContent { KccTheme { SharedClubRoute("me", repository = repo) } }
        compose.onNodeWithTag("club-invitation-accept-west").performScrollTo().performClick()
        compose.onNodeWithTag("club-invitation-accept-west").assertIsNotEnabled()
        compose.onNodeWithTag("club-invitation-decline-west").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }
        compose.waitUntil { repo.completed }
        compose.onNodeWithTag("club-invitation-west").assertExists()
        compose.onNodeWithTag("club-invitation-accept-west").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("west", repo.actions.first().second["clubId"]) }
    }

    @Test fun inboxInvitationOpensClubPageAndMarksItRead() {
        var opened = false
        var read: String? = null
        val item = AppNotification("invite-1", NotificationCategory.CLUB_INVITE, "Inbjudan till Västkusten", "Välj acceptera eller neka", null, false, System.currentTimeMillis(), relatedEntityId = "west")
        compose.setContent { KccTheme { NotificationsScreen(
            state = NotificationsState.Loaded(listOf(item)), onMarkRead = { read = it },
            onMarkAllRead = {}, onBack = {}, onOpenClub = { opened = true },
        ) } }
        compose.onNodeWithText("Inbjudan till Västkusten").performClick()
        compose.runOnIdle { assertEquals(true, opened); assertEquals("invite-1", read) }
    }

    @Test fun cooldownBlocksAcceptButStillAllowsDecline() {
        val repo = InvitationRepository().apply {
            invitations = listOf(invite)
            cooldown = Instant.now().plusSeconds(86400).toString()
        }
        compose.setContent { KccTheme { SharedClubRoute("me", repository = repo) } }
        compose.onNodeWithTag("club-invitation-accept-west").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("club-invitation-decline-west").assertIsEnabled().performClick()
        compose.waitUntil { repo.actions.isNotEmpty() }
        compose.runOnIdle { assertEquals("declineInvitation", repo.actions.single().first) }
    }

    @Test fun selectingPublicClubDoesNotHidePendingInvitations() {
        val repo = InvitationRepository().apply { invitations = listOf(invite) }
        compose.setContent { KccTheme { SharedClubRoute("me", repository = repo) } }
        compose.onNodeWithText("Öppen klubb").performScrollTo().performClick()
        compose.onNodeWithTag("club-invitation-west").performScrollTo().assertExists()
        compose.onNodeWithTag("club-invitation-accept-west").assertIsEnabled().performClick()
        compose.waitUntil { repo.actions.isNotEmpty() }
        compose.runOnIdle { assertEquals("acceptInvitation", repo.actions.single().first) }
    }
}

private class InvitationRepository : ClubChatDesignRepository() {
    val revision = MutableStateFlow<Long?>(0L)
    var invitations = emptyList<ClubInvitation>()
    var cooldown: String? = null
    var loads = 0
    var gate: CompletableDeferred<Unit>? = null
    var fail = false
    var completed = false
    val actions = mutableListOf<Pair<String, Map<String, Any?>>>()
    override fun observeAccess() = MutableStateFlow<ClubAccess>(ClubAccess.None)
    override fun observeInvitationsRevision() = revision
    override suspend fun state(): ClubState { loads++; return ClubState(joinAllowedAt = cooldown, invitations = invitations) }
    override suspend fun directory(cursor: String?) = ClubPage(listOf(
        Club("public", "Öppen klubb", "Alla är välkomna", "Kungsbacka", "open", "owner", null, 1, 0, ClubStats()),
    ), null)
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> {
        actions += action to fields
        try {
            gate?.await()
            if (fail) throw ClubException("unavailable")
            invitations = emptyList()
            revision.value = (revision.value ?: 0L) + 1L
            return emptyMap()
        } finally { completed = true }
    }
}
