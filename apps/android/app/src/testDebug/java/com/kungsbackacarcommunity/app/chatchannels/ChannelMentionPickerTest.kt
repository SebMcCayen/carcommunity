package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the community composer's @-picker: open, filter, select,
 * insert, the client-side cap, and the fact that a picked mention sends the UID —
 * never the name the server refuses to parse. CI-run in the instrumented-tests
 * emulator job.
 */
@RunWith(AndroidJUnit4::class)
class ChannelMentionPickerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val alice = MentionCandidate("uid-alice", "Alice")
    private val bob = MentionCandidate("uid-bob", "Bob")
    private val bobby = MentionCandidate("uid-bobby", "Bobby")

    private var sentText: String? = null
    private var sentMentions: List<String>? = null

    private fun setContent(
        candidates: List<MentionCandidate> = listOf(alice, bob, bobby),
        droppedMentionCount: Int = 0,
    ) {
        composeTestRule.setContent {
            KccTheme {
                ChannelChatContent(
                    messages = emptyList(),
                    currentUid = "uid-self",
                    loading = false,
                    emptyText = "empty",
                    canLoadOlder = false,
                    isLoadingOlder = false,
                    onSend = { text, mentions, _ ->
                        sentText = text
                        sentMentions = mentions
                    },
                    onRetry = {},
                    onLoadOlder = {},
                    // The picker only exists on the community channel; convoy
                    // passes no candidates (see the no-picker case below).
                    surface = ChatSurface.CommunityChannel,
                    mentionCandidates = candidates,
                    droppedMentionCount = droppedMentionCount,
                )
            }
        }
    }

    private fun input() = composeTestRule.onNodeWithTag(CHANNEL_INPUT_TEST_TAG)

    /** Resolved from resources, not hardcoded: Swedish is the default locale. */
    private fun sendButton() =
        composeTestRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.channel_send),
        )

    @Test
    fun typingAnAtSignOpensThePicker() {
        setContent()
        composeTestRule.onNodeWithTag(MENTION_PICKER_TEST_TAG).assertDoesNotExist()
        input().performTextInput("hey @")
        composeTestRule.onNodeWithTag(MENTION_PICKER_TEST_TAG).assertIsDisplayed()
        // A bare "@" lists everyone — a legitimate "who's here?".
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-alice")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-bob")).assertIsDisplayed()
    }

    @Test
    fun theQueryFiltersAsTheUserTypes() {
        setContent()
        input().performTextInput("@bob")
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-bob")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-bobby")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-alice")).assertDoesNotExist()
    }

    @Test
    fun anEmailsAtSignDoesNotOpenThePicker() {
        setContent()
        input().performTextInput("mail me at seb@")
        composeTestRule.onNodeWithTag(MENTION_PICKER_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun selectingACandidateInsertsItsLabelAndClosesThePicker() {
        setContent()
        input().performTextInput("hey @bo")
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-bobby")).performClick()
        composeTestRule.onNodeWithText("hey @Bobby ").assertIsDisplayed()
        // The query is consumed, so the picker closes rather than re-suggesting
        // over the text it just resolved.
        composeTestRule.onNodeWithTag(MENTION_PICKER_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun sendingCarriesTheUidBehindTheInsertedLabel() {
        setContent()
        input().performTextInput("hey @bo")
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-bob")).performClick()
        input().performTextInput("look at this")
        sendButton().performClick()
        composeTestRule.runOnIdle {
            assertEquals("hey @Bob look at this", sentText)
            // The UID, never the name — "Bob" alone could be either Bob.
            assertEquals(listOf("uid-bob"), sentMentions)
        }
    }

    @Test
    fun editingAMentionsLabelDropsItsUidBeforeSend() {
        setContent()
        input().performTextInput("@bo")
        composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-bob")).performClick()
        // Break the inserted label: "@Bob " -> "@Bo ". The uid must not survive
        // onto text that no longer names him.
        input().performTextReplacement("@Bo ")
        sendButton().performClick()
        composeTestRule.runOnIdle {
            assertEquals("@Bo ", sentText)
            assertEquals(emptyList<String>(), sentMentions)
        }
    }

    @Test
    fun theEleventhDistinctMentionIsRefusedWithAMessage() {
        val many = (1..MAX_MESSAGE_MENTIONS + 1).map { MentionCandidate("uid-$it", "M$it") }
        setContent(candidates = many)
        // Type each member's full name: the picker pages at
        // MENTION_PICKER_MAX_RESULTS, so a bare "@" would not list all eleven.
        repeat(MAX_MESSAGE_MENTIONS) { index ->
            input().performTextInput("@M${index + 1}")
            composeTestRule.onNodeWithTag(mentionCandidateTestTag("uid-${index + 1}")).performClick()
        }
        composeTestRule.onNodeWithTag(MENTION_CAP_TEST_TAG).assertDoesNotExist()

        input().performTextInput("@M${MAX_MESSAGE_MENTIONS + 1}")
        composeTestRule
            .onNodeWithTag(mentionCandidateTestTag("uid-${MAX_MESSAGE_MENTIONS + 1}"))
            .performClick()
        // Refused client-side: the server would answer this with a hard
        // invalid-argument, i.e. a failed send instead of a sentence.
        composeTestRule.onNodeWithTag(MENTION_CAP_TEST_TAG).assertIsDisplayed()

        sendButton().performClick()
        composeTestRule.runOnIdle { assertEquals(MAX_MESSAGE_MENTIONS, sentMentions?.size) }
    }

    @Test
    fun aConvoyStyleComposerWithNoCandidatesHasNoPicker() {
        // Convoy passes no candidates: convoyChat-post accepts no mentions.
        setContent(candidates = emptyList())
        input().performTextInput("hey @")
        composeTestRule.onNodeWithTag(MENTION_PICKER_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun droppedMentionsSurfaceOneNote() {
        setContent(droppedMentionCount = 1)
        composeTestRule.onNodeWithTag(MENTION_DROPPED_TEST_TAG).assertIsDisplayed()
    }
}
