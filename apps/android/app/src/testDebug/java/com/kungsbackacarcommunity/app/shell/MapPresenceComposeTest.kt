package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapPresenceComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // With the frame clock paused, publish writes made outside composition
    // explicitly before advancing it; waitForIdle alone does not notify them.
    @Test fun metadataRefreshUsesCurrentPayloadWithoutRestartingOrDelayingTheFade() {
        var items by mutableStateOf(mapOf("driver" to 1))
        var visible = emptyList<MapPresentItem<Int>>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val frame = rememberMapPresence(items)
            SideEffect { visible = frame }
        }
        compose.mainClock.advanceTimeBy(256); compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1f, visible.single().alpha)
            items = mapOf("driver" to 2)
            Snapshot.sendApplyNotifications()
        }
        // Flush snapshot notification and recomposition frames without completing the 200 ms exit.
        compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, visible.single().value); assertEquals(1f, visible.single().alpha) }
    }
    @Test fun retainedAuthorizedExitDisablesInteractionAndPrivacyFlushesItWithoutWaiting() {
        var items by mutableStateOf(mapOf("driver" to "photo"))
        var allowed by mutableStateOf(setOf("driver"))
        var visible = emptyList<MapPresentItem<String>>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val frame = rememberMapPresence(items, allowed = allowed)
            SideEffect { visible = frame }
            frame.forEach { Box(Modifier.testTag(it.id)) }
        }
        compose.mainClock.advanceTimeBy(256); compose.waitForIdle()
        compose.runOnIdle { items = emptyMap(); Snapshot.sendApplyNotifications() }
        // Flush snapshot notification and recomposition frames without completing the 200 ms exit.
        compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(visible.single().interactive)
            allowed = emptySet()
            Snapshot.sendApplyNotifications()
        }
        // Flush snapshot notification and recomposition frames without completing the 200 ms exit.
        compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
        compose.onNodeWithTag("driver").assertDoesNotExist()
        compose.runOnIdle { assertTrue(visible.isEmpty()) }
    }
    @Test fun hiddenMapClearsPublicExitSnapshots() {
        var items by mutableStateOf(mapOf("static" to "badge"))
        var active by mutableStateOf(true)
        var visible = emptyList<MapPresentItem<String>>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val frame = rememberMapPresence(items, active = active, retainRemovedPublicItems = true)
            SideEffect { visible = frame }
        }
        compose.mainClock.advanceTimeBy(256); compose.waitForIdle()
        compose.runOnIdle { items = emptyMap(); active = false; Snapshot.sendApplyNotifications() }
        // Flush snapshot notification and recomposition frames without completing the 200 ms exit.
        compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
        compose.runOnIdle { assertTrue(visible.isEmpty()) }
    }
}
