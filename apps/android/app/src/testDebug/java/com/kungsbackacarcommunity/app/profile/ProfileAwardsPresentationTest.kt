package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.BadgeCounters
import com.kungsbackacarcommunity.app.badges.BadgeShowcase
import com.kungsbackacarcommunity.app.design.KccTheme
import java.text.DateFormat
import java.util.Date
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAwardsPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realAwardDateAppearsInTheWallAndItsDetailRemainsReachable() {
        val awardedAt = 1_700_000_000_000L
        compose.setContent {
            KccTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProfileBadgesSection(
                        BadgeShowcase.from(
                            listOf(Badge("garage_created", null, awardedAt)), BadgeCounters.NONE,
                        ),
                    )
                }
            }
        }
        val date = context.getString(
            R.string.badgeShowcase_awardedOn,
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(awardedAt)),
        )
        compose.onNodeWithText(date).performScrollTo().assertIsDisplayed()
        // This is the held canonical milestone, not a PDF sample badge/level.
        compose.onNodeWithText(context.getString(R.string.badges_badgeNames_garage_created)).performScrollTo().performClick()
        compose.onAllNodesWithText(date).assertCountEquals(2)
    }
}
