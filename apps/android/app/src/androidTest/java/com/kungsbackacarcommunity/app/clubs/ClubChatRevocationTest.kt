package com.kungsbackacarcommunity.app.clubs

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.CHAT_QUICK_EMOJI_ROW_TEST_TAG
import com.kungsbackacarcommunity.app.design.chatQuickEmojiTestTag
import com.kungsbackacarcommunity.app.profile.LiveProfile
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*

/** Security UX: membership loss must dispose history AND unsent drafts, not just disable send. */
class ClubChatRevocationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun matchProductionImeWindow() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun assertDraft(text: String) {
        assertEquals(text, compose.onNodeWithTag("club-chat-input")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private val member = ClubMembership("first", ClubRole.Member, "2026-10-27T10:00:00Z")
    @Test fun revokedMembershipClearsHistoryAndDraftBeforeAnotherClubOpens() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(ClubMessage("one", "friend", "Friend", "Private club message", 1L))))
        val gate = CompletableDeferred<Unit>()
        repo.sendGate = gate
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText("Private club message").assertIsDisplayed()
        compose.onNodeWithTag("club-chat-input").performTextInput("Private outgoing message")
        compose.onNodeWithText(str(R.string.clubs_send)).performClick()
        compose.waitUntil { compose.runOnIdle { repo.sendRequests.size == 1 } }
        assertDraft("")
        compose.onNodeWithText("Private outgoing message").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertIsDisplayed()
        compose.onNodeWithTag("club-chat-input").performTextInput("Unsent private draft")
        assertDraft("Unsent private draft")
        compose.runOnIdle {
            assertFalse(gate.isCompleted)
            assertNull(repo.sent)
            repo.messages.value = ClubChatState.Unavailable
        }
        compose.onNodeWithText("Private club message").assertDoesNotExist()
        compose.onNodeWithText("Private outgoing message").assertDoesNotExist()
        compose.onNodeWithText("Unsent private draft").assertDoesNotExist()
        compose.onNodeWithTag("club-chat-input").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertDoesNotExist()
        compose.runOnIdle {
            repo.messages.value = ClubChatState.Ready(member.copy(clubId = "second"), emptyList())
        }
        assertDraft("")
        // A late response from the disposed room must not repopulate the new club.
        compose.runOnIdle { gate.complete(Unit) }
        compose.onNodeWithText("Unsent private draft").assertDoesNotExist()
        compose.onNodeWithText("Private outgoing message").assertDoesNotExist()
        compose.onNodeWithText("Private club message").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).assertDoesNotExist()
        assertDraft("")
        compose.runOnIdle { assertEquals(1, repo.sendRequests.size) }
    }
    @Test fun olderPagesTrackRemoteRemovalAndAdvancePastRedactedRows() {
        val start = ClubMessageCursor("recent", 100, 123)
        val next = ClubMessageCursor("old25", 50, 456)
        val repo = FakeClubRepository(ClubChatState.Ready(member,
            listOf(ClubMessage("recent", "friend", "Friend", "Recent", 100000L)), oldestCursor = start, hasOlder = true))
        repo.historical.value = listOf(ClubMessage("old1", "friend", "Friend", "Moderated remotely", 90000L))
        repo.pageResults = listOf(ClubMessagePage((1..25).map { "old$it" }, next, true), ClubMessagePage(emptyList(), null, false))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText(str(R.string.channel_loadOlder)).performClick()
        compose.onNodeWithText("Moderated remotely").assertExists()
        compose.runOnIdle { repo.historical.value = emptyList() }
        compose.onNodeWithText("Moderated remotely").assertDoesNotExist()
        // Query exhaustion comes from 25 raw rows, not the now-zero visible rows.
        compose.onNodeWithText(str(R.string.channel_loadOlder)).performClick()
        compose.waitUntil { repo.cursorRequests.size == 2 }
        compose.runOnIdle { assertEquals(listOf(start, next), repo.cursorRequests) }
    }
    @Test fun loadedHistoryKeepsEveryDisplacedWindowLiveAndHonorsModeration() {
        fun message(i: Int) = ClubMessage("m$i", "author$i", "Member $i", "Message $i", i * 1000L)
        fun cursor(i: Int) = ClubMessageCursor("m$i", i.toLong(), i * 1_000_000 + 123)
        val all = (1..102).map(::message)
        val repo = FakeClubRepository(ClubChatState.Unavailable)
        repo.historical.value = all
        // The raw page ends on a redacted row. It must still advance the nanos cursor.
        repo.redactedIds.value = setOf("m26")
        repo.pageResults = listOf(
            ClubMessagePage((50 downTo 26).map { "m$it" }, cursor(26), true),
            ClubMessagePage((25 downTo 1).map { "m$it" }, cursor(1), true),
            ClubMessagePage(emptyList(), null, false),
        )
        var hidden = emptySet<String>()
        fun publishLatest(last: Int) {
            val raw = repo.historical.value.filter { it.createdAtMillis!! <= last * 1000L }
                .sortedByDescending { it.createdAtMillis }.take(25)
            val boundary = raw.last().createdAtMillis!! / 1000L
            repo.messages.value = ClubChatState.Ready(member,
                raw.filterNot { it.id in repo.redactedIds.value }, hidden, cursor(boundary.toInt()), raw.size == 25)
        }
        fun show(text: String) {
            compose.onNodeWithTag("club-message-list").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        fun loadOlder() {
            show(str(R.string.channel_loadOlder))
            compose.onNodeWithText(str(R.string.channel_loadOlder)).performClick()
            compose.waitForIdle()
        }
        publishLatest(75)
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        loadOlder()
        loadOlder()
        // Each arrival gets its own settled window, so StateFlow cannot conflate them.
        // Only one partial query may exist beyond full batches, not one per arrival.
        for (last in 76..101) {
            compose.runOnIdle { publishLatest(last) }
            compose.runOnIdle {
                val retainedIdCount = 25 + (last - 75)
                val retainedQueryBound = (retainedIdCount + 24) / 25
                assertEquals("Active history + retained queries after arrival $last",
                    2 + retainedQueryBound, repo.activeRowQueryCount())
            }
        }
        for (i in 101 downTo 1) if (i != 26) show("Message $i")
        compose.runOnIdle {
            assertTrue(repo.rowRequests.all { it.size in 1..25 })
            assertTrue(repo.activeRowIds().containsAll((1..101).map { "m$it" }))
        }
        // Displaced rows must still react to remote redaction, block, and deletion.
        for (i in listOf(51, 76)) {
            show("Message $i")
            compose.runOnIdle { repo.redactedIds.value += "m$i" }
            compose.onNodeWithText("Message $i").assertDoesNotExist()
        }
        for (i in listOf(52, 77, 20)) {
            show("Message $i")
            compose.runOnIdle { hidden = hidden + "author$i"; publishLatest(101) }
            compose.onNodeWithText("Message $i").assertDoesNotExist()
        }
        for (i in listOf(53, 78, 21)) {
            show("Message $i")
            compose.runOnIdle {
                repo.historical.value = repo.historical.value.filterNot { it.id == "m$i" }
                publishLatest(101)
            }
            compose.onNodeWithText("Message $i").assertDoesNotExist()
        }
        compose.runOnIdle { publishLatest(102) }
        show("Message 102")
        loadOlder()
        compose.runOnIdle { assertEquals(listOf(cursor(51), cursor(26), cursor(1)), repo.cursorRequests) }
        compose.onNodeWithText(str(R.string.channel_loadOlder)).assertDoesNotExist()
    }
    @Test fun arrivalsDuringPageLoadStayCoveredAndRevocationDisposesPinnedIds() {
        val initial = (1..25).map { ClubMessage("m$it", "friend", "Friend", "Message $it", it * 1000L) }
        val boundary = ClubMessageCursor("m1", 1, 987654321)
        val repo = FakeClubRepository(ClubChatState.Ready(member, initial.reversed(), oldestCursor = boundary, hasOlder = true))
        repo.historical.value = initial
        val gate = CompletableDeferred<Unit>()
        repo.pageGate = gate
        repo.pageResults = listOf(ClubMessagePage(listOf("older"), ClubMessageCursor("older", 0, 123), false))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithTag("club-message-list").performScrollToNode(hasText(str(R.string.channel_loadOlder)))
        compose.onNodeWithText(str(R.string.channel_loadOlder)).performClick()
        compose.waitUntil { repo.cursorRequests.size == 1 }
        compose.runOnIdle {
            val arriving = ClubMessage("new", "friend", "Friend", "New arrival", 26000L)
            repo.historical.value += arriving
            repo.messages.value = ClubChatState.Ready(member, listOf(arriving) + initial.drop(1).reversed(),
                oldestCursor = ClubMessageCursor("m2", 2, 456), hasOlder = true)
        }
        compose.onNodeWithTag("club-message-list").performScrollToNode(hasText("Message 1"))
        compose.onNodeWithText("Message 1").assertIsDisplayed()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("club-chat-input").performTextInput("Private paged draft")
        compose.runOnIdle {
            assertEquals(listOf(boundary), repo.cursorRequests)
            assertTrue(repo.activeRowIds().containsAll(initial.map { it.id } + "older" + "new"))
            repo.messages.value = ClubChatState.Unavailable
        }
        compose.onNodeWithText("Message 1").assertDoesNotExist()
        compose.onNodeWithText("Private paged draft").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.activeRowIds().isEmpty()) }
        // Even a new session for the same membership must not reuse private query IDs.
        compose.runOnIdle {
            repo.pageGate = null
            repo.pageResults = listOf(
                ClubMessagePage(emptyList(), null, false),
                ClubMessagePage(listOf("fresh-history"), ClubMessageCursor("fresh-history", 0, 999), false),
            )
            repo.messages.value = ClubChatState.Ready(member,
                listOf(ClubMessage("fresh", "friend", "Friend", "Fresh session", 100000L)),
                oldestCursor = ClubMessageCursor("fresh", 100, 111), hasOlder = true)
            repo.historical.value = initial.map { it.copy(text = "Late private snapshot") } +
                ClubMessage("fresh-history", "friend", "Friend", "Fresh history", 0L)
        }
        compose.onNodeWithText(str(R.string.channel_loadOlder)).performClick()
        compose.onNodeWithText("Fresh history").assertIsDisplayed()
        compose.onNodeWithText("Late private snapshot").assertDoesNotExist()
        compose.onNodeWithText("Private paged draft").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf("fresh", "fresh-history"), repo.activeRowIds())
            assertEquals(ClubMessageCursor("fresh", 100, 111), repo.cursorRequests.last())
        }
        // Background/re-entry must dispose the new session's pinned queries too,
        // even if the stopped activity does not draw another composition frame.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        repo.messages.value = ClubChatState.Unavailable
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Fresh history").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(repo.activeRowIds().isEmpty())
            repo.messages.value = ClubChatState.Ready(member, emptyList())
        }
        compose.onNodeWithText("Late private snapshot").assertDoesNotExist()
        compose.onNodeWithText("Private paged draft").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.activeRowIds().isEmpty()) }
    }
    @Test fun backgroundReturnRequiresFreshProofAndDiscardsDraft() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(ClubMessage("one", "friend", "Friend", "Before background", 1L))))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithTag("club-chat-input").performTextInput("Private unsent text")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        repo.messages.value = ClubChatState.Unavailable
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Before background").assertDoesNotExist()
        compose.onNodeWithText("Private unsent text").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.chatCalls >= 2) }
    }
    @Test fun createClubThenLeaveReturnsToDirectoryWithCooldown() {
        val repo = FakeClubRepository(ClubChatState.Unavailable)
        compose.setContent { KccTheme { SharedClubRoute("me", repository = repo) } }
        compose.onNodeWithText(str(R.string.clubs_create)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.clubs_name)).performTextInput("Västkustklubben")
        compose.onNodeWithText(str(R.string.clubs_description)).performTextInput("En klubb för alla bilintresserade")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("club-county-field").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("club-county-field").performScrollTo().performClick()
        compose.onNodeWithTag("club-county-list").performScrollToNode(hasTestTag("club-county-13"))
        compose.onNodeWithTag("club-county-13").performClick()
        compose.onNodeWithTag("club-locality-field").performScrollTo().performClick()
        compose.onNodeWithTag("club-locality-search").performTextInput("Kungsbacka")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("club-locality-list").performScrollToNode(hasTestTag("club-locality-1384TC117"))
        compose.onNodeWithTag("club-locality-1384TC117").performClick()
        compose.onNodeWithText(str(R.string.clubs_save)).performClick()
        compose.onNodeWithText("Västkustklubben").assertExists()
        compose.onNodeWithText(str(R.string.clubs_manage)).assertExists()
        compose.runOnIdle {
            assertEquals("open", repo.createdPayload?.get("visibility"))
            assertEquals("Västkustklubben", repo.createdPayload?.get("name"))
            assertEquals("Kungsbacka, Hallands län", repo.createdPayload?.get("location"))
        }
        compose.onNodeWithText(str(R.string.clubs_leave)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.clubs_confirm)).performClick()
        compose.onNodeWithText(str(R.string.clubs_create)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Västkustklubben").assertDoesNotExist()
    }
    @Test fun textAndEmojiAreSentWithoutTransformation() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, emptyList()))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithTag("club-chat-input").performTextInput("Hej klubben 🚗👋")
        compose.onNodeWithText(str(R.string.clubs_send)).performClick()
        compose.waitUntil { repo.sent != null }
        compose.runOnIdle { assertEquals("Hej klubben 🚗👋", repo.sent); assertTrue(repo.operation!!.length >= 16) }
    }
    @Test fun blockedAuthorsDisappearFromAlreadyLoadedMessages() {
        val message = ClubMessage("one", "hidden", "Hidden", "Blocked text", 1L)
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(message)))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText("Blocked text").assertIsDisplayed()
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(member, listOf(message), setOf("hidden")) }
        compose.onNodeWithText("Blocked text").assertDoesNotExist()
    }

    @Test fun failedSendClearsComposerRetainsBubbleAndRetriesSameIdWithoutConsumingNextDraft() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, emptyList()))
        repo.sendFailuresRemaining = 1
        val firstGate = CompletableDeferred<Unit>()
        val retryGate = CompletableDeferred<Unit>()
        repo.sendGate = firstGate
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithTag("club-chat-input").performTextInput("Keep this draft 🚗")
        compose.onNodeWithText(str(R.string.clubs_send)).performClick()
        compose.waitUntil { compose.runOnIdle { repo.sendRequests.size == 1 } }
        assertDraft("")
        compose.onNodeWithText("Keep this draft 🚗").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(firstGate.isCompleted)
            assertNull(repo.sent)
            firstGate.complete(Unit)
        }
        // An ambiguous failure retains the outgoing bubble, not a restored input.
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertDoesNotExist()
        assertDraft("")
        compose.onAllNodesWithText("Keep this draft 🚗").assertCountEquals(1)
        compose.onNodeWithText(str(R.string.clubs_send)).assertIsNotEnabled()
        compose.onNodeWithTag("club-chat-input").performTextInput("Another message")
        compose.runOnIdle { repo.sendGate = retryGate }
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).performClick()
        compose.waitUntil { compose.runOnIdle { repo.sendRequests.size == 2 } }
        assertDraft("Another message")
        compose.onAllNodesWithText("Keep this draft 🚗").assertCountEquals(1)
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.clubs_send)).assertIsNotEnabled()
        compose.onNodeWithTag("club-chat-input").performTextReplacement("Edited next draft 🚙")
        compose.runOnIdle {
            assertEquals(repo.sendRequests[0], repo.sendRequests[1])
            assertEquals("Keep this draft 🚗", repo.sendRequests[0].first)
            assertTrue(repo.sendRequests[0].second.length >= 16)
            assertFalse(retryGate.isCompleted)
            assertNull(repo.sent)
            retryGate.complete(Unit)
        }
        assertDraft("Edited next draft 🚙")
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).assertDoesNotExist()
        compose.onAllNodesWithText("Keep this draft 🚗").assertCountEquals(1)
        compose.runOnIdle {
            assertEquals("Keep this draft 🚗", repo.sent)
            repo.messages.value = ClubChatState.Ready(member, listOf(ClubMessage(
                clubPendingMessageId("me", repo.sendRequests[0].second), "me", "Me", "Keep this draft 🚗", 1L,
            )))
            repo.sendGate = null
        }
        compose.onAllNodesWithText("Keep this draft 🚗").assertCountEquals(1)
        assertDraft("Edited next draft 🚙")
        compose.onNodeWithText(str(R.string.clubs_send)).performClick()
        compose.waitUntil { compose.runOnIdle { repo.sendRequests.size == 3 } }
        assertDraft("")
        compose.onNodeWithText("Edited next draft 🚙").assertIsDisplayed()
        compose.runOnIdle {
            assertNotEquals(repo.sendRequests[1].second, repo.sendRequests[2].second)
            assertEquals("Edited next draft 🚙", repo.sent)
        }
    }

    @Test fun quickEmojiRetriesSameOperationAndNeverConsumesTypedDraft() {
        val repo = FakeClubRepository(ClubChatState.Ready(member, emptyList()))
        repo.sendFailuresRemaining = 1
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithTag("club-chat-input").performTextInput("Unsent typed message")
        compose.onNodeWithTag(CHAT_QUICK_EMOJI_ROW_TEST_TAG).assertExists()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).performClick()
        compose.waitUntil { repo.sendRequests.size == 1 }
        compose.onNodeWithText("Unsent typed message").assertIsDisplayed()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).performClick()
        compose.waitUntil { repo.sendRequests.size == 2 }
        compose.runOnIdle {
            assertEquals(repo.sendRequests[0], repo.sendRequests[1])
            assertEquals("😊", repo.sent)
        }
        compose.onNodeWithText("Unsent typed message").assertIsDisplayed()
    }

    @Test fun profileOverlayIsDistinctFilteredAndDisposedWhenMembershipIsRevoked() {
        val visible = ClubMessage("one", "friend", "Stored friend", "Visible message", 1L)
        val hidden = ClubMessage("hidden", "blocked", "Blocked friend", "Hidden body", 2L)
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(visible, visible.copy(id = "two"), hidden), setOf("blocked")))
        val requested = mutableListOf<Set<String>>()
        var active = 0
        val live = MutableStateFlow(mapOf("friend" to LiveProfile("Current friend", null)))
        val profiles = object : LiveProfileRepository {
            override fun observeProfiles(uids: Set<String>) = flow {
                requested += uids; active++
                try { emitAll(live) } finally { active-- }
            }
            override suspend fun loadProfiles(uids: Set<String>) = emptyMap<String, LiveProfile>()
        }
        compose.setContent { KccTheme { ClubChatRoute("me", repo, profiles) } }
        compose.onAllNodesWithText("Current friend").assertCountEquals(2)
        compose.onNodeWithText("Hidden body").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(setOf("friend")), requested)
            assertEquals(1, active)
            live.value = mapOf("friend" to LiveProfile("Changed friend", null))
        }
        compose.onAllNodesWithText("Changed friend").assertCountEquals(2)
        compose.runOnIdle { repo.messages.value = ClubChatState.Unavailable }
        compose.onNodeWithText("Visible message").assertDoesNotExist()
        compose.onNodeWithText("Changed friend").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, active) }
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(member, listOf(visible)) }
        compose.onNodeWithText("Changed friend").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, active) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        repo.messages.value = ClubChatState.Unavailable
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Changed friend").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, active) }
    }

    @Test fun profileFailureKeepsReceivingClubMessages() {
        val repo = FakeClubRepository(ClubChatState.Ready(member,
            listOf(ClubMessage("one", "friend", "Stored friend", "Before profile failure", 1L))))
        val profiles = object : LiveProfileRepository {
            override fun observeProfiles(uids: Set<String>): Flow<Map<String, LiveProfile>> = flow { error("Local profile failure") }
            override suspend fun loadProfiles(uids: Set<String>) = emptyMap<String, LiveProfile>()
        }
        compose.setContent { KccTheme { ClubChatRoute("me", repo, profiles) } }
        compose.onNodeWithText("Stored friend").assertIsDisplayed()
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(member,
            listOf(ClubMessage("two", "friend", "Stored friend", "After profile failure", 2L))) }
        compose.onNodeWithText("After profile failure").assertIsDisplayed()
    }

    @Test fun finiteProfileBatchesRefreshOnNewMessagesWithSameSendersAfterCacheExpiry() {
        val first = ClubMessage("one", "friend", "Stored friend", "First message", 1L)
        val blocked = ClubMessage("blocked", "hidden", "Hidden member", "Blocked message", 0L)
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(first, blocked), setOf("hidden")))
        val requested = mutableListOf<Set<String>>()
        var completed = 0
        var remoteReads = 0
        var now = 0L
        var expiresAt = 0L
        var backendProfile = LiveProfile("First profile", null)
        var cached = emptyMap<String, LiveProfile>()
        val profiles = object : LiveProfileRepository {
            // Mirrors Firebase's finite cache-then-refresh flow, not an always-live StateFlow.
            override fun observeProfiles(uids: Set<String>) = flow {
                requested += uids
                emit(cached.filterKeys { it in uids })
                if (now >= expiresAt) {
                    remoteReads++
                    cached = mapOf("friend" to backendProfile)
                    expiresAt = now + 1_000L
                    emit(cached.filterKeys { it in uids })
                }
                completed++
            }
            override suspend fun loadProfiles(uids: Set<String>) = emptyMap<String, LiveProfile>()
        }
        compose.setContent { KccTheme { ClubChatRoute("me", repo, profiles) } }
        compose.onNodeWithText("First profile").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, completed)
            assertEquals(1, remoteReads)
            backendProfile = LiveProfile("Refreshed profile", null)
            now = 999L
            repo.messages.value = ClubChatState.Ready(member,
                listOf(first.copy(id = "two", text = "Second message", createdAtMillis = 2L), first, blocked), setOf("hidden"))
        }
        compose.onAllNodesWithText("First profile").assertCountEquals(2)
        compose.runOnIdle {
            assertEquals(2, completed)
            assertEquals("A fresh cache avoids another remote read", 1, remoteReads)
            now = 1_001L
            repo.messages.value = ClubChatState.Ready(member,
                listOf(first.copy(id = "three", text = "Third message", createdAtMillis = 3L),
                    first.copy(id = "two", text = "Second message", createdAtMillis = 2L), first, blocked), setOf("hidden"))
        }
        compose.onAllNodesWithText("Refreshed profile").assertCountEquals(3)
        compose.onNodeWithText("First profile").assertDoesNotExist()
        compose.onNodeWithText("Blocked message").assertDoesNotExist()
        // Unrelated draft recomposition must not re-read profiles or restart a finite batch.
        compose.onNodeWithTag("club-chat-input").performTextInput("Private unsent draft")
        compose.runOnIdle {
            assertEquals(3, completed)
            assertEquals(2, remoteReads)
            assertEquals(List(3) { setOf("friend") }, requested)
        }
        compose.runOnIdle { repo.messages.value = ClubChatState.Unavailable }
        compose.onNodeWithText("Private unsent draft").assertDoesNotExist()
        compose.onNodeWithText("Refreshed profile").assertDoesNotExist()
    }

    @Test fun finiteFailedProfileReadRecoversOnNextMessageWithSameSenderSet() {
        val first = ClubMessage("one", "friend", "Stored friend", "Before recovery", 1L)
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(first)))
        val requested = mutableListOf<Set<String>>()
        var completed = 0
        var failed = true
        val profiles = object : LiveProfileRepository {
            override fun observeProfiles(uids: Set<String>) = flow {
                requested += uids
                // Firebase's failed uncached reads return an absent entry, then complete.
                emit(if (failed) emptyMap() else mapOf("friend" to LiveProfile("Recovered friend", null)))
                completed++
            }
            override suspend fun loadProfiles(uids: Set<String>) = emptyMap<String, LiveProfile>()
        }
        compose.setContent { KccTheme { ClubChatRoute("me", repo, profiles) } }
        compose.onNodeWithText("Stored friend").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, completed)
            failed = false
            repo.messages.value = ClubChatState.Ready(member,
                listOf(first.copy(id = "two", text = "After recovery", createdAtMillis = 2L), first))
        }
        compose.onAllNodesWithText("Recovered friend").assertCountEquals(2)
        compose.onNodeWithText("Stored friend").assertDoesNotExist()
        compose.onNodeWithText("After recovery").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, completed)
            assertEquals(List(2) { setOf("friend") }, requested)
        }
    }

    @Test fun moderatorCanHideOwnBubbleAndRemovedSelectionClosesActions() {
        val own = ClubMessage("own", "me", "Me", "My moderated message", 1L)
        val mod = member.copy(role = ClubRole.Moderator)
        val repo = FakeClubRepository(ClubChatState.Ready(mod, listOf(own)))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText(own.text).performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.clubs_report)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.clubs_block)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.clubs_hideMessage)).performClick()
        compose.waitUntil { repo.managementRequests.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("removeMessage", repo.managementRequests.single().first)
            assertEquals("own", repo.managementRequests.single().second["messageId"])
        }
        compose.onNodeWithText(own.text).performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.clubs_hideMessage)).assertIsDisplayed()
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(mod, emptyList()) }
        compose.onNodeWithText(str(R.string.clubs_hideMessage)).assertDoesNotExist()
        compose.onNodeWithText(own.text).assertDoesNotExist()
    }

    @Test fun memberCanBlockOtherSenderFromBubbleAndBlockingClosesSelection() {
        val other = ClubMessage("other", "friend", "Friend", "Selected incoming message", 1L)
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(other)))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText(other.text).performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.clubs_report)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.clubs_hideMessage)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.clubs_block)).performClick()
        compose.waitUntil { repo.blockedAuthors == listOf("friend") }
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(member, listOf(other), setOf("friend")) }
        compose.onNodeWithText(other.text).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.clubs_report)).assertDoesNotExist()
    }

    @Test fun reportFromBubbleRetainsDriverReasonAndSelectedMessageIdentity() {
        val other = ClubMessage("reported-message", "friend", "Friend", "Report this message", 1L)
        val repo = FakeClubRepository(ClubChatState.Ready(member, listOf(other)))
        compose.setContent { KccTheme { ClubChatRoute("me", repo) } }
        compose.onNodeWithText(other.text).performTouchInput { longClick() }
        compose.onNodeWithText(str(R.string.clubs_report)).performClick()
        compose.onNodeWithText(str(R.string.chat_reportReasonHateOrAbuse)).performClick()
        compose.waitUntil { repo.managementRequests.isNotEmpty() }
        compose.runOnIdle {
            val (action, fields) = repo.managementRequests.single()
            assertEquals("reportMessage", action)
            assertEquals(mapOf("clubId" to member.clubId, "messageId" to other.id, "reason" to "hate"), fields)
            assertTrue(repo.blockedAuthors.isEmpty())
        }
        compose.onNodeWithText(str(R.string.clubs_reported)).assertIsDisplayed()
    }
    @Test fun chatCooldownRetainsDraftDisablesSendAndStillYieldsToRevocation() {
        val repo = FakeClubRepository(ClubChatState.Ready(member,
            listOf(ClubMessage("one", "friend", "Friend", "Private cooldown history", 1L))))
        val cooldown = com.kungsbackacarcommunity.app.chat.ChatSendCooldown { 0L }
        val gate = CompletableDeferred<Unit>()
        repo.sendGate = gate
        repo.sendFailure = ClubException("chat-rate-limited", com.kungsbackacarcommunity.app.chat.ChatRateLimit(30_000))
        compose.setContent { KccTheme { ClubChatRoute("cooldown-local-" + member.clubId, repo, sendCooldown = cooldown) } }
        compose.onNodeWithText("Private cooldown history").assertIsDisplayed()
        compose.onNodeWithTag("club-chat-input").performTextInput("Private cooldown draft 🚗")
        compose.onNodeWithText(str(R.string.clubs_send)).performClick()
        compose.waitUntil { compose.runOnIdle { repo.sendRequests.size == 1 } }
        assertDraft("")
        compose.onNodeWithText("Private cooldown draft 🚗").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.channel_statusSending)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(gate.isCompleted)
            assertNull(repo.sent)
            gate.complete(Unit)
        }
        assertDraft("Private cooldown draft 🚗")
        compose.onAllNodes(hasText("Private cooldown draft 🚗") and !hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("chat-flood-countdown").assertExists()
        compose.onNodeWithText(str(R.string.clubs_send)).assertIsNotEnabled()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).assertIsNotEnabled()
        compose.runOnIdle { repo.messages.value = ClubChatState.Unavailable }
        compose.onNodeWithText("Private cooldown draft 🚗").assertDoesNotExist()
        compose.onNodeWithText("Private cooldown history").assertDoesNotExist()
        compose.onNodeWithTag("club-chat-input").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).assertDoesNotExist()
        compose.onNodeWithTag("chat-flood-countdown").assertDoesNotExist()
        compose.runOnIdle { repo.messages.value = ClubChatState.Ready(member.copy(clubId = "second"), emptyList()) }
        assertDraft("")
        compose.onNodeWithText("Private cooldown draft 🚗").assertDoesNotExist()
        compose.onNodeWithText("Private cooldown history").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.channel_statusFailedRetry)).assertDoesNotExist()
        compose.onNodeWithTag("chat-flood-countdown").assertExists()
        compose.runOnIdle {
            assertTrue(cooldown.isBlocked())
            assertEquals(1, repo.sendRequests.size)
            assertNull(repo.sent)
        }
    }

}

private class FakeClubRepository(initial: ClubChatState) : ClubRepository {
    val messages = MutableStateFlow(initial)
    val historical = MutableStateFlow<List<ClubMessage>>(emptyList())
    val redactedIds = MutableStateFlow<Set<String>>(emptySet())
    val rowRequests = mutableListOf<List<String>>()
    private val activeRows = mutableListOf<List<String>>()
    fun activeRowIds() = activeRows.flatten().toSet()
    fun activeRowQueryCount() = activeRows.size
    var pageGate: CompletableDeferred<Unit>? = null
    var pageResults: List<ClubMessagePage> = emptyList()
    val cursorRequests = mutableListOf<ClubMessageCursor>()
    val access = MutableStateFlow<ClubAccess>(ClubAccess.None)
    var account = ClubState()
    var createdPayload: Map<String, Any?>? = null
    var sent: String? = null
    var operation: String? = null
    var chatCalls = 0
    var sendFailuresRemaining = 0
    var sendFailure: Exception? = null
    var sendGate: CompletableDeferred<Unit>? = null
    val sendRequests = mutableListOf<Pair<String, String>>()
    val managementRequests = mutableListOf<Pair<String, Map<String, Any?>>>()
    val blockedAuthors = mutableListOf<String>()
    override fun chat(): Flow<ClubChatState> { chatCalls++; return messages }
    override fun observeEnabled() = flowOf(true)
    override fun observePublicAttendanceReady() = flowOf(true)
    override fun observeAccess(): Flow<ClubAccess> = access
    override suspend fun directory(cursor: String?): ClubPage<Club> = ClubPage(emptyList(), null)
    override suspend fun state(): ClubState = account
    override suspend fun get(clubId: String): Club = error("unused")
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> = ClubPage(emptyList(), null)
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String): Map<String, Any?> {
        managementRequests += action to fields
        when (action) {
            "create" -> {
                createdPayload = fields
                val club = Club("new-club", fields["name"] as String, fields["description"] as String,
                    fields["location"] as String, fields["visibility"] as String, "me", null, 1, 0, ClubStats())
                val membership = ClubMembership(club.id, ClubRole.Owner, "2026-10-27T10:00:00Z")
                account = ClubState(club, membership)
                access.value = ClubAccess.Member(membership)
            }
            "leave" -> {
                account = ClubState(joinAllowedAt = java.time.Instant.now().plusSeconds(86400).toString())
                access.value = ClubAccess.None
            }
        }
        return mapOf("clubId" to "new-club")
    }
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String = error("unused")
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor): ClubMessagePage {
        cursorRequests.add(before)
        pageGate?.await()
        return pageResults.getOrElse(cursorRequests.lastIndex) { ClubMessagePage(emptyList(), null, false) }
    }
    override fun observeMessageRows(clubId: String, messageIds: List<String>): Flow<List<ClubMessage>> = flow {
        require(messageIds.size in 1..25)
        rowRequests.add(messageIds)
        activeRows.add(messageIds)
        try {
            emitAll(combine(historical, redactedIds) { rows, redacted -> rows.filter { it.id in messageIds && it.id !in redacted } })
        } finally { activeRows.remove(messageIds) }
    }
    override suspend fun send(clubId: String, text: String, operationId: String) {
        sendRequests += text to operationId
        sendGate?.await()
        sendFailure?.let { throw it }
        if (sendFailuresRemaining > 0) { sendFailuresRemaining--; throw ClubException("unavailable") }
        sent = text; operation = operationId
    }
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> = ClubPage(emptyList(), null)
    override fun eventParticipants(eventId: String): Flow<List<Club>> = flowOf(emptyList())
    override fun observeEventParticipants(eventId: String, clubIds: List<String>): Flow<List<Club>> = flowOf(emptyList())
    override fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?> = flowOf(false)
    override suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club> = emptyList()
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) = Unit
    override suspend fun blockAuthor(uid: String) { blockedAuthors += uid }
}
