package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.points.PointsEntriesState
import com.kungsbackacarcommunity.app.points.PointsEntry
import com.kungsbackacarcommunity.app.points.PointsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownRouteAccountTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ownerHistory_isClearedAndUnsubscribedOnAccountChange_andAccountGateRevocation() {
        val uid = mutableStateOf("owner-a")
        val allowed = mutableStateOf(true)
        val activeReaders = mutableSetOf<String>()
        val entries = mapOf(
            "owner-a" to MutableStateFlow<PointsEntriesState>(PointsEntriesState.Loaded(listOf(
                PointsEntry("a", 7, 7, "Owner A private credit", 1L),
            ))),
            "owner-b" to MutableStateFlow<PointsEntriesState>(PointsEntriesState.Loading),
        )
        val points = object : PointsRepository {
            override fun observeBalance(uid: String): Flow<Long?> = flowOf(null)
            override fun observeEntries(uid: String): Flow<PointsEntriesState> = flow {
                activeReaders += uid
                try { emitAll(entries.getValue(uid)) } finally { activeReaders -= uid }
            }
        }
        val stats = object : CrownHuntStatsRepository {
            override fun observeStats(uid: String): Flow<CrownStatsUiState> = flowOf(
                CrownStatsUiState.Loaded(null, CrownSeasonBoard("2026-10", emptyList(), null)),
            )
        }
        compose.setContent {
            KccTheme {
                CrownHuntRoute(stats, allowed.value, {}, uid = uid.value, pointsRepository = points)
            }
        }
        compose.onNodeWithText("Owner A private credit").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { uid.value = "owner-b" }
        compose.onNodeWithText("Owner A private credit").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("owner-b"), activeReaders) }
        compose.runOnIdle { allowed.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptySet<String>(), activeReaders) }
    }
}
