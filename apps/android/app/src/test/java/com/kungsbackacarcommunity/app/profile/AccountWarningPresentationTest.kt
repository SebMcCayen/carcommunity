package com.kungsbackacarcommunity.app.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountWarningPresentationTest {
    @Test fun `initial and pending reads do not establish a warning`() = runTest {
        val f = Fixture()
        val pending = CompletableDeferred<AccountWarningPage>()
        f.source.reply = { _, _ -> pending.await() }
        assertTrue(f.state.warnings.isEmpty())
        val refresh = async { f.state.refresh() }
        runCurrent()
        assertTrue(f.state.loading)
        assertTrue(f.state.warnings.isEmpty())
        pending.complete(page())
        refresh.await()
        assertFalse(f.state.loading)
        assertTrue(f.state.warnings.isEmpty())
    }

    @Test fun `initial failure establishes no warning`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> error("unavailable") }
        f.state.refresh()
        assertTrue(f.state.failed)
        assertTrue(f.state.warnings.isEmpty())
    }

    @Test fun `confirmed active rows preserve reason and expiry`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning()) }
        f.state.refresh()
        assertEquals(listOf(warning()), f.state.warnings)
        assertFalse(f.state.failed)
    }

    @Test fun `empty and locally expired pages are followed to an older active warning`() = runTest {
        val f = Fixture()
        f.source.reply = { cursor, _ -> when (cursor) {
            null -> page(next = "page-2")
            "page-2" -> page(warning(deadline = 10), next = "page-3")
            "page-3" -> page(warning(id = "older", deadline = null))
            else -> error("Unexpected cursor")
        } }
        f.state.refresh()
        assertEquals(listOf(null, "page-2", "page-3"), f.source.cursors)
        assertEquals(listOf("older"), f.state.warnings.map { it.id })
    }

    @Test fun `first confirmed row becomes visible before later pages finish`() = runTest {
        val f = Fixture()
        val pending = CompletableDeferred<AccountWarningPage>()
        f.source.reply = { cursor, _ -> if (cursor == null) page(warning(), next = "next") else pending.await() }
        val refresh = async { f.state.refresh() }
        runCurrent()
        assertEquals(listOf(warning()), f.state.warnings)
        assertTrue(f.state.loading)
        pending.complete(page(warning(id = "other")))
        refresh.await()
        assertEquals(listOf("warning", "other"), f.state.warnings.map { it.id })
    }

    @Test fun `failed refresh retains a known active warning until confirmed removal`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning()) }
        f.state.refresh()
        f.source.reply = { _, _ -> error("unavailable") }
        f.state.refresh()
        assertTrue(f.state.failed)
        assertEquals(listOf(warning()), f.state.warnings)
        f.source.reply = { _, _ -> page() }
        f.state.refresh()
        assertTrue(f.state.warnings.isEmpty()) // Successful server refresh confirms revocation/removal.
        assertFalse(f.state.failed)
    }

    @Test fun `refresh does not discard known warning on a later page`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning()) }
        f.state.refresh()
        val pending = CompletableDeferred<AccountWarningPage>()
        f.source.reply = { cursor, _ -> if (cursor == null) page(next = "next") else pending.await() }
        val refresh = async { f.state.refresh() }
        runCurrent()
        assertEquals(listOf(warning()), f.state.warnings)
        pending.complete(page(warning()))
        refresh.await()
        assertEquals(listOf(warning()), f.state.warnings)
    }

    @Test fun `later page failure retains confirmed rows and retry restarts complete pagination`() = runTest {
        val f = Fixture()
        f.source.reply = { cursor, _ -> if (cursor == null) page(warning(), next = "next") else error("unavailable") }
        f.state.refresh()
        assertTrue(f.state.failed)
        assertEquals(listOf(warning()), f.state.warnings)
        f.source.reply = { cursor, _ -> if (cursor == null) page(warning(), next = "next") else page(warning(id = "older")) }
        f.state.refresh()
        assertEquals(listOf(null, "next", null, "next"), f.source.cursors)
        assertEquals(listOf("warning", "older"), f.state.warnings.map { it.id })
        assertFalse(f.state.failed)
    }

    @Test fun `expiry removes last row and failure cannot restore it`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning(deadline = 101)) }
        f.state.refresh()
        f.clock = 101
        f.state.expire()
        assertTrue(f.state.warnings.isEmpty())
        f.source.reply = { _, _ -> error("unavailable") }
        f.state.refresh()
        assertTrue(f.state.warnings.isEmpty())
    }

    @Test fun `signout clears reasons immediately and discards pending old response even after same uid returns`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning()) }
        f.state.refresh()
        val pending = CompletableDeferred<AccountWarningPage>()
        f.source.reply = { _, _ -> pending.await() }
        val refresh = async { f.state.refresh() }
        runCurrent()
        f.source.currentUid = null
        f.state.authChanged(null)
        assertTrue(f.state.warnings.isEmpty())
        f.source.currentUid = OWNER
        f.state.authChanged(OWNER)
        pending.complete(page(warning()))
        refresh.await()
        assertTrue(f.state.warnings.isEmpty())
    }

    @Test fun `another account cannot fetch or display prior reasons`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning()) }
        f.state.refresh()
        f.source.currentUid = "other-owner"
        f.state.authChanged("other-owner")
        f.state.refresh()
        assertTrue(f.state.warnings.isEmpty())
        assertEquals(1, f.source.cursors.size)
    }

    @Test fun `response ownership mismatch clears prior reasons`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning()) }
        f.state.refresh()
        f.source.reply = { _, _ -> AccountWarningPage("other-owner", listOf(warning(id = "foreign"))) }
        f.state.refresh()
        assertTrue(f.state.warnings.isEmpty())
    }

    @Test fun `current auth read still guards a response before auth callback arrives`() = runTest {
        val f = Fixture()
        val pending = CompletableDeferred<AccountWarningPage>()
        f.source.reply = { _, _ -> pending.await() }
        val refresh = async { f.state.refresh() }
        runCurrent()
        f.source.currentUid = "other-owner"
        pending.complete(page(warning()))
        refresh.await()
        assertTrue(f.state.warnings.isEmpty())
        assertFalse(f.state.loading)
    }

    @Test fun `repeated cursors stop requests without hiding confirmed active warning`() = runTest {
        val f = Fixture()
        f.source.reply = { _, _ -> page(warning(), next = "repeated") }
        f.state.refresh()
        assertEquals(listOf(null, "repeated"), f.source.cursors)
        assertTrue(f.state.failed)
        assertEquals(listOf(warning()), f.state.warnings)
    }

    @Test fun `each request uses fresh elapsed time for server remaining validity`() = runTest {
        val f = Fixture()
        f.source.reply = { cursor, started ->
            if (cursor == null) {
                assertEquals(100L, started)
                f.clock = 200
                page(next = "next")
            } else {
                assertEquals(200L, started)
                page(AccountWarning.decode(mapOf("id" to "timed", "reason" to "Reason", "remainingMillis" to 10), started)!!)
            }
        }
        f.state.refresh()
        assertEquals(210L, f.state.warnings.single().deadline)
    }

    private class Fixture {
        var clock = 100L
        val source = Source()
        val state = AccountWarningPresentation(OWNER, source) { clock }
    }
    private class Source : AccountWarningsSource {
        override var currentUid: String? = OWNER
        val cursors = mutableListOf<String?>()
        var reply: suspend (String?, Long) -> AccountWarningPage = { _, _ -> page() }
        override fun observe(uid: String, onAuthChanged: (String?) -> Unit, onChanged: () -> Unit): () -> Unit = {}
        override suspend fun load(cursor: String?, requestStarted: Long): AccountWarningPage {
            cursors += cursor
            return reply(cursor, requestStarted)
        }
    }
    private companion object {
        const val OWNER = "warning-owner"
        fun warning(id: String = "warning", deadline: Long? = 1000) =
            AccountWarning(id, "Respect other members", "2026-11-01T00:00:00Z", deadline)
        fun page(vararg rows: AccountWarning, next: String? = null) = AccountWarningPage(OWNER, rows.toList(), next)
    }
}
