package com.kungsbackacarcommunity.app.convoy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalConvoyInviteUsageStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("invite-usage-test", Context.MODE_PRIVATE)
    private val store = LocalConvoyInviteUsageStore(prefs)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun persistsCountsOncePerFriendAndIsolatesAccountsAndSignedOut() {
        store.record("a", listOf("friend", "friend", "a", ""))
        store.record("a", listOf("friend"))
        store.record("b", listOf("other"))
        store.record("", listOf("friend"))
        assertEquals(mapOf("friend" to 2), LocalConvoyInviteUsageStore(prefs).counts("a"))
        assertEquals(mapOf("other" to 1), store.counts("b"))
        assertEquals(emptyMap<String, Int>(), store.counts("c"))
        assertEquals(emptyMap<String, Int>(), store.counts(""))
    }

    @Test fun capsFriendsAndAccountsAndDoesNotCreateEmptyAccounts() {
        repeat(6) { batch -> store.record("a", (0 until 50).map { "friend-${batch * 50 + it}" }) }
        assertEquals(200, store.counts("a").size)
        store.record("empty", emptyList())
        repeat(4) { store.record("user-$it", listOf("friend")) }
        assertTrue(store.counts("a").isEmpty())
        assertEquals(mapOf("friend" to 1), store.counts("user-0"))
        assertEquals(1, prefs.all.size)
    }

    @Test fun corruptOrOversizedLocalHintsFallBackAndCanBeReplaced() {
        prefs.edit().putString("accounts_v1", "invalid-json").commit()
        assertTrue(store.counts("a").isEmpty())
        store.record("a", listOf("friend"))
        assertEquals(mapOf("friend" to 1), store.counts("a"))
        prefs.edit().putString("accounts_v1", "x".repeat(150_001)).commit()
        assertTrue(store.counts("a").isEmpty())
        store.record("a", listOf("friend"))
        assertEquals(mapOf("friend" to 1), store.counts("a"))
    }
}
