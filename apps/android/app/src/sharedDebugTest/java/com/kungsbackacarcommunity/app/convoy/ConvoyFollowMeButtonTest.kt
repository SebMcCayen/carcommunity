package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-level Compose regressions: persistent leadership and transient reactions have separate gates. */
@RunWith(AndroidJUnit4::class)
class ConvoyFollowMeButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var leaderUid by mutableStateOf<String?>(null)

    private class FakeReactionRepository : ConvoyReactionRepository {
        val sends = mutableListOf<ConvoyReactionKind>()
        var result: ConvoyReactionSendResult = ConvoyReactionSendResult.Sent

        override suspend fun send(
            convoyId: String,
            kind: ConvoyReactionKind,
            clientId: String,
        ): ConvoyReactionSendResult {
            sends += kind
            return result
        }

        override fun observeReactions(
            convoyId: String,
            sinceMillis: Long,
        ): Flow<ConvoyReactionEvent> = emptyFlow()
    }

    private inner class Member(val uid: String) {
        val reactions = FakeReactionRepository()
        val requests = mutableListOf<Boolean>()
        var operation: suspend (Boolean) -> Unit = { active -> applyLeadership(active) }

        fun applyLeadership(active: Boolean) {
            if (active) leaderUid = uid
            else if (leaderUid == uid) leaderUid = null
        }

        suspend fun toggle(active: Boolean) {
            requests += active
            operation(active)
        }
    }

    private fun showHosts(vararg members: Member, persistent: Boolean = true) {
        composeTestRule.setContent {
            KccTheme {
                Column(Modifier.fillMaxSize()) {
                    for (member in members) {
                        ConvoyReactionsHost(
                            convoyId = "convoy",
                            repository = member.reactions,
                            modifier = Modifier.weight(1f).testTag(member.uid),
                            followMeLeading = leaderUid == member.uid,
                            onFollowMeToggle = if (persistent) member::toggle else null,
                        )
                    }
                }
            }
        }
    }

    private fun button(member: Member, kind: ConvoyReactionKind = ConvoyReactionKind.FollowMe) =
        composeTestRule.onNode(
            hasTestTag(convoyReactionButtonTag(kind)) and hasAnyAncestor(hasTestTag(member.uid)),
        )

    @Test
    fun onOffAndOnAgainBypassThePriorReactionCooldown() {
        val a = Member("A")
        showHosts(a)

        button(a).assertIsNotSelected().assertIsEnabled().performClick()
        button(a).assertIsSelected().assertIsEnabled()
        button(a).performClick()
        button(a).assertIsNotSelected().assertIsEnabled()
        button(a).performClick()
        button(a).assertIsSelected().assertIsEnabled()

        composeTestRule.runOnIdle {
            assertEquals(listOf(true, false, true), a.requests)
            assertEquals("A", leaderUid)
            // Only the first activation broadcasts; stopping is silent and the
            // next activation still respects the transient reaction cooldown.
            assertEquals(listOf(ConvoyReactionKind.FollowMe), a.reactions.sends)
        }
    }

    @Test
    fun takeoverUpdatesBothSelectionsAndFormerLeaderCanReclaimDuringCooldown() {
        val a = Member("A")
        val b = Member("B")
        showHosts(a, b)

        button(a).assertIsNotSelected().performClick()
        button(a).assertIsSelected()
        button(b).assertIsNotSelected().performClick()
        button(a).assertIsNotSelected().assertIsEnabled()
        button(b).assertIsSelected()

        // A's earlier FollowMe broadcast is still cooling down after B takes over.
        button(a).performClick()
        button(a).assertIsSelected()
        button(b).assertIsNotSelected().assertIsEnabled()
        composeTestRule.runOnIdle {
            assertEquals("A", leaderUid)
            assertEquals(listOf(true, true), a.requests)
            assertEquals(listOf(true), b.requests)
            assertEquals(listOf(ConvoyReactionKind.FollowMe), a.reactions.sends)
            assertEquals(listOf(ConvoyReactionKind.FollowMe), b.reactions.sends)
        }
    }

    @Test
    fun pendingActivationDebouncesBeforeRecompositionAndDisablesOnlyFollowMe() {
        val a = Member("A")
        val completion = CompletableDeferred<Unit>()
        a.operation = { active ->
            completion.await()
            a.applyLeadership(active)
        }
        showHosts(a)

        // Invoke the same captured action twice before the disabled UI can
        // recompose: the host guard must also prevent a duplicate request.
        val click = button(a).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeTestRule.runOnIdle {
            click()
            click()
        }
        button(a).assertIsNotEnabled().assertIsNotSelected()
        button(a).performClick()
        button(a, ConvoyReactionKind.Police).assertIsEnabled().performClick()
        button(a, ConvoyReactionKind.HelloGoodbye).assertIsEnabled().performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(true), a.requests)
            assertEquals(null, leaderUid)
            assertEquals(ConvoyReactionKind.entries.toSet(), a.reactions.sends.toSet())
            completion.complete(Unit)
        }
        button(a).assertIsSelected().assertIsEnabled()
    }

    @Test
    fun pendingStopKeepsActualSelectionUntilLeadershipChanges() {
        val a = Member("A")
        leaderUid = "A"
        val completion = CompletableDeferred<Unit>()
        a.operation = { active ->
            completion.await()
            a.applyLeadership(active)
        }
        showHosts(a)

        button(a).assertIsSelected().performClick()
        button(a).assertIsSelected().assertIsNotEnabled()
        button(a).performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(false), a.requests)
            assertEquals("A", leaderUid)
            assertEquals(emptyList<ConvoyReactionKind>(), a.reactions.sends)
            completion.complete(Unit)
        }
        button(a).assertIsNotSelected().assertIsEnabled()
    }

    @Test
    fun failedToggleReturningWithoutStateChangeClearsPendingAndAllowsRetry() {
        val a = Member("A")
        leaderUid = "A"
        // The production Unit callback also returns normally when its repository
        // returns null on failure. Only observed state can establish success.
        a.operation = {}
        showHosts(a)

        button(a).performClick()
        button(a).assertIsSelected().assertIsEnabled()
        composeTestRule.runOnIdle { a.operation = { active -> a.applyLeadership(active) } }
        button(a).performClick()
        button(a).assertIsNotSelected().assertIsEnabled()
        composeTestRule.runOnIdle { assertEquals(listOf(false, false), a.requests) }
    }

    @Test
    fun throwingToggleClearsPendingAndAllowsRetryWithoutOptimisticSelection() {
        val a = Member("A")
        val completion = CompletableDeferred<Unit>()
        a.operation = { completion.await() }
        showHosts(a)

        button(a).performClick()
        button(a).assertIsNotEnabled().assertIsNotSelected()
        composeTestRule.runOnIdle {
            completion.completeExceptionally(IllegalStateException("toggle failed"))
        }
        button(a).assertIsEnabled().assertIsNotSelected()
        composeTestRule.runOnIdle { a.operation = { active -> a.applyLeadership(active) } }
        button(a).performClick()
        button(a).assertIsEnabled().assertIsSelected()
        composeTestRule.runOnIdle {
            assertEquals(listOf(true, true), a.requests)
            assertEquals(listOf(ConvoyReactionKind.FollowMe), a.reactions.sends)
        }
    }

    @Test
    fun cancelledToggleAlsoClearsPending() {
        val a = Member("A")
        a.operation = { throw CancellationException("toggle cancelled") }
        showHosts(a)

        button(a).performClick()
        button(a).assertIsEnabled().assertIsNotSelected()
        composeTestRule.runOnIdle { a.operation = { active -> a.applyLeadership(active) } }
        button(a).performClick()
        button(a).assertIsEnabled().assertIsSelected()
        composeTestRule.runOnIdle { assertEquals(listOf(true, true), a.requests) }
    }

    @Test
    fun persistentModeKeepsCooldownsForOtherReactionKinds() {
        val a = Member("A")
        showHosts(a)

        for (kind in listOf(ConvoyReactionKind.Police, ConvoyReactionKind.HelloGoodbye)) {
            button(a, kind).assertIsEnabled().performClick()
            button(a, kind).assertIsNotEnabled().performClick()
        }
        button(a).assertIsEnabled().performClick()
        button(a).assertIsSelected().assertIsEnabled()
        composeTestRule.runOnIdle {
            assertEquals(ConvoyReactionKind.entries.toList(), a.reactions.sends)
            assertEquals(listOf(true), a.requests)
        }
    }

    @Test
    fun broadcastOnlyFallbackKeepsCooldownForEveryReactionEvenIfLeadingIsTrue() {
        val a = Member("A")
        leaderUid = "A"
        showHosts(a, persistent = false)

        for (kind in ConvoyReactionKind.entries) {
            button(a, kind).assertIsEnabled().performClick()
            button(a, kind).assertIsNotEnabled().performClick()
        }
        composeTestRule.runOnIdle {
            assertEquals(ConvoyReactionKind.entries.toList(), a.reactions.sends)
            assertEquals(emptyList<Boolean>(), a.requests)
        }
    }

    @Test
    fun serverReactionCooldownCannotPreventPersistentOffOrReactivation() {
        val a = Member("A")
        a.reactions.result =
            ConvoyReactionSendResult.RateLimited(ConvoyReactionKind.FollowMe, 120_000L)
        showHosts(a)

        button(a).performClick()
        button(a).assertIsSelected().assertIsEnabled().performClick()
        button(a).assertIsNotSelected().assertIsEnabled().performClick()
        button(a).assertIsSelected().assertIsEnabled()
        composeTestRule.runOnIdle {
            assertEquals(listOf(true, false, true), a.requests)
            assertEquals(listOf(ConvoyReactionKind.FollowMe), a.reactions.sends)
        }
    }
}
