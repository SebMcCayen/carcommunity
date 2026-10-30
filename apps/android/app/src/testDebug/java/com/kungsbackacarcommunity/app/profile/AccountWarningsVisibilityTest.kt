package com.kungsbackacarcommunity.app.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises the production section with local auth/signal/callable boundaries; no Firebase. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class AccountWarningsVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private var clock = 100L
    private val owner = mutableStateOf(OWNER)

    @Test fun pendingInitialReadShowsNoWarningSectionOrPlaceholder() {
        val source = start()
        awaitRequests(source, 1)
        absent()
    }

    @Test fun emptySuccessShowsNoWarningSectionOrPlaceholder() {
        val source = start()
        reply(source, 0, page())
        absent()
    }

    @Test fun initialFailureShowsNoWarningSectionOrPlaceholder() {
        val source = start()
        awaitRequests(source, 1)
        compose.runOnIdle { source.requests[0].response.completeExceptionally(IllegalStateException("unavailable")) }
        absent()
    }

    @Test fun actualWarningsShowReasonsAndExpiryIncludingUntilRevoked() {
        val source = start()
        reply(source, 0, page(warning(), warning("permanent", deadline = null, expiry = null)))
        compose.onNodeWithText(str(R.string.accountWarnings_title)).assertIsDisplayed()
        compose.onNodeWithText(REASON).assertIsDisplayed()
        compose.onNodeWithText("Reason permanent").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.accountWarnings_expires), substring = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.accountWarnings_untilRevoked)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.accountWarnings_empty)).assertDoesNotExist()
    }

    @Test fun expiredFirstPageDoesNotHideActiveWarningOnLaterPage() {
        val source = start()
        reply(source, 0, page(warning(deadline = 100), next = "next-page"))
        awaitRequests(source, 2)
        absent()
        compose.runOnIdle { assertEquals("next-page", source.requests[1].cursor) }
        reply(source, 1, page(warning("older")))
        compose.onNodeWithText("Reason older").assertIsDisplayed()
    }

    @Test fun refreshFailurePreservesKnownWarningAndSuccessfulRetryConfirmsRevocation() {
        val source = start()
        reply(source, 0, page(warning()))
        compose.runOnIdle { source.signal() }
        awaitRequests(source, 2)
        compose.onNodeWithText(REASON).assertIsDisplayed()
        compose.runOnIdle { source.requests[1].response.completeExceptionally(IllegalStateException("unavailable")) }
        compose.onNodeWithText(REASON).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.accountWarnings_retry)).performClick()
        reply(source, 2, page())
        absent()
    }

    @Test fun serverSignalRemovingLastWarningRemovesEntireSection() {
        val source = start()
        reply(source, 0, page(warning()))
        compose.onNodeWithText(REASON).assertIsDisplayed()
        compose.runOnIdle { source.signal() }
        reply(source, 1, page())
        absent()
        compose.onNodeWithText(REASON).assertDoesNotExist()
    }

    @Test fun lastWarningExpiryRemovesTitleAndReasonWithoutWaitingForNetwork() {
        val source = start()
        reply(source, 0, page(warning(deadline = 1000)))
        compose.onNodeWithText(REASON).assertIsDisplayed()
        compose.runOnIdle { clock = 1000 }
        compose.mainClock.advanceTimeBy(1100)
        absent()
        compose.onNodeWithText(REASON).assertDoesNotExist()
    }

    @Test fun signoutHidesKnownReasonImmediatelyAndLateResponseCannotRestoreIt() {
        val source = start()
        reply(source, 0, page(warning()))
        compose.runOnIdle { source.signal() }
        awaitRequests(source, 2)
        compose.runOnIdle { source.authenticate(null) }
        absent()
        compose.onNodeWithText(REASON).assertDoesNotExist()
        reply(source, 1, page(warning()))
        absent()
        compose.onNodeWithText(REASON).assertDoesNotExist()
    }

    @Test fun switchingProfileUidDisposesOldListenerAndNeverShowsOldResponse() {
        val source = start()
        awaitRequests(source, 1)
        compose.runOnIdle {
            source.authenticate("second-owner")
            owner.value = "second-owner"
        }
        awaitRequests(source, 2)
        reply(source, 0, page(warning()))
        absent()
        reply(source, 1, AccountWarningPage("second-owner", listOf(warning("second"))))
        compose.onNodeWithText(REASON).assertDoesNotExist()
        compose.onNodeWithText("Reason second").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(OWNER, "second-owner"), source.observed)
            assertEquals(listOf(OWNER), source.disposed)
        }
    }

    private fun start(): Source {
        val source = Source()
        compose.setContent { KccTheme { AccountWarningsContent(owner.value, source) { clock } } }
        return source
    }
    private fun awaitRequests(source: Source, count: Int) = compose.waitUntil(5000) {
        compose.runOnIdle { source.requests.size >= count }
    }
    private fun reply(source: Source, index: Int, page: AccountWarningPage) {
        awaitRequests(source, index + 1)
        compose.runOnIdle { source.requests[index].response.complete(page) }
        compose.waitForIdle()
    }
    private fun absent() {
        for (id in listOf(R.string.accountWarnings_title, R.string.accountWarnings_loading,
            R.string.accountWarnings_empty, R.string.accountWarnings_error,
            R.string.accountWarnings_retry, R.string.accountWarnings_more)) {
            compose.onNodeWithText(str(id)).assertDoesNotExist()
        }
    }
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private data class Request(val cursor: String?, val response: CompletableDeferred<AccountWarningPage> = CompletableDeferred())
    private class Source : AccountWarningsSource {
        override var currentUid: String? = OWNER
        val requests = mutableListOf<Request>()
        val observed = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        private var authChanged: ((String?) -> Unit)? = null
        private var changed: (() -> Unit)? = null
        override fun observe(uid: String, onAuthChanged: (String?) -> Unit, onChanged: () -> Unit): () -> Unit {
            observed += uid
            authChanged = onAuthChanged
            changed = onChanged
            return {
                disposed += uid
                authChanged = null
                changed = null
            }
        }
        fun authenticate(uid: String?) { currentUid = uid; authChanged?.invoke(uid) }
        fun signal() { changed?.invoke() }
        override suspend fun load(cursor: String?, requestStarted: Long): AccountWarningPage {
            val request = Request(cursor)
            requests += request
            return request.response.await()
        }
    }
    private companion object {
        const val OWNER = "warning-owner"
        const val REASON = "Respect other members"
        fun warning(id: String = "warning", deadline: Long? = 10000, expiry: String? = "2026-11-01T00:00:00Z") =
            AccountWarning(id, if (id == "warning") REASON else "Reason $id", expiry, deadline)
        fun page(vararg rows: AccountWarning, next: String? = null) = AccountWarningPage(OWNER, rows.toList(), next)
    }
}
