package com.kungsbackacarcommunity.app.clubs

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The same fake-only production dialog checks run on JVM and in the test APK. */
@RunWith(AndroidJUnit4::class)
class ClubCalendarSubscriptionControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repo = CalendarFixtureRepository()
    private var restoreClipboard = false
    private var previousClip: ClipData? = null
    private val clipboard get() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private fun text(id: Int) = compose.activity.getString(id)
    private fun tag(name: String) = compose.onNodeWithTag("club_calendar_$name")

    @After fun restoreClipboardAfterCopy() {
        if (restoreClipboard) {
            val previous = previousClip
            if (previous != null) clipboard.setPrimaryClip(previous)
            else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun render() {
        compose.setContent { KccTheme { ClubCalendarSubscriptionControl("club-a", repo) } }
    }

    private fun request(index: Int, action: String, version: Long?, club: String = "club-a"): CalendarFixtureRequest {
        compose.waitUntil(5_000) { repo.requests.size > index }
        return repo.requests[index].also {
            assertEquals(club, it.clubId)
            assertEquals(action, it.action)
            assertEquals(version, it.expectedVersion)
        }
    }

    private fun complete(request: CalendarFixtureRequest, state: CalendarSubscription) {
        compose.runOnIdle { request.result.complete(state) }
        compose.waitForIdle()
    }

    private fun open(active: Boolean = false, version: Long = 7L) {
        tag("subscribe").performClick()
        complete(request(0, "status", null), CalendarSubscription(active, version))
    }

    private fun assertNoBearerOnScreen() {
        compose.onAllNodes(hasText(calendarFixtureToken, substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText(calendarFixtureUrl, substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription(calendarFixtureToken, substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun openingAndRefreshingStatusNeverIssueALinkImplicitly() {
        render()
        compose.runOnIdle { assertTrue(repo.requests.isEmpty()) }
        open()
        tag("issue").performScrollTo().assertIsDisplayed()
        tag("copy").assertDoesNotExist()
        assertNoBearerOnScreen()
        compose.runOnIdle { assertEquals(listOf("status"), repo.requests.map { it.action }) }
        compose.onNodeWithText(text(R.string.clubs_close)).performClick()
        tag("subscribe").performClick()
        complete(request(1, "status", null), CalendarSubscription(false, 7))
        compose.runOnIdle { assertEquals(listOf("status", "status"), repo.requests.map { it.action }) }
    }

    @Test fun explicitIssueCopyAndRevokeUseCurrentVersionsAndASensitiveClipboard() {
        render()
        open()
        tag("issue").performScrollTo().performClick()
        complete(request(1, "issue", 7), CalendarSubscription(true, 8, calendarFixtureUrl))
        assertNoBearerOnScreen()
        previousClip = clipboard.primaryClip
        restoreClipboard = true
        tag("copy").performScrollTo().performClick()
        compose.runOnIdle {
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals(calendarFixtureUrl, clip.getItemAt(0).text.toString())
            assertEquals(text(R.string.clubCalendar_clipboardLabel), clip.description.label.toString())
            assertTrue(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
            assertEquals(2, repo.requests.size) // Copy is local; it must never issue or fetch again.
        }
        tag("copied").performScrollTo().assertIsDisplayed()
        assertNoBearerOnScreen()
        tag("revoke").performScrollTo().performClick()
        complete(request(2, "revoke", 8), CalendarSubscription(false, 9))
        tag("copy").assertDoesNotExist()
        tag("copied").assertDoesNotExist()
        tag("revoke").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.clubCalendar_inactive)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("status", "issue", "revoke"), repo.requests.map { it.action }) }
        assertNoBearerOnScreen()
    }

    @Test fun existingLinkRequiresExplicitReplacementAndExplainsOldLinkRevocation() {
        render()
        open(active = true, version = 12)
        tag("copy").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.clubCalendar_replaceWarning)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, repo.requests.size) }
        tag("issue").performScrollTo().performClick()
        complete(request(1, "issue", 12), CalendarSubscription(true, 13, calendarFixtureUrl))
        tag("copy").performScrollTo().assertIsDisplayed()
        assertNoBearerOnScreen()
    }

    @Test fun failedStatusRetriesStatusAndKeepsIssueUnavailableUntilItSucceeds() {
        render()
        tag("subscribe").performClick()
        val status = request(0, "status", null)
        compose.runOnIdle { status.result.completeExceptionally(ClubException("unavailable")) }
        compose.onNodeWithText(text(R.string.clubCalendar_error)).performScrollTo().assertIsDisplayed()
        tag("issue").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.clubs_retry)).performScrollTo().performClick()
        complete(request(1, "status", null), CalendarSubscription(false, 20))
        tag("issue").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("status", "status"), repo.requests.map { it.action }) }
    }

    @Test fun failedIssueDropsTheBearerAndRetryNeverAutomaticallyReissues() {
        render()
        open()
        tag("issue").performScrollTo().performClick()
        val issue = request(1, "issue", 7)
        compose.runOnIdle { issue.result.completeExceptionally(ClubException("unavailable")) }
        tag("copy").assertDoesNotExist()
        tag("issue").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.clubs_retry)).performScrollTo().performClick()
        complete(request(2, "status", null), CalendarSubscription(true, 8))
        tag("copy").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("status", "issue", "status"), repo.requests.map { it.action }) }
        assertNoBearerOnScreen()
    }

    @Test fun abortedMutationRequiresRefreshAndAnExplicitNewIssueWithTheFreshVersion() {
        render()
        open()
        tag("issue").performScrollTo().performClick()
        val issue = request(1, "issue", 7)
        compose.runOnIdle {
            issue.result.completeExceptionally(
                FirebaseFunctionsException("Synthetic conflict", FirebaseFunctionsException.Code.ABORTED, null),
            )
        }
        compose.onNodeWithText(text(R.string.clubCalendar_changed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic conflict").assertDoesNotExist()
        tag("copy").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.clubs_retry)).performScrollTo().performClick()
        complete(request(2, "status", null), CalendarSubscription(true, 42))
        compose.runOnIdle { assertEquals(3, repo.requests.size) }
        tag("issue").performScrollTo().performClick()
        complete(request(3, "issue", 42), CalendarSubscription(true, 43, calendarFixtureUrl))
        assertNoBearerOnScreen()
    }

    @Test fun failedRevokeDropsTheIssuedUrlAndRetryOnlyFetchesStatus() {
        render()
        open()
        tag("issue").performScrollTo().performClick()
        complete(request(1, "issue", 7), CalendarSubscription(true, 8, calendarFixtureUrl))
        tag("revoke").performScrollTo().performClick()
        val revoke = request(2, "revoke", 8)
        tag("copy").assertDoesNotExist()
        compose.runOnIdle { revoke.result.completeExceptionally(ClubException("unavailable")) }
        compose.onNodeWithText(text(R.string.clubCalendar_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.clubs_retry)).performScrollTo().performClick()
        complete(request(3, "status", null), CalendarSubscription(true, 8))
        tag("copy").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("status", "issue", "revoke", "status"), repo.requests.map { it.action }) }
        assertNoBearerOnScreen()
    }

    @Test fun busyGuardPreventsDuplicateIssueAndRevokeEvenThroughRepeatedSemanticClicks() {
        render()
        open(active = true)
        tag("issue").performScrollTo().performClick()
        val issue = request(1, "issue", 7)
        tag("issue").assertIsNotEnabled()
        repeat(2) { tag("issue").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() } }
        tag("revoke").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, repo.requests.size) }
        complete(issue, CalendarSubscription(true, 8, calendarFixtureUrl))
        tag("revoke").performScrollTo().performClick()
        val revoke = request(2, "revoke", 8)
        repeat(2) { tag("revoke").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() } }
        compose.runOnIdle { assertEquals(3, repo.requests.size) }
        complete(revoke, CalendarSubscription(false, 9))
    }

    @Test fun dismissCancelsIssueAndLateCompletionCannotReopenOrRestoreItsLink() {
        render()
        open()
        tag("issue").performScrollTo().performClick()
        val issue = request(1, "issue", 7)
        compose.onNodeWithText(text(R.string.clubs_close)).performClick()
        compose.waitUntil(5_000) { issue.cancelled }
        compose.runOnIdle { issue.result.complete(CalendarSubscription(true, 8, calendarFixtureUrl)) }
        tag("copy").assertDoesNotExist()
        tag("subscribe").performClick()
        complete(request(2, "status", null), CalendarSubscription(true, 8))
        tag("copy").assertDoesNotExist()
        assertNoBearerOnScreen()
    }

    @Test fun changingClubCancelsTheOldRequestAndRequiresOpeningTheNewClubsDialog() {
        val club = mutableStateOf("club-a")
        compose.setContent { KccTheme { ClubCalendarSubscriptionControl(club.value, repo) } }
        open()
        tag("issue").performScrollTo().performClick()
        val issue = request(1, "issue", 7)
        compose.runOnIdle { club.value = "club-b" }
        compose.waitUntil(5_000) { issue.cancelled }
        tag("copy").assertDoesNotExist()
        tag("subscribe").performClick()
        complete(request(2, "status", null, "club-b"), CalendarSubscription(false, 0))
        compose.runOnIdle { issue.result.complete(CalendarSubscription(true, 8, calendarFixtureUrl)) }
        tag("copy").assertDoesNotExist()
    }

    @Test fun replacingTheRepositoryCancelsTheOldIdentityAndLoadsFreshStatus() {
        val next = CalendarFixtureRepository()
        val selected = mutableStateOf<ClubCalendarSubscriptionRepository>(repo)
        compose.setContent { KccTheme { ClubCalendarSubscriptionControl("club-a", selected.value) } }
        open()
        tag("issue").performScrollTo().performClick()
        val issue = request(1, "issue", 7)
        compose.runOnIdle { selected.value = next }
        compose.waitUntil(5_000) { issue.cancelled && next.requests.size == 1 }
        compose.runOnIdle {
            assertEquals("status", next.requests.single().action)
            assertNull(next.requests.single().expectedVersion)
            next.requests.single().result.complete(CalendarSubscription(false, 0))
            issue.result.complete(CalendarSubscription(true, 8, calendarFixtureUrl))
        }
        tag("copy").assertDoesNotExist()
        assertNoBearerOnScreen()
    }

    @Test fun losingMembershipOrChangingItsKeyDisposesPendingState() {
        val membership = mutableStateOf<ClubMembership?>(ClubMembership("club-a", ClubRole.Member, "2026-10-29T10:00:00Z"))
        compose.setContent {
            KccTheme {
                // Mirrors SharedClubRoute's verified membership boundary, including a same-club rejoin.
                membership.value?.let { member -> key(member) { ClubCalendarSubscriptionControl(member.clubId, repo) } }
            }
        }
        open()
        tag("issue").performScrollTo().performClick()
        val issue = request(1, "issue", 7)
        compose.runOnIdle { membership.value = null }
        compose.waitUntil(5_000) { issue.cancelled }
        tag("subscribe").assertDoesNotExist()
        compose.runOnIdle { membership.value = ClubMembership("club-a", ClubRole.Member, "2026-10-29T11:00:00Z") }
        tag("copy").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, repo.requests.size) }
        tag("subscribe").performClick()
        val status = request(2, "status", null)
        compose.runOnIdle { membership.value = membership.value?.copy(joinedAt = "2026-10-29T12:00:00Z") }
        compose.waitUntil(5_000) { status.cancelled }
        tag("copy").assertDoesNotExist()
        tag("subscribe").assertIsDisplayed()
    }

    @Test fun membershipLossWithdrawsAnAlreadyIssuedLinkAndRejoinCannotRecoverIt() {
        val membership = mutableStateOf<ClubMembership?>(ClubMembership("club-a", ClubRole.Member, "2026-10-29T10:00:00Z"))
        compose.setContent {
            KccTheme {
                membership.value?.let { member -> key(member) { ClubCalendarSubscriptionControl(member.clubId, repo) } }
            }
        }
        open()
        tag("issue").performScrollTo().performClick()
        complete(request(1, "issue", 7), CalendarSubscription(true, 8, calendarFixtureUrl))
        tag("copy").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { membership.value = null }
        tag("copy").assertDoesNotExist()
        tag("subscribe").assertDoesNotExist()
        compose.runOnIdle { membership.value = ClubMembership("club-a", ClubRole.Member, "2026-10-29T12:00:00Z") }
        tag("subscribe").performClick()
        complete(request(2, "status", null), CalendarSubscription(true, 8))
        tag("copy").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("status", "issue", "status"), repo.requests.map { it.action }) }
        assertNoBearerOnScreen()
    }

    @Test fun missingRepositoryFailsLocallyWithoutCreatingALink() {
        compose.setContent { KccTheme { ClubCalendarSubscriptionControl("club-a", repository = null) } }
        tag("subscribe").performClick()
        compose.onNodeWithText(text(R.string.clubCalendar_error)).performScrollTo().assertIsDisplayed()
        tag("issue").assertDoesNotExist()
        tag("copy").assertDoesNotExist()
    }
}
